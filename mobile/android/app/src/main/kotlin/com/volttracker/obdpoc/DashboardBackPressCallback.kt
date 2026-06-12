package com.volttracker.obdpoc

import android.webkit.WebView
import androidx.activity.OnBackPressedCallback

/**
 * System Back: give the dashboard SPA first crack at it (close a modal, exit fullscreen, or
 * return to the Drive home tab) before the OS backgrounds/exits the app. evaluateJavascript is
 * async, so the press is always consumed here; [exitToBackground] re-dispatches the default Back
 * behavior only if the dashboard reports it had nothing to dismiss.
 */
class DashboardBackPressCallback(
    private val webView: () -> WebView?,
    private val exitToBackground: () -> Unit,
) : OnBackPressedCallback(true) {
    override fun handleOnBackPressed() {
        val wv = webView()
        if (wv == null) {
            exitToBackground()
            return
        }
        wv.evaluateJavascript(BACK_PROBE_JS) { result ->
            if (result != "true") {
                exitToBackground()
            }
        }
    }

    companion object {
        /** JS probe asking the dashboard whether it consumed the Back press. */
        internal const val BACK_PROBE_JS =
            "(window.VoltDashboard && typeof VoltDashboard.handleAndroidBack === 'function')" +
                " ? !!VoltDashboard.handleAndroidBack() : false"
    }
}
