package com.volttracker.obdpoc

import android.util.Log
import android.webkit.WebView
import org.json.JSONObject
import java.util.concurrent.Executor

/**
 * Owns the one safe path from native code into the dashboard WebView: [publish] invokes a
 * whitelisted `window.VoltTrackerNative.*` function with a JSON-quoted payload. Extracted from
 * [MainActivity] so the allowlist enforcement and the page-ready / tear-down gating are
 * unit-testable without spinning up an Activity.
 *
 * The function-name allowlist is the security boundary: a compromised or buggy caller can only
 * reach the dashboard entry points below, and [JSONObject.quote] makes the payload structurally
 * un-escapable, so script injection through this path is not possible.
 */
class DashboardPublisher(
    private val webView: WebView?,
    private val isAlive: Liveness,
    private val uiExecutor: Executor,
) {
    /**
     * Liveness gate for the host. A local interface instead of `java.util.function.BooleanSupplier`
     * so this compiles at minSdk 23 without core-library desugaring.
     */
    fun interface Liveness {
        fun isAlive(): Boolean
    }

    @Volatile
    private var pageReady = false

    fun setPageReady(ready: Boolean) {
        pageReady = ready
    }

    fun isPageReady(): Boolean = pageReady

    /** Invokes `window.VoltTrackerNative.<functionName>(payload)` if it is on the allowlist. */
    fun publish(
        functionName: String,
        jsonPayload: String?,
    ) {
        if (!ALLOWED_FUNCTIONS.contains(functionName)) {
            Log.w(AppPrefs.LOG_TAG, "callDashboard: refused unknown function name: $functionName")
            return
        }
        val wv = webView ?: return
        if (!pageReady) {
            return
        }
        // JSONObject.quote leaves U+2028 (line separator) and U+2029 (paragraph separator) raw, but
        // both are line terminators inside a JS string literal on pre-ES2019 WebViews, so a raw one
        // makes evaluateJavascript throw a SyntaxError and silently drop the update. Escape them to
        // their \u2028 / \u2029 forms so the payload stays a single valid JS string literal.
        val quotedPayload =
            JSONObject
                .quote(jsonPayload ?: "{}")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029")
        val script = "window.VoltTrackerNative.$functionName($quotedPayload);"
        uiExecutor.execute {
            if (!isAlive.isAlive() || !pageReady) {
                return@execute
            }
            wv.evaluateJavascript(script, null)
        }
    }

    private companion object {
        val ALLOWED_FUNCTIONS: Set<String> =
            setOf(
                "updateTelemetry",
                "backfillTelemetry",
                "setStatus",
                "setDevices",
                "setHistory",
                "setStorage",
                "setTrips",
                "setTripsPage",
                "setInsights",
                "setTripRoute",
                "setCurrentSessionRoute",
                "setBatterySohHistory",
                "setAppState",
                "setRestoreProgress",
                "showToast",
                "showTripUndo",
                "applyRestoredPreferences",
                "openTrip",
                "openTripReceipt",
                "restoreView",
                "setBackupReceipt",
            )
    }
}
