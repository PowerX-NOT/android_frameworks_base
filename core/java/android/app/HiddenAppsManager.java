/*
 * SPDX-License-Identifier: Apache-2.0
 */
package android.app;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;

import com.android.internal.app.IHiddenAppsManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.List;

/**
 * System-level Hidden Apps API for {@code com.android.applock} and privileged system components.
 *
 * @hide
 */
@SystemService(Context.HIDDEN_APPS_SERVICE)
public class HiddenAppsManager {

    /** App is visible everywhere. */
    public static final int HIDE_NONE = 0;
    /** Hide from launcher home screen only. */
    public static final int HIDE_LAUNCHER = 1;
    /** Hide from launcher, Settings, PM queries, recents, and related surfaces. */
    public static final int HIDE_COMPLETE = 2;

    /** @hide */
    @IntDef({HIDE_NONE, HIDE_LAUNCHER, HIDE_COMPLETE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface HideMode {}

    /** @hide */
    public static final String SETTING_CONFIG = "hiddenapps_config";
    /** @hide */
    public static final String SETTING_ALLOW_NOTIFICATIONS = "hiddenapps_allow_notifications";

    private final IHiddenAppsManager mService;

    /** @hide */
    public HiddenAppsManager(@NonNull Context context, @NonNull IHiddenAppsManager service) {
        mService = service;
    }

    /** @hide */
    @HideMode
    public int getHiddenMode(@NonNull String packageName) {
        try {
            return mService.getHiddenMode(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public boolean shouldHideFromLauncher(@NonNull String packageName) {
        try {
            return mService.shouldHideFromLauncher(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void setHiddenMode(@NonNull String packageName, @HideMode int mode) {
        try {
            mService.setHiddenMode(packageName, mode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public boolean isAllowNotificationsFromHiddenApps() {
        try {
            return mService.isAllowNotificationsFromHiddenApps();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void setAllowNotificationsFromHiddenApps(boolean allow) {
        try {
            mService.setAllowNotificationsFromHiddenApps(allow);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @NonNull
    public List<String> getHideablePackages() {
        try {
            List<String> list = mService.getHideablePackages();
            return list != null ? list : Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @NonNull
    public List<String> getHiddenPackages() {
        try {
            List<String> list = mService.getHiddenPackages();
            return list != null ? list : Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns hidden apps with labels and launch components for the authenticated drawer.
     *
     * @hide
     */
    @NonNull
    public List<HiddenAppInfo> getHiddenAppsForDrawer() {
        try {
            List<HiddenAppInfo> list = mService.getHiddenAppsForDrawer();
            return list != null ? list : Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Tells the system the authenticated hidden-apps drawer is showing in Launcher3.
     * While active, Launcher3 may resolve and launch {@link #HIDE_COMPLETE} packages.
     *
     * @hide
     */
    public void setAuthenticatedHiddenDrawerActive(boolean active) {
        try {
            mService.setAuthenticatedHiddenDrawerActive(active);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
