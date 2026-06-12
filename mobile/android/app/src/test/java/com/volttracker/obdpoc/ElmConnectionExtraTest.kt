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
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives the stream-backed [ElmConnection] methods — [ElmConnection.isOpen],
 * [ElmConnection.wakeNudge], [ElmConnection.sendEscape], and the socket-open lifecycle — against
 * fake streams/sockets. No real Bluetooth socket is involved.
 */
class ElmConnectionExtraTest {
    // --- open socket lifecycle ---------------------------------------------------------------

    @Test
    fun openSocketRecordsTimingsAndMarksStreamsOpen() {
        val input = ByteArrayInputStream(ByteArray(0))
        val output = ByteArrayOutputStream()
        val socket = FakeElmSocket(inputStreamValue = input, outputStreamValue = output)
        val connection = ElmConnection(clock = SteppingClock(100L, 150L, 200L, 240L))

        connection.openSocketForTest(socket, 500L)

        assertTrue("successful socket open exposes both streams", connection.isOpen())
        assertEquals("RFCOMM connect duration is measured from the injected clock", 50L, connection.rfcommConnectMs)
        assertEquals("stream acquisition duration is measured separately", 40L, connection.getStreamsMs)
        assertEquals("connect should be called once", 1, socket.connectCalls.get())
    }

    @Test
    fun openSocketRecordsRfcommConnectFailurePhase() {
        val socket = FakeElmSocket(connectFailure = IOException("connect boom"))
        val connection = ElmConnection(clock = SteppingClock(10L, 25L))

        val expected =
            assertThrows(IOException::class.java) {
                connection.openSocketForTest(socket, 500L)
            }

        assertEquals("connect boom", expected.message)
        assertEquals("rfcomm_connect", connection.lastErrorPhase)
        assertEquals(15L, connection.rfcommConnectMs)
        assertFalse(connection.isOpen())
    }

    @Test
    fun openSocketRecordsStreamFailurePhase() {
        val socket = FakeElmSocket(inputFailure = IOException("streams boom"))
        val connection = ElmConnection(clock = SteppingClock(1L, 2L, 10L, 15L))

        val expected =
            assertThrows(IOException::class.java) {
                connection.openSocketForTest(socket, 500L)
            }

        assertEquals("streams boom", expected.message)
        assertEquals("get_streams", connection.lastErrorPhase)
        assertEquals(5L, connection.getStreamsMs)
        assertFalse(connection.isOpen())
    }

    @Test
    fun closeClosesStreamsAndUnderlyingSocket() {
        val input = CloseCountingInputStream()
        val output = CloseCountingOutputStream()
        val socket = FakeElmSocket(inputStreamValue = input, outputStreamValue = output)
        val connection = ElmConnection(clock = SteppingClock(0L, 0L, 0L, 0L))
        connection.openSocketForTest(socket, 500L)

        connection.close()

        assertEquals("input stream closed", 1, input.closeCalls.get())
        assertEquals("output stream closed", 1, output.closeCalls.get())
        assertEquals("socket closed", 1, socket.closeCalls.get())
        assertFalse("connection no longer reports open after close", connection.isOpen())
    }

    @Test
    fun closeClearsStaleTransactTruncation() {
        val connection = ElmConnection(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
        connection.lastTransactTruncated = true

        connection.close()

        assertFalse(
            "close() must clear per-session truncation state",
            connection.lastTransactTruncated,
        )
    }

    @Test
    fun openSocketClearsStaleTransactTruncation() {
        val connection = ElmConnection(clock = SteppingClock(0L, 0L, 0L, 0L))
        connection.lastTransactTruncated = true

        connection.openSocketForTest(FakeElmSocket(), 500L)

        assertFalse(
            "a fresh socket open must not inherit the previous session's truncation state",
            connection.lastTransactTruncated,
        )
    }

    @Test
    fun watchdogClosesSocketWhenConnectNeverCompletes() {
        val socket = FakeElmSocket(connectDelayMs = 35L, failIfClosedDuringConnect = true)
        val connection = ElmConnection()

        assertThrows(IOException::class.java) {
            connection.openSocketForTest(socket, 1L)
        }

        assertTrue("watchdog flag should record that it fired", connection.watchdogFired)
        assertTrue("watchdog should close the pending socket", socket.closeCalls.get() >= 1)
        assertEquals("rfcomm_connect", connection.lastErrorPhase)
    }

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
        val connection =
            ElmConnection(ByteArrayInputStream(byteArrayOf('A'.code.toByte())), out, clock = ElmConnection.Clock { 0L })

        connection.wakeNudge(200L)

        // The nudge is exactly one carriage-return byte (0x0D), no command text.
        assertArrayEquals(byteArrayOf(0x0D), out.toByteArray())
    }

    @Test
    fun wakeNudgeReportsResponseWhenInputIsAvailable() {
        val out = ByteArrayOutputStream()
        val connection =
            ElmConnection(ByteArrayInputStream(byteArrayOf('A'.code.toByte())), out, clock = ElmConnection.Clock { 0L })

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
        val connection = ElmConnection(ThrowingInputStream(), out, clock = ElmConnection.Clock { 0L })

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

    private class CloseCountingInputStream : ByteArrayInputStream(ByteArray(0)) {
        val closeCalls = AtomicInteger()

        override fun close() {
            closeCalls.incrementAndGet()
            super.close()
        }
    }

    private class CloseCountingOutputStream : ByteArrayOutputStream() {
        val closeCalls = AtomicInteger()

        override fun close() {
            closeCalls.incrementAndGet()
            super.close()
        }
    }

    private class FakeElmSocket(
        private val inputStreamValue: InputStream = ByteArrayInputStream(ByteArray(0)),
        private val outputStreamValue: OutputStream = ByteArrayOutputStream(),
        private val connectFailure: IOException? = null,
        private val inputFailure: IOException? = null,
        private val connectDelayMs: Long = 0L,
        private val failIfClosedDuringConnect: Boolean = false,
    ) : ElmConnection.ElmSocket {
        val connectCalls = AtomicInteger()
        val closeCalls = AtomicInteger()

        @Volatile private var closed = false

        @Volatile private var connected = false

        override val isConnected: Boolean
            get() = connected

        override val inputStream: InputStream
            get() {
                inputFailure?.let { throw it }
                return inputStreamValue
            }

        override val outputStream: OutputStream
            get() = outputStreamValue

        override fun connect() {
            connectCalls.incrementAndGet()
            if (connectDelayMs > 0L) {
                Thread.sleep(connectDelayMs)
            }
            connectFailure?.let { throw it }
            if (failIfClosedDuringConnect && closed) {
                throw IOException("socket closed by watchdog")
            }
            connected = true
        }

        override fun close() {
            closed = true
            connected = false
            closeCalls.incrementAndGet()
        }
    }
}
