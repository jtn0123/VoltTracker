package com.volttracker.obdpoc;

import android.util.Log;
import android.webkit.WebView;
import java.util.Set;
import java.util.concurrent.Executor;
import org.json.JSONObject;

/**
 * Owns the one safe path from native code into the dashboard WebView: {@link #publish} invokes a
 * whitelisted {@code window.VoltTrackerNative.*} function with a JSON-quoted payload. Extracted
 * from {@link MainActivity} (A4) so the allowlist enforcement and the page-ready / tear-down gating
 * are unit-testable without spinning up an Activity.
 *
 * <p>The function-name allowlist is the security boundary: a compromised or buggy caller can only
 * reach the six dashboard entry points below, and {@link JSONObject#quote} makes the payload
 * structurally un-escapable, so script injection through this path is not possible.
 */
final class DashboardPublisher {

    /**
     * Liveness gate for the host. A local interface (not {@code
     * java.util.function.BooleanSupplier}, which is API 24+) so this compiles at minSdk 23 without
     * core-library desugaring.
     */
    @FunctionalInterface
    interface Liveness {
        boolean isAlive();
    }

    /** Closed set of dashboard entry points {@link #publish} may call. */
    private static final Set<String> ALLOWED_FUNCTIONS =
            Set.of(
                    "updateTelemetry",
                    "setStatus",
                    "setDevices",
                    "setHistory",
                    "setStorage",
                    "setAppState");

    private final WebView webView;
    private final Liveness isAlive;
    private final Executor uiExecutor;
    private volatile boolean pageReady;

    /**
     * @param webView the dashboard WebView (held for this publisher's lifetime; replace the
     *     publisher, not the field, if the WebView is ever recreated)
     * @param isAlive returns false once the host is finishing/destroyed, so a late dispatch can't
     *     fire {@code evaluateJavascript} against a torn-down WebView
     * @param uiExecutor marshals the actual {@code evaluateJavascript} call onto the UI thread
     *     (production passes {@code Activity::runOnUiThread}; tests pass a synchronous executor)
     */
    DashboardPublisher(WebView webView, Liveness isAlive, Executor uiExecutor) {
        this.webView = webView;
        this.isAlive = isAlive;
        this.uiExecutor = uiExecutor;
    }

    void setPageReady(boolean ready) {
        this.pageReady = ready;
    }

    boolean isPageReady() {
        return pageReady;
    }

    /**
     * Invokes {@code window.VoltTrackerNative.<functionName>(payload)} if it is on the allowlist.
     */
    void publish(String functionName, String jsonPayload) {
        if (!ALLOWED_FUNCTIONS.contains(functionName)) {
            Log.w(
                    MainActivity.TAG,
                    "callDashboard: refused unknown function name: " + functionName);
            return;
        }
        final WebView wv = webView;
        if (!pageReady || wv == null) {
            return;
        }
        final String script =
                "window.VoltTrackerNative."
                        + functionName
                        + "("
                        + JSONObject.quote(jsonPayload == null ? "{}" : jsonPayload)
                        + ");";
        uiExecutor.execute(
                () -> {
                    // Re-check tear-down signals on the UI thread: the host may have been
                    // destroyed,
                    // or the dashboard may not have completed its JS-ready handshake yet. Without
                    // this, evaluateJavascript can fire against a torn-down WebView and crash on
                    // some
                    // Android builds.
                    if (!isAlive.isAlive() || !pageReady) {
                        return;
                    }
                    wv.evaluateJavascript(script, null);
                });
    }
}
