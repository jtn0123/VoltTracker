package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreezeFrameTest {
    @Test
    fun aFrameZeroReplyDecodesLikeItsLiveTwin() {
        assertEquals(
            FreezeFrame.Reading("engine rpm", 1726.0, "rpm"),
            FreezeFrame.parse("0C", "7E8 05 42 0C 00 1A F8\r>"),
        )
        assertEquals(FreezeFrame.Reading("vehicle speed", 72.0, "km/h"), FreezeFrame.parse("0D", "42 0D 00 48"))
        assertEquals(FreezeFrame.Reading("coolant temperature", 88.0, "deg C"), FreezeFrame.parse("05", "42 05 00 80"))
    }

    @Test
    fun noSnapshotOrAnotherFrameIsNoReading() {
        assertNull(FreezeFrame.parse("0C", "NO DATA"))
        assertNull(FreezeFrame.parse("0C", null))
        assertNull("frame 01 is not the frame asked for", FreezeFrame.parse("0C", "42 0C 01 1A F8"))
        assertNull("a truncated reply", FreezeFrame.parse("0C", "42 0C 00"))
        assertNull("a negative response", FreezeFrame.parse("0C", "7F 02 12"))
    }

    @Test
    fun requestsAskForFrameZero() {
        assertEquals("020C00", FreezeFrame.request("0C"))
        assertEquals("020200", FreezeFrame.DTC_REQUEST)
    }

    @Test
    fun theDtcRequestIsParsedAsTheFreezeFrameCode() {
        val codes = ObdProtocol.parseDiagnosticTroubleCodes(FreezeFrame.DTC_REQUEST, "42 02 00 04 20", "")
        assertEquals(listOf("P0420"), codes.map { it.code })
        assertEquals("freeze-frame", codes.single().status)
    }

    @Test
    fun readingsRoundTripAndSkipJunk() {
        val readings =
            listOf(FreezeFrame.Reading("engine load", 38.0, "%"), FreezeFrame.Reading("fuel level", 64.0, "%"))
        val json = FreezeFrame.toJson("P0420", readings)
        assertEquals(readings, FreezeFrame.readingsFrom(json))
        json.getJSONArray("readings").put("junk").put(org.json.JSONObject().put("name", "x"))
        assertEquals(readings, FreezeFrame.readingsFrom(json))
        assertEquals(emptyList<FreezeFrame.Reading>(), FreezeFrame.readingsFrom(null))
    }
}
