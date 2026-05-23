package com.android.internal.app;

import com.android.internal.app.IAppLockStateListener;
import com.android.internal.app.IAppSessionListener;

/**
 * System App Lock service ({@code applock}).
 * @hide
 */
interface IAppLockManager {
    boolean isEnabled();
    void setEnabled(boolean enabled);

    int getLockBehavior();
    void setLockBehavior(int behavior);
    int getLockTimeout();
    void setLockTimeout(int timeoutSeconds);

    boolean isHideNotificationContentEnabled();
    void setHideNotificationContent(boolean hide);

    int getAppLockState(String packageName);
    int getAppLockStateForUser(String packageName, int userId);

    void addLockedApp(String packageName);
    void removeLockedApp(String packageName);

    List<String> getLockedPackages();
    List<String> getLockablePackages();
    boolean isPackageLockable(String packageName);

    void unlockApp(String packageName, int userId);
    void promptUnlock(String packageName, int userId);

    void registerAppLockStateListener(IAppLockStateListener listener);
    void unregisterAppLockStateListener(IAppLockStateListener listener);
    void registerAppSessionListener(IAppSessionListener listener);
    void unregisterAppSessionListener(IAppSessionListener listener);

    /** Write an App Lock-owned Secure setting (com.android.applock only). */
    boolean putSecureString(String key, String value);
    /** Write an App Lock-owned Secure setting (com.android.applock only). */
    boolean putSecureInt(String key, int value);
}
