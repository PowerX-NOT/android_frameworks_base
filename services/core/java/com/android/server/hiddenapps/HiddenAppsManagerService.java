/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.hiddenapps;

import android.app.HiddenAppsManager;
import android.app.HiddenAppInfo;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.app.IHiddenAppsManager;
import com.android.internal.app.IHiddenAppsStateListener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Central authority for hidden-app visibility across the system.
 */
public class HiddenAppsManagerService extends IHiddenAppsManager.Stub {
    private static final String TAG = "HiddenAppsManagerService";
    private static final boolean DEBUG = false;

    private static final String SETTINGS_PACKAGE = "com.android.applock";
    private static final String LAUNCHER_PACKAGE = "com.android.launcher3";
    private static final Set<String> BLACKLISTED_PACKAGES = Set.of(
            "android",
            SETTINGS_PACKAGE,
            "com.android.settings"
    );

    private Context mContext;
    private HiddenAppsController mController;

    /** Set by Launcher3 after App Lock auth while the hidden-apps drawer is visible. */
    private volatile boolean mAuthenticatedHiddenDrawerActive;

    /**
     * Complete-hide packages that may be resolved while {@link #mAuthenticatedHiddenDrawerActive}
     * is true (launcher launch + permission grant UI for those apps).
     */
    private final Set<String> mAuthenticatedDrawerCompleteHidePackages = new HashSet<>();

    private final RemoteCallbackList<IHiddenAppsStateListener> mListeners =
            new RemoteCallbackList<>();

    /** Intent being resolved for an activity start; scoped to the calling thread. */
    private final ThreadLocal<HiddenLaunchContext> mHiddenLaunchContext = new ThreadLocal<>();

    private static final class HiddenLaunchContext {
        final Intent intent;
        final int filterCallingUid;

        HiddenLaunchContext(Intent intent, int filterCallingUid) {
            this.intent = intent;
            this.filterCallingUid = filterCallingUid;
        }
    }

    private static final class Holder {
        private static final HiddenAppsManagerService INSTANCE = new HiddenAppsManagerService();
    }

    public static HiddenAppsManagerService get() {
        return Holder.INSTANCE;
    }

    private HiddenAppsManagerService() {
    }

    public static void systemReady(Context context) {
        HiddenAppsManagerService instance = get();
        instance.onSystemReady(context);
        ServiceManager.addService(Context.HIDDEN_APPS_SERVICE, instance);
        Slog.i(TAG, "registered " + Context.HIDDEN_APPS_SERVICE);
    }

    private void onSystemReady(Context context) {
        mContext = context;
        mController = new HiddenAppsController(context, BLACKLISTED_PACKAGES);
        mController.init();
    }

    // --- IHiddenAppsManager ---

    @Override
    public int getHiddenMode(String packageName) {
        return mController != null ? mController.getHiddenMode(packageName)
                : HiddenAppsManager.HIDE_NONE;
    }

    @Override
    public boolean isAppHidden(String packageName) {
        return mController != null && mController.isAppHidden(packageName);
    }

    @Override
    public boolean isAppCompletelyHidden(String packageName) {
        return mController != null && mController.isAppCompletelyHidden(packageName);
    }

    @Override
    public boolean shouldHideFromLauncher(String packageName) {
        return mController != null && mController.shouldHideFromLauncher(packageName);
    }

    @Override
    public void setHiddenMode(String packageName, int mode) {
        enforceManageHiddenApps();
        if (DEBUG) {
            Slog.i(TAG, "setHiddenMode pkg=" + packageName + " mode=" + modeName(mode)
                    + " caller=" + callerLabel(Binder.getCallingUid()));
        }
        mController.setHiddenMode(packageName, mode);
        notifyHiddenAppsChanged();
    }

    @Override
    public void setAllowNotificationsFromHiddenApps(boolean allow) {
        enforceManageHiddenApps();
        if (DEBUG) {
            Slog.i(TAG, "setAllowNotificationsFromHiddenApps allow=" + allow
                    + " caller=" + callerLabel(Binder.getCallingUid()));
        }
        mController.setAllowNotificationsFromHiddenApps(allow);
        notifyHiddenAppsChanged();
    }

    @Override
    public boolean isAllowNotificationsFromHiddenApps() {
        return mController != null && mController.isAllowNotificationsFromHiddenApps();
    }

    @Override
    public List<String> getHideablePackages() {
        return mController != null ? mController.getHideablePackages() : List.of();
    }

    @Override
    public List<String> getHiddenPackages() {
        return mController != null ? mController.getHiddenPackages() : List.of();
    }

    @Override
    public List<HiddenAppInfo> getHiddenAppsForDrawer(String callingPackage) {
        enforceDrawerCaller(callingPackage);
        if (mController == null) {
            return List.of();
        }
        final List<HiddenAppInfo> entries = new ArrayList<>();
        final PackageManager pm = mContext.getPackageManager();
        final long token = Binder.clearCallingIdentity();
        try {
            for (String pkg : mController.getHiddenPackages()) {
                if (TextUtils.isEmpty(pkg) || !mController.isAppHidden(pkg)) {
                    continue;
                }
                String label = pkg;
                ComponentName launchComponent = null;
                Bitmap icon = null;
                try {
                    ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                    label = info.loadLabel(pm).toString();
                    icon = drawableToBitmap(info.loadIcon(pm));
                    Intent launch = pm.getLaunchIntentForPackage(pkg);
                    if (launch != null) {
                        launchComponent = launch.getComponent();
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    Slog.w(TAG, "hidden drawer pkg not found: " + pkg);
                }
                entries.add(new HiddenAppInfo(pkg, label, launchComponent, icon));
                if (DEBUG) {
                    Slog.i(TAG, "drawer entry pkg=" + pkg + " label=" + label
                            + " launch=" + launchComponent);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        if (DEBUG) {
            Slog.i(TAG, "getHiddenAppsForDrawer caller=" + callingPackage
                    + " count=" + entries.size());
        }
        return entries;
    }

    @Override
    public void setAuthenticatedHiddenDrawerActive(boolean active) {
        enforceLauncherCaller();
        mAuthenticatedHiddenDrawerActive = active;
        synchronized (mAuthenticatedDrawerCompleteHidePackages) {
            mAuthenticatedDrawerCompleteHidePackages.clear();
            if (active && mController != null) {
                for (String pkg : mController.getHiddenPackages()) {
                    if (mController.isAppCompletelyHidden(pkg)) {
                        mAuthenticatedDrawerCompleteHidePackages.add(pkg);
                    }
                }
            }
        }
        if (DEBUG) {
            Slog.i(TAG, "authenticatedHiddenDrawerActive=" + active
                    + " pkgs=" + mAuthenticatedDrawerCompleteHidePackages
                    + " caller=" + callerLabel(Binder.getCallingUid()));
        }
    }

    @Override
    public void registerHiddenAppsStateListener(IHiddenAppsStateListener listener) {
        if (listener != null) {
            mListeners.register(listener);
        }
    }

    @Override
    public void unregisterHiddenAppsStateListener(IHiddenAppsStateListener listener) {
        if (listener != null) {
            mListeners.unregister(listener);
        }
    }

    // --- System-server hooks (PackageManager, LauncherApps, notifications) ---

    /**
     * Whether {@code targetPackage} should be omitted from launcher activity enumeration for
     * {@code callingUid}.
     */
    public boolean shouldFilterFromLauncher(String targetPackage, int callingUid) {
        if (mController == null || TextUtils.isEmpty(targetPackage)) return false;
        if (!mController.shouldHideFromLauncher(targetPackage)) {
            return false;
        }
        final boolean bypass = canBypassHiddenFilter(callingUid);
        final boolean filter = !bypass;
        if (DEBUG && filter) {
            Slog.i(TAG, "filter launcher pkg=" + targetPackage + " mode="
                    + modeName(mController.getHiddenMode(targetPackage))
                    + " caller=" + callerLabel(callingUid));
        }
        return filter;
    }

    /**
     * Whether {@code targetPackage} should be treated as not installed for package-manager queries
     * from {@code callingUid}.
     */
    public boolean shouldFilterFromPackageManager(String targetPackage, int callingUid) {
        if (mController == null || TextUtils.isEmpty(targetPackage)) return false;
        if (!mController.isAppCompletelyHidden(targetPackage)) {
            return false;
        }
        if (canAccessCompletelyHiddenPackage(targetPackage, callingUid)) {
            return false;
        }
        final boolean bypass = canBypassHiddenFilter(callingUid);
        final boolean filter = !bypass;
        if (filter) {
            if (mAuthenticatedHiddenDrawerActive) {
                Slog.w(TAG, "filter pm pkg=" + targetPackage + " mode=complete caller="
                        + callerLabel(callingUid) + " sessionPkgs="
                        + mAuthenticatedDrawerCompleteHidePackages);
            } else if (DEBUG) {
                Slog.i(TAG, "filter pm pkg=" + targetPackage + " mode=complete caller="
                        + callerLabel(callingUid));
            }
        }
        return filter;
    }

    /**
     * Records a successful hidden-drawer launch so the package stays accessible for the
     * remainder of the authenticated session (permission UI, app self-queries, etc.).
     */
    public void onHiddenDrawerAppLaunched(String packageName) {
        if (TextUtils.isEmpty(packageName) || mController == null) {
            return;
        }
        if (!mController.isAppCompletelyHidden(packageName)) {
            return;
        }
        synchronized (mAuthenticatedDrawerCompleteHidePackages) {
            mAuthenticatedDrawerCompleteHidePackages.add(packageName);
        }
    }

    /**
     * Whether {@code callingUid} may access a {@link HiddenAppsManager#HIDE_COMPLETE} package
     * during an authenticated hidden-drawer session.
     */
    private boolean canAccessCompletelyHiddenPackage(String targetPackage, int callingUid) {
        if (!mController.isAppCompletelyHidden(targetPackage)) {
            return false;
        }
        if (!mAuthenticatedHiddenDrawerActive) {
            return isHiddenDrawerLaunchAllowed(callingUid);
        }
        synchronized (mAuthenticatedDrawerCompleteHidePackages) {
            if (mAuthenticatedDrawerCompleteHidePackages.contains(targetPackage)) {
                // While the drawer session is active, allow all PM access to these packages
                // (launcher, PermissionController, the running app, etc.).
                return true;
            }
        }
        return isHiddenDrawerLaunchAllowed(callingUid);
    }

    /**
     * Whether {@code callingUid} may resolve or launch {@link HiddenAppsManager#HIDE_COMPLETE}
     * packages from the authenticated hidden-apps drawer.
     */
    public boolean isHiddenDrawerLaunchAllowed(@Nullable Intent intent, int callingUid) {
        if (!isLauncherCaller(callingUid)) {
            return false;
        }
        if (mAuthenticatedHiddenDrawerActive) {
            return true;
        }
        if (intent != null && intent.getBooleanExtra(
                HiddenAppsManager.EXTRA_ALLOW_HIDDEN_LAUNCH, false)) {
            return true;
        }
        HiddenLaunchContext ctx = mHiddenLaunchContext.get();
        return ctx != null && ctx.intent != null && ctx.intent.getBooleanExtra(
                HiddenAppsManager.EXTRA_ALLOW_HIDDEN_LAUNCH, false);
    }

    /** @see #isHiddenDrawerLaunchAllowed(Intent, int) */
    public boolean isHiddenDrawerLaunchAllowed(int callingUid) {
        return isHiddenDrawerLaunchAllowed(null, callingUid);
    }

    /**
     * Tracks the intent being resolved so PM filtering can honor hidden-drawer launches.
     */
    public void pushHiddenLaunchContext(Intent intent, int filterCallingUid) {
        if (intent == null) {
            mHiddenLaunchContext.remove();
            return;
        }
        mHiddenLaunchContext.set(new HiddenLaunchContext(new Intent(intent), filterCallingUid));
    }

    /** Clears {@link #pushHiddenLaunchContext(Intent, int)} state for the current thread. */
    public void popHiddenLaunchContext() {
        mHiddenLaunchContext.remove();
    }

    /**
     * Whether notifications from {@code packageName} should be suppressed.
     */
    public boolean shouldSuppressNotification(String packageName) {
        if (mController == null || !mController.isAppCompletelyHidden(packageName)) {
            return false;
        }
        final boolean suppress = !mController.isAllowNotificationsFromHiddenApps();
        if (DEBUG && suppress) {
            Slog.i(TAG, "suppress notification pkg=" + packageName);
        }
        return suppress;
    }

    public void onPackageRemoved(String packageName) {
        if (mController != null) {
            mController.cleanupPackage(packageName);
        }
    }

    private boolean isLauncherCaller(int callingUid) {
        if (UserHandle.getAppId(callingUid) < Process.FIRST_APPLICATION_UID) {
            return false;
        }
        final String[] packages = mContext.getPackageManager().getPackagesForUid(callingUid);
        if (packages == null) {
            return false;
        }
        for (String pkg : packages) {
            if (LAUNCHER_PACKAGE.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    private void enforceLauncherCaller() {
        if (!isLauncherCaller(Binder.getCallingUid())) {
            throw new SecurityException("Only " + LAUNCHER_PACKAGE
                    + " may change hidden drawer session state");
        }
    }

    private boolean canBypassHiddenFilter(int callingUid) {
        // Only allow bypass for calls that originate from within system_server itself.
        // Do not grant blanket bypass to all callers with system/shared UIDs (e.g. Settings app).
        final int callingPid = Binder.getCallingPid();
        if (callingPid == Process.myPid()) {
            if (DEBUG) {
                Slog.d(TAG, "bypass uid=" + callingUid + " pid=" + callingPid
                        + " reason=system_server_internal");
            }
            return true;
        }
        final String[] packages = mContext.getPackageManager().getPackagesForUid(callingUid);
        if (packages == null) {
            return false;
        }
        for (String pkg : packages) {
            if (SETTINGS_PACKAGE.equals(pkg)) {
                if (DEBUG) {
                    Slog.d(TAG, "bypass uid=" + callingUid + " pkg=" + pkg + " reason=settings");
                }
                return true;
            }
        }
        return false;
    }

    private void enforceManageHiddenApps() {
        final int uid = Binder.getCallingUid();
        if (UserHandle.getAppId(uid) < Process.FIRST_APPLICATION_UID) {
            return;
        }
        final String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
        if (packages != null) {
            for (String pkg : packages) {
                if (SETTINGS_PACKAGE.equals(pkg)) {
                    return;
                }
            }
        }
        throw new SecurityException("Only " + SETTINGS_PACKAGE + " may manage hidden apps");
    }

    private void enforceDrawerCaller(String callingPackage) {
        final int uid = Binder.getCallingUid();
        if (UserHandle.getAppId(uid) < Process.FIRST_APPLICATION_UID) {
            return;
        }
        final String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
        if (packages != null) {
            for (String pkg : packages) {
                if (LAUNCHER_PACKAGE.equals(pkg)) {
                    if (DEBUG && !LAUNCHER_PACKAGE.equals(callingPackage)) {
                        Slog.w(TAG, "drawer caller package mismatch reported="
                                + callingPackage + " actual=" + pkg);
                    }
                    return;
                }
            }
        }
        Slog.e(TAG, "drawer caller DENIED uid=" + uid + " reportedPkg=" + callingPackage
                + " uidPkgs=" + (packages != null ? String.join(",", packages) : "null"));
        throw new SecurityException("Only " + LAUNCHER_PACKAGE + " may query hidden apps for drawer");
    }

    private void notifyHiddenAppsChanged() {
        final int n = mListeners.beginBroadcast();
        try {
            for (int i = 0; i < n; i++) {
                try {
                    mListeners.getBroadcastItem(i).onHiddenAppsChanged();
                } catch (RemoteException e) {
                    // ignore dead listener
                }
            }
        } finally {
            mListeners.finishBroadcast();
        }
    }

    private String callerLabel(int uid) {
        if (mContext == null) {
            return Integer.toString(uid);
        }
        final String[] pkgs = mContext.getPackageManager().getPackagesForUid(uid);
        if (pkgs == null || pkgs.length == 0) {
            return "uid:" + uid;
        }
        return pkgs[0] + "(" + uid + ")";
    }

    private static String modeName(int mode) {
        return switch (mode) {
            case HiddenAppsManager.HIDE_COMPLETE -> "complete";
            case HiddenAppsManager.HIDE_LAUNCHER -> "launcher";
            default -> "none";
        };
    }

    @Nullable
    private static Bitmap drawableToBitmap(@Nullable Drawable drawable) {
        if (drawable == null) {
            return null;
        }
        if (drawable instanceof BitmapDrawable bitmapDrawable) {
            Bitmap bitmap = bitmapDrawable.getBitmap();
            if (bitmap != null) {
                return bitmap;
            }
        }
        final int width = Math.max(1, drawable.getIntrinsicWidth());
        final int height = Math.max(1, drawable.getIntrinsicHeight());
        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        return bitmap;
    }
}
