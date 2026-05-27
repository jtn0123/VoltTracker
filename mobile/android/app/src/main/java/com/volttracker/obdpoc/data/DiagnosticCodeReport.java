package com.volttracker.obdpoc.data;

import static com.volttracker.obdpoc.data.ObdStoreSupport.clean;
import static com.volttracker.obdpoc.data.ObdStoreSupport.nullableLong;

import android.database.Cursor;
import org.json.JSONException;
import org.json.JSONObject;

/** Stable read-model for diagnostic-code rows before they are serialized for the dashboard. */
final class DiagnosticCodeReport {

    static final String[] QUERY_COLUMNS = {
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
        "raw_response"
    };

    final long id;
    final String dtc;
    final String status;
    final String statusLabel;
    final String moduleKey;
    final String moduleName;
    final String header;
    final long firstSeenMs;
    final long lastSeenMs;
    final long seenCount;
    final Long lastSessionId;
    final String rawResponse;

    DiagnosticCodeReport(
            long id,
            String dtc,
            String status,
            String statusLabel,
            String moduleKey,
            String moduleName,
            String header,
            long firstSeenMs,
            long lastSeenMs,
            long seenCount,
            Long lastSessionId,
            String rawResponse) {
        this.id = id;
        this.dtc = clean(dtc);
        this.status = clean(status);
        this.statusLabel = clean(statusLabel);
        this.moduleKey = clean(moduleKey);
        this.moduleName = clean(moduleName);
        this.header = clean(header);
        this.firstSeenMs = firstSeenMs;
        this.lastSeenMs = lastSeenMs;
        this.seenCount = seenCount;
        this.lastSessionId = lastSessionId;
        this.rawResponse = clean(rawResponse);
    }

    static DiagnosticCodeReport fromCursor(Cursor cursor) {
        return new DiagnosticCodeReport(
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
                nullableLong(cursor, "last_session_id"),
                cursor.getString(cursor.getColumnIndexOrThrow("raw_response")));
    }

    JSONObject toJson() {
        JSONObject item = new JSONObject();
        try {
            item.put("id", id);
            item.put("dtc", dtc);
            item.put("status", status);
            item.put("statusLabel", statusLabel);
            item.put("moduleKey", moduleKey);
            item.put("moduleName", moduleName);
            item.put("header", header);
            item.put("firstSeenMs", firstSeenMs);
            item.put("lastSeenMs", lastSeenMs);
            item.put("seenCount", seenCount);
            item.put("lastSessionId", lastSessionId == null ? JSONObject.NULL : lastSessionId);
            item.put("rawResponse", rawResponse);
        } catch (JSONException ignored) {
            // Field names and primitive values are static.
        }
        return item;
    }
}
