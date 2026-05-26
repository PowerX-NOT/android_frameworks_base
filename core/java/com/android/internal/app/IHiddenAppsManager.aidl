package com.android.internal.app;

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

    void registerHiddenAppsStateListener(IHiddenAppsStateListener listener);
    void unregisterHiddenAppsStateListener(IHiddenAppsStateListener listener);
}
