/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.app;

/**
 * Callback for per-app unlock session changes on {@link IAppLockManager}.
 * @hide
 */
oneway interface IAppSessionListener {
    void onAppUnlocked(String packageName, int userId);
    void onAppLocked(String packageName, int userId);
}
