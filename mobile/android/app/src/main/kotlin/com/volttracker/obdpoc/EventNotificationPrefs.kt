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
    fun chargeCompleteEnabled(): Boolean =
        prefs.getBoolean(PREF_CHARGE_COMPLETE_ENABLED, DEFAULT_CHARGE_COMPLETE_ENABLED)

    fun newDtcEnabled(): Boolean = prefs.getBoolean(PREF_NEW_DTC_ENABLED, DEFAULT_NEW_DTC_ENABLED)

    fun lowSocEnabled(): Boolean = prefs.getBoolean(PREF_LOW_SOC_ENABLED, DEFAULT_LOW_SOC_ENABLED)

    fun lowSocThresholdPct(): Double =
        clampSoc(prefs.getFloat(PREF_LOW_SOC_THRESHOLD, DEFAULT_LOW_SOC_THRESHOLD.toFloat()).toDouble())

    fun highPackTempEnabled(): Boolean = prefs.getBoolean(PREF_HIGH_TEMP_ENABLED, DEFAULT_HIGH_TEMP_ENABLED)

    fun highPackTempThresholdC(): Double =
        clampTemp(prefs.getFloat(PREF_HIGH_TEMP_THRESHOLD, DEFAULT_HIGH_TEMP_THRESHOLD.toFloat()).toDouble())

    fun autoScanOnConnectEnabled(): Boolean = prefs.getBoolean(PREF_AUTO_SCAN_ENABLED, DEFAULT_AUTO_SCAN_ENABLED)

    fun setChargeCompleteEnabled(enabled: Boolean) = putBool(PREF_CHARGE_COMPLETE_ENABLED, enabled)

    fun setNewDtcEnabled(enabled: Boolean) = putBool(PREF_NEW_DTC_ENABLED, enabled)

    fun setLowSocEnabled(enabled: Boolean) = putBool(PREF_LOW_SOC_ENABLED, enabled)

    fun setLowSocThresholdPct(pct: Double) {
        prefs.edit { putFloat(PREF_LOW_SOC_THRESHOLD, clampSoc(pct).toFloat()) }
    }

    fun setHighPackTempEnabled(enabled: Boolean) = putBool(PREF_HIGH_TEMP_ENABLED, enabled)

    fun setHighPackTempThresholdC(celsius: Double) {
        prefs.edit { putFloat(PREF_HIGH_TEMP_THRESHOLD, clampTemp(celsius).toFloat()) }
    }

    fun setAutoScanOnConnectEnabled(enabled: Boolean) = putBool(PREF_AUTO_SCAN_ENABLED, enabled)

    /** Epoch millis of the last completed auto-scan, or 0 when none has run. */
    fun lastAutoScanAtMs(): Long = prefs.getLong(PREF_LAST_AUTO_SCAN_MS, 0L)

    fun setLastAutoScanAtMs(ms: Long) {
        prefs.edit { putLong(PREF_LAST_AUTO_SCAN_MS, ms) }
    }

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
        prefs.edit { putString(PREF_LAST_SCAN_DTCS, arr.toString()) }
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
            payload.put("autoScanOnConnect", autoScanOnConnectEnabled())
        } catch (_: JSONException) {
            // Local values are safe.
        }
        return payload.toString()
    }

    private fun putBool(
        key: String,
        value: Boolean,
    ) {
        prefs.edit { putBoolean(key, value) }
    }

    companion object {
        const val PREF_CHARGE_COMPLETE_ENABLED = "notify_charge_complete_enabled"
        const val PREF_NEW_DTC_ENABLED = "notify_new_dtc_enabled"
        const val PREF_LOW_SOC_ENABLED = "notify_low_soc_enabled"
        const val PREF_LOW_SOC_THRESHOLD = "notify_low_soc_threshold_pct"
        const val PREF_HIGH_TEMP_ENABLED = "notify_high_pack_temp_enabled"
        const val PREF_HIGH_TEMP_THRESHOLD = "notify_high_pack_temp_threshold_c"
        const val PREF_AUTO_SCAN_ENABLED = "auto_scan_on_connect_enabled"
        const val PREF_LAST_AUTO_SCAN_MS = "auto_scan_last_at_ms"
        const val PREF_LAST_SCAN_DTCS = "notify_last_scan_dtcs"

        const val DEFAULT_CHARGE_COMPLETE_ENABLED = true
        const val DEFAULT_NEW_DTC_ENABLED = true
        const val DEFAULT_LOW_SOC_ENABLED = false
        const val DEFAULT_LOW_SOC_THRESHOLD = 20.0
        const val DEFAULT_HIGH_TEMP_ENABLED = false
        const val DEFAULT_HIGH_TEMP_THRESHOLD = 45.0
        const val DEFAULT_AUTO_SCAN_ENABLED = false

        private const val SOC_MIN = 1.0
        private const val SOC_MAX = 99.0
        private const val TEMP_MIN = 20.0
        private const val TEMP_MAX = 80.0

        private fun clampSoc(value: Double): Double =
            if (value.isNaN()) DEFAULT_LOW_SOC_THRESHOLD else value.coerceIn(SOC_MIN, SOC_MAX)

        private fun clampTemp(value: Double): Double =
            if (value.isNaN()) DEFAULT_HIGH_TEMP_THRESHOLD else value.coerceIn(TEMP_MIN, TEMP_MAX)
    }
}
