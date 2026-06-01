package com.volttracker.obdpoc;

import android.annotation.SuppressLint;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * One-shot helper that configures the dashboard {@link WebView}: applies the project's hardened
 * {@link WebSettings}, wires the chrome/web view clients, attaches the {@link VoltBridge}
 * JavaScript interface, and loads {@code file:///android_asset/dashboard/index.html}.
 *
 * <p>Extracted from {@link MainActivity} so the Activity does not own ~50 lines of WebView wiring.
 * The Activity keeps ownership of the {@link WebView} reference and its lifecycle; this helper only
 * handles the one-time setup.
 */
final class WebViewBootstrap {

    private static final String DASHBOARD_ORIGIN = "file:///android_asset/dashboard/";
    private static final String DASHBOARD_URL = DASHBOARD_ORIGIN + "index.html";
    private static final String BRIDGE_NAME = "VoltTrackerAndroid";

    private WebViewBootstrap() {}

    /**
     * Configures {@code webView}, attaches {@code bridge} as the {@link #BRIDGE_NAME} JS interface,
     * then loads the dashboard. The dashboard calls {@code VoltTrackerAndroid.dashboardReady()}
     * once its scripts have created {@code window.VoltTrackerNative}; {@code onPageFinished} is too
     * early on some WebView builds.
     */
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    static void configure(WebView webView, VoltBridge bridge) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setTextZoom(100);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        webView.setWebChromeClient(
                new WebChromeClient() {
                    @Override
                    public boolean onConsoleMessage(ConsoleMessage message) {
                        if (message == null) {
                            return true;
                        }
                        // Log every level (not just ERROR) so dashboard JS warnings/info from
                        // bindListenerGuarded() and friends actually surface in adb logcat.
                        // Returning true suppresses the WebView's default chromium log line,
                        // so this is the ONLY surface for non-error dashboard console output.
                        String line =
                                "dashboard console: "
                                        + message.message()
                                        + " ("
                                        + message.sourceId()
                                        + ":"
                                        + message.lineNumber()
                                        + ")";
                        switch (message.messageLevel()) {
                            case ERROR:
                                Log.e(MainActivity.TAG, line);
                                break;
                            case WARNING:
                                Log.w(MainActivity.TAG, line);
                                break;
                            case DEBUG:
                                Log.d(MainActivity.TAG, line);
                                break;
                            case TIP:
                            case LOG:
                            default:
                                Log.i(MainActivity.TAG, line);
                                break;
                        }
                        return true;
                    }
                });
        // Origin guard: the dashboard only ever navigates within file:///android_asset/dashboard/.
        // External links open via the VoltBridge ACTION_VIEW intent, never in this WebView, so any
        // attempt to navigate the main frame elsewhere is unexpected (a bug or injected content)
        // and
        // is blocked. shouldOverrideUrlLoading is not invoked for the initial loadUrl() below or
        // for
        // sub-resource loads (those are bounded by the CSP), only for navigations.
        webView.setWebViewClient(
                new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(
                            WebView view, WebResourceRequest request) {
                        return blockOffOrigin(request == null ? null : request.getUrl().toString());
                    }

                    @SuppressWarnings("deprecation") // String overload still fires on API 23
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, String url) {
                        return blockOffOrigin(url);
                    }
                });
        webView.addJavascriptInterface(bridge, BRIDGE_NAME);
        webView.loadUrl(DASHBOARD_URL);
    }

    /** Returns true (consume, do not navigate) for any URL outside the dashboard origin. */
    private static boolean blockOffOrigin(String url) {
        if (url != null && url.startsWith(DASHBOARD_ORIGIN)) {
            return false;
        }
        Log.w(MainActivity.TAG, "Blocked off-origin WebView navigation: " + url);
        return true;
    }
}
