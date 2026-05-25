/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.wm;

import static android.app.AppLockManager.AppLockState.LOCKED;
import static android.app.AppLockManager.AppLockState.NONE;
import static android.app.AppLockManager.AppLockState.UNLOCKED;
import static android.content.pm.PackageManager.ACTION_REQUEST_PERMISSIONS_FOR_OTHER;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppLockManager;
import android.app.AppLockManager.AppLockState;
import android.app.IApplicationThread;
import android.app.WindowConfiguration;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Process;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;
import android.widget.Toast;

import com.android.internal.app.IAppLockManager;
import com.android.internal.app.IAppLockStateListener;
import com.android.internal.app.IAppSessionListener;
import com.android.server.applock.AppLockController;
import com.android.server.policy.PermissionPolicyInternal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * System App Lock service: locked-app policy, session unlock, and launch interception.
 *
 * @hide
 */
public class AppLockService extends IAppLockManager.Stub implements IAppLockService {
    private static final String TAG = "AppLockService";

    /** System auth overlay (PIN/pattern/biometric) in {@code com.android.applock}. */
    private static final String AUTH_PACKAGE = "com.android.applock";
    private static final String AUTH_ACTIVITY = "com.android.applock.auth.AuthenticateActivity";
    /** Intent action understood by the App Lock auth overlay. */
    private static final String ACTION_AUTH_UNLOCK = "com.android.applock.action.SYSTEM_UNLOCK";

    private static final String EXTRA_LOCKED_UID = AppLockManager.EXTRA_LOCKED_UID;
    private static final String EXTRA_LOCKED_PACKAGE = AppLockManager.EXTRA_LOCKED_PACKAGE;
    private static final String EXTRA_LOCKED_COMPONENT = AppLockManager.EXTRA_LOCKED_COMPONENT;
    private static final String EXTRA_APP_LABEL = "app_label";

    private static final String SETTING_LOCK_BEHAVIOR = AppLockManager.SETTING_LOCK_BEHAVIOR;
    private static final String SETTING_LOCK_TIMEOUT = AppLockManager.SETTING_LOCK_TIMEOUT;
    private static final String SETTING_HIDE_NOTIFICATION_CONTENT =
            AppLockManager.SETTING_HIDE_NOTIFICATION_CONTENT;

    private static final int LOCK_BEHAVIOR_ON_LEAVE = AppLockManager.LOCK_BEHAVIOR_ON_LEAVE;
    private static final int LOCK_BEHAVIOR_TIMEOUT = AppLockManager.LOCK_BEHAVIOR_TIMEOUT;
    private static final int LOCK_BEHAVIOR_ON_SCREEN_OFF = AppLockManager.LOCK_BEHAVIOR_ON_SCREEN_OFF;
    private static final int LOCK_BEHAVIOR_ON_KILL = AppLockManager.LOCK_BEHAVIOR_ON_KILL;

    /** Packages that must never be subject to app lock. */
    private static final Set<String> PROTECTED_PACKAGES = Set.of(
            "android",
            AUTH_PACKAGE,
            "com.android.settings"
    );

    private ActivityTaskManagerService mAtms;
    private Context mContext;
    private AppLockController mController;
    private SettingsObserver mSettingsObserver;
    private ResolveInfo mAuthResolveInfo;
    private Intent mConfirmIntent;
    private int mRequestCode;

    private final RemoteCallbackList<IAppLockStateListener> mAppLockStateListeners =
            new RemoteCallbackList<>();
    private final RemoteCallbackList<IAppSessionListener> mAppSessionListeners =
            new RemoteCallbackList<>();

    private final Set<String> mUnlockedApps = ConcurrentHashMap.newKeySet();
    private final Set<String> mPendingUnlocks = new HashSet<>();
    private final Map<String, Long> mUnlockTimestamps = new HashMap<>();
    private final Map<String, Runnable> mTimeoutRunnables = new HashMap<>();
    private String mLastFocusedAppKey;
    private int mLockBehavior = LOCK_BEHAVIOR_ON_LEAVE;
    private int mLockTimeout = AppLockManager.DEFAULT_LOCK_TIMEOUT;
    private boolean mHideNotificationContent = true;
    private boolean mKeyguardDone = true;
    private boolean mCheckRecentTasks;
    private int mCurrentUserId;

    private static final class Holder {
        private static final AppLockService INSTANCE = new AppLockService();
    }

    public static AppLockService get() {
        return Holder.INSTANCE;
    }

    private AppLockService() {
    }

    public static void systemReady(Context context, ActivityTaskManagerService atms) {
        AppLockService instance = get();
        instance.onSystemReady(context, atms);
        ServiceManager.addService(Context.APP_LOCK_SERVICE, instance);
        Slog.i(TAG, "registered " + Context.APP_LOCK_SERVICE);
    }

    private void onSystemReady(Context context, ActivityTaskManagerService atms) {
        mContext = context;
        mAtms = atms;

        try {
            mAuthResolveInfo = context.getPackageManager().resolveActivity(
                    getConfirmIntent(), PackageManager.MATCH_DEFAULT_ONLY);
        } catch (Exception e) {
            Slog.w(TAG, "Could not resolve auth activity", e);
        }

        mRequestCode = getConfirmIntent().toString().hashCode() & 0x0FFFFFFF;
        mSettingsObserver = new SettingsObserver(atms.mH);
        mSettingsObserver.onChange(true);

        mController = new AppLockController(context, PROTECTED_PACKAGES);
        mController.init();

        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        filter.addDataScheme("package");
        context.registerReceiverAsUser(mPackageRemovedReceiver, UserHandle.ALL, filter,
                null, atms.mH);
    }

    private final BroadcastReceiver mPackageRemovedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getData() == null) return;
            String packageName = intent.getData().getSchemeSpecificPart();
            if (TextUtils.isEmpty(packageName)) return;
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            cleanupPackage(packageName);
        }
    };

    private void cleanupPackage(String packageName) {
        if (mController == null) return;
        debugSession("cleanupPackage pkg=" + packageName + " " + sessionSnapshot(packageName, 0));
        mController.cleanupPackage(packageName);
        notifyAppLockStateChanged(packageName, false);

        for (String key : new ArrayList<>(mUnlockedApps)) {
            if (key.endsWith(":" + packageName)) {
                mUnlockedApps.remove(key);
                mUnlockTimestamps.remove(key);
                cancelTimeoutLock(key);
            }
        }
        synchronized (mPendingUnlocks) {
            mPendingUnlocks.removeIf(key -> key.endsWith(":" + packageName));
        }
    }

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(SETTING_LOCK_BEHAVIOR), false, this, -1);
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(SETTING_LOCK_TIMEOUT), false, this, -1);
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(SETTING_HIDE_NOTIFICATION_CONTENT), false, this, -1);
        }

        @Override
        public void onChange(boolean selfChange) {
            ContentResolver resolver = mContext.getContentResolver();
            int newBehavior = Settings.Secure.getIntForUser(resolver, SETTING_LOCK_BEHAVIOR,
                    LOCK_BEHAVIOR_ON_LEAVE, UserHandle.USER_SYSTEM);
            int newTimeout = Settings.Secure.getIntForUser(resolver, SETTING_LOCK_TIMEOUT,
                    AppLockManager.DEFAULT_LOCK_TIMEOUT, UserHandle.USER_SYSTEM);
            boolean policyChanged = newBehavior != mLockBehavior || newTimeout != mLockTimeout;
            mLockBehavior = newBehavior;
            mLockTimeout = newTimeout;
            if (policyChanged) {
                applyLockPolicyChange();
            }
            boolean hideNotificationContent = Settings.Secure.getIntForUser(resolver,
                    SETTING_HIDE_NOTIFICATION_CONTENT, 1, UserHandle.USER_SYSTEM) != 0;
            boolean hideChanged = hideNotificationContent != mHideNotificationContent;
            mHideNotificationContent = hideNotificationContent;
            if (hideChanged) {
                notifyNotificationHidingChanged();
            }
        }
    }

    private Intent getConfirmIntent() {
        if (mConfirmIntent == null) {
            mConfirmIntent = new Intent(ACTION_AUTH_UNLOCK);
            mConfirmIntent.setClassName(AUTH_PACKAGE, AUTH_ACTIVITY);
            mConfirmIntent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        }
        return mConfirmIntent;
    }

    // --- IAppLockManager ---

    @Override
    public boolean isEnabled() {
        return mController != null && mController.isEnabled();
    }

    @Override
    public void setEnabled(boolean enabled) {
        enforceSettingsManager();
        if (mController == null) return;
        mController.setEnabled(enabled);
        if (!enabled) {
            lockAllSessionsAndNotify();
        }
    }

    @Override
    public int getLockBehavior() {
        return mLockBehavior;
    }

    @Override
    public void setLockBehavior(int behavior) {
        enforceSettingsManager();
        putSecureIntSetting(SETTING_LOCK_BEHAVIOR, behavior);
        if (behavior != mLockBehavior) {
            mLockBehavior = behavior;
            applyLockPolicyChange();
        }
    }

    @Override
    public int getLockTimeout() {
        return mLockTimeout;
    }

    @Override
    public void setLockTimeout(int timeoutSeconds) {
        enforceSettingsManager();
        putSecureIntSetting(SETTING_LOCK_TIMEOUT, timeoutSeconds);
        if (timeoutSeconds != mLockTimeout) {
            mLockTimeout = timeoutSeconds;
            applyLockPolicyChange();
        }
    }

    @Override
    public boolean isHideNotificationContentEnabled() {
        return mHideNotificationContent;
    }

    @Override
    public void setHideNotificationContent(boolean hide) {
        enforceSettingsManager();
        putSecureIntSetting(SETTING_HIDE_NOTIFICATION_CONTENT, hide ? 1 : 0);
        // In-memory state and listener updates are applied in SettingsObserver.onChange().
    }

    /** Whether notification content should be hidden for this locked app. */
    public boolean shouldHideNotificationContent(String packageName) {
        return mHideNotificationContent && hasAppLock(packageName);
    }

    /**
     * Returns true when the package is App Lock protected and the user has not unlocked
     * the current session on {@code userId}.
     */
    public boolean shouldBlockUninstall(String packageName, int userId) {
        if (mController == null || !mController.isEnabled()) return false;
        if (!mController.isAppLocked(packageName)) return false;
        if (!mKeyguardDone) return true;

        String key = sessionKey(userId, packageName);
        boolean sessionUnlocked = mUnlockedApps.contains(key);
        if (sessionUnlocked && mLockBehavior == LOCK_BEHAVIOR_TIMEOUT) {
            Long lastUsed = mUnlockTimestamps.get(key);
            if (lastUsed != null
                    && (SystemClock.elapsedRealtime() - lastUsed) > (mLockTimeout * 1000L)) {
                sessionUnlocked = false;
            }
        }
        return !sessionUnlocked;
    }

    private static final String UNINSTALL_BLOCKED_TOAST_RES = "applock_uninstall_blocked_toast";

    private CharSequence getUninstallBlockedToastText() {
        try {
            Context pkgContext = mContext.createPackageContext(AUTH_PACKAGE, 0);
            int resId = pkgContext.getResources().getIdentifier(
                    UNINSTALL_BLOCKED_TOAST_RES, "string", AUTH_PACKAGE);
            if (resId != 0) {
                return pkgContext.getText(resId);
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "App Lock package not found for uninstall toast", e);
        }
        return "To uninstall a locked app, unlock it first";
    }

    /** Shows a toast when uninstall is blocked for a locked app. */
    public void showUninstallBlockedToast() {
        if (mContext == null || mAtms == null) return;
        final CharSequence text = getUninstallBlockedToastText();
        mAtms.mH.post(() -> Toast.makeText(mContext, text, Toast.LENGTH_LONG).show());
    }

    private void notifyNotificationHidingChanged() {
        final List<String> lockedPackages = mController != null
                ? mController.getLockedPackages() : List.of();
        final int count = mAppLockStateListeners.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                try {
                    mAppLockStateListeners.getBroadcastItem(i).onAppLockStateChanged("", false);
                } catch (RemoteException e) {
                    Slog.w(TAG, "app lock state listener failed", e);
                }
            }
            for (String packageName : lockedPackages) {
                for (int i = 0; i < count; i++) {
                    try {
                        mAppLockStateListeners.getBroadcastItem(i)
                                .onAppLockStateChanged(packageName, true);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "app lock state listener failed", e);
                    }
                }
            }
        } finally {
            mAppLockStateListeners.finishBroadcast();
        }
    }

    @Override
    public int getAppLockState(String packageName) {
        return computeAppLockState(packageName).ordinal();
    }

    public boolean hasAppLock(String packageName) {
        return computeAppLockState(packageName).hasAppLock();
    }

    @Override
    public void addLockedApp(String packageName) {
        enforceSettingsManager();
        if (mController == null) return;
        mController.addLockedApp(packageName);
        notifyAppLockStateChanged(packageName, true);
    }

    @Override
    public void removeLockedApp(String packageName) {
        enforceSettingsManager();
        if (mController == null) return;
        mController.removeLockedApp(packageName);
        int uid = getPackageUid(packageName);
        if (uid >= 0) {
            markSessionLocked(packageName, UserHandle.getUserId(uid));
        }
        notifyAppLockStateChanged(packageName, false);
    }

    @Override
    public List<String> getLockedPackages() {
        return mController != null ? mController.getLockedPackages() : List.of();
    }

    @Override
    public List<String> getLockablePackages() {
        return mController != null ? mController.getLockablePackages() : List.of();
    }

    @Override
    public boolean isPackageLockable(String packageName) {
        return mController != null && mController.isPackageLockable(packageName);
    }

    @Override
    public void unlockApp(String packageName, int userId) {
        if (TextUtils.isEmpty(packageName)) return;
        markSessionUnlocked(packageName, userId);
        synchronized (mPendingUnlocks) {
            mPendingUnlocks.remove(sessionKey(userId, packageName));
        }
    }

    @Override
    public void promptUnlock(String packageName, int userId) {
        if (TextUtils.isEmpty(packageName) || mAtms == null) return;

        Intent intent = new Intent(getConfirmIntent());
        intent.putExtra(EXTRA_LOCKED_PACKAGE, packageName);
        intent.putExtra(EXTRA_LOCKED_UID, userId);
        intent.putExtra(EXTRA_APP_LABEL, resolveAppLabel(packageName, userId));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

        long identity = Binder.clearCallingIdentity();
        try {
            mAtms.getActivityStartController()
                    .obtainStarter(intent, "AppLock.promptUnlock")
                    .setCallingUid(0)
                    .setActivityInfo(mAuthResolveInfo != null ? mAuthResolveInfo.activityInfo : null)
                    .execute();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void registerAppLockStateListener(IAppLockStateListener listener) {
        mAppLockStateListeners.register(listener);
    }

    @Override
    public void unregisterAppLockStateListener(IAppLockStateListener listener) {
        mAppLockStateListeners.unregister(listener);
    }

    @Override
    public void registerAppSessionListener(IAppSessionListener listener) {
        mAppSessionListeners.register(listener);
    }

    @Override
    public void unregisterAppSessionListener(IAppSessionListener listener) {
        mAppSessionListeners.unregister(listener);
    }

    // --- IAppLockService (WM hooks) ---

    @Override
    public boolean isAppLocked(ActivityRecord r) {
        if (r == null || !hasLockedPackages() || r.isNoDisplay()
                || r.isActivityTypeHomeOrRecents()) {
            if (r != null && mController != null && mController.isAppLocked(r.packageName)) {
                debugSession("isAppLocked(ActivityRecord)=false pkg=" + r.packageName
                        + " noDisplay=" + r.isNoDisplay()
                        + " homeOrRecents=" + r.isActivityTypeHomeOrRecents()
                        + " hasLockedPackages=" + hasLockedPackages());
            }
            return false;
        }

        int userId = r.mUserId;
        if (mAuthResolveInfo == null || mCurrentUserId != userId) {
            refreshAuthResolveInfo(userId);
            mSettingsObserver.onChange(true);
        }
        return isAppLocked(r.packageName, r.getUid(), r.mActivityComponent);
    }

    @Override
    public boolean isAppLocked(String packageName, int uid, ComponentName component) {
        if (mController == null || !mController.isEnabled()) return false;
        if (PROTECTED_PACKAGES.contains(packageName)) return false;
        if (!mController.isAppLocked(packageName)) return false;

        int userId = UserHandle.getUserId(uid);
        String key = sessionKey(userId, packageName);
        boolean sessionUnlocked = mUnlockedApps.contains(key);

        if (sessionUnlocked && mLockBehavior == LOCK_BEHAVIOR_TIMEOUT) {
            Long lastUsed = mUnlockTimestamps.get(key);
            if (lastUsed != null
                    && (SystemClock.elapsedRealtime() - lastUsed) > (mLockTimeout * 1000L)) {
                sessionUnlocked = false;
                debugSession("isAppLocked timeout expired pkg=" + packageName + " "
                        + sessionSnapshot(packageName, userId));
            }
        }

        boolean locked = mKeyguardDone && !sessionUnlocked;
        if (!locked) {
            debugSession("isAppLocked=false pkg=" + packageName + " reason="
                    + explainNotLocked(packageName, uid) + " " + sessionSnapshot(packageName, userId));
        }
        return locked;
    }

    @Override
    public void lockTopApp(Task task, String reason) {
        if (task == null || !hasLockedPackages()) return;
        ActivityRecord r = task.topRunningActivityLocked();
        if (r == null) return;
        if (!isAppLocked(r)) {
            if (mController.isAppLocked(r.packageName)) {
                debugSession("lockTopApp skip not locked reason=" + reason + " pkg="
                        + r.packageName + " " + explainNotLocked(r.packageName, r.getUid()));
            }
            return;
        }
        if (mUnlockedApps.contains(sessionKey(r))) {
            debugSession("lockTopApp skip already unlocked reason=" + reason + " pkg="
                    + r.packageName + " " + sessionSnapshot(r.packageName, r.mUserId));
            return;
        }
        startAuthPrompt(r, reason);
    }

    @Override
    public boolean prepareLockedTaskForResume(Task task, String reason) {
        if (task == null || !hasLockedPackages()) {
            return true;
        }
        ActivityRecord top = task.getTopNonFinishingActivity();
        if (top == null) {
            top = task.topRunningActivityLocked();
        }
        if (top == null) {
            return true;
        }
        if (isAuthActivity(top.mActivityComponent)) {
            return true;
        }
        if (!isAppLocked(top)) {
            return true;
        }
        startAuthPrompt(top, reason);
        ensureAuthVisibleInTask(task, top);
        final ActivityRecord newTop = task.getTopNonFinishingActivity();
        if (newTop != null && isAuthActivity(newTop.mActivityComponent)) {
            return true;
        }
        if (newTop != null && isAppLocked(newTop)) {
            debugSession("prepareLockedTaskForResume block reason=" + reason + " pkg="
                    + newTop.packageName + " " + sessionSnapshot(newTop.packageName, newTop.mUserId));
            return false;
        }
        return true;
    }

    @Override
    public boolean checkLockApp(ActivityRecord prev, ActivityRecord next) {
        if (next == null) return false;
        debugSession("checkLockApp enter next=" + next.packageName + " prev="
                + (prev != null ? prev.packageName : "null")
                + (prev != null && prev.finishing ? " (finishing)" : "")
                + " " + sessionSnapshot(next.packageName, next.mUserId));
        // Back finishes the previous activity before focus moves; relock here because
        // clearUnlockedApp() may run without a matching onAppFocusChanged relock.
        if (prev != null && prev.finishing && mLockBehavior == LOCK_BEHAVIOR_ON_LEAVE
                && mUnlockedApps.contains(sessionKey(prev))) {
            if ((next.isActivityTypeHomeOrRecents()
                    || !prev.packageName.equals(next.packageName))
                    && !shouldIgnoreFocusChangeForRelock(next)) {
                debugSession("checkLockApp relock finishing prev=" + prev.packageName);
                markSessionLocked(prev.packageName, prev.mUserId);
            }
        }
        if (!isAppLocked(next)) {
            debugSession("checkLockApp allow resume without auth pkg=" + next.packageName
                    + " reason=" + explainNotLocked(next.packageName, next.getUid()));
            if (!shouldIgnoreFocusChangeForRelock(next)) {
                clearUnlockedApp(next);
            }
            return false;
        }
        finishPermissionDialogsInTask(next.getTask());
        if (!startAuthPrompt(next, "AppLock.checkLockApp")) {
            debugSession("checkLockApp startAuthPrompt failed pkg=" + next.packageName);
            return false;
        }
        debugSession("checkLockApp blocking resume for auth pkg=" + next.packageName);
        if (prev != null && prev.finishing) {
            prev.setVisibility(false);
        }
        next.mRootWindowContainer.ensureActivitiesVisible();
        return true;
    }

    @Override
    public boolean checkUnlockApp(ActivityRecord r, int resultCode, Intent data) {
        if (r.requestCode != mRequestCode) return false;
        if (data == null) return true;
        try {
            int userId = UserHandle.getUserId(data.getIntExtra(EXTRA_LOCKED_UID, 0));
            String packageName = data.getStringExtra(EXTRA_LOCKED_PACKAGE);
            String pendingKey = sessionKey(userId, packageName);
            synchronized (mPendingUnlocks) {
                mPendingUnlocks.remove(pendingKey);
            }
            debugSession("checkUnlockApp pkg=" + packageName + " resultCode=" + resultCode
                    + " resultTo=" + (r.resultTo != null ? r.resultTo.packageName : "null")
                    + " " + sessionSnapshot(packageName, userId));
            if (resultCode == Activity.RESULT_OK && packageName != null) {
                markSessionUnlocked(packageName, userId);
            } else if (r.resultTo != null) {
                debugSession("checkUnlockApp canceled, finishing resultTo="
                        + r.resultTo.packageName);
                r.resultTo.finishIfPossible("applock-canceled", false);
            }
            return true;
        } catch (Exception e) {
            Slog.w(TAG, "checkUnlockApp failed", e);
            return false;
        }
    }

    @Override
    public boolean isAuthActivity(ComponentName component) {
        return component != null
                && AUTH_PACKAGE.equals(component.getPackageName())
                && AUTH_ACTIVITY.equals(component.getClassName());
    }

    @Override
    public boolean isTopAppLocked(ActivityManager.RecentTaskInfo rti, int topUserId) {
        rti.isTopAppLocked = false;
        if (!mCheckRecentTasks || mController == null || !mController.isEnabled()) {
            return false;
        }
        ComponentName component = rti.baseIntent.getComponent();
        String packageName = component != null ? component.getPackageName() : "";
        long identity = Binder.clearCallingIdentity();
        try {
            int userId = UserHandle.getUserId(topUserId);
            if (isAuthActivity(component)) {
                rti.isTopAppLocked = true;
            } else if (mController.isAppLocked(packageName)) {
                // Always mask recents for App Lock protected apps (ignore session/relock).
                rti.isTopAppLocked = true;
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return rti.isTopAppLocked;
    }

    @Override
    public void getRecentTasksCheck(int callingUid, int userId) {
        mCheckRecentTasks = callingUid != Process.SYSTEM_UID
                && mAtms.mWindowManager.isKeyguardSecure(userId);
    }

    @Override
    public void setKeyguardDoneLocked(boolean done) {
        try {
            if (done) {
                mKeyguardDone = true;
                mAtms.mWindowManager.getDefaultDisplayContentLocked()
                        .getDefaultTaskDisplayArea().forAllTasks(this::addVisibleTaskToUnlocked);
            } else {
                mKeyguardDone = false;
                if (mLockBehavior == LOCK_BEHAVIOR_TIMEOUT && mLastFocusedAppKey != null) {
                    scheduleTimeoutLock(mLastFocusedAppKey);
                }
                if (mLockBehavior == LOCK_BEHAVIOR_ON_SCREEN_OFF) {
                    lockAllSessionsAndNotify();
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "setKeyguardDoneLocked failed", e);
            mKeyguardDone = done;
        }
    }

    @Override
    public void onAppFocusChanged(ActivityRecord newFocus, Task newTask) {
        if (!hasLockedPackages()) {
            mLastFocusedAppKey = null;
            return;
        }
        if (newFocus != null && shouldIgnoreFocusChangeForRelock(newFocus)) {
            deferPermissionDialogUntilUnlocked(newFocus);
            return;
        }
        String newKey = newFocus != null ? sessionKey(newFocus) : null;
        debugSession("onAppFocusChanged focus="
                + (newFocus != null ? newFocus.packageName : "null")
                + " newKey=" + newKey + " lastKey=" + mLastFocusedAppKey);
        if (mLastFocusedAppKey != null && !mLastFocusedAppKey.equals(newKey)) {
            scheduleTimeoutLock(mLastFocusedAppKey);
            if (mLockBehavior == LOCK_BEHAVIOR_ON_LEAVE
                    && mUnlockedApps.contains(mLastFocusedAppKey)) {
                debugSession("onAppFocusChanged relock on leave lastKey=" + mLastFocusedAppKey
                        + " newKey=" + newKey);
                relockFromSessionKey(mLastFocusedAppKey);
            }
            if (newFocus != null && newFocus.isActivityTypeHomeOrRecents()) {
                synchronized (mPendingUnlocks) {
                    if (mPendingUnlocks.remove(mLastFocusedAppKey)) {
                        debugSession("onAppFocusChanged clear pending on leave to home key="
                                + mLastFocusedAppKey);
                    }
                }
            }
        }
        if (newKey != null) {
            cancelTimeoutLock(newKey);
            mUnlockTimestamps.put(newKey, SystemClock.elapsedRealtime());
        }
        mLastFocusedAppKey = newKey;
        lockTopApp(newTask, "AppLock.onAppFocusChanged");
    }

    @Override
    public void onWindowingModeChanged(Task task, int prevWindowingMode) {
        if (task == null || mLockBehavior != LOCK_BEHAVIOR_ON_LEAVE) return;

        int currMode = task.getWindowingMode();
        if (!WindowConfiguration.isFloating(prevWindowingMode)
                && WindowConfiguration.isFloating(currMode) && task.isVisible()) {
            ActivityRecord r = task.topRunningActivityLocked();
            if (isAppLocked(r)) {
                markSessionUnlocked(r.packageName, r.mUserId);
            }
            return;
        }

        if (mUnlockedApps.isEmpty()) return;
        if ((!WindowConfiguration.inMultiWindowMode(prevWindowingMode)
                && !WindowConfiguration.isFloating(prevWindowingMode))
                || WindowConfiguration.inMultiWindowMode(currMode)
                || WindowConfiguration.isFloating(currMode)) {
            return;
        }

        ActivityRecord r = task.topRunningActivityLocked();
        if (task.isVisible()) {
            if (currMode == WindowConfiguration.WINDOWING_MODE_FULLSCREEN) {
                if (r != null) clearUnlockedApp(r);
                else clearUnlockedApp();
            }
        } else {
            if (r != null) markSessionLocked(r.packageName, r.mUserId);
            ActivityRecord lastPaused = task.mLastPausedActivity;
            if (lastPaused != null) {
                markSessionLocked(lastPaused.packageName, lastPaused.mUserId);
            }
            if (task.realActivity != null) {
                markSessionLocked(task.realActivity.getPackageName(), task.mUserId);
            }
        }
    }

    @Override
    public void clearUnlockedApp() {
        if (mLockBehavior != LOCK_BEHAVIOR_ON_LEAVE || mUnlockedApps.isEmpty()) return;
        debugSession("clearUnlockedApp all sessions=" + mUnlockedApps);
        lockAllSessionsAndNotify();
        lockVisibleMultiWindowApps(mAtms.mWindowManager.getDefaultDisplayContentLocked());
    }

    @Override
    public void clearUnlockedApp(ActivityRecord r) {
        if (r == null || mLockBehavior != LOCK_BEHAVIOR_ON_LEAVE) return;
        if (r.occludesParent() || r.isActivityTypeHomeOrRecents()) {
            if (r.isActivityTypeHomeOrRecents() && r.mTransitionController.isTransientLaunch(r)) {
                return;
            }
            boolean wasUnlocked = mUnlockedApps.contains(sessionKey(r));
            debugSession("clearUnlockedApp record pkg=" + r.packageName + " wasUnlocked="
                    + wasUnlocked);
            clearUnlockedApp();
            if (wasUnlocked) {
                markSessionUnlocked(r.packageName, r.mUserId);
            } else {
                markSessionLocked(r.packageName, r.mUserId);
            }
            if (WindowConfiguration.isFloating(r.getWindowingMode())) {
                lockVisibleFullscreenApps(mAtms.mWindowManager.getDefaultDisplayContentLocked());
            }
        }
    }

    @Override
    public void removeTask(Task task, String reason) {
        if (task == null || mUnlockedApps.isEmpty()) return;

        if (mLockBehavior == LOCK_BEHAVIOR_ON_KILL) {
            markTaskSessionsLocked(task);
            return;
        }

        if (mLockBehavior != LOCK_BEHAVIOR_ON_LEAVE || !"remove-task".equals(reason)) {
            return;
        }
        if (!WindowConfiguration.inMultiWindowMode(task.getWindowingMode())
                && !WindowConfiguration.isFloating(task.getWindowingMode())) {
            return;
        }
        markTaskSessionsLocked(task);
    }

    @Override
    public void onAppDied(String packageName, int userId) {
        if (AUTH_PACKAGE.equals(packageName)) {
            synchronized (mPendingUnlocks) {
                mPendingUnlocks.clear();
            }
            return;
        }
        if (mLockBehavior == LOCK_BEHAVIOR_ON_KILL) {
            markSessionLocked(packageName, userId);
        }
    }

    // --- internals ---

    private AppLockState computeAppLockState(String packageName) {
        if (mController == null || !mController.isEnabled()) return NONE;
        if (PROTECTED_PACKAGES.contains(packageName) || !mController.isAppLocked(packageName)) {
            return NONE;
        }
        if (!mKeyguardDone) return LOCKED;

        int userId = UserHandle.getUserId(Binder.getCallingUid());
        String key = sessionKey(userId, packageName);
        boolean sessionUnlocked = mUnlockedApps.contains(key);
        if (sessionUnlocked && mLockBehavior == LOCK_BEHAVIOR_TIMEOUT) {
            Long lastUsed = mUnlockTimestamps.get(key);
            if (lastUsed != null
                    && (SystemClock.elapsedRealtime() - lastUsed) > (mLockTimeout * 1000L)) {
                sessionUnlocked = false;
            }
        }
        return sessionUnlocked ? UNLOCKED : LOCKED;
    }

    private boolean hasLockedPackages() {
        return mController != null && mController.isEnabled() && mController.hasLockedPackages();
    }

    private ActivityRecord findAuthActivityInTask(Task task) {
        if (task == null) {
            return null;
        }
        final ActivityRecord[] found = new ActivityRecord[1];
        task.forAllActivities(r -> {
            if (found[0] == null && !r.finishing && isAuthActivity(r.mActivityComponent)) {
                found[0] = r;
            }
        });
        return found[0];
    }

    private void ensureAuthVisibleInTask(Task task, ActivityRecord lockedTop) {
        if (task == null || lockedTop == null) {
            return;
        }
        final ActivityRecord auth = findAuthActivityInTask(task);
        if (auth == null) {
            return;
        }
        final ActivityRecord top = task.getTopNonFinishingActivity();
        if (top != null && isAuthActivity(top.mActivityComponent)) {
            return;
        }
        if (isAppLocked(lockedTop)) {
            task.moveActivityToFront(auth);
            auth.mRootWindowContainer.ensureActivitiesVisible();
            debugSession("ensureAuthVisibleInTask pkg=" + lockedTop.packageName);
        }
    }

    private boolean startAuthPrompt(ActivityRecord target, String reason) {
        if (target == null || mAtms == null) return false;

        String pendingKey = sessionKey(target);
        if (mUnlockedApps.contains(pendingKey)) {
            debugSession("startAuthPrompt skip session already unlocked reason=" + reason
                    + " pkg=" + target.packageName + " " + sessionSnapshot(target.packageName,
                    target.mUserId));
            return true;
        }

        synchronized (mPendingUnlocks) {
            if (mPendingUnlocks.contains(pendingKey)) {
                final Task task = target.getTask();
                final ActivityRecord auth = findAuthActivityInTask(task);
                final ActivityRecord top = task != null ? task.getTopNonFinishingActivity() : null;
                if (auth != null && top != null && isAuthActivity(top.mActivityComponent)) {
                    debugSession("startAuthPrompt skip auth on top reason=" + reason + " pkg="
                            + target.packageName);
                    return true;
                }
                if (auth != null && top != null && isAppLocked(top)) {
                    ensureAuthVisibleInTask(task, top);
                    final ActivityRecord topAfter = task.getTopNonFinishingActivity();
                    if (topAfter != null && isAuthActivity(topAfter.mActivityComponent)) {
                        debugSession("startAuthPrompt brought auth to front reason=" + reason
                                + " pkg=" + target.packageName);
                        return true;
                    }
                }
                debugSession("startAuthPrompt clear stale pending reason=" + reason + " pkg="
                        + target.packageName + " authInTask=" + (auth != null));
                mPendingUnlocks.remove(pendingKey);
            }
            mPendingUnlocks.add(pendingKey);
        }

        debugSession("startAuthPrompt launching auth reason=" + reason + " pkg="
                + target.packageName + " hasProcess=" + (target.app != null) + " "
                + sessionSnapshot(target.packageName, target.mUserId));

        finishPermissionDialogsInTask(target.getTask());

        try {
            Intent intent = new Intent(getConfirmIntent());
            intent.putExtra(EXTRA_LOCKED_UID, target.getUid());
            intent.putExtra(EXTRA_LOCKED_PACKAGE, target.packageName);
            intent.putExtra(EXTRA_LOCKED_COMPONENT,
                    target.intent.getComponent() != null
                            ? target.intent.getComponent().flattenToString() : "");
            intent.putExtra(EXTRA_APP_LABEL, resolveAppLabel(target.packageName, target.mUserId));

            WindowProcessController wpc = target.app;
            if (wpc == null) {
                mAtms.getActivityStartController()
                        .obtainStarter(intent, reason)
                        .setCallingUid(0)
                        .setResultTo(target.token)
                        .setRequestCode(mRequestCode)
                        .setActivityInfo(mAuthResolveInfo != null
                                ? mAuthResolveInfo.activityInfo : null)
                        .execute();
            } else {
                startActivityAsCaller(wpc.getThread(), target.packageName, intent,
                        "", target.token, target.resultWho, mRequestCode);
            }
            abortAnimation(target);
            debugSession("startAuthPrompt started auth for pkg=" + target.packageName);
            return true;
        } catch (Exception e) {
            Slog.w(TAG, "startAuthPrompt failed for " + target.packageName, e);
            synchronized (mPendingUnlocks) {
                mPendingUnlocks.remove(pendingKey);
            }
            debugSession("startAuthPrompt failed pkg=" + target.packageName + " " + e.getMessage());
            return false;
        }
    }

    private void refreshAuthResolveInfo(int userId) {
        List<ResolveInfo> list = mContext.getPackageManager()
                .queryIntentActivitiesAsUser(mConfirmIntent, PackageManager.MATCH_SYSTEM_ONLY, userId);
        for (int i = 0; list != null && i < list.size(); i++) {
            ResolveInfo ri = list.get(i);
            if ((ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                mAuthResolveInfo = ri;
                int newUserId = UserHandle.getUserId(ri.activityInfo.applicationInfo.uid);
                if (mCurrentUserId != newUserId) {
                    mCurrentUserId = newUserId;
                }
                break;
            }
        }
    }

    private static String sessionKey(int userId, String packageName) {
        return userId + ":" + packageName;
    }

    private static String sessionKey(ActivityRecord r) {
        return sessionKey(r.mUserId, r.packageName);
    }

    private static void debugSession(String msg) {
        Slog.i(TAG, "[Session] " + msg);
    }

    private String sessionSnapshot(String packageName, int userId) {
        String key = sessionKey(userId, packageName);
        boolean pending;
        synchronized (mPendingUnlocks) {
            pending = mPendingUnlocks.contains(key);
        }
        return "{key=" + key + " unlocked=" + mUnlockedApps.contains(key)
                + " pendingAuth=" + pending
                + " behavior=" + lockBehaviorName(mLockBehavior)
                + " keyguardDone=" + mKeyguardDone
                + " lastFocus=" + mLastFocusedAppKey
                + " unlockedSessions=" + mUnlockedApps.size() + "}";
    }

    private static String lockBehaviorName(int behavior) {
        switch (behavior) {
            case LOCK_BEHAVIOR_TIMEOUT:
                return "timeout";
            case LOCK_BEHAVIOR_ON_SCREEN_OFF:
                return "screen_off";
            case LOCK_BEHAVIOR_ON_KILL:
                return "on_kill";
            default:
                return "on_leave";
        }
    }

    private String explainNotLocked(String packageName, int uid) {
        if (mController == null || !mController.isEnabled()) {
            return "applock_disabled";
        }
        if (PROTECTED_PACKAGES.contains(packageName)) {
            return "protected_package";
        }
        if (!mController.isAppLocked(packageName)) {
            return "not_in_lock_list";
        }
        int userId = UserHandle.getUserId(uid);
        String key = sessionKey(userId, packageName);
        if (!mKeyguardDone) {
            return "keyguard_not_done";
        }
        if (mUnlockedApps.contains(key)) {
            if (mLockBehavior == LOCK_BEHAVIOR_TIMEOUT) {
                Long lastUsed = mUnlockTimestamps.get(key);
                if (lastUsed != null
                        && (SystemClock.elapsedRealtime() - lastUsed)
                        <= (mLockTimeout * 1000L)) {
                    return "session_unlocked_within_timeout";
                }
                return "session_unlocked_timeout_stale";
            }
            return "session_unlocked";
        }
        synchronized (mPendingUnlocks) {
            if (mPendingUnlocks.contains(key)) {
                return "auth_pending_but_session_locked";
            }
        }
        return "should_be_locked";
    }

    /**
     * Returns whether runtime permission UI for {@code packageName} must wait until App Lock
     * authentication completes.
     */
    public boolean shouldBlockPermissionDialogStart(String packageName, int userId) {
        if (TextUtils.isEmpty(packageName) || mController == null || !mController.isEnabled()) {
            return false;
        }
        if (PROTECTED_PACKAGES.contains(packageName) || !mController.isAppLocked(packageName)) {
            return false;
        }
        try {
            int uid = mContext.getPackageManager().getPackageUidAsUser(packageName, 0, userId);
            return isAppLocked(packageName, uid, null);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String getPermissionDialogTargetPackage(ActivityRecord r) {
        if (r.intent == null) {
            return r.packageName;
        }
        if (ACTION_REQUEST_PERMISSIONS_FOR_OTHER.equals(r.intent.getAction())) {
            String pkg = r.intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
            if (!TextUtils.isEmpty(pkg)) {
                return pkg;
            }
        }
        if (!TextUtils.isEmpty(r.launchedFromPackage)) {
            return r.launchedFromPackage;
        }
        return r.packageName;
    }

    private void deferPermissionDialogUntilUnlocked(ActivityRecord r) {
        if (isAuthActivity(r.mActivityComponent) || r.intent == null || mAtms == null) {
            return;
        }
        PermissionPolicyInternal policy = mAtms.getPermissionPolicyInternal();
        if (policy == null || !policy.isIntentToPermissionDialog(r.intent)) {
            return;
        }
        String targetPkg = getPermissionDialogTargetPackage(r);
        if (shouldBlockPermissionDialogStart(targetPkg, r.mUserId) && !r.finishing) {
            r.finishIfPossible("applock-defer-permission", false);
        }
    }

    private void finishPermissionDialogsInTask(Task task) {
        if (task == null || mAtms == null) {
            return;
        }
        PermissionPolicyInternal policy = mAtms.getPermissionPolicyInternal();
        if (policy == null) {
            return;
        }
        task.forAllActivities(r -> {
            if (!r.finishing && r.intent != null
                    && policy.isIntentToPermissionDialog(r.intent)
                    && shouldBlockPermissionDialogStart(
                            getPermissionDialogTargetPackage(r), r.mUserId)) {
                r.finishIfPossible("applock-pending-auth", false);
            }
        });
    }

    /**
     * System overlays (runtime permission grant, App Lock auth) are not treated as leaving the
     * previously focused locked app for ON_LEAVE relock.
     */
    private boolean shouldIgnoreFocusChangeForRelock(ActivityRecord newFocus) {
        if (newFocus == null) {
            return false;
        }
        if (isAuthActivity(newFocus.mActivityComponent)) {
            return true;
        }
        final Intent intent = newFocus.intent;
        if (intent == null || mAtms == null) {
            return false;
        }
        PermissionPolicyInternal policy = mAtms.getPermissionPolicyInternal();
        return policy != null && policy.isIntentToPermissionDialog(intent);
    }

    private void markTaskSessionsLocked(Task task) {
        ActivityRecord r = task.topRunningActivityLocked();
        if (r != null) {
            markSessionLocked(r.packageName, r.mUserId);
        }
        if (task.mLastPausedActivity != null) {
            markSessionLocked(task.mLastPausedActivity.packageName, task.mLastPausedActivity.mUserId);
        }
        if (task.realActivity != null) {
            markSessionLocked(task.realActivity.getPackageName(), task.mUserId);
        }
    }

    private void relockFromSessionKey(String key) {
        int colon = key.indexOf(':');
        if (colon <= 0 || colon >= key.length() - 1) return;
        try {
            int userId = Integer.parseInt(key.substring(0, colon));
            String pkg = key.substring(colon + 1);
            markSessionLocked(pkg, userId);
        } catch (NumberFormatException ignored) {
        }
    }

    private void markSessionUnlocked(String packageName, int userId) {
        String key = sessionKey(userId, packageName);
        if (mUnlockedApps.add(key)) {
            mUnlockTimestamps.put(key, SystemClock.elapsedRealtime());
            notifyAppUnlocked(packageName, userId);
            debugSession("markSessionUnlocked pkg=" + packageName + " "
                    + sessionSnapshot(packageName, userId));
        } else {
            debugSession("markSessionUnlocked noop already unlocked pkg=" + packageName);
        }
    }

    private void markSessionLocked(String packageName, int userId) {
        String key = sessionKey(userId, packageName);
        if (mUnlockedApps.remove(key)) {
            mUnlockTimestamps.remove(key);
            cancelTimeoutLock(key);
            notifyAppLocked(packageName, userId);
            debugSession("markSessionLocked pkg=" + packageName + " "
                    + sessionSnapshot(packageName, userId));
        } else {
            debugSession("markSessionLocked noop already locked pkg=" + packageName + " "
                    + sessionSnapshot(packageName, userId));
        }
    }

    /** Clears in-memory unlock sessions when relock policy changes. */
    private void applyLockPolicyChange() {
        debugSession("applyLockPolicyChange clearing sessions behavior="
                + lockBehaviorName(mLockBehavior) + " unlockedCount=" + mUnlockedApps.size());
        mLastFocusedAppKey = null;
        lockAllSessionsAndNotify();
    }

    private void lockAllSessionsAndNotify() {
        if (mUnlockedApps.isEmpty()) return;
        debugSession("lockAllSessionsAndNotify count=" + mUnlockedApps.size()
                + " sessions=" + mUnlockedApps);
        String[] keys = mUnlockedApps.toArray(new String[0]);
        mUnlockedApps.clear();
        mUnlockTimestamps.clear();
        clearAllTimeouts();
        for (String key : keys) {
            int colon = key.indexOf(':');
            if (colon <= 0 || colon >= key.length() - 1) continue;
            try {
                int userId = Integer.parseInt(key.substring(0, colon));
                String pkg = key.substring(colon + 1);
                notifyAppLocked(pkg, userId);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void scheduleTimeoutLock(String key) {
        if (mLockBehavior != LOCK_BEHAVIOR_TIMEOUT || !mUnlockedApps.contains(key)) return;
        cancelTimeoutLock(key);
        Runnable r = () -> {
            synchronized (mAtms.mGlobalLock) {
                ActivityRecord top = mAtms.mRootWindowContainer.getTopResumedActivity();
                String topKey = top != null ? sessionKey(top) : null;
                if (!key.equals(topKey)) {
                    relockFromSessionKey(key);
                }
                mTimeoutRunnables.remove(key);
            }
        };
        mTimeoutRunnables.put(key, r);
        mAtms.mH.postDelayed(r, mLockTimeout * 1000L);
    }

    private void cancelTimeoutLock(String key) {
        Runnable r = mTimeoutRunnables.remove(key);
        if (r != null) mAtms.mH.removeCallbacks(r);
    }

    private void clearAllTimeouts() {
        for (Runnable r : mTimeoutRunnables.values()) {
            mAtms.mH.removeCallbacks(r);
        }
        mTimeoutRunnables.clear();
    }

    private void notifyAppLockStateChanged(String packageName, boolean locked) {
        final int count = mAppLockStateListeners.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                try {
                    mAppLockStateListeners.getBroadcastItem(i)
                            .onAppLockStateChanged(packageName, locked);
                } catch (RemoteException e) {
                    Slog.w(TAG, "app lock state listener failed", e);
                }
            }
        } finally {
            mAppLockStateListeners.finishBroadcast();
        }
    }

    private void notifyAppUnlocked(String packageName, int userId) {
        int count = mAppSessionListeners.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                mAppSessionListeners.getBroadcastItem(i).onAppUnlocked(packageName, userId);
            } catch (RemoteException e) {
                Slog.w(TAG, "session listener (unlock) failed", e);
            }
        }
        mAppSessionListeners.finishBroadcast();
    }

    private void notifyAppLocked(String packageName, int userId) {
        int count = mAppSessionListeners.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                mAppSessionListeners.getBroadcastItem(i).onAppLocked(packageName, userId);
            } catch (RemoteException e) {
                Slog.w(TAG, "session listener (lock) failed", e);
            }
        }
        mAppSessionListeners.finishBroadcast();
    }

    private void addVisibleTaskToUnlocked(Task task) {
        if (task.isLeafTask() && task.isVisible()) {
            ActivityRecord r = task.topRunningActivityLocked();
            if (isAppLocked(r)) {
                lockTopApp(task, "AppLock.setKeyguardDone");
            }
        }
    }

    private void lockVisibleMultiWindowApps(DisplayContent dc) {
        if (dc == null) {
            dc = mAtms.mWindowManager.getDefaultDisplayContentLocked();
        }
        dc.getDefaultTaskDisplayArea().forAllTasks(task -> {
            if (task.isLeafTask()
                    && (WindowConfiguration.inMultiWindowMode(task.getWindowingMode())
                    || WindowConfiguration.isFloating(task.getWindowingMode()))
                    && task.isVisible()) {
                ActivityRecord r = task.topRunningActivityLocked();
                if (isAppLocked(r)) {
                    markSessionUnlocked(r.packageName, r.mUserId);
                }
            }
        });
    }

    private void lockVisibleFullscreenApps(DisplayContent dc) {
        if (dc == null) {
            dc = mAtms.mWindowManager.getDefaultDisplayContentLocked();
        }
        dc.getDefaultTaskDisplayArea().forAllTasks(task -> {
            if (task.isLeafTask()
                    && task.getWindowingMode() == WindowConfiguration.WINDOWING_MODE_FULLSCREEN
                    && task.isVisible()) {
                ActivityRecord r = task.topRunningActivityLocked();
                if (isAppLocked(r)) {
                    markSessionUnlocked(r.packageName, r.mUserId);
                }
            }
        });
    }

    private void abortAnimation(ActivityRecord r) {
        if (r == null) return;
        try {
            if (r.getOptions() != null && r.getOptions().getRemoteAnimationAdapter() != null) {
                r.getOptions().getRemoteAnimationAdapter().getRunner().onAnimationCancelled();
            }
        } catch (Exception e) {
            Slog.w(TAG, "abortAnimation failed", e);
        }
        r.abortAndClearOptionsAnimation();
    }

    private int startActivityAsCaller(IApplicationThread caller, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo, String resultWho,
            int requestCode) {
        return mAtms.getActivityStartController()
                .obtainStarter(intent, "AppLock.startActivityAsCaller")
                .setCaller(caller)
                .setCallingPackage(callingPackage)
                .setResolvedType(resolvedType)
                .setResultTo(resultTo)
                .setResultWho(resultWho)
                .setRequestCode(requestCode)
                .execute();
    }

    private String resolveAppLabel(String packageName, int userId) {
        try {
            PackageManager pm = mContext.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfoAsUser(packageName, 0, userId);
            CharSequence label = pm.getApplicationLabel(ai);
            return label != null ? label.toString() : packageName;
        } catch (Exception e) {
            return packageName;
        }
    }

    private int getPackageUid(String packageName) {
        try {
            return mContext.getPackageManager().getApplicationInfo(packageName, 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private void enforceSettingsManager() {
        final int uid = Binder.getCallingUid();
        if (uid == Process.SYSTEM_UID || uid == Process.SHELL_UID) {
            return;
        }
        final String pkg = mContext.getPackageManager().getNameForUid(uid);
        if ("com.android.applock".equals(pkg)) {
            return;
        }
        throw new SecurityException("UID " + uid + " (" + pkg + ") cannot manage App Lock settings");
    }

    private void putSecureIntSetting(String key, int value) {
        final long token = Binder.clearCallingIdentity();
        try {
            Settings.Secure.putIntForUser(mContext.getContentResolver(), key, value,
                    UserHandle.USER_SYSTEM);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static final Set<String> ALLOWED_SECURE_STRING_KEYS = Set.of(
            AppLockManager.SETTING_SECURITY_TYPE,
            AppLockManager.SETTING_CREDENTIAL_HASH
    );

    private static final Set<String> ALLOWED_SECURE_INT_KEYS = Set.of(
            AppLockManager.SETTING_BIOMETRIC_ENABLED,
            AppLockManager.SETTING_PREFER_BIOMETRIC
    );

    @Override
    public boolean putSecureString(String key, String value) {
        enforceSettingsManager();
        if (!ALLOWED_SECURE_STRING_KEYS.contains(key)) {
            throw new SecurityException("App Lock cannot write Secure key: " + key);
        }
        final long token = Binder.clearCallingIdentity();
        try {
            return Settings.Secure.putStringForUser(mContext.getContentResolver(), key, value,
                    UserHandle.USER_SYSTEM);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean putSecureInt(String key, int value) {
        enforceSettingsManager();
        if (!ALLOWED_SECURE_INT_KEYS.contains(key)) {
            throw new SecurityException("App Lock cannot write Secure key: " + key);
        }
        putSecureIntSetting(key, value);
        return true;
    }
}
