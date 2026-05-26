/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.hiddenapps;

import android.app.HiddenAppsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
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
        return mController.isAppHidden(packageName);
    }

    @Override
    public boolean isAppCompletelyHidden(String packageName) {
        return mController.isAppCompletelyHidden(packageName);
    }

    @Override
    public boolean shouldHideFromLauncher(String packageName) {
        return mController.shouldHideFromLauncher(packageName);
    }

    @Override
    public void setHiddenMode(String packageName, int mode) {
        enforceManageHiddenApps();
        mController.setHiddenMode(packageName, mode);
        notifyHiddenAppsChanged();
    }

    @Override
    public void setAllowNotificationsFromHiddenApps(boolean allow) {
        enforceManageHiddenApps();
        mController.setAllowNotificationsFromHiddenApps(allow);
        notifyHiddenAppsChanged();
    }

    @Override
    public boolean isAllowNotificationsFromHiddenApps() {
        return mController.isAllowNotificationsFromHiddenApps();
    }

    @Override
    public List<String> getHideablePackages() {
        return mController.getHideablePackages();
    }

    @Override
    public List<String> getHiddenPackages() {
        return mController.getHiddenPackages();
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
        if (mController == null) return false;
        if (!mController.shouldHideFromLauncher(targetPackage)) {
            return false;
        }
        return !canBypassHiddenFilter(callingUid);
    }

    /**
     * Whether {@code targetPackage} should be treated as not installed for package-manager queries
     * from {@code callingUid}.
     */
    public boolean shouldFilterFromPackageManager(String targetPackage, int callingUid) {
        if (mController == null) return false;
        if (!mController.isAppCompletelyHidden(targetPackage)) {
            return false;
        }
        return !canBypassHiddenFilter(callingUid);
    }

    /**
     * Whether notifications from {@code packageName} should be suppressed.
     */
    public boolean shouldSuppressNotification(String packageName) {
        if (mController == null) return false;
        if (!mController.isAppCompletelyHidden(packageName)) {
            return false;
        }
        return !mController.isAllowNotificationsFromHiddenApps();
    }

    public void onPackageRemoved(String packageName) {
        if (mController != null) {
            mController.cleanupPackage(packageName);
        }
    }

    private boolean canBypassHiddenFilter(int callingUid) {
        final int appId = UserHandle.getAppId(callingUid);
        if (appId < Process.FIRST_APPLICATION_UID) {
            return true;
        }
        final String[] packages = mContext.getPackageManager().getPackagesForUid(callingUid);
        if (packages == null) {
            return false;
        }
        for (String pkg : packages) {
            if (SETTINGS_PACKAGE.equals(pkg)) {
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
}
