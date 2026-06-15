package com.volttracker.obdpoc.widget

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.volttracker.obdpoc.MainActivity

/**
 * Persists the compact [WidgetSnapshot] to the app's shared-prefs file so the out-of-process
 * widget can read the latest vehicle state without binding to the service.
 *
 * The write is intentionally cheap (five primitive prefs keys, `apply()` — never `commit()`) and
 * crash-safe: every public method swallows storage failures so a snapshot write can never break a
 * live OBD session. [writeIfChanged] debounces — it only persists when the meaningful fields
 * (SOC / charging / connected / vehicle state) actually changed, so the 1 Hz telemetry stream does
 * not thrash prefs or trigger a widget redraw on every identical sample.
 *
 * It reuses [MainActivity.PREFS] (`volt_obd_prefs`) under a dedicated key namespace so it shares the
 * existing event-notification settings file rather than opening a second prefs file.
 */
class WidgetSnapshotStore(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE),
    )

    /** Reads the last persisted snapshot, or [WidgetSnapshot.EMPTY] when none / on read failure. */
    fun read(): WidgetSnapshot =
        try {
            if (!prefs.contains(KEY_UPDATED_AT)) {
                WidgetSnapshot.EMPTY
            } else {
                WidgetSnapshot(
                    socPct = prefs.getInt(KEY_SOC, WidgetSnapshot.UNKNOWN_SOC),
                    charging = prefs.getBoolean(KEY_CHARGING, false),
                    connected = prefs.getBoolean(KEY_CONNECTED, false),
                    vehicleState = prefs.getString(KEY_VEHICLE_STATE, "") ?: "",
                    updatedAtMs = prefs.getLong(KEY_UPDATED_AT, 0L),
                )
            }
        } catch (ex: RuntimeException) {
            // ClassCastException from a corrupted/typed-over key, or any prefs failure: fall back to
            // the empty snapshot rather than letting the widget update crash.
            WidgetSnapshot.EMPTY
        }

    /**
     * Persists [snapshot] only when its meaningful fields differ from what is already stored.
     * Returns true when a write actually happened (so the caller can decide whether to nudge the
     * widget). The timestamp alone never forces a write — only SOC/charging/connected/vehicleState.
     */
    fun writeIfChanged(snapshot: WidgetSnapshot): Boolean {
        return try {
            val current = read()
            if (sameDisplayFields(current, snapshot)) {
                return false
            }
            prefs.edit {
                putInt(KEY_SOC, snapshot.socPct)
                putBoolean(KEY_CHARGING, snapshot.charging)
                putBoolean(KEY_CONNECTED, snapshot.connected)
                putString(KEY_VEHICLE_STATE, snapshot.vehicleState)
                putLong(KEY_UPDATED_AT, snapshot.updatedAtMs)
            }
            true
        } catch (ex: RuntimeException) {
            // A snapshot write must never propagate into the live session; drop it silently.
            false
        }
    }

    private fun sameDisplayFields(
        a: WidgetSnapshot,
        b: WidgetSnapshot,
    ): Boolean =
        a.socPct == b.socPct &&
            a.charging == b.charging &&
            a.connected == b.connected &&
            a.vehicleState == b.vehicleState

    companion object {
        private const val KEY_SOC = "widget_snapshot_soc"
        private const val KEY_CHARGING = "widget_snapshot_charging"
        private const val KEY_CONNECTED = "widget_snapshot_connected"
        private const val KEY_VEHICLE_STATE = "widget_snapshot_vehicle_state"
        private const val KEY_UPDATED_AT = "widget_snapshot_updated_at"
    }
}
