package com.volttracker.obdpoc

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat

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
     * Fatal WebView events the host must react to (report item B1): the renderer process dying
     * and the dashboard main frame failing to load. Both leave the page dead or broken with no
     * JS alive to tell anyone, so the host tears the WebView down and recovers natively.
     * Callbacks arrive on the main thread (standard WebViewClient threading).
     */
    interface CrashEvents {
        /**
         * The renderer process is gone. [view] is the (now dead) WebView that lost it, so the
         * host can ignore stale reports from an already-replaced instance. [crashed] is
         * [RenderProcessGoneDetail.didCrash]: true for a real renderer crash, false when the
         * system reclaimed the renderer (typically low memory).
         */
        fun onRendererGone(
            view: WebView?,
            crashed: Boolean,
        )

        /** The dashboard main frame failed to load; the page is broken, not just a sub-resource. */
        fun onMainFrameLoadFailed(
            view: WebView?,
            errorCode: Int,
            description: String,
        )
    }

    /**
     * Configures [webView], attaches [bridge] as the [BRIDGE_NAME] JS interface, then loads the
     * dashboard. The dashboard calls `VoltTrackerAndroid.dashboardReady()` once its scripts have
     * created `window.VoltTrackerNative`; `onPageFinished` is too early on some WebView builds.
     *
     * [crashEvents] receives renderer-death / main-frame-failure notifications; without it the
     * renderer-gone default is still "consume, never crash the app" (the platform default kills
     * the whole Activity), just with no recovery.
     */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    @JvmStatic
    @JvmOverloads
    fun configure(
        webView: WebView,
        bridge: Any,
        crashEvents: CrashEvents? = null,
    ) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        // Both file-URL settings are deprecated (and default to false on API 16+),
        // but they are still honored by the WebView, so the hardened profile pins
        // them off explicitly rather than trusting the platform default.
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

        // A fresh WebView paints opaque white until the dashboard's first frame
        // renders, which flashes against the near-black UI on cold start. Pin the
        // WebView background to the same dark chrome color as the status/nav bars
        // so the load is seamless instead of a white blink.
        webView.setBackgroundColor(ContextCompat.getColor(webView.context, R.color.volt_dark))

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
                        ConsoleMessage.MessageLevel.ERROR -> Log.e(AppPrefs.LOG_TAG, line)
                        ConsoleMessage.MessageLevel.WARNING -> Log.w(AppPrefs.LOG_TAG, line)
                        ConsoleMessage.MessageLevel.DEBUG ->
                            if (BuildConfig.DEBUG) Log.d(AppPrefs.LOG_TAG, line)
                        ConsoleMessage.MessageLevel.TIP,
                        ConsoleMessage.MessageLevel.LOG,
                        null,
                        -> if (BuildConfig.DEBUG) Log.i(AppPrefs.LOG_TAG, line)
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
                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: Bitmap?,
                ) {
                    StartupTrace.mark("webview_page_started")
                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageCommitVisible(
                    view: WebView?,
                    url: String?,
                ) {
                    StartupTrace.mark("webview_page_commit_visible")
                    super.onPageCommitVisible(view, url)
                }

                override fun onPageFinished(
                    view: WebView?,
                    url: String?,
                ) {
                    StartupTrace.mark("webview_page_finished")
                    super.onPageFinished(view, url)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = blockOffOrigin(request?.url?.toString())

                @Suppress("DEPRECATION") // String overload still fires on API 23
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    url: String?,
                ): Boolean = blockOffOrigin(url)

                // Renderer death (report item B1). The platform default for an unhandled
                // renderer-gone event is to kill the app process, so this MUST return true.
                // Recovery (detach + destroy + recreate) is the host's job via crashEvents;
                // didCrash() distinguishes a real crash from a low-memory kill in the logs.
                // Only invoked on API >= 26; older WebViews never killed the host this way.
                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?,
                ): Boolean {
                    // didCrash() itself is an API 26 method; the callback only fires on O+,
                    // but minSdk is 23 so lint (correctly) demands the explicit guard.
                    val crashed =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && detail?.didCrash() == true
                    Log.e(
                        AppPrefs.LOG_TAG,
                        "dashboard WebView render process gone (didCrash=$crashed)",
                    )
                    crashEvents?.onRendererGone(view, crashed)
                    return true
                }

                // Main-frame load failure (a missing/corrupt dashboard asset, etc.) otherwise
                // shows a silent broken page; route it into the same native recovery path.
                // Sub-resource errors are ignored: the page's own JS handles those.
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame != true) {
                        return
                    }
                    val code = error?.errorCode ?: 0
                    val description = error?.description?.toString() ?: "unknown error"
                    Log.e(
                        AppPrefs.LOG_TAG,
                        "dashboard main-frame load error (code=$code): $description",
                    )
                    crashEvents?.onMainFrameLoadFailed(view, code, description)
                }
            }
        StartupTrace.mark("webview_add_js_interface_start")
        webView.addJavascriptInterface(bridge, BRIDGE_NAME)
        StartupTrace.mark("webview_add_js_interface_end")
        StartupTrace.mark("webview_load_url_start")
        webView.loadUrl(DASHBOARD_URL)
        StartupTrace.mark("webview_load_url_end")
    }

    /** Returns true (consume, do not navigate) for any URL outside the dashboard origin. */
    private fun blockOffOrigin(url: String?): Boolean {
        if (url != null && url.startsWith(DASHBOARD_ORIGIN)) {
            return false
        }
        Log.w(AppPrefs.LOG_TAG, "Blocked off-origin WebView navigation: $url")
        return true
    }
}
