package com.volttracker.obdpoc;

import android.annotation.SuppressLint;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
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

    private static final String DASHBOARD_URL = "file:///android_asset/dashboard/index.html";
    private static final String BRIDGE_NAME = "VoltTrackerAndroid";

    private WebViewBootstrap() {}

    /**
     * Configures {@code webView}, attaches {@code bridge} as the {@link #BRIDGE_NAME} JS interface,
     * then loads the dashboard. {@code onPageReady} runs from {@link WebViewClient#onPageFinished}
     * on the UI thread.
     */
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    static void configure(WebView webView, VoltBridge bridge, Runnable onPageReady) {
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
        // WebViewClient.onPageFinished is documented as not-quite one-shot: it can fire on
        // redirects or intermediate loads inside the same logical navigation. Gate to a single
        // invocation for our dashboard URL so the post-load publish chain doesn't repeat.
        final boolean[] readyFired = {false};
        webView.setWebViewClient(
                new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        if (readyFired[0]) {
                            return;
                        }
                        if (url == null || !url.startsWith(DASHBOARD_URL)) {
                            return;
                        }
                        readyFired[0] = true;
                        onPageReady.run();
                    }
                });
        webView.addJavascriptInterface(bridge, BRIDGE_NAME);
        webView.loadUrl(DASHBOARD_URL);
    }
}
