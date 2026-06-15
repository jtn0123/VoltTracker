package com.volttracker.obdpoc.widget

import android.content.Context
import android.util.Log
import com.volttracker.obdpoc.MainActivity
import com.volttracker.obdpoc.MainActivityUtils
import org.json.JSONObject

/**
 * Bridges the live OBD session to the home-screen widget. The service calls [onTelemetry] /
 * [onStatus] whenever it publishes new state; this class derives a compact [WidgetSnapshot],
 * persists it via [WidgetSnapshotStore] (debounced), and — only when something actually changed —
 * nudges [VoltWidgetProvider] to redraw so the widget is not stuck on its ~30-minute system cadence.
 *
 * Every method is crash-safe: a failure here must never disturb the session's broadcast/telemetry
 * path, so all work is wrapped and swallowed. Snapshot fields are MERGED across calls (telemetry
 * supplies SOC/charging/vehicleState; status supplies the connection flag), so a status-only update
 * keeps the last known SOC and vice-versa.
 */
class WidgetUpdater(
    private val context: Context,
    private val store: WidgetSnapshotStore,
) {
    constructor(context: Context) : this(context.applicationContext, WidgetSnapshotStore(context))

    // Guards the read -> merge -> write cycle so concurrent status/telemetry entrypoints cannot
    // clobber each other's fields (e.g. a status-only update racing a telemetry update).
    private val snapshotLock = Any()

    /** Folds a telemetry payload into the snapshot (SOC, charging, vehicleState, connected). */
    fun onTelemetry(payload: JSONObject?) {
        if (payload == null) {
            return
        }
        try {
            synchronized(snapshotLock) {
                val previous = store.read()
                val soc = if (payload.has("soc") && !payload.isNull("soc")) payload.optInt("soc") else previous.socPct
                val vehicleState = payload.optString("vehicleState", previous.vehicleState)
                val connected =
                    if (payload.has("connected")) payload.optBoolean("connected") else previous.connected
                val merged =
                    WidgetSnapshot(
                        socPct = soc,
                        charging = vehicleState == VEHICLE_STATE_CHARGING,
                        connected = connected,
                        vehicleState = vehicleState,
                        updatedAtMs = System.currentTimeMillis(),
                    )
                persistAndMaybeRefresh(merged)
            }
        } catch (ex: RuntimeException) {
            Log.w(MainActivity.TAG, "widget telemetry update failed", ex)
        }
    }

    /** Folds a status state (e.g. "connected", "idle") into the snapshot's connection flag. */
    fun onStatus(state: String?) {
        try {
            synchronized(snapshotLock) {
                val previous = store.read()
                val connected = MainActivityUtils.isConnectedState(state)
                val merged =
                    previous.copy(
                        connected = connected,
                        updatedAtMs = System.currentTimeMillis(),
                    )
                persistAndMaybeRefresh(merged)
            }
        } catch (ex: RuntimeException) {
            Log.w(MainActivity.TAG, "widget status update failed", ex)
        }
    }

    private fun persistAndMaybeRefresh(snapshot: WidgetSnapshot) {
        if (store.writeIfChanged(snapshot)) {
            requestRefresh(context)
        }
    }

    companion object {
        private const val VEHICLE_STATE_CHARGING = "charging"

        /**
         * Redraws every placed instance of [VoltWidgetProvider] via a direct in-process
         * [AppWidgetManager.updateAppWidget] call rather than a self-sent APPWIDGET_UPDATE broadcast.
         *
         * This is deliberate: the manifest gates the exported receiver behind
         * `android.permission.BIND_APPWIDGET` so other apps cannot spam it, and an app-sent broadcast
         * would be dropped by that same gate (we don't hold the system-only permission). The direct
         * update needs no broadcast permission and is cheaper. No-op and swallowed when no widget is
         * placed or the manager is unavailable.
         */
        @JvmStatic
        fun requestRefresh(context: Context) {
            try {
                VoltWidgetProvider.refreshAll(context)
            } catch (ex: RuntimeException) {
                Log.w(MainActivity.TAG, "widget refresh failed", ex)
            }
        }
    }
}
