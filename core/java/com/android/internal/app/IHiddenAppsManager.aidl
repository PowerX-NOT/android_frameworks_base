package com.android.internal.app;

import android.app.HiddenAppInfo;

/**
 * System Hidden Apps service ({@code hiddenapps}).
 * @hide
 */
interface IHiddenAppsManager {
    int getHiddenMode(String packageName);
    boolean shouldHideFromLauncher(String packageName);

    void setHiddenMode(String packageName, int mode);
    void setAllowNotificationsFromHiddenApps(boolean allow);
    boolean isAllowNotificationsFromHiddenApps();

    List<String> getHideablePackages();
    List<String> getHiddenPackages();

    /** Returns hidden apps with labels for the authenticated launcher drawer. */
    List<HiddenAppInfo> getHiddenAppsForDrawer();

    /** Notifies the system that the authenticated hidden-apps drawer is open or closed. */
    void setAuthenticatedHiddenDrawerActive(boolean active);
}
