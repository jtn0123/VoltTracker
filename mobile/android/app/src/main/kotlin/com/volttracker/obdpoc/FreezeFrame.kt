package com.volttracker.obdpoc

import org.json.JSONArray
import org.json.JSONObject

/**
 * The car's freeze frame: the snapshot of readings the engine computer saved when it set a trouble
 * code (SAE J1979 Mode 02). Frame 00 is read one PID at a time — `02 0C 00` answers
 * `42 0C 00 1A F8` — and each reply is decoded exactly like its live Mode 01 twin (`41 0C 1A F8`),
 * so a snapshot value is never decoded differently from the same live reading.
 */
object FreezeFrame {
    /** The frame-00 request for the code that triggered the snapshot. */
    const val DTC_REQUEST = "020200"

    /** Snapshot readings worth showing a driver, in display order. */
    val PIDS = listOf("0D", "0C", "04", "05", "0F", "11", "2F", "42", "1F")

    /** One decoded snapshot reading: "engine rpm" · 1726 · "rpm". */
    data class Reading(
        val name: String,
        val value: Double,
        val unit: String,
    )

    /** `02 <pid> 00`: frame 00 of [pid]. */
    fun request(pid: String): String = "02${pid}00"

    /** The snapshot reading in a frame-00 reply to [request]`(`[pid]`)`, or null when there isn't one. */
    fun parse(
        pid: String,
        response: String?,
    ): Reading? {
        if (response == null) return null
        val marker = "42${pid}00"
        for (line in ObdProtocol.adapterHexLines(response)) {
            val at = line.indexOf(marker)
            if (at < 0) continue
            val asLive = "41 $pid " + line.substring(at + marker.length).chunked(2).joinToString(" ")
            val parsed = ObdProtocol.parseKnownValue("01$pid", asLive) ?: continue
            val value = parsed.valueNumeric ?: continue
            return Reading(parsed.name, value, parsed.unit)
        }
        return null
    }

    /** The scan telemetry's `freezeFrame` object: the triggering code and its readings. */
    fun toJson(
        dtc: String?,
        readings: List<Reading>,
    ): JSONObject =
        JSONObject()
            .put("dtc", dtc ?: "")
            .put(
                "readings",
                JSONArray().apply {
                    readings.forEach {
                        put(
                            JSONObject().put("name", it.name).put("value", it.value).put("unit", it.unit),
                        )
                    }
                },
            )

    /** Reads back [toJson]; unreadable entries are skipped. */
    fun readingsFrom(json: JSONObject?): List<Reading> {
        val rows = json?.optJSONArray("readings") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val name = row.optString("name", "")
            val value = row.optDouble("value", Double.NaN)
            if (name.isEmpty() || value.isNaN()) null else Reading(name, value, row.optString("unit", ""))
        }
    }
}
