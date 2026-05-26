package com.android.internal.app;

/**
 * Callback when hidden-app configuration changes.
 * @hide
 */
oneway interface IHiddenAppsStateListener {
    void onHiddenAppsChanged();
}
