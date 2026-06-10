package com.volttracker.obdpoc.data

import android.database.Cursor
import org.json.JSONException
import org.json.JSONObject

/** JSON row/summary helpers for [ObdStoreReports]. */
object ObdStoreReportJson {
    @Throws(JSONException::class)
    fun capabilityFromCursor(cursor: Cursor): JSONObject =
        JSONObject()
            .put("id", cursor.getLong(cursor.getColumnIndexOrThrow("_id")))
            .put("adapterKey", ObdStoreSupport.clean(cursor.getString(cursor.getColumnIndexOrThrow("adapter_key"))))
            .put("protocol", ObdStoreSupport.clean(cursor.getString(cursor.getColumnIndexOrThrow("protocol"))))
            .put("header", ObdStoreSupport.clean(cursor.getString(cursor.getColumnIndexOrThrow("header"))))
            .put("command", ObdStoreSupport.clean(cursor.getString(cursor.getColumnIndexOrThrow("command"))))
            .put("pid", ObdStoreSupport.clean(cursor.getString(cursor.getColumnIndexOrThrow("pid"))))
            .put("name", ObdStoreSupport.clean(cursor.getString(cursor.getColumnIndexOrThrow("name"))))
            .put("unit", ObdStoreSupport.clean(cursor.getString(cursor.getColumnIndexOrThrow("unit"))))
            .put("supported", cursor.getInt(cursor.getColumnIndexOrThrow("supported")) == 1)
            .put("responseCount", cursor.getLong(cursor.getColumnIndexOrThrow("response_count")))
            .put("firstSeenMs", cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_ms")))
            .put("lastSeenMs", cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_ms")))
            .put(
                "sample",
                ObdStoreSupport.parseObject(cursor.getString(cursor.getColumnIndexOrThrow("sample_json"))),
            )

    fun statusCounts(counts: Map<String, Long>): JSONObject {
        val payload = JSONObject()
        for ((key, value) in counts) {
            payload.put(key, value)
        }
        return payload
    }

    fun notFound(message: String): JSONObject =
        JSONObject().safePut("ok", false).safePut("error", "not_found").safePut("message", message)

    fun exportError(message: String): JSONObject =
        JSONObject().safePut("ok", false).safePut("error", "export_failed").safePut("message", message)

    fun boxedOrNull(value: Number?): Any = value ?: JSONObject.NULL

    private fun JSONObject.safePut(
        key: String,
        value: Any?,
    ): JSONObject =
        try {
            put(key, value)
        } catch (ignored: JSONException) {
            this
        }
}
