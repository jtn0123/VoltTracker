package com.volttracker.obdpoc.ui.diag

import org.json.JSONObject
import java.util.Locale

/** One raw reading on Live signals › All readings: "Pack voltage" · "359.2 V", and how old it is. */
data class RawReading(
    val key: String,
    val label: String,
    val value: String,
    /** How long ago the car last reported it (its `…StaleMs`), when the sample says. */
    val ageMs: Long? = null,
)

/**
 * Every reading in a live sample, named from its key ("packVoltage" → "Pack voltage") and sorted by
 * name: the whole feed a mechanic or a curious owner may want, beyond the curated Live signals.
 * Bookkeeping keys ([skip]), staleness ages, empty values and nested objects are left out.
 */
fun rawReadings(
    sample: JSONObject,
    skip: Set<String>,
): List<RawReading> =
    sample
        .keys()
        .asSequence()
        .filterNot { it in skip || it.endsWith(STALE_SUFFIX) || sample.isNull(it) }
        .mapNotNull { key ->
            val value = valueText(sample.opt(key)) ?: return@mapNotNull null
            val (name, unit) = splitUnit(key)
            val age = sample.optLong(key + STALE_SUFFIX, -1L).takeIf { it >= 0L }
            RawReading(key, humanize(name), if (unit == null) value else "$value $unit", age)
        }.sortedBy { it.label.lowercase(Locale.US) }
        .toList()

/** "12 s old" once a reading is older than [OLD_MS]; null while it's fresh. */
fun RawReading.ageText(): String? {
    val ms = ageMs?.takeIf { it >= OLD_MS } ?: return null
    val s = ms / 1000
    return if (s < 120) "$s s old" else "${s / 60} min old"
}

/** Case-insensitive match on the label or the key, for the search box. */
fun List<RawReading>.matching(query: String): List<RawReading> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return filter { it.label.contains(q, ignoreCase = true) || it.key.contains(q, ignoreCase = true) }
}

private fun valueText(value: Any?): String? =
    when (value) {
        is Boolean -> if (value) "yes" else "no"
        is Int, is Long -> value.toString()
        is Number -> {
            val d = value.toDouble()
            if (d.isNaN() || d.isInfinite()) {
                null
            } else if (d == Math.rint(d) && kotlin.math.abs(d) < 1e12) {
                d.toLong().toString()
            } else {
                "%.3f".format(Locale.US, d).trimEnd('0').trimEnd('.')
            }
        }
        is String -> value.trim().takeIf { it.isNotEmpty() }?.take(MAX_TEXT)
        else -> null
    }

/** A key's unit suffix ("batteryTempC" → "batteryTemp" + "°C"), when it has a known one. */
private fun splitUnit(key: String): Pair<String, String?> {
    for ((suffix, unit) in UNIT_SUFFIXES) {
        // "TempC" keeps its "Temp": only the trailing unit letter goes.
        val strip = if (suffix.startsWith("Temp")) 1 else suffix.length
        if (key.length > suffix.length && key.endsWith(suffix)) return key.dropLast(strip) to unit
    }
    // A bare trailing C (Celsius) or A (amps) after a lower-case word: "coolantC", "packCurrentA".
    val last = key.last()
    if (key.length > 2 && key[key.length - 2].isLowerCase() && (last == 'C' || last == 'A')) {
        return key.dropLast(1) to if (last == 'C') "°C" else "A"
    }
    if (key.endsWith("Voltage") || key.endsWith("Volts")) return key to "V"
    return key to null
}

private fun humanize(key: String): String {
    val words =
        key
            .replace(Regex("([a-z])([0-9])"), "$1 $2")
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
            .replace('_', ' ')
            .trim()
            .lowercase(Locale.US)
            .split(' ')
            .filter { it.isNotEmpty() }
            .mapNotNull { WORDS[it] ?: it }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    return words.replaceFirstChar { it.titlecase(Locale.US) }
}

/** Words a key abbreviates, spelled the way the rest of the app writes them. */
private val WORDS =
    mapOf(
        "ev" to "EV",
        "soc" to "SOC",
        "soh" to "SOH",
        "ac" to "A/C",
        "hv" to "HV",
        "12v" to "12 V",
        "aux" to "",
        "rpm" to "RPM",
        "gps" to "GPS",
        "fl" to "front left",
        "fr" to "front right",
        "rl" to "rear left",
        "rr" to "rear right",
        "pe" to "power electronics",
        "a" to "A",
        "b" to "B",
    )

private const val STALE_SUFFIX = "StaleMs"
private const val OLD_MS = 5_000L
private const val MAX_TEXT = 60

/** Longest suffix first, so "Kwh" wins over "Kw". */
private val UNIT_SUFFIXES =
    listOf(
        "Kwh" to "kWh",
        "Kph" to "km/h",
        "Mph" to "mph",
        "Pct" to "%",
        "Kpa" to "kPa",
        "Psi" to "psi",
        "Rpm" to "rpm",
        "Kw" to "kW",
        "Km" to "km",
        "Nm" to "Nm",
        "Mv" to "mV",
        "Ah" to "Ah",
        "Ms" to "ms",
        "TempC" to "°C",
        "TempF" to "°F",
    ).sortedByDescending { it.first.length }
