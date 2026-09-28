package com.volttracker.obdpoc

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.volttracker.obdpoc.service.ObdService
import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import org.json.JSONObject

/**
 * Health's Scan now, Clear codes and Share report. A scan or clear goes to the remembered adapter
 * the way the classic dashboard sends it — a quick trouble-code scan ([ObdService.ACTION_SCAN]),
 * or Mode 04 ([ObdService.ACTION_CLEAR_DTC]) only after a confirmation that says what it erases.
 * The demo sends nothing: it simulates both.
 *
 * Also keeps when the car's codes were last read or cleared (from the service's scan and clear
 * results), so Health can tell the codes the car still reports from ones it has dropped.
 */
class ComposeDtcActions(
    private val activity: Activity,
    private val host: DeviceCommands,
    private val prefs: () -> SharedPreferences?,
    private val store: LiveUiStateStore,
    private val toast: (String) -> Unit,
    /** Re-reads the saved codes after a scan or clear lands. */
    private val reload: () -> Unit,
) {
    fun scan() {
        if (store.state.value.settings.demoActive) {
            store.onDemoScan()
            toast(DEMO_SCAN)
            return
        }
        val (address, name) = rememberedAdapter() ?: return
        host.rememberDevice(address, name)
        host.startObdService(ObdService.ACTION_SCAN, address, name, DiagnosticScanProfile.QUICK.wireName)
    }

    fun clear() {
        val demo = store.state.value.settings.demoActive
        val adapter = if (demo) null else rememberedAdapter() ?: return
        confirm(if (demo) "$CLEAR_WARNING\n\n$DEMO_NOTE" else CLEAR_WARNING) {
            if (adapter == null) {
                store.onDemoClear()
            } else {
                host.rememberDevice(adapter.first, adapter.second)
                host.startObdService(ObdService.ACTION_CLEAR_DTC, adapter.first, adapter.second)
            }
        }
    }

    fun share(report: String) {
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, SHARE_SUBJECT)
                .putExtra(Intent.EXTRA_TEXT, report)
        try {
            activity.startActivity(Intent.createChooser(send, SHARE_SUBJECT))
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "health report share failed", ex)
            toast(SHARE_FAILED)
        }
    }

    /** A service result: a finished scan or a successful clear re-times the codes and re-reads them. */
    fun onTelemetry(payload: JSONObject) {
        val at = payload.optLong("updatedAt", 0L).takeIf { it > 0L } ?: System.currentTimeMillis()
        val key =
            when {
                payload.optString("source") == SOURCE_SCAN && payload.optBoolean("dtcScanValid", false) ->
                    PREF_LAST_SCAN_MS
                payload.optString("source") == SOURCE_CLEAR && payload.optBoolean("clearDtcOk", false) ->
                    PREF_LAST_CLEAR_MS
                else -> return
            }
        prefs()?.edit { putLong(key, at) }
        reload()
    }

    /** When the car's codes were last read — here or by the on-connect auto-scan — or null if never. */
    fun lastScanAtMs(): Long? {
        val p = prefs() ?: return null
        val read = maxOf(p.getLong(PREF_LAST_SCAN_MS, 0L), EventNotificationPrefs(p).lastAutoScanAtMs())
        return read.takeIf { it > 0L }
    }

    /** When the car's codes were last cleared from here, or null if never. */
    fun lastClearAtMs(): Long? = prefs()?.getLong(PREF_LAST_CLEAR_MS, 0L)?.takeIf { it > 0L }

    private fun rememberedAdapter(): Pair<String, String>? {
        val device = host.requireDeviceCatalog().getLastOrCandidateDevice()
        val address = bridgeSafe(device.optString("address", ""), BRIDGE_MAX_ADDRESS_LEN)
        val name = bridgeSafe(device.optString("name", ""), BRIDGE_MAX_NAME_LEN)
        if (!validBridgeBluetoothAddress(address)) {
            toast(NO_ADAPTER)
            return null
        }
        return address to name
    }

    private fun confirm(
        message: String,
        onConfirmed: () -> Unit,
    ) {
        try {
            AlertDialog
                .Builder(activity)
                .setTitle(CLEAR_TITLE)
                .setMessage(message)
                .setPositiveButton(CLEAR_LABEL) { _, _ -> onConfirmed() }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        } catch (ex: RuntimeException) {
            // The activity can be finishing between the tap and the dialog.
            Log.w(AppPrefs.LOG_TAG, "clear-codes dialog failed", ex)
        }
    }

    companion object {
        /** Compose-only bookkeeping: when this screen last saw the car's codes read or cleared. */
        const val PREF_LAST_SCAN_MS = "compose_dtc_last_scan_ms"
        const val PREF_LAST_CLEAR_MS = "compose_dtc_last_clear_ms"

        private const val SOURCE_SCAN = "scan"
        private const val SOURCE_CLEAR = "clear-dtc"
        private const val NO_ADAPTER = "No remembered adapter yet. Connect once to save it."
        private const val DEMO_SCAN = "Demo / Testing: scan simulated. Nothing was sent to a car."
        private const val DEMO_NOTE = "Demo / Testing: this is simulated. Nothing is sent to a car."
        private const val CLEAR_TITLE = "Clear the car's trouble codes?"
        private const val CLEAR_LABEL = "Clear codes"
        private const val CLEAR_WARNING =
            "This sends OBD-II Mode 04 through your adapter, turning off the check-engine light and " +
                "erasing stored codes. Emissions readiness monitors reset and can take 100+ miles of " +
                "mixed driving to set again — some inspection stations fail the car until then. " +
                "Permanent codes stay until the car clears them itself.\n\n" +
                "Do it parked, in Park, ignition on."
        private const val SHARE_SUBJECT = "Volt health report"
        private const val SHARE_FAILED = "Couldn't open the share sheet."
    }
}
