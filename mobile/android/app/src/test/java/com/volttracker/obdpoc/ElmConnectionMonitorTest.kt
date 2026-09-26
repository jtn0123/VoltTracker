package com.volttracker.obdpoc

import com.volttracker.obdpoc.engine.ElmConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

/** [ElmConnection.monitor] against in-memory streams: listen, stop with one byte, wait for the prompt. */
class ElmConnectionMonitorTest {
    /** Serves queued text; [onWrite] lets the "adapter" react to what the app sends. */
    private class ScriptedInput : InputStream() {
        private val pending = StringBuilder()

        @Synchronized
        fun feed(text: String) {
            pending.append(text)
        }

        @Synchronized
        override fun available(): Int = pending.length

        @Synchronized
        override fun read(): Int {
            if (pending.isEmpty()) return -1
            val c = pending[0].code
            pending.deleteCharAt(0)
            return c
        }

        @Synchronized
        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (pending.isEmpty()) return 0
            val n = minOf(len, pending.length)
            for (i in 0 until n) b[off + i] = pending[i].code.toByte()
            pending.delete(0, n)
            return n
        }
    }

    private class ReactiveOutput(
        private val onWrite: (String) -> Unit,
    ) : ByteArrayOutputStream() {
        override fun write(b: Int) {
            super.write(b)
            onWrite(String(byteArrayOf(b.toByte()), StandardCharsets.US_ASCII))
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            super.write(b, off, len)
            onWrite(String(b, off, len, StandardCharsets.US_ASCII))
        }
    }

    /** Advances 10 ms per read, so windows end after a bounded number of polls. */
    private val clock =
        AtomicLong(0L).let { time -> ElmConnection.Clock { time.addAndGet(10L) } }

    @Test
    fun listensThenStopsWithOneByteAndReadsUntilPrompt() {
        val input = ScriptedInput()
        val out =
            ReactiveOutput { written ->
                if (written == "STM\r") input.feed("10 24 80 40 00 00 60 D9\r")
                if (written == "\r") input.feed("STOPPED\r\r>")
            }
        val connection = ElmConnection(input, out, clock)

        val result = connection.monitor("STM", 200L, 500L) { true }

        assertEquals("STM\r\r", out.toString("US-ASCII"))
        assertTrue(result.gotPrompt)
        assertFalse(result.endedEarly)
        assertFalse(result.capped)
        assertEquals("10 24 80 40 00 00 60 D9\rSTOPPED\r\r>", result.text)
        assertEquals(1, SwcanFrameDecoder.parseMonitorOutput(result.text).size)
    }

    @Test
    fun adapterExitingOnItsOwnSkipsTheStopByte() {
        val input = ScriptedInput()
        val out = ReactiveOutput { written -> if (written == "STM\r") input.feed("?\r\r>") }
        val connection = ElmConnection(input, out, clock)

        val result = connection.monitor("STM", 200L, 500L) { true }

        assertTrue(result.endedEarly)
        assertTrue(result.gotPrompt)
        assertEquals("STM\r", out.toString("US-ASCII"))
    }

    @Test
    fun missingPromptAfterStopIsReported() {
        val input = ScriptedInput()
        val out = ReactiveOutput { }
        val connection = ElmConnection(input, out, clock)

        val result = connection.monitor("STM", 50L, 50L) { true }

        assertFalse(result.gotPrompt)
        assertEquals("", result.text)
    }

    @Test
    fun floodIsCappedButStillStopsCleanly() {
        val input = ScriptedInput()
        val out =
            ReactiveOutput { written ->
                if (written == "STM\r") input.feed("10 24 80 40 00\r".repeat(6_000))
                if (written == "\r") input.feed("STOPPED\r\r>")
            }
        val connection = ElmConnection(input, out, clock)

        // ~704 reads drain the flood at 10 ms of fake clock each; the window closes just after.
        val result = connection.monitor("STM", 7_200L, 1_000L) { true }

        assertTrue(result.capped)
        assertTrue(result.gotPrompt)
        assertTrue(connection.lastTransactTruncated)
        assertEquals(64 * 1024, result.text.length)
    }

    @Test
    fun stopsWaitingWhenSessionEnds() {
        val input = ScriptedInput()
        val out = ReactiveOutput { }
        val connection = ElmConnection(input, out, clock)

        val result = connection.monitor("STM", 100_000L, 100_000L) { false }

        assertFalse(result.gotPrompt)
        assertEquals("STM\r\r", out.toString("US-ASCII"))
    }

    @Test
    fun closedStreamThrows() {
        assertThrows(IOException::class.java) { ElmConnection(null, null, clock).monitor("STM", 1L, 1L) { true } }
    }
}
