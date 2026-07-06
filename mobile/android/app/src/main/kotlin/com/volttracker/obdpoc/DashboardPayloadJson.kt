package com.volttracker.obdpoc

import org.json.JSONException
import org.json.JSONObject

/**
 * Pure builders for the `setStatus` and `setRestoreProgress` payloads [MainActivity] pushes to the
 * dashboard. Extracted (same style as [AppStateJson]) so the payload shapes stay unit-testable
 * without an Activity.
 */
object DashboardPayloadJson {
    /** The activity-side `setStatus` payload. */
    @JvmStatic
    fun status(
        state: String?,
        detail: String?,
        blocked: Boolean,
        bluetoothReady: Boolean,
        lastAddress: String?,
        lastName: String?,
    ): JSONObject {
        val payload = JSONObject()
        try {
            payload.put("state", state)
            payload.put("detail", detail)
            payload.put("blocked", blocked)
            payload.put("bluetoothReady", bluetoothReady)
            // Redact the Bluetooth MAC to its last 5 chars (as AppStatePayload.adapterJson already
            // does) before it reaches the WebView. The dashboard only renders it and checks
            // remembered-presence, both of which the redacted value satisfies; the full MAC is PII the
            // WebView never needs.
            payload.put("lastAddress", MainActivityUtils.redactAddress(lastAddress))
            payload.put("lastName", lastName)
        } catch (ignored: JSONException) {
            // JSONObject.put of strings/primitives does not throw for these inputs; ignore
            // defensively.
        }
        return payload
    }

    /** The `setRestoreProgress` payload; negative counters and blank phases are omitted. */
    @JvmStatic
    fun restoreProgress(
        visible: Boolean,
        busy: Boolean,
        title: String?,
        detail: String?,
        tone: String?,
        phase: String?,
        bytesDone: Long,
        bytesTotal: Long,
        rowsDone: Long,
        rowsTotal: Long,
        percent: Int,
        etaSeconds: Long,
        operation: String?,
    ): JSONObject {
        val payload = JSONObject()
        try {
            payload.put("visible", visible)
            payload.put("busy", busy)
            payload.put("title", title)
            payload.put("detail", detail)
            payload.put("tone", tone)
            if (!operation.isNullOrBlank()) {
                payload.put("operation", operation)
            }
            if (!phase.isNullOrBlank()) {
                payload.put("phase", phase)
            }
            if (bytesDone >= 0L) {
                payload.put("bytesDone", bytesDone)
            }
            if (bytesTotal >= 0L) {
                payload.put("bytesTotal", bytesTotal)
            }
            if (rowsDone >= 0L) {
                payload.put("rowsDone", rowsDone)
            }
            if (rowsTotal >= 0L) {
                payload.put("rowsTotal", rowsTotal)
            }
            if (percent >= 0) {
                payload.put("percent", percent.coerceIn(0, 100))
            }
            if (etaSeconds >= 0L) {
                payload.put("etaSeconds", etaSeconds)
            }
        } catch (ignored: JSONException) {
            // JSONObject.put of strings/primitives does not throw for these inputs; ignore
            // defensively.
        }
        return payload
    }
}
