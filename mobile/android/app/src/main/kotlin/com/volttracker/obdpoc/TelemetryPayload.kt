package com.volttracker.obdpoc

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Typed handoff for telemetry payloads before dashboard/log serialization. */
class TelemetryPayload private constructor(
    builder: Builder,
) {
    private val source: String? = builder.source
    private val connected: Boolean? = builder.connected
    private val updatedAt: Long? = builder.updatedAt
    private val adapter: String? = builder.adapter
    private val sampleCount: Int? = builder.sampleCount
    private val sessionMs: Long? = builder.sessionMs
    private val speedKph: Int? = builder.speedKph
    private val rpm: Int? = builder.rpm
    private val coolantC: Int? = builder.coolantC
    private val loadPct: Int? = builder.loadPct
    private val throttlePct: Int? = builder.throttlePct
    private val soc: Int? = builder.soc
    private val voltage: Double? = builder.voltage
    private val powerKw: Double? = builder.powerKw
    private val vehicleState: String? = builder.vehicleState
    private val vehicleStateConfidence: String? = builder.vehicleStateConfidence
    private val supportedPids: String? = builder.supportedPids
    private val raw: String? = builder.raw
    private val extras: JSONObject = copyObject(builder.extras)

    fun isEmpty(): Boolean = toJson().length() == 0

    fun source(): String = source ?: ""

    fun connected(): Boolean = connected == true

    fun updatedAt(): Long = updatedAt ?: 0L

    fun toJson(): JSONObject {
        val out = copyObject(extras)
        putIfNotNull(out, "source", source)
        putIfNotNull(out, "connected", connected)
        putIfNotNull(out, "updatedAt", updatedAt)
        putIfNotNull(out, "adapter", adapter)
        putIfNotNull(out, "sampleCount", sampleCount)
        putIfNotNull(out, "sessionMs", sessionMs)
        putIfNotNull(out, "speedKph", speedKph)
        putIfNotNull(out, "rpm", rpm)
        putIfNotNull(out, "coolantC", coolantC)
        putIfNotNull(out, "loadPct", loadPct)
        putIfNotNull(out, "throttlePct", throttlePct)
        putIfNotNull(out, "soc", soc)
        putIfNotNull(out, "voltage", voltage)
        putIfNotNull(out, "powerKw", powerKw)
        putIfNotNull(out, "vehicleState", vehicleState)
        putIfNotNull(out, "vehicleStateConfidence", vehicleStateConfidence)
        putIfNotNull(out, "supportedPids", supportedPids)
        putIfNotNull(out, "raw", raw)
        return out
    }

    class Builder {
        internal var source: String? = null
        internal var connected: Boolean? = null
        internal var updatedAt: Long? = null
        internal var adapter: String? = null
        internal var sampleCount: Int? = null
        internal var sessionMs: Long? = null
        internal var speedKph: Int? = null
        internal var rpm: Int? = null
        internal var coolantC: Int? = null
        internal var loadPct: Int? = null
        internal var throttlePct: Int? = null
        internal var soc: Int? = null
        internal var voltage: Double? = null
        internal var powerKw: Double? = null
        internal var vehicleState: String? = null
        internal var vehicleStateConfidence: String? = null
        internal var supportedPids: String? = null
        internal var raw: String? = null
        internal val extras = JSONObject()

        fun source(value: String?): Builder {
            source = value
            return this
        }

        fun connected(value: Boolean?): Builder {
            connected = value
            return this
        }

        fun updatedAt(value: Long?): Builder {
            updatedAt = value
            return this
        }

        fun adapter(value: String?): Builder {
            adapter = value
            return this
        }

        fun sampleCount(value: Int?): Builder {
            sampleCount = value
            return this
        }

        fun sessionMs(value: Long?): Builder {
            sessionMs = value
            return this
        }

        fun speedKph(value: Int?): Builder {
            speedKph = value
            return this
        }

        fun rpm(value: Int?): Builder {
            rpm = value
            return this
        }

        fun coolantC(value: Int?): Builder {
            coolantC = value
            return this
        }

        fun loadPct(value: Int?): Builder {
            loadPct = value
            return this
        }

        fun throttlePct(value: Int?): Builder {
            throttlePct = value
            return this
        }

        fun soc(value: Int?): Builder {
            soc = value
            return this
        }

        fun voltage(value: Double?): Builder {
            voltage = value
            return this
        }

        fun powerKw(value: Double?): Builder {
            powerKw = value
            return this
        }

        fun vehicleState(value: String?): Builder {
            vehicleState = value
            return this
        }

        fun vehicleStateConfidence(value: String?): Builder {
            vehicleStateConfidence = value
            return this
        }

        fun supportedPids(value: String?): Builder {
            supportedPids = value
            return this
        }

        fun raw(value: String?): Builder {
            raw = value
            return this
        }

        fun extra(
            key: String?,
            value: Any?,
        ): Builder {
            if (key == null || isTypedKey(key)) {
                return this
            }
            putIfNotNull(extras, key, value)
            return this
        }

        fun build(): TelemetryPayload = TelemetryPayload(this)
    }

    companion object {
        @JvmStatic
        fun fromJson(payload: JSONObject?): TelemetryPayload {
            if (payload == null) {
                return builder().build()
            }
            val builder =
                builder()
                    .source(optString(payload, "source"))
                    .connected(optBoolean(payload, "connected"))
                    .updatedAt(optLong(payload, "updatedAt"))
                    .adapter(optString(payload, "adapter"))
                    .sampleCount(optInteger(payload, "sampleCount"))
                    .sessionMs(optLong(payload, "sessionMs"))
                    .speedKph(optInteger(payload, "speedKph"))
                    .rpm(optInteger(payload, "rpm"))
                    .coolantC(optInteger(payload, "coolantC"))
                    .loadPct(optInteger(payload, "loadPct"))
                    .throttlePct(optInteger(payload, "throttlePct"))
                    .soc(optInteger(payload, "soc"))
                    .voltage(optDouble(payload, "voltage"))
                    .powerKw(optDouble(payload, "powerKw"))
                    .vehicleState(optString(payload, "vehicleState"))
                    .vehicleStateConfidence(optString(payload, "vehicleStateConfidence"))
                    .supportedPids(optString(payload, "supportedPids"))
                    .raw(optString(payload, "raw"))
            val keys = payload.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!isTypedKey(key)) {
                    builder.extra(key, payload.opt(key))
                }
            }
            return builder.build()
        }

        @JvmStatic
        fun builder(): Builder = Builder()
    }
}

private fun copyObject(source: JSONObject?): JSONObject {
    val copy = JSONObject()
    if (source == null) {
        return copy
    }
    val keys = source.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        putIfNotNull(copy, key, deepCopyValue(source.opt(key)))
    }
    return copy
}

private fun deepCopyValue(value: Any?): Any? {
    if (value is JSONObject) {
        return copyObject(value)
    }
    if (value is JSONArray) {
        val copy = JSONArray()
        for (i in 0 until value.length()) {
            copy.put(deepCopyValue(value.opt(i)))
        }
        return copy
    }
    return value
}

private fun isTypedKey(key: String): Boolean =
    when (key) {
        "source",
        "connected",
        "updatedAt",
        "adapter",
        "sampleCount",
        "sessionMs",
        "speedKph",
        "rpm",
        "coolantC",
        "loadPct",
        "throttlePct",
        "soc",
        "voltage",
        "powerKw",
        "vehicleState",
        "vehicleStateConfidence",
        "supportedPids",
        "raw",
        -> true

        else -> false
    }

private fun optString(
    payload: JSONObject,
    key: String,
): String? = if (payload.has(key) && !payload.isNull(key)) payload.optString(key) else null

private fun optBoolean(
    payload: JSONObject,
    key: String,
): Boolean? = if (payload.has(key) && !payload.isNull(key)) payload.optBoolean(key) else null

private fun optInteger(
    payload: JSONObject,
    key: String,
): Int? = if (payload.has(key) && !payload.isNull(key)) payload.optInt(key) else null

private fun optLong(
    payload: JSONObject,
    key: String,
): Long? = if (payload.has(key) && !payload.isNull(key)) payload.optLong(key) else null

private fun optDouble(
    payload: JSONObject,
    key: String,
): Double? = if (payload.has(key) && !payload.isNull(key)) payload.optDouble(key) else null

private fun putIfNotNull(
    out: JSONObject,
    key: String,
    value: Any?,
) {
    if (value == null) {
        return
    }
    try {
        out.put(key, value)
    } catch (ex: JSONException) {
        throw IllegalArgumentException("Could not encode telemetry field $key", ex)
    }
}
