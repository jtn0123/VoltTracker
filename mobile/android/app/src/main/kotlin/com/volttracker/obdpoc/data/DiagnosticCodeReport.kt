package com.volttracker.obdpoc.data

import android.database.Cursor
import org.json.JSONException
import org.json.JSONObject

/** Stable read-model for diagnostic-code rows before they are serialized for the dashboard. */
class DiagnosticCodeReport(
    @JvmField val id: Long,
    dtc: String?,
    status: String?,
    statusLabel: String?,
    moduleKey: String?,
    moduleName: String?,
    header: String?,
    @JvmField val firstSeenMs: Long,
    @JvmField val lastSeenMs: Long,
    @JvmField val seenCount: Long,
    @JvmField val lastSessionId: Long?,
    rawResponse: String?,
) {
    @JvmField
    val dtc: String = ObdStoreSupport.clean(dtc)

    @JvmField
    val status: String = ObdStoreSupport.clean(status)

    @JvmField
    val statusLabel: String = ObdStoreSupport.clean(statusLabel)

    @JvmField
    val moduleKey: String = ObdStoreSupport.clean(moduleKey)

    @JvmField
    val moduleName: String = ObdStoreSupport.clean(moduleName)

    @JvmField
    val header: String = ObdStoreSupport.clean(header)

    @JvmField
    val rawResponse: String = ObdStoreSupport.clean(rawResponse)

    fun toJson(): JSONObject {
        val item = JSONObject()
        try {
            item.put("id", id)
            item.put("dtc", dtc)
            item.put("status", status)
            item.put("statusLabel", statusLabel)
            item.put("moduleKey", moduleKey)
            item.put("moduleName", moduleName)
            item.put("header", header)
            item.put("firstSeenMs", firstSeenMs)
            item.put("lastSeenMs", lastSeenMs)
            item.put("seenCount", seenCount)
            item.put("lastSessionId", lastSessionId ?: JSONObject.NULL)
            item.put("rawResponse", rawResponse)
        } catch (_ignored: JSONException) {
            // Field names and primitive values are static.
        }
        return item
    }

    companion object {
        @JvmField
        val QUERY_COLUMNS =
            arrayOf(
                "_id",
                "dtc",
                "status",
                "status_label",
                "module_key",
                "module_name",
                "header",
                "first_seen_ms",
                "last_seen_ms",
                "seen_count",
                "last_session_id",
                "raw_response",
            )

        @JvmStatic
        fun fromCursor(cursor: Cursor): DiagnosticCodeReport =
            DiagnosticCodeReport(
                cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("dtc")),
                cursor.getString(cursor.getColumnIndexOrThrow("status")),
                cursor.getString(cursor.getColumnIndexOrThrow("status_label")),
                cursor.getString(cursor.getColumnIndexOrThrow("module_key")),
                cursor.getString(cursor.getColumnIndexOrThrow("module_name")),
                cursor.getString(cursor.getColumnIndexOrThrow("header")),
                cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_ms")),
                cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_ms")),
                cursor.getLong(cursor.getColumnIndexOrThrow("seen_count")),
                ObdStoreSupport.nullableLongBoxed(cursor, "last_session_id"),
                cursor.getString(cursor.getColumnIndexOrThrow("raw_response")),
            )
    }
}
