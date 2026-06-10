package com.volttracker.obdpoc

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * One-shot helper that configures the dashboard [WebView]: applies the project's hardened
 * [WebSettings], wires the chrome/web view clients, attaches the [VoltBridge] JavaScript interface,
 * and loads `file:///android_asset/dashboard/index.html`.
 *
 * Extracted from [MainActivity] so the Activity does not own ~50 lines of WebView wiring. The
 * Activity keeps ownership of the [WebView] reference and its lifecycle; this helper only handles
 * the one-time setup.
 */
object WebViewBootstrap {
    private const val DASHBOARD_ORIGIN = "file:///android_asset/dashboard/"
    private const val DASHBOARD_URL = DASHBOARD_ORIGIN + "index.html"
    private const val BRIDGE_NAME = "VoltTrackerAndroid"

    /**
     * Configures [webView], attaches [bridge] as the [BRIDGE_NAME] JS interface, then loads the
     * dashboard. The dashboard calls `VoltTrackerAndroid.dashboardReady()` once its scripts have
     * created `window.VoltTrackerNative`; `onPageFinished` is too early on some WebView builds.
     */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    @JvmStatic
    fun configure(
        webView: WebView,
        bridge: Any,
    ) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        settings.textZoom = 100
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.useWideViewPort = false
        settings.loadWithOverviewMode = false
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                    if (message == null) {
                        return true
                    }
                    // Log every level (not just ERROR) so dashboard JS warnings/info from
                    // bindListenerGuarded() and friends actually surface in adb logcat.
                    // Returning true suppresses the WebView's default chromium log line,
                    // so this is the ONLY surface for non-error dashboard console output.
                    val line =
                        "dashboard console: ${message.message()} " +
                            "(${message.sourceId()}:${message.lineNumber()})"
                    when (message.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> Log.e(MainActivity.TAG, line)
                        ConsoleMessage.MessageLevel.WARNING -> Log.w(MainActivity.TAG, line)
                        ConsoleMessage.MessageLevel.DEBUG -> Log.d(MainActivity.TAG, line)
                        ConsoleMessage.MessageLevel.TIP,
                        ConsoleMessage.MessageLevel.LOG,
                        null,
                        -> Log.i(MainActivity.TAG, line)
                    }
                    return true
                }
            }

        // Origin guard: the dashboard only ever navigates within file:///android_asset/dashboard/.
        // External links open via the VoltBridge ACTION_VIEW intent, never in this WebView, so any
        // attempt to navigate the main frame elsewhere is unexpected (a bug or injected content)
        // and is blocked. shouldOverrideUrlLoading is not invoked for the initial loadUrl() below
        // or for sub-resource loads (those are bounded by the CSP), only for navigations.
        webView.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = blockOffOrigin(request?.url?.toString())

                @Suppress("DEPRECATION") // String overload still fires on API 23
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    url: String?,
                ): Boolean = blockOffOrigin(url)
            }
        webView.addJavascriptInterface(bridge, BRIDGE_NAME)
        webView.loadUrl(DASHBOARD_URL)
    }

    /** Returns true (consume, do not navigate) for any URL outside the dashboard origin. */
    private fun blockOffOrigin(url: String?): Boolean {
        if (url != null && url.startsWith(DASHBOARD_ORIGIN)) {
            return false
        }
        Log.w(MainActivity.TAG, "Blocked off-origin WebView navigation: $url")
        return true
    }
}
