package com.volttracker.obdpoc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SwcanReadingsTest {
    private fun readingsFrom(vararg lines: String): List<SwcanReading> =
        SwcanFrameDecoder.decodeAll(SwcanFrameDecoder.parseMonitorOutput(lines.joinToString("\r")))

    @Test
    fun appendsValuesAndPerGroupAges() {
        val readings = SwcanReadings()
        readings.record(readingsFrom("10 24 80 40 00 00 60 D9 00 FC 00 00"), 1_000L)
        readings.record(readingsFrom("10 3D 40 40 00 00 41 40 42 41 00 00"), 4_000L)
        readings.record(readingsFrom("0C 41 40 40 00 05 00 05", "10 73 40 99 20"), 4_000L)
        val sample = JSONObject()
        readings.appendTo(sample, 10_000L)
        assertEquals(12.6, sample.getDouble("aux12vVoltage"), 1e-9)
        assertEquals(85.0, sample.getDouble("aux12vSocPct"), 1e-9)
        assertEquals(-2.0, sample.getDouble("aux12vCurrentA"), 1e-9)
        assertEquals(9_000L, sample.getLong("aux12vStaleMs"))
        assertEquals(264.0, sample.getDouble("tirePressureFrKpa"), 1e-9)
        assertEquals(6_000L, sample.getLong("tirePressureStaleMs"))
        assertEquals("locked", sample.getString("doorLockState"))
        assertEquals("fob", sample.getString("doorLockSource"))
        assertEquals("on", sample.getString("acState"))
        assertEquals(6_000L, sample.getLong("climateStaleMs"))
        assertFalse(sample.has("windowStaleMs"))
        assertFalse(sample.has("chargeCurrentLimitA"))
        assertEquals(10, readings.size())
    }

    @Test
    fun dropsValuesOlderThanMaxAge() {
        val readings = SwcanReadings(maxAgeMs = 5_000L)
        readings.record(readingsFrom("10 63 40 CB 00 48"), 0L)
        readings.record(readingsFrom("10 2E C0 CB 00 1B 80 00"), 4_000L)
        val sample = JSONObject()
        readings.appendTo(sample, 6_000L)
        assertFalse(sample.has("peCoolantTempC"))
        assertFalse(sample.has("peCoolantStaleMs"))
        assertEquals(55.0, sample.getDouble("clusterEvRangeKm"), 1e-9)
        assertEquals(2_000L, sample.getLong("rangeStaleMs"))
    }

    @Test
    fun emptyAndClearedStateAddsNothing() {
        val readings = SwcanReadings()
        val sample = JSONObject()
        readings.appendTo(sample, 0L)
        assertEquals(0, sample.length())
        readings.record(readingsFrom("10 63 40 CB 00 48"), 0L)
        readings.clear()
        readings.appendTo(sample, 0L)
        assertEquals(0, sample.length())
    }

    @Test
    fun everyFieldHasALiveSampleKey() {
        // One synthetic frame per decoder, so every field is populated at least once.
        val readings = SwcanReadings()
        readings.record(
            readingsFrom(
                "10 24 80 40 00 00 60 D9 00 FC 00 00",
                "10 3D 40 40 00 00 41 40 42 41 00 00",
                "0C 41 40 40 00 05 00 05",
                "0C 63 00 40 01",
                "0C 2F 60 40 00",
                "0C 2F 80 40 00",
                "0C 2F A0 40 00",
                "10 72 80 40 00",
                "0C 6A A0 40 00",
                "10 26 00 40 00 00 01",
                "10 64 A0 40 06 06",
                "10 44 00 99 00 00 00 00 00 7A",
                "10 81 40 99 00 64",
                "10 73 40 99 20",
                "10 27 00 CB 00 60 D1 94",
                "10 6D 40 99 00 00 55",
                "10 62 40 99 00 1E 00",
                "10 63 40 CB 00 48",
                "10 86 C0 CB 00 00 00 06 20 00",
                "10 2E C0 CB 00 1B 80 00",
                "10 44 80 CB 00 00 75 C0",
                "10 28 20 CB 00 00 00 53 00 00 00 00",
                "10 44 A0 CB 06 80 00 20 00 00 00 00",
                "10 44 60 CB 00 00 00 00 00 00 00 28",
                "10 39 00 40 02",
            ),
            0L,
        )
        assertEquals(SwcanField.entries.size, readings.size())
        val sample = JSONObject()
        readings.appendTo(sample, 0L)
        val staleKeys =
            sample
                .keys()
                .asSequence()
                .filter { it.endsWith("StaleMs") }
                .toList()
        assertEquals(SwcanGroup.entries.size, staleKeys.size)
        assertEquals(SwcanField.entries.size + SwcanGroup.entries.size, sample.length())
        assertTrue(sample.has("windowFrPct"))
        assertEquals("on", sample.getString("remoteStartState"))
    }
}
