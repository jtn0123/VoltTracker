package com.volttracker.obdpoc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure helper logic split out of [MainActivity] into [MainActivityUtils] —
 * JSON parsing, connection-state classification, value coalescing, and adapter-address redaction.
 */
class MainActivityTest {
    @Test
    fun parseJsonReadsValidObjects() {
        assertEquals(1, MainActivityUtils.parseJson("{\"a\":1}").optInt("a"))
    }

    @Test
    fun parseJsonReturnsEmptyForNullOrGarbage() {
        assertEquals(0, MainActivityUtils.parseJson(null).length())
        assertEquals(0, MainActivityUtils.parseJson("").length())
        assertEquals(0, MainActivityUtils.parseJson("not json").length())
    }

    @Test
    fun isConnectedStateRecognisesActiveStates() {
        assertTrue(MainActivityUtils.isConnectedState("connected"))
        assertTrue(MainActivityUtils.isConnectedState("CONNECTED"))
        assertTrue(MainActivityUtils.isConnectedState("connecting"))
        assertTrue(MainActivityUtils.isConnectedState("initializing"))
        assertTrue(MainActivityUtils.isConnectedState("scanning"))
        assertTrue(MainActivityUtils.isConnectedState("scan-complete"))
        assertTrue(MainActivityUtils.isConnectedState("demo"))
    }

    @Test
    fun isConnectedStateRejectsIdleStates() {
        assertFalse(MainActivityUtils.isConnectedState("idle"))
        assertFalse(MainActivityUtils.isConnectedState("ready"))
        assertFalse(MainActivityUtils.isConnectedState(null))
    }

    @Test
    fun coalesceReturnsFirstNonBlank() {
        assertEquals("a", MainActivityUtils.coalesce("a", "b", "c"))
        assertEquals("b", MainActivityUtils.coalesce("", "b", "c"))
        assertEquals("b", MainActivityUtils.coalesce("   ", "b", "c"))
        assertEquals("c", MainActivityUtils.coalesce(null, null, "c"))
        assertEquals("", MainActivityUtils.coalesce(null, null, null))
    }

    @Test
    fun redactAddressKeepsOnlyTheLastFiveChars() {
        assertEquals("...44:55", MainActivityUtils.redactAddress("00:11:22:33:44:55"))
    }

    @Test
    fun redactAddressDropsShortOrMissingAddresses() {
        assertEquals("", MainActivityUtils.redactAddress("ab"))
        assertEquals("", MainActivityUtils.redactAddress(null))
    }

    @Test
    fun errorPayloadUsesStableDashboardShape() {
        val payload: JSONObject = MainActivityUtils.errorPayload("storage_summary_failed", "broken")
        assertFalse(payload.optBoolean("ok", true))
        assertEquals("storage_summary_failed", payload.optString("error"))
        assertEquals("broken", payload.optString("message"))
    }
}
