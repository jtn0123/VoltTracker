package com.volttracker.obdpoc

import android.webkit.JavascriptInterface

/**
 * Bridge surface for [SharedDisplayPrefs], the native source of truth for the display
 * preferences both dashboards share. Both calls are synchronous: prefs.ts reads the snapshot
 * while it boots, before first paint, and SharedPreferences is safe to touch from the bridge
 * thread.
 */
open class VoltBridgeSharedPrefs(
    private val host: DashboardHost,
) {
    /** JSON object of every shared pref that has been set; `{}` before the prefs exist. */
    @JavascriptInterface
    fun getSharedPrefs(): String = host.sharedDisplayPrefs()?.snapshotJson() ?: "{}"

    /** Stores one shared pref from its JSON literal; false for an unknown key or a bad value. */
    @JavascriptInterface
    fun setSharedPref(
        key: String?,
        json: String?,
    ): Boolean = host.sharedDisplayPrefs()?.set(key.orEmpty(), json) ?: false
}
