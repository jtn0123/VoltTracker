package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Drives [ElmConnection.transact] against in-memory streams — no Bluetooth needed. */
class ElmConnectionTest {
    @Test
    fun transactWritesCommandAndReadsUntilPrompt() {
        val input = ReplyStream("41 0C 1880\r>")
        val out = TriggerOutputStream(input)
        val connection = ElmConnection(input, out)

        val response = connection.transact("010C", 1000L) { true }

        assertEquals("41 0C 1880\r>", response)
        // the command is written with a trailing carriage return
        assertEquals("010C\r", out.toString("US-ASCII"))
    }

    @Test
    fun transactReturnsImmediatelyWhenKeepWaitingIsFalse() {
        // Synthetic clock: starts at 0 and never advances. The loop has a 10 000 ms timeout,
        // but keepWaiting==false should short-circuit on the very first iteration, so we expect
        // the clock to be sampled at most twice (deadline computation + first loop check) and
        // the call to return immediately without spinning to the timeout.
        val now = AtomicLong(0L)
        val clockReads = AtomicInteger()
        val clock =
            ElmConnection.Clock {
                clockReads.incrementAndGet()
                now.get()
            }
        val connection =
            ElmConnection(
                ByteArrayInputStream(ByteArray(0)),
                ByteArrayOutputStream(),
                clock,
            )

        val response = connection.transact("010C", 10_000L) { false }

        assertEquals("", response)
        assertEquals(
            "transact should read the clock exactly twice (deadline + first loop test)",
            2,
            clockReads.get(),
        )
    }

    @Test
    fun transactThrowsWhenStreamIsNotOpen() {
        val expected =
            assertThrows(IOException::class.java) {
                ElmConnection().transact("010C", 1000L) { true }
            }
        assertTrue(expected.message!!.contains("not open"))
    }

    /** Yields its bytes only once [release] is called — i.e. after the request. */
    private class ReplyStream(
        reply: String,
    ) : InputStream() {
        private val data: ByteArray = reply.toByteArray(StandardCharsets.US_ASCII)
        private var pos = 0
        private var released = false

        fun release() {
            released = true
        }

        override fun available(): Int = if (released) data.size - pos else 0

        override fun read(): Int {
            if (!released || pos >= data.size) {
                return -1
            }
            return data[pos++].toInt() and 0xFF
        }
    }

    /** Releases the paired [ReplyStream] once the command has been written. */
    private class TriggerOutputStream(
        private val reply: ReplyStream,
    ) : ByteArrayOutputStream() {
        override fun write(b: ByteArray) {
            super.write(b, 0, b.size)
            reply.release()
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            super.write(b, off, len)
            reply.release()
        }

        override fun write(b: Int) {
            super.write(b)
            reply.release()
        }
    }
}
