/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.app;

/**
 * Callback for locked-app list changes on {@link IAppLockManager}.
 * @hide
 */
oneway interface IAppLockStateListener {
    void onAppLockStateChanged(String packageName, boolean locked);
}
