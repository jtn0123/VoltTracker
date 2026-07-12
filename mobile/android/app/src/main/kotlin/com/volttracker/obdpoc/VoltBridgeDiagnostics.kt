package com.volttracker.obdpoc

import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import com.volttracker.obdpoc.service.ObdService
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal class VoltBridgeDiagnostics(
    private val activity: DashboardHost,
) {
    fun clearVehicleDtcCodes() {
        val device = activity.requireDeviceCatalog().getLastOrCandidateDevice()
        val address = bridgeSafe(device.optString("address", ""), BRIDGE_MAX_ADDRESS_LEN)
        val name = bridgeSafe(device.optString("name", ""), BRIDGE_MAX_NAME_LEN)
        activity.runOnUiThread {
            if (!validBridgeBluetoothAddress(address)) {
                activity.publishStatus("blocked", "No remembered adapter yet. Connect once to save it.", true)
                return@runOnUiThread
            }
            activity.confirmBridgeAction(
                "Clear vehicle codes?",
                "This sends a real clear-DTC command through the remembered adapter. Emissions readiness monitors may reset.",
                "Clear codes",
            ) {
                activity.rememberDevice(address, name)
                activity.startObdService(ObdService.ACTION_CLEAR_DTC, address, name)
            }
        }
    }

    fun openExternalSearch(dtc: String?) {
        val code = bridgeSafe(dtc, BRIDGE_MAX_DTC_LEN)
        if (code.isEmpty()) return
        activity.runOnUiThread {
            try {
                val query = URLEncoder.encode(code + " Chevy Volt DTC", StandardCharsets.UTF_8.name())
                val uri = "https://www.google.com/search?q=$query".toUri()
                val intent = Intent(Intent.ACTION_VIEW, uri)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
            } catch (ex: Exception) {
                Log.w(AppPrefs.LOG_TAG, "openExternalSearch failed", ex)
                activity.publishStatus("blocked", "Could not open the browser for DTC lookup.", true)
            }
        }
    }

    fun forceStopPackage(packageName: String?): Boolean {
        // Normalize once: the allowlist is lower-cased, and the confirm dialog, the
        // force-stop call, and the status messages must all use the validated form
        // rather than the caller's mixed-case spelling.
        val pkg = bridgeSafe(packageName, BRIDGE_MAX_NAME_LEN).lowercase(Locale.US)
        // Validate against the known-OBD-app allowlist BEFORE showing the confirmation dialog:
        // dashboard JS must not be able to put an arbitrary package name in front of the user.
        if (pkg.isEmpty() || !CompetingAppDetector.KNOWN_OBD_PACKAGES.contains(pkg)) {
            return false
        }
        activity.confirmBridgeAction(
            "Force-stop OBD app?",
            "Ask Android to stop $pkg if it is holding the Bluetooth adapter. Only use this for a competing OBD app you recognize.",
            "Force-stop",
        ) {
            val stopped = activity.diagnostics().forceStopPackageFromBridge(pkg)
            activity.publishStatus(
                if (stopped) "ready" else "blocked",
                if (stopped) "Force-stop requested for $pkg." else "Could not force-stop $pkg.",
                !stopped,
            )
        }
        return true
    }

    // Clamp the JS-supplied count up front so a hostile/buggy dashboard cannot request an
    // unbounded read of the summary file.
    fun getRecentSessions(n: Int): String =
        activity.diagnostics().getRecentSessionsJson(n.coerceIn(0, MAX_RECENT_SESSIONS))

    fun shareDiagnostics() {
        activity.runOnUiThread { activity.diagnostics().shareDiagnosticsFromBridge() }
    }

    fun shareDiagnosticsDigest() {
        activity.runOnUiThread { activity.diagnostics().shareDiagnosticsDigestFromBridge() }
    }

    fun startTestConnection() {
        activity.runOnUiThread { activity.diagnostics().startTestConnectionFromBridge() }
    }

    fun scheduleAdapterReadyNotify(mins: Int) {
        val clamped = mins.coerceIn(MIN_ADAPTER_READY_MINS, MAX_ADAPTER_READY_MINS)
        activity.runOnUiThread {
            activity.diagnostics().scheduleAdapterReadyNotifyFromBridge(clamped)
        }
    }

    fun cancelAdapterReadyNotify() {
        activity.runOnUiThread { activity.diagnostics().cancelAdapterReadyNotifyFromBridge() }
    }

    fun openSetupGuide() {
        activity.runOnUiThread { activity.diagnostics().openSetupGuideFromBridge() }
    }

    companion object {
        /** Upper bound for the JS-supplied recent-session count. */
        internal const val MAX_RECENT_SESSIONS = 100

        /** Lower bound (minutes) for the JS-supplied adapter-ready notify delay. */
        internal const val MIN_ADAPTER_READY_MINS = 1

        /** Upper bound (minutes) for the JS-supplied adapter-ready notify delay. */
        internal const val MAX_ADAPTER_READY_MINS = 30
    }
}
