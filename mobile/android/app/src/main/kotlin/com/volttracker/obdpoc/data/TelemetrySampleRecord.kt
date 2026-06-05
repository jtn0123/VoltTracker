package com.volttracker.obdpoc.data

import org.json.JSONException
import org.json.JSONObject

class TelemetrySampleRecord(
    @JvmField val id: Long,
    @JvmField val sessionId: Long,
    @JvmField val capturedAtMs: Long,
    source: String?,
    vehicleState: String?,
    @JvmField val speedKph: Int,
    @JvmField val rpm: Int,
    @JvmField val coolantC: Int,
    @JvmField val loadPct: Int,
    @JvmField val throttlePct: Int,
    @JvmField val voltage: Double,
    @JvmField val soc: Double,
    @JvmField val batteryTemp: Double,
    @JvmField val powerKw: Double,
    @JvmField val sampleNumber: Int,
    @JvmField val sessionMs: Long,
    raw: String?,
    json: String?,
) {
    @JvmField val source: String = nonNull(source)

    @JvmField val vehicleState: String = nonNull(vehicleState)

    @JvmField val raw: String = nonNull(raw)

    @JvmField val json: String = nonNull(json)

    fun toJson(): JSONObject =
        try {
            JSONObject(json)
        } catch (ex: JSONException) {
            JSONObject()
        }
}

private fun nonNull(value: String?): String = value ?: ""
