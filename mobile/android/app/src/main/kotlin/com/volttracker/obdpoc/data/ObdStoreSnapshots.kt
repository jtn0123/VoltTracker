package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/** Builds write-side JSON/[ContentValues] snapshots and small upsert helper rows. */
class ObdStoreSnapshots {
    fun pidObservationPayload(
        observedAtMs: Long,
        command: String?,
        header: String?,
        pid: String?,
        name: String?,
        valueText: String?,
        valueNumeric: Double?,
        unit: String?,
        rawRequest: String?,
        rawResponse: String?,
    ): JSONObject {
        val payload = JSONObject()
        try {
            payload.put("observedAtMs", observedAtMs)
            payload.put("command", ObdStoreSupport.clean(command))
            payload.put("header", ObdStoreSupport.clean(header))
            payload.put("pid", ObdStoreSupport.clean(pid))
            payload.put("name", ObdStoreSupport.clean(name))
            payload.put("valueText", ObdStoreSupport.clean(valueText))
            if (valueNumeric != null) {
                payload.put("valueNumeric", valueNumeric)
            }
            payload.put("unit", ObdStoreSupport.clean(unit))
            payload.put("rawRequest", ObdStoreSupport.clean(rawRequest))
            payload.put("rawResponse", ObdStoreSupport.clean(rawResponse))
        } catch (ignored: JSONException) {
            // Local numeric/string values are safe.
        }
        return payload
    }

    fun pidObservationValues(
        sessionId: Long,
        observation: JSONObject,
        observedAtMs: Long,
    ): ContentValues {
        val values = ContentValues()
        values.put("session_id", sessionId)
        values.put("observed_at_ms", observedAtMs)
        values.put("command", ObdStoreSupport.clean(observation.optString("command", "")))
        values.put("header", ObdStoreSupport.clean(observation.optString("header", "")))
        values.put("pid", ObdStoreSupport.clean(observation.optString("pid", "")))
        values.put("name", ObdStoreSupport.clean(observation.optString("name", "")))
        values.put(
            "value_text",
            ObdStoreSupport.clean(observation.optString("valueText", observation.optString("value", ""))),
        )
        ObdStoreSupport.putOptionalDouble(values, "value_numeric", observation, "valueNumeric", "value")
        values.put("unit", ObdStoreSupport.clean(observation.optString("unit", "")))
        values.put(
            "raw_request",
            ObdStoreSupport.clean(observation.optString("rawRequest", observation.optString("request", ""))),
        )
        values.put(
            "raw_response",
            ObdStoreSupport.clean(observation.optString("rawResponse", observation.optString("raw", ""))),
        )
        values.put("json", observation.toString())
        return values
    }

    fun locationSamplePayload(
        capturedAtMs: Long,
        provider: String?,
        latitude: Double,
        longitude: Double,
        accuracyM: Double?,
        altitudeM: Double?,
        speedMps: Double?,
        bearingDeg: Double?,
        locationAgeMs: Long?,
        elapsedRealtimeNanos: Long?,
    ): JSONObject {
        val payload = JSONObject()
        try {
            payload.put("capturedAtMs", capturedAtMs)
            payload.put("provider", ObdStoreSupport.clean(provider))
            payload.put("latitude", latitude)
            payload.put("longitude", longitude)
            ObdStoreSupport.putNullable(payload, "accuracyM", accuracyM)
            ObdStoreSupport.putNullable(payload, "altitudeM", altitudeM)
            ObdStoreSupport.putNullable(payload, "speedMps", speedMps)
            ObdStoreSupport.putNullable(payload, "bearingDeg", bearingDeg)
            ObdStoreSupport.putNullable(payload, "locationAgeMs", locationAgeMs)
            ObdStoreSupport.putNullable(payload, "elapsedRealtimeNanos", elapsedRealtimeNanos)
        } catch (ignored: JSONException) {
            // Local numeric/string values are safe.
        }
        return payload
    }

    fun locationSampleValues(
        sessionId: Long,
        sample: JSONObject,
        capturedAtMs: Long,
    ): ContentValues {
        val values = ContentValues()
        values.put("session_id", sessionId)
        values.put("captured_at_ms", capturedAtMs)
        values.put(
            "provider",
            ObdStoreSupport.clean(sample.optString("provider", sample.optString("locationProvider", ""))),
        )
        values.put("latitude", sample.optDouble("latitude", 0.0))
        values.put("longitude", sample.optDouble("longitude", 0.0))
        ObdStoreSupport.putOptionalDouble(values, "accuracy_m", sample, "accuracyM")
        ObdStoreSupport.putOptionalDouble(values, "altitude_m", sample, "altitudeM")
        ObdStoreSupport.putOptionalDouble(values, "speed_mps", sample, "speedMps", "gpsSpeedMps")
        ObdStoreSupport.putOptionalDouble(values, "bearing_deg", sample, "bearingDeg")
        ObdStoreSupport.putOptionalLong(values, "location_age_ms", sample, "locationAgeMs")
        ObdStoreSupport.putOptionalLong(values, "elapsed_realtime_nanos", sample, "elapsedRealtimeNanos")
        values.put("json", sample.toString())
        return values
    }

    fun upsertDiagnosticCode(
        db: SQLiteDatabase,
        sessionId: Long,
        diagnosticCode: JSONObject,
        seenAtMs: Long,
    ): Long {
        val dtc =
            ObdStoreSupport
                .clean(diagnosticCode.optString("dtc", diagnosticCode.optString("code", "")))
                .uppercase(Locale.US)
        val status = ObdStoreSupport.clean(diagnosticCode.optString("status", "stored"))
        val moduleKey = ObdStoreSupport.clean(diagnosticCode.optString("moduleKey", "generic-obd"))
        if (dtc.isEmpty()) {
            return -1L
        }

        var existingId = -1L
        var firstSeenMs = seenAtMs
        var seenCount = 0L
        var lastSessionId = -1L
        db
            .query(
                VoltTrackerDb.TABLE_DIAGNOSTIC_CODES,
                arrayOf("_id", "first_seen_ms", "seen_count", "last_session_id"),
                "module_key = ? AND dtc = ? AND status = ?",
                arrayOf(moduleKey, dtc, status),
                null,
                null,
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    existingId = cursor.getLong(cursor.getColumnIndexOrThrow("_id"))
                    firstSeenMs = cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_ms"))
                    seenCount = cursor.getLong(cursor.getColumnIndexOrThrow("seen_count"))
                    lastSessionId = cursor.getLong(cursor.getColumnIndexOrThrow("last_session_id"))
                }
            }
        val nextSeenCount = if (lastSessionId == sessionId) seenCount else seenCount + 1L

        val values = ContentValues()
        values.put("dtc", dtc)
        values.put("status", status)
        values.put("status_label", ObdStoreSupport.clean(diagnosticCode.optString("statusLabel", status)))
        values.put("module_key", moduleKey)
        values.put("module_name", ObdStoreSupport.clean(diagnosticCode.optString("moduleName", "")))
        values.put("header", ObdStoreSupport.clean(diagnosticCode.optString("header", "")))
        values.put("first_seen_ms", firstSeenMs)
        values.put("last_seen_ms", seenAtMs)
        values.put("seen_count", kotlin.math.max(1L, nextSeenCount))
        values.put("last_session_id", sessionId)
        values.put(
            "raw_response",
            ObdStoreSupport.clean(diagnosticCode.optString("rawResponse", diagnosticCode.optString("raw", ""))),
        )
        values.put("json", diagnosticCode.toString())

        if (existingId > 0) {
            db.update(
                VoltTrackerDb.TABLE_DIAGNOSTIC_CODES,
                values,
                "_id = ?",
                arrayOf(existingId.toString()),
            )
            return existingId
        }
        return db.insertOrThrow(VoltTrackerDb.TABLE_DIAGNOSTIC_CODES, null, values)
    }

    fun findAdapterHistory(
        db: SQLiteDatabase,
        key: String,
    ): AdapterHistoryRecord? {
        db
            .query(
                VoltTrackerDb.TABLE_ADAPTER_HISTORY,
                null,
                "adapter_key = ?",
                arrayOf(key),
                null,
                null,
                null,
            ).use { cursor ->
                return if (cursor.moveToFirst()) ObdStoreSupport.readAdapterHistory(cursor) else null
            }
    }

    fun adapterHistoryValues(
        existing: AdapterHistoryRecord?,
        key: String,
        address: String?,
        name: String?,
        mode: String,
        status: String,
        samples: Int,
        supportedPids: String?,
        lastEventDetail: String?,
        sessionId: Long,
        now: Long,
    ): ContentValues {
        val values = ContentValues()
        values.put("adapter_key", key)
        values.put("address", ObdStoreSupport.clean(address))
        values.put("name", ObdStoreSupport.chooseLatest(ObdStoreSupport.clean(name), existing?.name ?: ""))
        values.put("first_seen_ms", existing?.firstSeenMs ?: now)
        values.put("last_seen_ms", now)
        values.put("connect_count", ObdStoreSupport.countForMode(existing, ObdLocalStore.MODE_OBD, mode))
        values.put("scan_count", ObdStoreSupport.countForMode(existing, ObdLocalStore.MODE_SCAN, mode))
        values.put("demo_count", ObdStoreSupport.countForMode(existing, ObdLocalStore.MODE_DEMO, mode))
        values.put("sample_count", kotlin.math.max(0, existing?.sampleCount ?: 0) + kotlin.math.max(0, samples))
        if (sessionId > 0) {
            values.put("last_session_id", sessionId)
        }
        values.put("last_mode", mode)
        values.put("last_status", status)
        values.put("supported_pids", ObdStoreSupport.chooseLatest(ObdStoreSupport.clean(supportedPids), existing?.supportedPids ?: ""))
        values.put("last_event_detail", ObdStoreSupport.clean(lastEventDetail))
        return values
    }
}
