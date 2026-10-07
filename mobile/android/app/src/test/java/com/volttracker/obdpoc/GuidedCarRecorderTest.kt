package com.volttracker.obdpoc

import com.volttracker.obdpoc.engine.GuidedCarRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What one guided-test listen keeps: counts, gaps, arrival times and body payload changes, never a sensitive frame. */
class GuidedCarRecorderTest {
    private val recorder = GuidedCarRecorder(startMs = 1_000L)

    private fun add(
        line: String,
        atMs: Long,
    ) = recorder.add(SwcanFrameDecoder.parseLine(line)!!, atMs)

    @Test
    fun countsAndGapsPerId() {
        add("10 24 80 40 C8 7B", 1_100L)
        add("10 24 80 40 C8 7B", 1_600L)
        add("10 24 80 40 C8 7C", 2_800L)
        add("0C 63 00 40 01", 2_000L)

        assertEquals("0C630040:1/1000-1000 10248040:3/100-1800/500-1200", recorder.idSummary())
        assertEquals(4, recorder.frames)
    }

    @Test
    fun theSlowBroadcastsKeepEveryArrival() {
        // Power mode (arb 0x121) and the tyres (0x1EA) are timed; the 12 V frame is not.
        add("10 24 20 40 02", 1_400L)
        add("10 24 80 40 C8 7B", 1_500L)
        add("10 24 20 40 02", 6_400L)
        add("10 3D 40 40 00 00 00 00", 7_000L)

        assertEquals("121:400,5400 1EA:6000", recorder.arrivalSummary())
    }

    @Test
    fun bodyPayloadsAreLoggedWhenTheyChange() {
        add("0C 63 00 40 01", 1_200L)
        add("0C 63 00 40 01", 1_300L)
        add("0C 63 00 40 00", 4_000L)
        // The 12 V frame is not a body frame: no change entries for it.
        add("10 24 80 40 C8 7B", 4_100L)
        add("10 24 80 40 C9 7B", 4_200L)

        val changes = recorder.changes.map { "%d %08X %s".format(it.atMs, it.id, it.data) }
        assertEquals(listOf("200 0C630040 01", "3000 0C630040 00"), changes)
    }

    @Test
    fun aChatteringFrameIsCappedAndCounted() {
        repeat(25) { i -> add("0C 63 00 40 0${i % 2}", 1_000L + i) }

        assertEquals(20, recorder.changes.size)
        assertEquals(5, recorder.droppedChanges)
    }

    @Test
    fun sensitiveAndPlainFramesAreNeverKept() {
        add("10 2A A0 97 01 02 03 04", 1_100L) // GPS (arb 0x155)
        add("10 28 C0 40 05 06", 1_200L) // passive entry (arb 0x146)
        add("7E8 03 41 0D 00", 1_300L) // 11-bit diagnostics

        assertEquals(0, recorder.frames)
        assertEquals("", recorder.idSummary())
        assertTrue(recorder.changes.isEmpty())
    }

    @Test
    fun theFuelFrameKeepsOnlyItsFuelDoorByte() {
        add("10 76 40 97 08 11 22 33 44 55", 1_100L)
        add("10 76 40 97 08 11 22 33 44 66", 1_200L)

        assertEquals(listOf("08"), recorder.changes.map { it.data })
        assertFalse(recorder.changes.any { it.data.contains("11") })
    }
}
