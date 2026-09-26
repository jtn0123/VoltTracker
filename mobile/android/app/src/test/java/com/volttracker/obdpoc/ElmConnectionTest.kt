package com.volttracker.obdpoc

import com.volttracker.obdpoc.engine.ElmConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Drives [ElmConnection.transact] against in-memory streams — no Bluetooth needed. */
class ElmConnectionTest {
    @Test
    fun transactWritesCommandAndReadsUntilPrompt() {
        val input = ReplyStream("41 0C 1880\r>")
        val out = TriggerOutputStream(input)
        val connection = ElmConnection(input, out)

        val response = connection.transact("010C", 1000L) { true }

        assertEquals("41 0C 1880\r>", response)
        assertEquals(false, connection.lastTransactTruncated)
        // the command is written with a trailing carriage return
        assertEquals("010C\r", out.toString("US-ASCII"))
    }

    @Test
    fun transactMarksPartialDeadlineResponseAsTruncated() {
        val input = ReplyStream("41 0C 1880\r")
        val out = TriggerOutputStream(input)
        val reads = AtomicInteger()
        val clock =
            ElmConnection.Clock {
                if (reads.getAndIncrement() < 2) {
                    0L
                } else {
                    100L
                }
            }
        val connection = ElmConnection(input, out, clock)

        val response = connection.transact("010C", 50L) { true }

        assertEquals("41 0C 1880\r", response)
        assertEquals(true, connection.lastTransactTruncated)
    }

    @Test
    fun transactCapsAnUnterminatedAdapterResponse() {
        val input = ReplyStream("A".repeat(80_000))
        val out = TriggerOutputStream(input)
        val connection = ElmConnection(input, out)

        val response = connection.transact("010C", 5_000L) { true }

        assertEquals(64 * 1024, response.length)
        assertTrue("a response stopped by the safety ceiling is truncated", connection.lastTransactTruncated)
    }

    @Test
    fun promptlessResponseExitsOnQuietPeriodNotTheFullTimeout() {
        // L5: when the ELM327 v1.4b drops the '>' prompt, transact must give up shortly after the
        // response goes quiet rather than spinning the full command timeout, so the caller's
        // prompt-recovery can start sooner. The reply below carries no '>' prompt.
        val input = ReplyStream("41 0C 1880\r")
        val out = TriggerOutputStream(input)
        // Monotonic clock: every read advances 50 ms, so the loop always terminates (at the 5000 ms
        // deadline if nothing else) — but the 250 ms quiet-period exit should fire long before that.
        val now = AtomicLong(0L)
        val clock = ElmConnection.Clock { now.getAndAdd(50L) }
        val connection = ElmConnection(input, out, clock)

        val response = connection.transact("010C", 5000L) { true }

        assertEquals("41 0C 1880\r", response)
        assertFalse(
            "a promptless response must exit on the quiet period, not be marked deadline-truncated",
            connection.lastTransactTruncated,
        )
        assertTrue(
            "transact must give up well before the 5000 ms timeout once the adapter goes quiet " +
                "(exited at ${now.get()} ms)",
            now.get() < 1500L,
        )
    }

    @Test
    fun echoedCommandDoesNotStartTheQuietPeriodBeforeASlowEcuAnswers() {
        // Real-car capture: with echo on, "010D" arrives at once but the ECU answers ~400 ms later.
        // The quiet-period exit used to fire on the echo alone and return just "010D".
        val now = AtomicLong(0L)
        val input = StagedStream(now, listOf(0L to "010D\r", 400L to "41 0D 28\r\r>"))
        val connection = ElmConnection(input, TriggerOutputStream(input.trigger), { now.getAndAdd(25L) })

        val response = connection.transact("010D", 1500L) { true }

        assertEquals("010D\r41 0D 28\r\r>", response)
        assertFalse(connection.lastTransactTruncated)
    }

    @Test
    fun searchingStatusDoesNotStartTheQuietPeriod() {
        val now = AtomicLong(0L)
        val input = StagedStream(now, listOf(0L to "SEARCHING...\r", 600L to "41 00 BE 1F B8 10\r\r>"))
        val connection = ElmConnection(input, TriggerOutputStream(input.trigger), { now.getAndAdd(25L) })

        val response = connection.transact("0100", 3000L) { true }

        assertTrue("the protocol-search reply must be read in full", response.endsWith(">"))
    }

    @Test
    fun replyBeyondEchoIgnoresEchoAndStatusText() {
        assertFalse(ElmConnection.hasReplyBeyondEcho("010D\r", "010D"))
        assertFalse(ElmConnection.hasReplyBeyondEcho("  010d \r\n", "010D"))
        assertFalse(ElmConnection.hasReplyBeyondEcho("0100\rSEARCHING...\r", "0100"))
        assertFalse(ElmConnection.hasReplyBeyondEcho("", "010D"))
        assertTrue(ElmConnection.hasReplyBeyondEcho("010D\r41 0D 28", "010D"))
        assertTrue(ElmConnection.hasReplyBeyondEcho("41 0D 28", "010D"))
        assertTrue(ElmConnection.hasReplyBeyondEcho("NO DATA", "010D"))
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
    fun interruptStopsAWaitingTransactionPromptly() {
        val commandWritten = CountDownLatch(1)
        val keepWaiting = AtomicBoolean(true)
        val interruptedOnReturn = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val output =
            object : ByteArrayOutputStream() {
                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    super.write(b, off, len)
                    commandWritten.countDown()
                }
            }
        val monotonicClock = ElmConnection.Clock { System.nanoTime() / 1_000_000L }
        val connection = ElmConnection(ByteArrayInputStream(ByteArray(0)), output, monotonicClock)
        val worker =
            Thread {
                try {
                    connection.transact("010C", 10_000L, keepWaiting::get)
                    interruptedOnReturn.set(Thread.currentThread().isInterrupted)
                } catch (ex: Throwable) {
                    failure.set(ex)
                }
            }
        worker.isDaemon = true
        worker.start()
        assertTrue("transaction should write its command before waiting", commandWritten.await(1, TimeUnit.SECONDS))

        worker.interrupt()
        worker.join(500L)
        val stoppedOnInterrupt = !worker.isAlive

        // Always release a broken implementation so a failed assertion cannot leave a hot loop
        // running for the remainder of the test process.
        keepWaiting.set(false)
        worker.join(1_000L)

        failure.get()?.let { throw AssertionError("waiting transaction threw", it) }
        assertTrue("interrupt must stop a waiting transaction promptly", stoppedOnInterrupt)
        assertTrue("the interrupted status must be preserved for the caller", interruptedOnReturn.get())
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

        val isReleased: Boolean get() = released

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

    /**
     * Delivers each (atMs, text) part once the command is written and the shared clock reaches
     * atMs, so a test can model an echo that arrives at once and a reply that arrives later.
     */
    private class StagedStream(
        private val now: AtomicLong,
        parts: List<Pair<Long, String>>,
    ) : InputStream() {
        val trigger = ReplyStream("")
        private val staged = parts.map { (atMs, text) -> atMs to text.toByteArray(StandardCharsets.US_ASCII) }
        private var partIndex = 0
        private var pos = 0
        private var writtenAtMs = -1L

        private fun current(): ByteArray? {
            if (trigger.available() == 0 && writtenAtMs < 0 && !triggered()) return null
            if (writtenAtMs < 0) writtenAtMs = now.get()
            while (partIndex < staged.size && pos >= staged[partIndex].second.size) {
                partIndex++
                pos = 0
            }
            val part = staged.getOrNull(partIndex) ?: return null
            return if (now.get() - writtenAtMs >= part.first) part.second else null
        }

        private fun triggered(): Boolean = trigger.isReleased

        override fun available(): Int = current()?.let { it.size - pos } ?: 0

        override fun read(): Int {
            val part = current() ?: return -1
            return part[pos++].toInt() and 0xFF
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
