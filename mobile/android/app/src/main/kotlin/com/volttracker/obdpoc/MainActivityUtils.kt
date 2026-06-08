package com.volttracker.obdpoc

import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * Pure helpers extracted from [MainActivity] so the Activity stays small and the helpers can be
 * unit-tested without Android-framework stubs.
 */
object MainActivityUtils {
    /** Returns a parsed object, or an empty one for `null`, blank, or malformed input. */
    @JvmStatic
    fun parseJson(json: String?): JSONObject =
        try {
            if (json == null || json.trim().isEmpty()) JSONObject() else JSONObject(json)
        } catch (ex: JSONException) {
            JSONObject()
        }

    /** Returns the dashboard-facing shape for native read failures. */
    @JvmStatic
    fun errorPayload(
        error: String?,
        message: String?,
    ): JSONObject {
        val payload = JSONObject()
        try {
            payload.put("ok", false)
            payload.put("error", error ?: "unknown_error")
            payload.put("message", message ?: "")
        } catch (ignored: JSONException) {
            // JSONObject with static keys should not fail; keep the helper total anyway.
        }
        return payload
    }

    /** True if [state] names a session phase where the OBD link is being driven. */
    @JvmStatic
    fun isConnectedState(state: String?): Boolean =
        when ((state ?: "").lowercase(Locale.US)) {
            "connected",
            "connecting",
            "initializing",
            "scanning",
            "scan-complete",
            "demo",
            -> true
            else -> false
        }

    /** Returns the first non-blank argument, or `""` if every argument is blank/null. */
    @JvmStatic
    fun coalesce(
        first: String?,
        second: String?,
        third: String?,
    ): String {
        if (!first.isNullOrBlank()) {
            return first
        }
        if (!second.isNullOrBlank()) {
            return second
        }
        if (!third.isNullOrBlank()) {
            return third
        }
        return ""
    }

    /** Returns the last 5 chars of a MAC-like address prefixed with "...", or "" for shorts. */
    @JvmStatic
    fun redactAddress(address: String?): String {
        if (address == null || address.length < 5) {
            return ""
        }
        return "..." + address.substring(address.length - 5)
    }
}
