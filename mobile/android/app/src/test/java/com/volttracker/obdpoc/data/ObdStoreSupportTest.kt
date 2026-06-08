package com.volttracker.obdpoc.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the stateless helpers extracted into [ObdStoreSupport]. Splitting them out of
 * [ObdLocalStore] is what makes this coverage possible.
 */
class ObdStoreSupportTest {
    @Test
    fun cleanTrimsAndTolueratesNull() {
        assertEquals("", ObdStoreSupport.clean(null))
        assertEquals("x", ObdStoreSupport.clean("  x  "))
        assertEquals("", ObdStoreSupport.clean("   "))
    }

    @Test
    fun boundedLimitClampsToOneThroughFiveHundred() {
        assertEquals("1", ObdStoreSupport.boundedLimit(0))
        assertEquals("1", ObdStoreSupport.boundedLimit(-20))
        assertEquals("50", ObdStoreSupport.boundedLimit(50))
        assertEquals("500", ObdStoreSupport.boundedLimit(9999))
    }

    @Test
    fun cleanModeNormalizesToAKnownMode() {
        assertEquals("obd", ObdStoreSupport.cleanMode("obd"))
        assertEquals("scan", ObdStoreSupport.cleanMode("SCAN"))
        assertEquals("demo", ObdStoreSupport.cleanMode(" demo "))
        // anything unrecognised falls back to obd
        assertEquals("obd", ObdStoreSupport.cleanMode("garbage"))
        assertEquals("obd", ObdStoreSupport.cleanMode(null))
    }

    @Test
    fun cleanStatusNormalizesAndDefaults() {
        assertEquals("error", ObdStoreSupport.cleanStatus("ERROR"))
        assertEquals("active", ObdStoreSupport.cleanStatus("active"))
        // blank defaults to complete
        assertEquals("complete", ObdStoreSupport.cleanStatus(""))
        assertEquals("complete", ObdStoreSupport.cleanStatus(null))
    }

    @Test
    fun adapterKeyPrefersTheAddress() {
        assertEquals("AA:BB:CC", ObdStoreSupport.adapterKey("aa:bb:cc", "obd"))
        assertEquals("demo", ObdStoreSupport.adapterKey("", "demo"))
        assertEquals("unknown", ObdStoreSupport.adapterKey("", "obd"))
        assertEquals("unknown", ObdStoreSupport.adapterKey(null, "obd"))
    }

    @Test
    fun chooseLatestPrefersANonBlankCandidate() {
        assertEquals("new", ObdStoreSupport.chooseLatest("new", "old"))
        assertEquals("old", ObdStoreSupport.chooseLatest("   ", "old"))
        assertEquals("old", ObdStoreSupport.chooseLatest(null, "old"))
    }

    @Test
    @Throws(JSONException::class)
    fun isUsefulTelemetryDetectsRealReadings() {
        assertFalse(ObdStoreSupport.isUsefulTelemetry(JSONObject()))
        assertFalse(ObdStoreSupport.isUsefulTelemetry(JSONObject().put("note", "hi")))
        assertFalse(ObdStoreSupport.isUsefulTelemetry(JSONObject().put("source", "obd")))
        assertFalse(ObdStoreSupport.isUsefulTelemetry(JSONObject().put("raw", "NO DATA")))
        assertTrue(ObdStoreSupport.isUsefulTelemetry(JSONObject().put("speedKph", 40)))
        assertTrue(ObdStoreSupport.isUsefulTelemetry(JSONObject().put("voltage", 13.8)))
        assertTrue(ObdStoreSupport.isUsefulTelemetry(JSONObject().put("latitude", 32.7)))
        assertTrue(ObdStoreSupport.isUsefulTelemetry(JSONObject().put("clearDtcOk", true)))
    }

    @Test
    fun optTimestampFallsBackWhenAbsent() {
        assertEquals(99L, ObdStoreSupport.optTimestamp(JSONObject(), "k", 99L))
        assertEquals(5L, ObdStoreSupport.optTimestamp(jsonWith("k", 5L), "k", 99L))
    }

    @Test
    fun parseObjectTreatsGarbageAsEmpty() {
        assertEquals(0, ObdStoreSupport.parseObject(null).length())
        assertEquals(0, ObdStoreSupport.parseObject("not json").length())
        assertEquals(1, ObdStoreSupport.parseObject("{\"a\":1}").optInt("a"))
    }

    @Test
    @Throws(JSONException::class)
    fun reverseFlipsArrayOrder() {
        val source = JSONArray().put(1).put(2).put(3)
        val reversed = ObdStoreSupport.reverse(source)
        assertEquals(3, reversed.getInt(0))
        assertEquals(2, reversed.getInt(1))
        assertEquals(1, reversed.getInt(2))
    }

    private fun jsonWith(
        key: String,
        value: Long,
    ): JSONObject =
        try {
            JSONObject().put(key, value)
        } catch (ex: JSONException) {
            throw IllegalStateException(ex)
        }
}
