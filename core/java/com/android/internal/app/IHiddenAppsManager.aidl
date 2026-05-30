package com.android.internal.app;

import android.app.HiddenAppInfo;
import com.android.internal.app.IHiddenAppsStateListener;

/**
 * System Hidden Apps service ({@code hiddenapps}).
 * @hide
 */
interface IHiddenAppsManager {
    int getHiddenMode(String packageName);
    boolean isAppHidden(String packageName);
    boolean isAppCompletelyHidden(String packageName);
    boolean shouldHideFromLauncher(String packageName);

    void setHiddenMode(String packageName, int mode);
    void setAllowNotificationsFromHiddenApps(boolean allow);
    boolean isAllowNotificationsFromHiddenApps();

    List<String> getHideablePackages();
    List<String> getHiddenPackages();

    /** Returns hidden apps with labels for the authenticated launcher drawer. */
    List<HiddenAppInfo> getHiddenAppsForDrawer(String callingPackage);

    /** Notifies the system that the authenticated hidden-apps drawer is open or closed. */
    void setAuthenticatedHiddenDrawerActive(boolean active);

    void registerHiddenAppsStateListener(IHiddenAppsStateListener listener);
    void unregisterHiddenAppsStateListener(IHiddenAppsStateListener listener);
}
