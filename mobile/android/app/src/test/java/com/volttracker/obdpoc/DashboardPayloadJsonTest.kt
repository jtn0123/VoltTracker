package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the payload shapes [MainActivity] publishes through [DashboardPayloadJson]. */
class DashboardPayloadJsonTest {
    @Test
    fun statusCarriesAllFields() {
        val payload =
            DashboardPayloadJson.status(
                "ready",
                "All good.",
                false,
                true,
                "AA:BB:CC:DD:EE:FF",
                "MyAdapter",
            )

        assertEquals("ready", payload.optString("state"))
        assertEquals("All good.", payload.optString("detail"))
        assertFalse(payload.optBoolean("blocked", true))
        assertTrue(payload.optBoolean("bluetoothReady"))
        assertEquals("AA:BB:CC:DD:EE:FF", payload.optString("lastAddress"))
        assertEquals("MyAdapter", payload.optString("lastName"))
    }

    @Test
    fun statusOmitsNullValues() {
        val payload = DashboardPayloadJson.status(null, null, true, false, null, null)

        assertFalse(payload.has("state"))
        assertFalse(payload.has("detail"))
        assertTrue(payload.optBoolean("blocked"))
    }

    @Test
    fun restoreProgressIncludesCountersAndClampsPercent() {
        val payload =
            DashboardPayloadJson.restoreProgress(
                true,
                true,
                "Restoring backup",
                "Halfway there.",
                "busy",
                "Copying rows",
                10L,
                20L,
                5L,
                50L,
                250,
                12L,
            )

        assertTrue(payload.optBoolean("visible"))
        assertTrue(payload.optBoolean("busy"))
        assertEquals("Restoring backup", payload.optString("title"))
        assertEquals("Halfway there.", payload.optString("detail"))
        assertEquals("busy", payload.optString("tone"))
        assertEquals("Copying rows", payload.optString("phase"))
        assertEquals(10L, payload.optLong("bytesDone"))
        assertEquals(20L, payload.optLong("bytesTotal"))
        assertEquals(5L, payload.optLong("rowsDone"))
        assertEquals(50L, payload.optLong("rowsTotal"))
        assertEquals("percent must clamp to 0..100", 100, payload.optInt("percent"))
        assertEquals(12L, payload.optLong("etaSeconds"))
    }

    @Test
    fun restoreProgressOmitsNegativeCountersAndBlankPhase() {
        val payload =
            DashboardPayloadJson.restoreProgress(
                false,
                false,
                null,
                null,
                "idle",
                "  ",
                -1L,
                -1L,
                -1L,
                -1L,
                -1,
                -1L,
            )

        assertFalse(payload.optBoolean("visible"))
        assertFalse(payload.has("phase"))
        assertFalse(payload.has("bytesDone"))
        assertFalse(payload.has("bytesTotal"))
        assertFalse(payload.has("rowsDone"))
        assertFalse(payload.has("rowsTotal"))
        assertFalse(payload.has("percent"))
        assertFalse(payload.has("etaSeconds"))
    }
}
