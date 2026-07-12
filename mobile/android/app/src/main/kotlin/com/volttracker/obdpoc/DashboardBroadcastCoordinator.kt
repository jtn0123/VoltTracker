package com.volttracker.obdpoc

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.volttracker.obdpoc.service.ObdService
import org.json.JSONObject

/**
 * Owns the dashboard-facing broadcast plumbing for the Activity lifecycle: receiver registration
 * against the [Context], and the `onReceive` -> parse -> dashboard-forwarding path for the OBD
 * telemetry/status broadcasts (plus the Bluetooth auto-connect triggers).
 *
 * Layering: this coordinator may drive the dashboard, but only through the [seam] callbacks the host
 * Activity supplies — it never touches WebView APIs directly (enforced by `ArchitectureBoundaryTest`).
 * The host keeps owning the [JsonSnapshot] last-value state (it is also read by the app-state /
 * logging paths), so the snapshot writes route back through [Seam.storeTelemetry] /
 * [Seam.storeStatus]; the overridable `markStorageSummaryDirty` / `publishStorageSummary` /
 * `onAdapterStatusForReadyNotify` seams keep test subclasses' overrides in the path.
 */
class DashboardBroadcastCoordinator(
    private val seam: Seam,
) {
    /**
     * Host-supplied seams. Each one routes back into the Activity so the snapshot state, the
     * dashboard publish channel, and the overridable hooks stay owned where the rest of the app
     * reads them. Behavior must match the pre-refactor MainActivity broadcast handlers exactly.
     */
    interface Seam {
        /** Stores the latest telemetry JSON in the host's last-value snapshot. */
        fun storeTelemetry(json: String)

        /** Stores the latest status JSON and returns its parsed `state` field (or `""`). */
        fun storeStatus(json: String): String

        /** Pushes a dashboard JS call (e.g. `updateTelemetry`, `setStatus`, `setAppState`). */
        fun callDashboard(
            functionName: String,
            jsonPayload: String?,
        )

        /** Marks the storage summary dirty (overridable hook on the host). */
        fun markStorageSummaryDirty()

        /** Publishes the storage summary un-throttled (overridable hook on the host). */
        fun publishStorageSummary()

        /** Publishes the storage summary throttled (overridable hook on the host). */
        fun publishStorageSummaryThrottled()

        /** True once the dashboard JS callback surface has completed its ready handshake. */
        fun isDashboardReady(): Boolean

        /** Pushes the current app-state snapshot to the dashboard. */
        fun publishAppState()

        /** Routes the latest status to the adapter ready-notify hook (overridable on the host). */
        fun onAdapterStatusForReadyNotify(status: JSONObject)

        /** Latest status snapshot, used to drive the ready-notify hook. */
        fun lastStatus(): JSONObject

        /** Runs an auto-connect attempt for the given trigger/observed address. */
        fun maybeAutoConnect(
            trigger: String,
            observedAddress: String?,
        )

        /** Extracts the ACL device address from a Bluetooth broadcast intent. */
        fun bluetoothDeviceAddress(intent: Intent): String
    }

    private val receivers = BroadcastReceiverGroup()

    private val obdReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                val json = intent.getStringExtra(ObdService.EXTRA_JSON) ?: "{}"
                when (intent.action) {
                    ObdService.BROADCAST_TELEMETRY -> onTelemetryBroadcast(json)
                    ObdService.BROADCAST_STATUS -> onStatusBroadcast(json)
                }
            }
        }

    private val autoConnectReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED ->
                        seam.maybeAutoConnect(
                            AutoConnectController.TRIGGER_BLUETOOTH_CONNECTED,
                            seam.bluetoothDeviceAddress(intent),
                        )

                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        if (state == BluetoothAdapter.STATE_ON) {
                            seam.maybeAutoConnect(AutoConnectController.TRIGGER_BLUETOOTH_ON, null)
                        }
                    }
                }
            }
        }

    private fun onTelemetryBroadcast(json: String) {
        seam.storeTelemetry(json)
        seam.markStorageSummaryDirty()
        // updateTelemetry already carries the complete hot sample. Rebuilding and publishing a
        // second app-state payload here duplicated JSON serialization and WebView evaluation on
        // every poll; non-telemetry app state is published on status/lifecycle/permission changes.
        seam.callDashboard("updateTelemetry", json)
    }

    private fun onStatusBroadcast(json: String) {
        val state = seam.storeStatus(json)
        seam.callDashboard("setStatus", json)
        if (!seam.isDashboardReady()) {
            StartupTrace.mark("status_storage_publish_skipped_not_ready")
        } else if ("idle" == state) {
            seam.publishStorageSummary()
        } else {
            seam.publishStorageSummaryThrottled()
        }
        seam.publishAppState()
        seam.onAdapterStatusForReadyNotify(seam.lastStatus())
    }

    fun register(context: Context) {
        val obdFilter =
            IntentFilter().apply {
                addAction(ObdService.BROADCAST_TELEMETRY)
                addAction(ObdService.BROADCAST_STATUS)
            }
        receivers.register(context, obdReceiver, obdFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val autoConnectFilter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
        // ACL_CONNECTED / ADAPTER_STATE_CHANGED are protected system broadcasts: the platform
        // delivers them to NOT_EXPORTED receivers on API 33+ (exported is only required for
        // app-to-app broadcasts), so keep this receiver closed to other apps.
        receivers.register(context, autoConnectReceiver, autoConnectFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun unregisterAll(context: Context) {
        receivers.unregisterAll(context)
    }
}
