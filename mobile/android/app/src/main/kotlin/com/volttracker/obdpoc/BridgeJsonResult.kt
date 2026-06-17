package com.volttracker.obdpoc

import org.json.JSONArray
import org.json.JSONObject

/**
 * Internal bridge result shape: native code builds typed JSON payloads first, and only the
 * JavaScript-interface boundary serializes them to strings.
 */
internal sealed interface BridgeJsonResult {
    fun serialize(): String

    data class ObjectPayload(
        private val body: JSONObject,
    ) : BridgeJsonResult {
        override fun serialize(): String = body.toString()
    }

    data class ArrayPayload(
        private val body: JSONArray,
    ) : BridgeJsonResult {
        override fun serialize(): String = body.toString()
    }

    companion object {
        fun obj(body: JSONObject): BridgeJsonResult = ObjectPayload(body)

        fun array(body: JSONArray): BridgeJsonResult = ArrayPayload(body)

        fun error(
            error: String,
            message: String,
        ): BridgeJsonResult = obj(MainActivityUtils.errorPayload(error, message))
    }
}
