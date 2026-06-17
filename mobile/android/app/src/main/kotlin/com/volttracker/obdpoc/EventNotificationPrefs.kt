package com.volttracker.obdpoc

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * SharedPreferences-backed, native-owned settings for the event-notification (M1) and
 * auto-DTC-scan (M3) features. Reached from the dashboard via the bridge, mirroring
 * [AutoConnectController]'s persistence pattern.
 *
 * The threshold alerts (low SOC / high pack temperature) are opt-in and default OFF; the
 * charge-complete and new-DTC notifications default ON because they are low-frequency and
 * directly actionable. Auto-scan-on-connect defaults OFF.
 *
 * This class only reads/writes settings + the last-scan bookkeeping; the firing decisions live in
 * [EventNotificationDecider] and the posting in [EventNotifier], both of which take a snapshot of
 * these values so they stay pure/testable.
 */
class EventNotificationPrefs(
    private val prefs: SharedPreferences,
) {
    // Monotonic version of the user-toggle/threshold settings, bumped by every settings setter. The
    // canonical value is persisted (see PREF_SETTINGS_VERSION) so it propagates across the SEPARATE
    // prefs instances the Activity (toggles) and the service (coordinator) each hold over the shared
    // file; the @Volatile mirror lets a same-instance bump be observed without a re-read. A reader
    // (the coordinator) caches its Settings snapshot and rebuilds only when this version moves, so a
    // mid-session toggle still takes effect on the next sample without 6 prefs lookups per sample. (G2)
    @Volatile
    private var settingsVersion: Long = prefs.getLong(PREF_SETTINGS_VERSION, 0L)

    /** Current settings-version; a change signals a cached [Settings] snapshot must be rebuilt. */
    fun settingsVersion(): Long = prefs.getLong(PREF_SETTINGS_VERSION, 0L)

    fun chargeCompleteEnabled(): Boolean =
        prefs.getBoolean(PREF_CHARGE_COMPLETE_ENABLED, DEFAULT_CHARGE_COMPLETE_ENABLED)

    fun newDtcEnabled(): Boolean = prefs.getBoolean(PREF_NEW_DTC_ENABLED, DEFAULT_NEW_DTC_ENABLED)

    fun lowSocEnabled(): Boolean = prefs.getBoolean(PREF_LOW_SOC_ENABLED, DEFAULT_LOW_SOC_ENABLED)

    fun lowSocThresholdPct(): Double =
        clampSoc(prefs.getFloat(PREF_LOW_SOC_THRESHOLD, DEFAULT_LOW_SOC_THRESHOLD.toFloat()).toDouble())

    fun highPackTempEnabled(): Boolean = prefs.getBoolean(PREF_HIGH_TEMP_ENABLED, DEFAULT_HIGH_TEMP_ENABLED)

    fun highPackTempThresholdC(): Double =
        clampTemp(prefs.getFloat(PREF_HIGH_TEMP_THRESHOLD, DEFAULT_HIGH_TEMP_THRESHOLD.toFloat()).toDouble())

    /**
     * User charge target % (M2). Feeds the target-SOC-reached ping and the complete-vs-interrupted
     * branch; the dashboard ETA reads its own localStorage mirror. Clamped to [TARGET_SOC_MIN,
     * TARGET_SOC_MAX]; defaults to a full charge.
     */
    fun targetSocPct(): Double =
        clampTargetSoc(prefs.getFloat(PREF_TARGET_SOC, DEFAULT_TARGET_SOC.toFloat()).toDouble())

    fun autoScanOnConnectEnabled(): Boolean = prefs.getBoolean(PREF_AUTO_SCAN_ENABLED, DEFAULT_AUTO_SCAN_ENABLED)

    /**
     * Whether the maintenance-overdue alert (M2) is on. Opt-in (default OFF): it surfaces a service
     * the user already chose to track, so it should not start nagging until they ask for it.
     */
    fun maintenanceDueEnabled(): Boolean =
        prefs.getBoolean(PREF_MAINTENANCE_DUE_ENABLED, DEFAULT_MAINTENANCE_DUE_ENABLED)

    fun setChargeCompleteEnabled(enabled: Boolean) = putBool(PREF_CHARGE_COMPLETE_ENABLED, enabled)

    fun setNewDtcEnabled(enabled: Boolean) = putBool(PREF_NEW_DTC_ENABLED, enabled)

    fun setLowSocEnabled(enabled: Boolean) = putBool(PREF_LOW_SOC_ENABLED, enabled)

    fun setLowSocThresholdPct(pct: Double) =
        mutateSettings { putFloat(PREF_LOW_SOC_THRESHOLD, clampSoc(pct).toFloat()) }

    fun setHighPackTempEnabled(enabled: Boolean) = putBool(PREF_HIGH_TEMP_ENABLED, enabled)

    fun setHighPackTempThresholdC(celsius: Double) =
        mutateSettings { putFloat(PREF_HIGH_TEMP_THRESHOLD, clampTemp(celsius).toFloat()) }

    fun setTargetSocPct(pct: Double) = mutateSettings { putFloat(PREF_TARGET_SOC, clampTargetSoc(pct).toFloat()) }

    // Written directly without a version bump: auto-scan-on-connect is NOT part of the decider's
    // cached [Settings] snapshot (see EventNotificationCoordinator.currentSettings), so bumping
    // PREF_SETTINGS_VERSION here would needlessly invalidate that cache and force a rebuild.
    fun setAutoScanOnConnectEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(PREF_AUTO_SCAN_ENABLED, enabled) }
    }

    // Written directly without a version bump: the maintenance-overdue alert (M2) runs on app-open,
    // not on the per-sample decider, so its toggle is not in the cached [Settings] snapshot and a
    // bump would needlessly invalidate that cache.
    fun setMaintenanceDueEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(PREF_MAINTENANCE_DUE_ENABLED, enabled) }
    }

    /** Epoch millis of the last completed auto-scan, or 0 when none has run. */
    fun lastAutoScanAtMs(): Long = prefs.getLong(PREF_LAST_AUTO_SCAN_MS, 0L)

    fun setLastAutoScanAtMs(ms: Long) {
        prefs.edit { putLong(PREF_LAST_AUTO_SCAN_MS, ms) }
    }

    /**
     * Whether a DTC baseline has ever been established (any scan has run and persisted its codes).
     *
     * Distinguishes "first scan ever" (no baseline — pre-existing codes are NOT new and must not
     * raise a false "new diagnostic codes" alert at first use) from "a previous scan found zero codes"
     * (baseline exists and is empty, so a newly-appearing code IS new). Tracked as its own flag rather
     * than inferred from an empty code set, which both states share.
     */
    fun hasDtcBaseline(): Boolean = prefs.getBoolean(PREF_DTC_BASELINE_SET, false)

    /** The DTC code set observed on the most recent scan; empty when no scan has run. */
    fun lastScanDtcCodes(): Set<String> {
        val raw = prefs.getString(PREF_LAST_SCAN_DTCS, null) ?: return emptySet()
        return try {
            val arr = JSONArray(raw)
            val out = LinkedHashSet<String>(arr.length())
            for (i in 0 until arr.length()) {
                val code = arr.optString(i, "").trim().uppercase()
                if (code.isNotEmpty()) {
                    out.add(code)
                }
            }
            out
        } catch (_: JSONException) {
            emptySet()
        }
    }

    fun setLastScanDtcCodes(codes: Collection<String>) {
        val arr = JSONArray()
        for (code in codes) {
            val clean = code.trim().uppercase()
            if (clean.isNotEmpty()) {
                arr.put(clean)
            }
        }
        // Persisting any scan's codes also establishes the baseline, so the next scan diffs against a
        // real prior set rather than treating pre-existing codes as newly-appeared.
        prefs.edit {
            putString(PREF_LAST_SCAN_DTCS, arr.toString())
            putBoolean(PREF_DTC_BASELINE_SET, true)
        }
    }

    /**
     * Crossing signatures the maintenance-overdue alert (M2) has already fired for, so a given
     * overdue crossing notifies once and never re-nags on later app-opens. Each is a
     * [MaintenanceDueEvaluator] signature (`<entryId>:km` / `<entryId>:months`). Persisted as a JSON
     * array, mirroring the DTC baseline set; empty when nothing has fired yet.
     */
    fun notifiedMaintenanceSignatures(): Set<String> {
        val raw = prefs.getString(PREF_MAINTENANCE_NOTIFIED, null) ?: return emptySet()
        return try {
            val arr = JSONArray(raw)
            val out = LinkedHashSet<String>(arr.length())
            for (i in 0 until arr.length()) {
                val sig = arr.optString(i, "").trim()
                if (sig.isNotEmpty()) {
                    out.add(sig)
                }
            }
            out
        } catch (_: JSONException) {
            emptySet()
        }
    }

    fun setNotifiedMaintenanceSignatures(signatures: Collection<String>) {
        val arr = JSONArray()
        for (sig in signatures) {
            val clean = sig.trim()
            if (clean.isNotEmpty()) {
                arr.put(clean)
            }
        }
        prefs.edit { putString(PREF_MAINTENANCE_NOTIFIED, arr.toString()) }
    }

    /** Settings snapshot the dashboard renders its toggles from. */
    fun stateJson(): String {
        val payload = JSONObject()
        try {
            payload.put("chargeComplete", chargeCompleteEnabled())
            payload.put("newDtc", newDtcEnabled())
            payload.put("lowSoc", lowSocEnabled())
            payload.put("lowSocThresholdPct", lowSocThresholdPct())
            payload.put("highPackTemp", highPackTempEnabled())
            payload.put("highPackTempThresholdC", highPackTempThresholdC())
            payload.put("targetSocPct", targetSocPct())
            payload.put("autoScanOnConnect", autoScanOnConnectEnabled())
            payload.put("maintenanceDue", maintenanceDueEnabled())
        } catch (_: JSONException) {
            // Local values are safe.
        }
        return payload.toString()
    }

    private fun putBool(
        key: String,
        value: Boolean,
    ) = mutateSettings { putBoolean(key, value) }

    /**
     * Applies a settings write and its accompanying version bump together. The read-modify-write of
     * the persisted version counter is serialized on a process-wide lock so it can't lose an
     * increment when the Activity (toggles) and the service (coordinator) — which hold SEPARATE
     * [EventNotificationPrefs] over the same process-wide [SharedPreferences] — write concurrently;
     * the version stays strictly monotonic. The bump is committed in the same editor as the setting
     * (atomic on disk) and mirrored into the @Volatile field so a same-instance bump is observed
     * without a re-read, invalidating a cached [Settings] reader. (G2)
     */
    private fun mutateSettings(block: SharedPreferences.Editor.() -> Unit) {
        synchronized(SETTINGS_WRITE_LOCK) {
            val editor = prefs.edit()
            editor.block()
            val next = prefs.getLong(PREF_SETTINGS_VERSION, 0L) + 1L
            editor.putLong(PREF_SETTINGS_VERSION, next)
            editor.apply()
            settingsVersion = next
        }
    }

    companion object {
        // Process-wide guard for the settings-write + version-bump read-modify-write. Static because
        // the Activity and the service hold separate EventNotificationPrefs instances over the same
        // (process-singleton) SharedPreferences, so a per-instance lock wouldn't serialize them.
        private val SETTINGS_WRITE_LOCK = Any()

        const val PREF_CHARGE_COMPLETE_ENABLED = "notify_charge_complete_enabled"
        const val PREF_NEW_DTC_ENABLED = "notify_new_dtc_enabled"
        const val PREF_LOW_SOC_ENABLED = "notify_low_soc_enabled"
        const val PREF_LOW_SOC_THRESHOLD = "notify_low_soc_threshold_pct"
        const val PREF_HIGH_TEMP_ENABLED = "notify_high_pack_temp_enabled"
        const val PREF_HIGH_TEMP_THRESHOLD = "notify_high_pack_temp_threshold_c"
        const val PREF_TARGET_SOC = "notify_target_soc_pct"
        const val PREF_AUTO_SCAN_ENABLED = "auto_scan_on_connect_enabled"
        const val PREF_LAST_AUTO_SCAN_MS = "auto_scan_last_at_ms"
        const val PREF_LAST_SCAN_DTCS = "notify_last_scan_dtcs"
        const val PREF_DTC_BASELINE_SET = "notify_dtc_baseline_set"
        const val PREF_MAINTENANCE_DUE_ENABLED = "notify_maintenance_due_enabled"
        const val PREF_MAINTENANCE_NOTIFIED = "notify_maintenance_signatures"
        const val PREF_SETTINGS_VERSION = "notify_settings_version"

        const val DEFAULT_CHARGE_COMPLETE_ENABLED = true
        const val DEFAULT_NEW_DTC_ENABLED = true
        const val DEFAULT_LOW_SOC_ENABLED = false
        const val DEFAULT_LOW_SOC_THRESHOLD = 20.0
        const val DEFAULT_HIGH_TEMP_ENABLED = false
        const val DEFAULT_HIGH_TEMP_THRESHOLD = 45.0
        const val DEFAULT_TARGET_SOC = 100.0
        const val DEFAULT_AUTO_SCAN_ENABLED = false
        const val DEFAULT_MAINTENANCE_DUE_ENABLED = false

        private const val SOC_MIN = 1.0
        private const val SOC_MAX = 99.0
        private const val TEMP_MIN = 20.0
        private const val TEMP_MAX = 80.0

        // Matches the dashboard target-SOC input clamp (charge.html / prefs.ts): a Volt owner charges
        // somewhere between a daily 50% floor and a full 100% charge.
        private const val TARGET_SOC_MIN = 50.0
        private const val TARGET_SOC_MAX = 100.0

        private fun clampSoc(value: Double): Double =
            if (value.isNaN()) DEFAULT_LOW_SOC_THRESHOLD else value.coerceIn(SOC_MIN, SOC_MAX)

        private fun clampTemp(value: Double): Double =
            if (value.isNaN()) DEFAULT_HIGH_TEMP_THRESHOLD else value.coerceIn(TEMP_MIN, TEMP_MAX)

        private fun clampTargetSoc(value: Double): Double =
            if (value.isNaN()) DEFAULT_TARGET_SOC else value.coerceIn(TARGET_SOC_MIN, TARGET_SOC_MAX)
    }
}
