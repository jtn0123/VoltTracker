package com.volttracker.obdpoc

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The display preferences both dashboards share: units, cost rates, the comparison gas car, the
 * charge target and the accessibility choices. Native is the source of truth. The WebView reads a
 * snapshot through the bridge at boot (and on every return to the foreground) and writes every
 * change back, so a rate set in the Compose Settings shows up in the classic dashboard and the
 * other way round.
 *
 * Keys are the WebView's own pref names so values cross the bridge unchanged; each is stored as
 * its JSON literal under `dash_pref_<key>`. The charge target is the exception: it already lives
 * in [EventNotificationPrefs] (it drives the target-reached alert), so it is read and written
 * there rather than duplicated. Every write is validated and clamped to the same ranges the
 * WebView inputs enforce, so a bad bridge call can never poison the cost model.
 */
class SharedDisplayPrefs(
    private val prefs: SharedPreferences,
) {
    enum class Units(
        val key: String,
    ) {
        IMPERIAL("imperial"),
        METRIC("metric"),
    }

    fun units(): Units = if (readString(KEY_UNITS) == Units.METRIC.key) Units.METRIC else Units.IMPERIAL

    /** Home electricity rate in $/kWh; 0 = not set. */
    fun pricePerKwh(): Double = readNumber(KEY_PRICE_PER_KWH) ?: 0.0

    /** Public / DC-fast rate in $/kWh; 0 = not set (every session bills at the home rate). */
    fun publicPricePerKwh(): Double = readNumber(KEY_PUBLIC_PRICE_PER_KWH) ?: 0.0

    /** Comparison gas car's MPG; null = not set. */
    fun mpg(): Double? = readNumber(KEY_MPG)?.takeIf { it > 0.0 }

    /** Gas price in $/gal; 0 = not set. */
    fun gasPricePerGal(): Double = readNumber(KEY_GAS_PRICE) ?: 0.0

    fun chargeTargetSoc(): Double = EventNotificationPrefs(prefs).targetSocPct()

    fun fontScale(): Double = readNumber(KEY_FONT_SCALE) ?: 1.0

    fun highContrast(): Boolean = readBoolean(KEY_HIGH_CONTRAST) ?: false

    fun quietTelemetry(): Boolean = readBoolean(KEY_QUIET_TELEMETRY) ?: true

    fun setUnits(units: Units) = set(KEY_UNITS, JSONObject.quote(units.key))

    fun setPricePerKwh(value: Double) = set(KEY_PRICE_PER_KWH, value.toString())

    fun setPublicPricePerKwh(value: Double) = set(KEY_PUBLIC_PRICE_PER_KWH, value.toString())

    fun setMpg(value: Double) = set(KEY_MPG, value.toString())

    fun setGasPricePerGal(value: Double) = set(KEY_GAS_PRICE, value.toString())

    fun setChargeTargetSoc(value: Double) = set(KEY_CHARGE_TARGET, value.toString())

    fun setFontScale(value: Double) = set(KEY_FONT_SCALE, value.toString())

    fun setHighContrast(on: Boolean) = set(KEY_HIGH_CONTRAST, on.toString())

    fun setQuietTelemetry(on: Boolean) = set(KEY_QUIET_TELEMETRY, on.toString())

    /**
     * Every shared key that has been set, as one JSON object of WebView pref values. Keys never set
     * are omitted so the WebView can tell "unset" (keep / migrate its own value) from a real value.
     */
    fun snapshotJson(): String {
        val out = JSONObject()
        for (key in KEYS) {
            if (key == KEY_CHARGE_TARGET) {
                if (prefs.contains(EventNotificationPrefs.PREF_TARGET_SOC)) out.put(key, chargeTargetSoc())
                continue
            }
            val raw = prefs.getString(PREFIX + key, null) ?: continue
            parse(raw)?.let { out.put(key, it) }
        }
        return out.toString()
    }

    /**
     * Stores one WebView pref from its JSON literal. Returns false (and stores nothing) for a key
     * outside the shared set or a value of the wrong type; numbers are clamped to the WebView's
     * ranges before they are written.
     */
    fun set(
        key: String,
        json: String?,
    ): Boolean {
        if (key !in KEYS || json == null) return false
        val value = normalize(key, parse(json)) ?: return false
        if (key == KEY_CHARGE_TARGET) {
            EventNotificationPrefs(prefs).setTargetSocPct((value as Number).toDouble())
        } else {
            prefs.edit { putString(PREFIX + key, literal(value)) }
        }
        return true
    }

    /** Copies every recognised shared key out of a WebView backup's `preferences` object. */
    fun applyAll(preferences: JSONObject?) {
        if (preferences == null) return
        for (key in KEYS) {
            if (!preferences.has(key)) continue
            set(key, literal(preferences.opt(key)))
        }
    }

    private fun readString(key: String): String? = prefs.getString(PREFIX + key, null)?.let(::parse) as? String

    private fun readNumber(key: String): Double? =
        (prefs.getString(PREFIX + key, null)?.let(::parse) as? Number)?.toDouble()?.takeIf { it.isFinite() }

    private fun readBoolean(key: String): Boolean? = prefs.getString(PREFIX + key, null)?.let(::parse) as? Boolean

    companion object {
        const val PREFIX = "dash_pref_"

        const val KEY_UNITS = "units"
        const val KEY_PRICE_PER_KWH = "pricePerKwh"
        const val KEY_PUBLIC_PRICE_PER_KWH = "publicPricePerKwh"
        const val KEY_MPG = "mpg"
        const val KEY_GAS_PRICE = "gasPricePerGal"
        const val KEY_CHARGE_TARGET = "chargeTargetSoc"
        const val KEY_FONT_SCALE = "fontScale"
        const val KEY_HIGH_CONTRAST = "highContrast"
        const val KEY_QUIET_TELEMETRY = "quietTelemetry"

        /** The WebView pref names native owns (mirrored by SHARED_PREF_KEYS in prefs.ts). */
        val KEYS: List<String> =
            listOf(
                KEY_UNITS,
                KEY_PRICE_PER_KWH,
                KEY_PUBLIC_PRICE_PER_KWH,
                KEY_MPG,
                KEY_GAS_PRICE,
                KEY_CHARGE_TARGET,
                KEY_FONT_SCALE,
                KEY_HIGH_CONTRAST,
                KEY_QUIET_TELEMETRY,
            )

        /** The text sizes offered in both dashboards. */
        val FONT_SCALES: List<Double> = listOf(1.0, 1.25, 1.5)

        // Same bounds as the WebView inputs (preferences.html / prefs.ts bindNumericPref).
        private val RANGES: Map<String, ClosedFloatingPointRange<Double>> =
            mapOf(
                KEY_PRICE_PER_KWH to 0.0..2.0,
                KEY_PUBLIC_PRICE_PER_KWH to 0.0..2.0,
                // 0 clears the comparison car; otherwise the WebView's 5–150 bounds apply.
                KEY_MPG to 0.0..150.0,
                KEY_GAS_PRICE to 0.0..10.0,
                KEY_CHARGE_TARGET to 50.0..100.0,
                KEY_FONT_SCALE to 1.0..1.5,
            )
        private const val MPG_MIN = 5.0

        private fun parse(json: String): Any? =
            try {
                JSONTokener(json).nextValue()
            } catch (_: JSONException) {
                null
            }

        private fun literal(value: Any?): String? =
            when (value) {
                null, JSONObject.NULL -> null
                is String -> JSONObject.quote(value)
                is Boolean -> value.toString()
                is Number -> JSONObject.numberToString(value)
                else -> null
            }

        private fun normalize(
            key: String,
            value: Any?,
        ): Any? =
            when (key) {
                KEY_UNITS -> (value as? String)?.takeIf { it == "imperial" || it == "metric" }
                KEY_HIGH_CONTRAST, KEY_QUIET_TELEMETRY -> value as? Boolean
                else -> clampNumber(key, (value as? Number)?.toDouble())
            }

        private fun clampNumber(
            key: String,
            value: Double?,
        ): Double? {
            if (value == null || !value.isFinite()) return null
            val clamped = value.coerceIn(RANGES.getValue(key))
            return if (key == KEY_MPG && clamped > 0.0) clamped.coerceAtLeast(MPG_MIN) else clamped
        }
    }
}
