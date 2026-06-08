package com.volttracker.obdpoc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Drives the stream-backed [ElmConnection] methods — [ElmConnection.isOpen],
 * [ElmConnection.wakeNudge] and [ElmConnection.sendEscape] — against in-memory streams, mirroring
 * the fake-stream harness in `ElmConnectionTest`. No Bluetooth socket is involved.
 *
 * [ElmConnection.open] is intentionally not covered here: it creates a real `BluetoothSocket` via
 * `BluetoothDevice.createRfcommSocketToServiceRecord` and reads its streams, with no injection seam
 * for a fake socket, so it requires instrumentation rather than a JVM unit test.
 */
class ElmConnectionExtraTest {
    // --- isOpen -----------------------------------------------------------------------------

    @Test
    fun isOpenIsFalseWhenStreamsAreMissing() {
        assertFalse("a connection with no streams is not open", ElmConnection().isOpen())
        assertFalse(
            "an input-only connection is not open",
            ElmConnection(input = ByteArrayInputStream(ByteArray(0))).isOpen(),
        )
        assertFalse(
            "an output-only connection is not open",
            ElmConnection(output = ByteArrayOutputStream()).isOpen(),
        )
    }

    @Test
    fun isOpenIsTrueWhenBothStreamsAreSupplied() {
        val connection = ElmConnection(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
        assertTrue(connection.isOpen())
    }

    @Test
    fun isOpenIsFalseAfterClose() {
        val connection = ElmConnection(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
        assertTrue("open before close", connection.isOpen())
        connection.close()
        assertFalse("close() nulls both streams", connection.isOpen())
    }

    // --- wakeNudge --------------------------------------------------------------------------

    @Test
    fun wakeNudgeWritesSingleCarriageReturn() {
        val out = ByteArrayOutputStream()
        // Clock pinned at 0: the deadline (0 + tolerance) is always in the future for the first
        // iteration, so the loop runs, sees the available byte and returns immediately.
        val connection = ElmConnection(ByteArrayInputStream(byteArrayOf('A'.code.toByte())), out) { 0L }

        connection.wakeNudge(200L)

        // The nudge is exactly one carriage-return byte (0x0D), no command text.
        assertArrayEquals(byteArrayOf(0x0D), out.toByteArray())
    }

    @Test
    fun wakeNudgeReportsResponseWhenInputIsAvailable() {
        val out = ByteArrayOutputStream()
        val connection = ElmConnection(ByteArrayInputStream(byteArrayOf('A'.code.toByte())), out) { 0L }

        val result = connection.wakeNudge(200L)

        assertTrue("a byte was readable, so a response is reported", result.gotResponse)
        // Clock never advances, so the recorded first-read duration is zero.
        assertEquals(0L, result.durationMs)
        assertEquals(0L, connection.firstReadMs)
    }

    @Test
    fun wakeNudgeReportsNoResponseWhenDeadlinePassesImmediately() {
        val out = ByteArrayOutputStream()
        // Stepping clock: 0 (start) then 200 (>= deadline) so the loop body never executes and no
        // real Thread.sleep is hit, keeping the test fast and deterministic.
        val clock = SteppingClock(0L, 200L, 200L)
        val connection = ElmConnection(ByteArrayInputStream(ByteArray(0)), out, clock)

        val result = connection.wakeNudge(200L)

        assertFalse("deadline elapsed before any read", result.gotResponse)
        // Still writes the carriage return before waiting.
        assertArrayEquals(byteArrayOf(0x0D), out.toByteArray())
        // firstReadMs = nowMs() - start = 200 - 0.
        assertEquals(200L, result.durationMs)
        assertEquals(200L, connection.firstReadMs)
    }

    @Test
    fun wakeNudgeThrowsWhenStreamIsNotOpen() {
        val expected =
            assertThrows(IOException::class.java) {
                ElmConnection().wakeNudge(200L)
            }
        assertTrue(expected.message!!.contains("not open"))
    }

    @Test
    fun wakeNudgePropagatesInputFailureAndRecordsErrorPhase() {
        val out = ByteArrayOutputStream()
        val connection = ElmConnection(ThrowingInputStream(), out) { 0L }

        val expected =
            assertThrows(IOException::class.java) {
                connection.wakeNudge(200L)
            }
        assertEquals("boom", expected.message)
        assertEquals("first_read", connection.lastErrorPhase)
    }

    // --- sendEscape -------------------------------------------------------------------------

    @Test
    fun sendEscapeWritesEscapeByteAndDrainsInput() {
        val out = ByteArrayOutputStream()
        // Pre-load the echo the adapter sends back; sendEscape should drain it after writing 0x1B.
        val input = ByteArrayInputStream("STOPPED\r>".toByteArray())
        val connection = ElmConnection(input, out)

        connection.sendEscape(0L)

        // Exactly the ELM escape byte (0x1B) is written.
        assertArrayEquals(byteArrayOf(0x1B), out.toByteArray())
        // The echoed bytes were consumed by drainInput.
        assertEquals(0, input.available())
    }

    @Test
    fun sendEscapeNoOpsWhenOutputStreamIsMissing() {
        // No output stream: the method returns early without throwing and writes nothing.
        val connection = ElmConnection(input = ByteArrayInputStream(ByteArray(0)), output = null)
        connection.sendEscape(0L)
        // Nothing to assert beyond "did not throw" — exercising the early-return branch.
    }

    @Test
    fun sendEscapeNoOpsWhenInputStreamIsMissing() {
        val out = ByteArrayOutputStream()
        // Output present but input absent: returns early before writing the escape byte.
        val connection = ElmConnection(input = null, output = out)
        connection.sendEscape(0L)
        assertEquals("input==null short-circuits before the write", 0, out.size())
    }

    /** A stepping [ElmConnection.Clock] that returns each supplied value in turn. */
    private class SteppingClock(
        private vararg val values: Long,
    ) : ElmConnection.Clock {
        private var index = 0

        override fun nowMs(): Long {
            val value = values[minOf(index, values.size - 1)]
            index++
            return value
        }
    }

    /** An [InputStream] that fails on `available()` to exercise the IO-error path. */
    private class ThrowingInputStream : InputStream() {
        override fun available(): Int = throw IOException("boom")

        override fun read(): Int = throw IOException("boom")
    }
}
