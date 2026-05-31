/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.hiddenapps;

import android.app.HiddenAppsManager;
import android.app.HiddenAppInfo;
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
import android.os.ServiceManager;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.app.IHiddenAppsManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Central authority for hidden-app visibility across the system.
 */
public class HiddenAppsManagerService extends IHiddenAppsManager.Stub {
    private static final String TAG = "HiddenAppsManagerService";

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
     * Complete-hide packages accessible while {@link #mAuthenticatedHiddenDrawerActive} is true.
     */
    private final Set<String> mAuthenticatedDrawerCompleteHidePackages = new HashSet<>();

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
    public boolean shouldHideFromLauncher(String packageName) {
        return mController != null && mController.shouldHideFromLauncher(packageName);
    }

    @Override
    public void setHiddenMode(String packageName, int mode) {
        enforceManageHiddenApps();
        mController.setHiddenMode(packageName, mode);
    }

    @Override
    public void setAllowNotificationsFromHiddenApps(boolean allow) {
        enforceManageHiddenApps();
        mController.setAllowNotificationsFromHiddenApps(allow);
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
    public List<HiddenAppInfo> getHiddenAppsForDrawer() {
        enforceLauncherCaller();
        if (mController == null) {
            return List.of();
        }
        final List<HiddenAppInfo> entries = new ArrayList<>();
        final PackageManager pm = mContext.getPackageManager();
        final long token = Binder.clearCallingIdentity();
        try {
            for (String pkg : mController.getHiddenPackages()) {
                if (TextUtils.isEmpty(pkg)) {
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
            }
        } finally {
            Binder.restoreCallingIdentity(token);
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
        return !canBypassHiddenFilter(callingUid);
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
        if (canAccessCompletelyHiddenPackage(targetPackage)) {
            return false;
        }
        return !canBypassHiddenFilter(callingUid);
    }

    /**
     * Records a launch from the authenticated hidden drawer so the package stays PM-visible
     * for the rest of the session.
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
     * Whether {@code callingUid} may launch from the authenticated hidden-apps drawer.
     */
    public boolean isHiddenDrawerLaunchAllowed(int callingUid) {
        return isLauncherCaller(callingUid) && mAuthenticatedHiddenDrawerActive;
    }

    /**
     * Whether notifications from {@code packageName} should be suppressed.
     */
    public boolean shouldSuppressNotification(String packageName) {
        if (mController == null || !mController.isAppCompletelyHidden(packageName)) {
            return false;
        }
        return !mController.isAllowNotificationsFromHiddenApps();
    }

    public void onPackageRemoved(String packageName) {
        if (mController != null) {
            mController.cleanupPackage(packageName);
        }
    }

    private boolean canAccessCompletelyHiddenPackage(String targetPackage) {
        if (!mAuthenticatedHiddenDrawerActive || mController == null) {
            return false;
        }
        if (!mController.isAppCompletelyHidden(targetPackage)) {
            return false;
        }
        synchronized (mAuthenticatedDrawerCompleteHidePackages) {
            return mAuthenticatedDrawerCompleteHidePackages.contains(targetPackage);
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
                    + " may access hidden drawer APIs");
        }
    }

    private boolean canBypassHiddenFilter(int callingUid) {
        // Only allow bypass for calls that originate from within system_server itself.
        final int callingPid = Binder.getCallingPid();
        if (callingPid == Process.myPid()) {
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

    private static Bitmap drawableToBitmap(Drawable drawable) {
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
