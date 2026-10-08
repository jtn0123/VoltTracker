package com.volttracker.obdpoc

import com.volttracker.obdpoc.engine.GuidedCarRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What one guided-test listen keeps, a chunk at a time: counts, gaps, arrival times and allowlisted payload changes. */
class GuidedCarRecorderTest {
    private val recorder = GuidedCarRecorder(startMs = 1_000L)

    private fun add(
        line: String,
        atMs: Long,
        drained: Boolean = false,
    ) = recorder.add(SwcanFrameDecoder.parseLine(line)!!, atMs, drained)

    private fun flush(atMs: Long): Map<String, String> = recorder.flush(atMs).fields.toMap()

    @Test
    fun countsAndGapsPerId() {
        add("10 24 80 40 C8 7B", 1_100L)
        add("10 24 80 40 C8 7B", 1_600L)
        add("10 24 80 40 C8 7C", 2_800L)
        add("0C 63 00 40 01", 2_000L)

        val chunk = flush(3_000L)

        assertEquals("0C630040:1/1000-1000 10248040:3/100-1800/500-1200", chunk["ids"])
        assertEquals("4", chunk["frames"])
        assertEquals("0", chunk["fromMs"])
        assertEquals("2000", chunk["toMs"])
        assertEquals(4, recorder.frames)
    }

    @Test
    fun theSlowBroadcastsKeepEveryArrival() {
        // Power mode (arb 0x121) and the tyres (0x1EA) are timed; the 12 V frame is not.
        add("10 24 20 40 02", 1_400L)
        add("10 24 80 40 C8 7B", 1_500L)
        add("10 24 20 40 02", 6_400L)
        add("10 3D 40 40 00 00 00 00", 7_000L)

        assertEquals("121:400,5400 1EA:6000", flush(8_000L)["arrivals"])
    }

    @Test
    fun allowlistedPayloadsAreLoggedWhenTheyChange() {
        add("0C 63 00 40 01", 1_200L)
        add("0C 63 00 40 01", 1_300L)
        add("0C 63 00 40 00", 4_000L)
        // The 12 V frame is allowlisted but not a body frame: no change entries for it.
        add("10 24 80 40 C8 7B", 4_100L)
        add("10 24 80 40 C9 7B", 4_200L)

        assertEquals("200 0C630040 01 | 3000 0C630040 00", flush(5_000L)["changes"])
    }

    @Test
    fun eachChunkStartsAfreshButAPayloadOnlyChangesOnce() {
        add("0C 63 00 40 01", 1_200L)
        flush(2_000L)
        add("0C 63 00 40 01", 2_500L)
        add("10 24 20 40 02", 2_600L)

        val second = flush(3_000L)

        assertEquals("1000", second["fromMs"])
        assertEquals("2000", second["toMs"])
        assertEquals("2", second["frames"])
        assertEquals("2", second["chunk"])
        assertTrue(second.getValue("ids").contains("0C630040:1/1500-1500"))
        assertEquals("the door payload is the same as last chunk's", "1600 10242040 02", second["changes"])
        assertEquals(2, recorder.chunks)
        assertEquals(3, recorder.frames)
    }

    @Test
    fun framesFromTheAdaptersQueueAreMarked() {
        add("10 24 20 40 02", 1_400L)
        add("10 24 20 40 00", 6_000L, drained = true)

        val chunk = flush(6_000L)

        assertEquals("121:400,5000*", chunk["arrivals"])
        assertEquals("400 10242040 02 | 5000* 10242040 00", chunk["changes"])
        assertEquals("1", chunk["drainedFrames"])
        assertEquals(1, recorder.drained)
    }

    @Test
    fun aChatteringFrameIsCappedAndCounted() {
        repeat(45) { i -> add("0C 63 00 40 0${i % 2}", 1_000L + i) }

        val chunk = recorder.flush(2_000L)
        val fields = chunk.fields.toMap()

        assertEquals(40, fields.getValue("changes").split(" | ").size)
        assertEquals("5", fields["droppedChanges"])
        assertTrue(chunk.truncated)
        assertTrue(recorder.lossy)
    }

    @Test
    fun theFuelRateInTheFastLampFrameIsNotAChange() {
        // arb 0x132, as on 10-07: only byte 7 (the instantaneous fuel rate) moves, about every frame.
        // A door closed before it and opening in the middle of it: both are their own changes.
        add("0C 63 00 40 00", 1_000L)
        repeat(60) { i ->
            add("10 26 40 40 04 0E 00 00 00 00 00 %02X".format(i), 1_000L + i * 50L)
            if (i == 20) add("0C 63 00 40 01", 2_010L)
        }
        // An ABS lamp (byte 0 bit 0) coming on is still a change.
        add("10 26 40 40 05 0E 00 00 00 00 00 3C", 4_100L)
        // The same frame from another sender is followed on its own.
        add("10 26 40 99 04 0E 00 00 00 00 00 00", 4_200L)

        val chunk = recorder.flush(5_000L)
        val fields = chunk.fields.toMap()

        assertEquals(
            "0 0C630040 00 | 0 10264040 04 0E 00 00 00 00 | 1010 0C630040 01 | " +
                "3100 10264040 05 0E 00 00 00 00 | " +
                "3200 10264099 04 0E 00 00 00 00",
            fields["changes"],
        )
        assertEquals("0", fields["droppedChanges"])
        assertTrue("every arrival still counts", fields.getValue("ids").contains("10264040:61/"))
        assertFalse(chunk.truncated)
    }

    @Test
    fun theFastLampFrameStillOverflowsWhenItsLampsChatter() {
        repeat(45) { i -> add("10 26 40 40 0${i % 2} 0E 00 00 00 00 00 00", 1_000L + i) }

        val chunk = recorder.flush(2_000L)

        assertEquals(
            40,
            chunk.fields
                .toMap()
                .getValue("changes")
                .split(" | ")
                .size,
        )
        assertEquals("5", chunk.fields.toMap()["droppedChanges"])
        assertTrue(chunk.truncated)
    }

    @Test
    fun aFollowedPrefixCarriesAcrossChunks() {
        add("10 26 40 40 04 0E 00 00 00 00 00 01", 1_100L)
        flush(2_000L)
        add("10 26 40 40 04 0E 00 00 00 00 00 02", 2_100L)

        assertEquals("only the fuel rate moved since the last chunk", "", flush(3_000L)["changes"])
    }

    @Test
    fun arrivalsPastTheirCapAreCounted() {
        repeat(305) { i -> add("10 24 20 40 02", 1_000L + i * 10L) }

        val chunk = recorder.flush(5_000L)

        assertEquals("5", chunk.fields.toMap()["droppedArrivals"])
        assertTrue(chunk.truncated)
    }

    @Test
    fun aFieldTooLongIsCutAndNamed() {
        // Every non-sensitive parameter id from 0x200 to 0x7FF, once each: an id list too long to log whole.
        (0x200..0x7FF).filterNot { it in SwcanPrivacy.SENSITIVE_PIDS }.forEach { pid ->
            val id = 0x10000040 or (pid shl 13)
            add(
                "%02X %02X %02X %02X 00".format(id ushr 24, (id ushr 16) and 0xFF, (id ushr 8) and 0xFF, id and 0xFF),
                1_000L,
            )
        }

        val chunk = recorder.flush(2_000L)
        val fields = chunk.fields.toMap()

        assertEquals(GuidedCarRecorder.MAX_FIELD_CHARS, fields.getValue("ids").length)
        assertEquals("ids", fields["truncated"])
        assertTrue(chunk.truncated)
    }

    @Test
    fun aWholeChunkIsNotTruncated() {
        add("0C 63 00 40 01", 1_200L)

        val chunk = recorder.flush(2_000L)

        assertFalse(chunk.truncated)
        assertEquals("", chunk.fields.toMap()["truncated"])
        assertFalse(recorder.lossy)
    }

    @Test
    fun sensitiveAndPlainFramesAreNeverKept() {
        add("10 2A A0 97 01 02 03 04", 1_100L) // GPS (arb 0x155)
        add("10 28 C0 40 05 06", 1_200L) // passive entry (arb 0x146)
        add("10 90 C0 40 07 08", 1_250L) // OnStar Wi-Fi passphrase (arb 0x486)
        add("7E8 03 41 0D 00", 1_300L) // 11-bit diagnostics

        val chunk = flush(2_000L)

        assertEquals(0, recorder.frames)
        assertEquals("", chunk["ids"])
        assertEquals("", chunk["changes"])
    }

    @Test
    fun theFuelFrameKeepsOnlyItsFuelDoorByte() {
        add("10 76 40 97 08 11 22 33 44 55", 1_100L)
        add("10 76 40 97 08 11 22 33 44 66", 1_200L)

        val changes = flush(2_000L).getValue("changes")

        assertEquals("100 10764097 08", changes)
        assertFalse(changes.contains("11"))
    }

    @Test
    fun everyLoggedChangeIsAllowlisted() {
        assertTrue(SwcanPrivacy.PAYLOAD_PIDS.containsAll(GuidedCarRecorder.CHANGE_PIDS))
        assertTrue(SwcanPrivacy.PAYLOAD_PIDS.containsAll(GuidedCarRecorder.TIMED_PIDS))
        assertTrue(GuidedCarRecorder.CHANGE_PIDS.containsAll(GuidedCarRecorder.CHANGE_BYTES.keys))
    }
}
