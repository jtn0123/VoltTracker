package com.volttracker.obdpoc

import org.json.JSONException
import org.json.JSONObject

/**
 * One-line summary of a recorded session, appended to `files/obd-logs/sessions-summary.jsonl` on
 * session end, and read via `VoltBridge#getRecentSessions(int)` to render the last-connected badge
 * and adapter-health pill.
 */
class SessionSummary(
    @JvmField val startMs: Long,
    @JvmField val endMs: Long,
    outcome: String?,
    @JvmField val sampleCount: Int,
    adapter: String?,
    adapterAddress: String?,
    @JvmField val failureClass: String?,
) {
    @JvmField val outcome: String = outcome ?: ""

    @JvmField val adapter: String = adapter ?: ""

    @JvmField val adapterAddress: String = adapterAddress ?: ""

    fun toJson(): JSONObject {
        val json = JSONObject()
        try {
            json.put("startMs", startMs)
            json.put("endMs", endMs)
            json.put("outcome", outcome)
            json.put("sampleCount", sampleCount)
            json.put("adapter", adapter)
            json.put("adapterAddress", adapterAddress)
            if (failureClass != null) {
                json.put("failureClass", failureClass)
            }
        } catch (ignored: JSONException) {
            // Local values are safe.
        }
        return json
    }

    companion object {
        /** Outcome strings written to the rollup file. */
        const val OUTCOME_SUCCESS: String = "success"
        const val OUTCOME_FAILED: String = "failed"
        const val OUTCOME_ABORTED: String = "aborted"

        @JvmStatic
        fun fromJson(json: JSONObject?): SessionSummary? {
            if (json == null) {
                return null
            }
            val failureClass =
                if (json.has("failureClass")) {
                    json.opt("failureClass")?.takeUnless { it == JSONObject.NULL }?.toString()
                } else {
                    null
                }
            return SessionSummary(
                json.optLong("startMs"),
                json.optLong("endMs"),
                json.optString("outcome"),
                json.optInt("sampleCount"),
                json.optString("adapter"),
                json.optString("adapterAddress"),
                failureClass,
            )
        }
    }
}
