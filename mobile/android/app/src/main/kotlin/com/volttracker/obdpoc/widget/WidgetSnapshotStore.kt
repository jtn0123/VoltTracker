package com.volttracker.obdpoc.widget

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.volttracker.obdpoc.AppPrefs

/**
 * Persists the compact [WidgetSnapshot] to the app's shared-prefs file so the out-of-process
 * widget can read the latest vehicle state without binding to the service.
 *
 * Writes are THROTTLED, not per sample. Every `apply()` here rewrites the whole shared
 * `volt_obd_prefs.xml`, and Android makes the main thread wait for pending `apply()` writes at
 * service start commands and activity stops (`QueuedWork.waitToFinish`). Bumping the freshness
 * timestamp on every 1 Hz sample (the old behavior) kept a prefs fsync permanently in flight during
 * a live or demo session, so those lifecycle points blocked behind the disk — an ANR on slow
 * storage. Now the newest merged snapshot is held in memory ([read] returns it) and reaches disk:
 * - immediately when the connection or charging flag flips (or nothing has been persisted yet), so
 *   plugging in / unplugging / disconnecting shows at once and the session's final state is saved;
 * - otherwise at most once per [minPersistIntervalMs] (SOC / vehicle-state changes and the
 *   freshness clock), which is far finer than the widget's minute-granular "updated … ago" line.
 *
 * [writeIfChanged] returns true (asking the caller to redraw) only when a persisted display field
 * changed or the relative freshness label is due ([FRESHNESS_REDRAW_INTERVAL_MS]). Every method
 * swallows storage failures so a snapshot write can never break a live OBD session.
 *
 * It reuses [AppPrefs.FILE] (`volt_obd_prefs`) under a dedicated `widget_snapshot_*` key namespace so
 * it shares the existing event-notification settings file rather than opening a second prefs file.
 */
class WidgetSnapshotStore(
    private val prefs: SharedPreferences,
    private val minPersistIntervalMs: Long = MIN_PERSIST_INTERVAL_MS,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE),
    )

    private val lock = Any()

    // Newest merged snapshot, possibly ahead of what is on disk. Null until the first write.
    private var latest: WidgetSnapshot? = null

    // Snapshot clock of the last disk write by this instance; 0 = none yet (first write persists).
    private var lastPersistAtMs = 0L

    /** The newest snapshot (in-memory when this instance has written one), else the persisted one. */
    fun read(): WidgetSnapshot = synchronized(lock) { latest } ?: readPersisted()

    /** Reads the last persisted snapshot, or [WidgetSnapshot.EMPTY] when none / on read failure. */
    fun readPersisted(): WidgetSnapshot =
        try {
            if (!prefs.contains(KEY_UPDATED_AT)) {
                WidgetSnapshot.EMPTY
            } else {
                val changedAt = prefs.getLong(KEY_UPDATED_AT, 0L)
                WidgetSnapshot(
                    socPct = prefs.getInt(KEY_SOC, WidgetSnapshot.UNKNOWN_SOC),
                    charging = prefs.getBoolean(KEY_CHARGING, false),
                    connected = prefs.getBoolean(KEY_CONNECTED, false),
                    vehicleState = prefs.getString(KEY_VEHICLE_STATE, "") ?: "",
                    updatedAtMs = changedAt,
                    // Older snapshots written before this key existed fall back to the change time.
                    lastSampleAtMs = prefs.getLong(KEY_LAST_SAMPLE_AT, changedAt),
                )
            }
        } catch (ex: RuntimeException) {
            // ClassCastException from a corrupted/typed-over key, or any prefs failure: fall back to
            // the empty snapshot rather than letting the widget update crash.
            WidgetSnapshot.EMPTY
        }

    /**
     * Merges [snapshot] into the in-memory state, persists it when the throttle allows (see the
     * class doc), and reports whether the widget should redraw.
     *
     * The freshness timestamp ([WidgetSnapshot.lastSampleAtMs], falling back to [updatedAtMs])
     * advances on EVERY call in memory, so the next write carries the latest sample time and a
     * steady charge/parked period is not wrongly flagged stale. The change time ([updatedAtMs]) only
     * moves when a display field (SOC/charging/connected/vehicleState) actually changed.
     */
    fun writeIfChanged(snapshot: WidgetSnapshot): Boolean =
        try {
            synchronized(lock) { mergeAndMaybePersist(snapshot) }
        } catch (ex: RuntimeException) {
            // A snapshot write must never propagate into the live session; drop it silently.
            false
        }

    private fun mergeAndMaybePersist(snapshot: WidgetSnapshot): Boolean {
        val persisted = readPersisted()
        val base = latest ?: persisted
        val sampleAt = snapshot.freshnessAtMs()
        val next =
            if (sameDisplayFields(base, snapshot) && base.hasData()) {
                // No display change: keep the change time, advance only the sample clock.
                base.copy(lastSampleAtMs = maxOf(base.freshnessAtMs(), sampleAt))
            } else {
                snapshot.copy(lastSampleAtMs = sampleAt)
            }
        latest = next
        val now = maxOf(snapshot.updatedAtMs, sampleAt)
        val urgent =
            !persisted.hasData() || persisted.connected != next.connected || persisted.charging != next.charging
        val displayDirty = !sameDisplayFields(persisted, next)
        // A wall-clock step backwards must not stall persistence until the clock catches up.
        val due = now - lastPersistAtMs >= minPersistIntervalMs || now < lastPersistAtMs
        val dirty = displayDirty || next.freshnessAtMs() > persisted.freshnessAtMs()
        if (!urgent && !(due && dirty)) {
            return false
        }
        val lastRedrawAt = prefs.getLong(KEY_LAST_REDRAW_AT, persisted.updatedAtMs)
        val redraw = displayDirty || next.freshnessAtMs() - lastRedrawAt >= FRESHNESS_REDRAW_INTERVAL_MS
        prefs.edit {
            putInt(KEY_SOC, next.socPct)
            putBoolean(KEY_CHARGING, next.charging)
            putBoolean(KEY_CONNECTED, next.connected)
            putString(KEY_VEHICLE_STATE, next.vehicleState)
            putLong(KEY_UPDATED_AT, next.updatedAtMs)
            putLong(KEY_LAST_SAMPLE_AT, next.freshnessAtMs())
            if (redraw) {
                putLong(KEY_LAST_REDRAW_AT, maxOf(next.updatedAtMs, next.freshnessAtMs()))
            }
        }
        lastPersistAtMs = now
        return redraw
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

        /** Last display-CHANGE time; drives the debounced redraw + doubles as the "any data" sentinel. */
        private const val KEY_UPDATED_AT = "widget_snapshot_updated_at"

        /** Last SAMPLE arrival time (bumped every sample); drives the freshness/stale signal. */
        private const val KEY_LAST_SAMPLE_AT = "widget_snapshot_last_sample_at"

        /** Last requested widget redraw, used to advance relative freshness copy at a bounded rate. */
        private const val KEY_LAST_REDRAW_AT = "widget_snapshot_last_redraw_at"

        internal const val FRESHNESS_REDRAW_INTERVAL_MS = 60_000L

        /** Minimum spacing of non-urgent disk writes (SOC / state / freshness while streaming). */
        const val MIN_PERSIST_INTERVAL_MS = 30_000L
    }
}
