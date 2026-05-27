/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.hiddenapps;

import android.app.HiddenAppsManager;
import android.content.Context;
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

import java.util.List;
import java.util.Set;

/**
 * Central authority for hidden-app visibility across the system.
 */
public class HiddenAppsManagerService extends IHiddenAppsManager.Stub {
    private static final String TAG = "HiddenAppsManagerService";
    private static final boolean DEBUG = true;

    private static final String SETTINGS_PACKAGE = "com.android.applock";
    private static final Set<String> BLACKLISTED_PACKAGES = Set.of(
            "android",
            SETTINGS_PACKAGE,
            "com.android.settings"
    );

    private Context mContext;
    private HiddenAppsController mController;

    private final RemoteCallbackList<IHiddenAppsStateListener> mListeners =
            new RemoteCallbackList<>();

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
        final boolean bypass = canBypassHiddenFilter(callingUid);
        final boolean filter = !bypass;
        if (DEBUG && filter) {
            Slog.i(TAG, "filter pm pkg=" + targetPackage + " mode=complete caller="
                    + callerLabel(callingUid));
        }
        return filter;
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
}
