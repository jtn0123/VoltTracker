package com.volttracker.obdpoc.widget

import android.content.Context
import android.util.Log
import com.volttracker.obdpoc.AppPrefs
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
 * supplies SOC/vehicleState/connected, with the charging flag derived from vehicleState; status
 * supplies the connection flag), so a status-only update keeps the last known SOC and vice-versa.
 */
class WidgetUpdater(
    private val context: Context,
    private val store: WidgetSnapshotStore,
) {
    constructor(context: Context) : this(context.applicationContext, WidgetSnapshotStore(context))

    // Guards the read -> merge -> write cycle so concurrent status/telemetry entrypoints cannot
    // clobber each other's fields (e.g. a status-only update racing a telemetry update).
    private val snapshotLock = Any()

    /**
     * Folds a telemetry payload into the snapshot. The payload supplies SOC, vehicleState, and
     * connected; the charging flag is derived from vehicleState rather than read as a payload field.
     */
    fun onTelemetry(payload: JSONObject?) {
        if (payload == null) {
            return
        }
        try {
            synchronized(snapshotLock) {
                val previous = store.read()
                val soc =
                    if (payload.has("soc") && !payload.isNull("soc")) {
                        roundSoc(payload.optDouble("soc", Double.NaN), previous.socPct)
                    } else {
                        previous.socPct
                    }
                val vehicleState = payload.optString("vehicleState", previous.vehicleState)
                val connected =
                    if (payload.has("connected")) payload.optBoolean("connected") else previous.connected
                val now = System.currentTimeMillis()
                val merged =
                    WidgetSnapshot(
                        socPct = soc,
                        charging = vehicleState == WidgetStateFormatter.VEHICLE_STATE_CHARGING,
                        connected = connected,
                        vehicleState = vehicleState,
                        // updatedAtMs proposes "now" as the new change time; the store keeps it only
                        // when a display field actually changed. lastSampleAtMs always advances so the
                        // freshness line keeps ticking even on an identical (flat-but-live) sample.
                        updatedAtMs = now,
                        lastSampleAtMs = now,
                    )
                persistAndMaybeRefresh(merged)
            }
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "widget telemetry update failed", ex)
        }
    }

    /**
     * Folds a status state (e.g. "connected", "idle") into the snapshot's connection flag.
     *
     * A status transition is NOT a fresh telemetry sample, so it proposes a new [updatedAtMs]
     * (the connection flag is a display field) but must leave [WidgetSnapshot.lastSampleAtMs]
     * untouched. Bumping the sample time here would reset the freshness/stale signal on every
     * status ping — a mid-charge disconnect or a stream of "connected" pings against a frozen SOC
     * would keep reading "Updated just now" forever and never age out to stale.
     */
    fun onStatus(state: String?) {
        try {
            synchronized(snapshotLock) {
                val previous = store.read()
                val connected = MainActivityUtils.isConnectedState(state)
                val now = System.currentTimeMillis()
                val merged =
                    previous.copy(
                        connected = connected,
                        updatedAtMs = now,
                    )
                persistAndMaybeRefresh(merged)
            }
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "widget status update failed", ex)
        }
    }

    private fun persistAndMaybeRefresh(snapshot: WidgetSnapshot) {
        if (store.writeIfChanged(snapshot)) {
            requestRefresh(context)
        }
    }

    companion object {
        /**
         * Rounds the Double SOC telemetry value to the nearest whole percent, matching the dashboard's
         * SOC rounding, rather than truncating it (82.9 -> 83, not 82). A non-finite reading keeps the
         * previously stored value so a single garbage sample never blanks the widget.
         */
        private fun roundSoc(
            soc: Double,
            previousSocPct: Int,
        ): Int = if (soc.isFinite()) Math.round(soc).toInt() else previousSocPct

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
                Log.w(AppPrefs.LOG_TAG, "widget refresh failed", ex)
            }
        }
    }
}
