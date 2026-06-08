package com.volttracker.obdpoc.data

import org.json.JSONException
import org.json.JSONObject

class StatusEventRecord(
    @JvmField val id: Long,
    @JvmField val sessionId: Long,
    @JvmField val occurredAtMs: Long,
    kind: String?,
    state: String?,
    detail: String?,
    @JvmField val blocked: Boolean,
    payload: String?,
) {
    @JvmField val kind: String = kind ?: ""

    @JvmField val state: String = state ?: ""

    @JvmField val detail: String = detail ?: ""

    @JvmField val payload: String = payload ?: ""

    fun toJson(): JSONObject =
        try {
            JSONObject(payload)
        } catch (ex: JSONException) {
            JSONObject()
        }
}
