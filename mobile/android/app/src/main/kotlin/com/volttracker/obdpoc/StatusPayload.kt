package com.volttracker.obdpoc

import org.json.JSONException
import org.json.JSONObject

/** Typed builder for live service-status payloads sent to the dashboard and session log. */
class StatusPayload(
    state: String?,
    detail: String?,
    @JvmField val blocked: Boolean,
    adapter: String?,
    @JvmField val updatedAt: Long,
    @JvmField val logFile: String?,
    @JvmField val failureClass: FailureClass?,
    @JvmField val lastVoltage: Double?,
    competingApps: String?,
    @JvmField val extras: JSONObject?,
) {
    @JvmField
    val state: String = state ?: ""

    @JvmField
    val detail: String = detail ?: ""

    @JvmField
    val adapter: String = adapter ?: ""

    @JvmField
    val competingApps: String = competingApps ?: ""

    fun toJson(): JSONObject {
        val payload = JSONObject()
        try {
            payload.put("state", state)
            payload.put("detail", detail)
            payload.put("blocked", blocked)
            payload.put("adapter", adapter)
            payload.put("updatedAt", updatedAt)
            if (logFile != null) {
                payload.put("logFile", logFile)
            }
            if (failureClass != null) {
                payload.put("failureClass", failureClass.wireName())
            }
            if (lastVoltage != null) {
                payload.put("lastVoltage", lastVoltage.toDouble())
            }
            if (competingApps.isNotEmpty()) {
                payload.put("competingApps", competingApps)
            }
            if (extras != null) {
                val keys = extras.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    payload.put(key, extras.get(key))
                }
            }
        } catch (_ignored: JSONException) {
            // Static field names and local primitive values are safe.
        }
        return payload
    }
}
