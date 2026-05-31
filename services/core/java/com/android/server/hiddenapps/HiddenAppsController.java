/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.hiddenapps;

import android.app.HiddenAppsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persists per-package hide mode for the Hidden Apps framework service. */
public class HiddenAppsController {
    private static final String TAG = "HiddenAppsController";

    private static final String KEY_PACKAGES = "packages";

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final LauncherApps mLauncherApps;
    private final Set<String> mBlacklistedPackages;
    private final Handler mHandler;

    private final Map<String, Integer> mHiddenModes = new HashMap<>();
    private boolean mAllowNotificationsFromHidden;

    private ContentObserver mConfigObserver;
    private ContentObserver mAllowNotificationsObserver;

    public HiddenAppsController(Context context, Set<String> blacklistedPackages) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        mLauncherApps = context.getSystemService(LauncherApps.class);
        mBlacklistedPackages = blacklistedPackages;
        mHandler = new Handler(Looper.getMainLooper());
    }

    public void init() {
        registerSettingsObservers();
        loadFromSettings();
    }

    private void registerSettingsObservers() {
        mConfigObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                loadHiddenModes();
            }
        };
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(HiddenAppsManager.SETTING_CONFIG),
                false, mConfigObserver, UserHandle.USER_ALL);

        mAllowNotificationsObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                loadAllowNotifications();
            }
        };
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(HiddenAppsManager.SETTING_ALLOW_NOTIFICATIONS),
                false, mAllowNotificationsObserver, UserHandle.USER_ALL);
    }

    private void loadFromSettings() {
        loadHiddenModes();
        loadAllowNotifications();
    }

    private void loadAllowNotifications() {
        mAllowNotificationsFromHidden = Settings.Secure.getIntForUser(mContentResolver,
                HiddenAppsManager.SETTING_ALLOW_NOTIFICATIONS, 0, UserHandle.USER_SYSTEM) != 0;
    }

    private void loadHiddenModes() {
        String jsonStr = Settings.Secure.getString(mContentResolver, HiddenAppsManager.SETTING_CONFIG);
        synchronized (this) {
            mHiddenModes.clear();
            if (!TextUtils.isEmpty(jsonStr)) {
                try {
                    JSONObject config = new JSONObject(jsonStr);
                    JSONObject pkgs = config.optJSONObject(KEY_PACKAGES);
                    if (pkgs != null) {
                        Iterator<String> keys = pkgs.keys();
                        while (keys.hasNext()) {
                            String pkg = keys.next();
                            int mode = pkgs.optInt(pkg, HiddenAppsManager.HIDE_NONE);
                            if (!TextUtils.isEmpty(pkg) && isValidMode(mode)
                                    && mode != HiddenAppsManager.HIDE_NONE) {
                                mHiddenModes.put(pkg, mode);
                            }
                        }
                    }
                } catch (JSONException e) {
                    Slog.e(TAG, "Failed to parse hiddenapps_config JSON", e);
                }
            }
        }
    }

    public boolean isAllowNotificationsFromHiddenApps() {
        return mAllowNotificationsFromHidden;
    }

    public void setAllowNotificationsFromHiddenApps(boolean allow) {
        runWithClearCallingIdentity(() -> Settings.Secure.putIntForUser(mContentResolver,
                HiddenAppsManager.SETTING_ALLOW_NOTIFICATIONS, allow ? 1 : 0,
                UserHandle.USER_SYSTEM));
        mAllowNotificationsFromHidden = allow;
    }

    @HiddenAppsManager.HideMode
    public int getHiddenMode(String packageName) {
        if (TextUtils.isEmpty(packageName)) return HiddenAppsManager.HIDE_NONE;
        synchronized (this) {
            return mHiddenModes.getOrDefault(packageName, HiddenAppsManager.HIDE_NONE);
        }
    }

    public boolean isAppCompletelyHidden(String packageName) {
        return getHiddenMode(packageName) == HiddenAppsManager.HIDE_COMPLETE;
    }

    public boolean shouldHideFromLauncher(String packageName) {
        int mode = getHiddenMode(packageName);
        return mode == HiddenAppsManager.HIDE_LAUNCHER
                || mode == HiddenAppsManager.HIDE_COMPLETE;
    }

    public void setHiddenMode(String packageName, @HiddenAppsManager.HideMode int mode) {
        if (TextUtils.isEmpty(packageName) || !isValidMode(mode)) return;
        if (mode != HiddenAppsManager.HIDE_NONE && !isPackageHideable(packageName)) {
            Slog.w(TAG, "Cannot hide package - not hideable: " + packageName);
            return;
        }
        synchronized (this) {
            if (mode == HiddenAppsManager.HIDE_NONE) {
                if (mHiddenModes.remove(packageName) != null) {
                    saveConfigToSettings();
                }
            } else if (!mHiddenModes.containsKey(packageName)
                    || mHiddenModes.get(packageName) != mode) {
                mHiddenModes.put(packageName, mode);
                saveConfigToSettings();
            }
        }
    }

    public List<String> getHiddenPackages() {
        synchronized (this) {
            return new ArrayList<>(mHiddenModes.keySet());
        }
    }

    public List<String> getHideablePackages() {
        List<String> result = new ArrayList<>();
        if (mLauncherApps != null) {
            try {
                List<LauncherActivityInfo> activities = mLauncherApps.getActivityList(
                        null, UserHandle.of(UserHandle.USER_SYSTEM));
                Set<String> seen = new HashSet<>();
                for (LauncherActivityInfo info : activities) {
                    String pkgName = info.getApplicationInfo().packageName;
                    if (!mBlacklistedPackages.contains(pkgName) && !seen.contains(pkgName)) {
                        result.add(pkgName);
                        seen.add(pkgName);
                    }
                }
            } catch (Exception e) {
                Slog.e(TAG, "Failed to get hideable packages from LauncherApps", e);
            }
        }
        if (result.isEmpty()) {
            try {
                PackageManager pm = mContext.getPackageManager();
                List<ApplicationInfo> apps = pm.getInstalledApplications(0);
                for (ApplicationInfo appInfo : apps) {
                    if (mBlacklistedPackages.contains(appInfo.packageName)) continue;
                    if (isSystemUid(appInfo.uid)) continue;
                    if (pm.getLaunchIntentForPackage(appInfo.packageName) != null) {
                        result.add(appInfo.packageName);
                    }
                }
            } catch (Exception e) {
                Slog.e(TAG, "Failed to get hideable packages from PackageManager", e);
            }
        }
        return result;
    }

    public boolean isPackageHideable(String packageName) {
        if (TextUtils.isEmpty(packageName)) return false;
        if (mBlacklistedPackages.contains(packageName)) return false;
        return getHideablePackages().contains(packageName);
    }

    public void cleanupPackage(String packageName) {
        synchronized (this) {
            if (mHiddenModes.remove(packageName) != null) {
                saveConfigToSettings();
            }
        }
    }

    private void saveConfigToSettings() {
        try {
            JSONObject config = new JSONObject();
            JSONObject pkgs = new JSONObject();
            synchronized (this) {
                for (Map.Entry<String, Integer> entry : mHiddenModes.entrySet()) {
                    pkgs.put(entry.getKey(), entry.getValue());
                }
            }
            config.put(KEY_PACKAGES, pkgs);
            final String json = config.toString();
            runWithClearCallingIdentity(() ->
                    Settings.Secure.putString(mContentResolver, HiddenAppsManager.SETTING_CONFIG,
                            json));
        } catch (JSONException e) {
            Slog.e(TAG, "Failed to save hiddenapps_config JSON", e);
        }
    }

    private static boolean isValidMode(int mode) {
        return mode == HiddenAppsManager.HIDE_NONE
                || mode == HiddenAppsManager.HIDE_LAUNCHER
                || mode == HiddenAppsManager.HIDE_COMPLETE;
    }

    private static void runWithClearCallingIdentity(Runnable action) {
        final long token = Binder.clearCallingIdentity();
        try {
            action.run();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static boolean isSystemUid(int uid) {
        int appId = UserHandle.getAppId(uid);
        return appId == android.os.Process.ROOT_UID || appId == android.os.Process.SYSTEM_UID;
    }
}
