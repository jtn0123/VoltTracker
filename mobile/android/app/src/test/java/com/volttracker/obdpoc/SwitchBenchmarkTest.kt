package com.volttracker.obdpoc

import com.volttracker.obdpoc.engine.SwcanListenRunner
import com.volttracker.obdpoc.engine.SwitchBenchmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The bus-switch timing check against a fake adapter, in fake time. */
class SwitchBenchmarkTest {
    private val clock = FakeClock()
    private val io = FakeCarIo(clock)
    private val bus = FakeBus(io)
    private val benchmark = SwitchBenchmark(io, bus, clock::now)

    private fun batching() {
        io.replies["STBC 1"] = "OK\r\r>"
        io.replies[SwitchBenchmark.BATCH_RESTORE] = "OK|OK|OK|OK|OK|OK|OK|OK|A6\r\r>"
        io.preamble = { if (it.contains('|')) listOf("A6|OK|OK|OK|OK|OK|OK|OK") else emptyList() }
    }

    @Test
    fun separateCyclesTimeEachPartOfTheSwitch() {
        // The bus answers 300 ms into each listen.
        io.onTick = { at -> if (at % 1_000L == 0L) io.broadcast(at + 250L, "10 24 80 40 C8 7B") }

        val outcome = benchmark.run("bench", batched = false, cycles = 3) { false }

        assertEquals(SwitchBenchmark.OK, outcome)
        val cycles = io.all("guided_bench")
        assertEquals(3, cycles.size)
        val first = cycles.first()
        assertEquals("separate", first["mode"])
        assertEquals("0", first["setupMs"])
        // Listens 1 s past the setup, then 60 ms to stop; 010D takes 40 ms.
        assertEquals("60", first["stopMs"])
        assertEquals("1100", first["totalMs"])
        assertEquals("40", first["hsMs"])
        assertEquals("true", first["hsOk"])
        assertEquals("", first["failure"])
        val summary = io.event("guided_bench_summary")!!
        assertEquals("3", summary["cycles"])
        assertEquals("3", summary["validCycles"])
        assertEquals("", summary["failures"])
        assertEquals("1100", summary["medianTotalMs"])
        assertEquals("3", summary["hsOk"])
        assertEquals(listOf("STI", "STDI"), io.commands.take(2))
        assertEquals(3, io.commands.count { it == SwcanListenRunner.MONITOR_COMMAND })
    }

    @Test
    fun itSendsNothingTheListenerDoesNotExceptBatchingAndOneSpeedRead() {
        batching()
        benchmark.run("separate", batched = false, cycles = 2) { false }
        benchmark.run("batched", batched = true, cycles = 2) { false }

        val allowed =
            setOf(
                "STI",
                "STDI",
                "STBC 1",
                "STBC 0",
                "010D",
                "<enter>",
                "<leave>",
                SwcanListenRunner.MONITOR_COMMAND,
                SwitchBenchmark.BATCH_SETUP,
                SwitchBenchmark.BATCH_RESTORE,
            )
        assertEquals(emptyList<String>(), io.commands.filterNot { it in allowed })
        // 010D is a normal HS read, and only ever sent once HS is back.
        io.commands.forEachIndexed { i, command ->
            if (command == "010D") {
                assertTrue(io.commands[i - 1] == "<leave>" || io.commands[i - 1] == SwitchBenchmark.BATCH_RESTORE)
            }
        }
    }

    @Test
    fun theAdapterIsIdentifiedOncePerTest() {
        benchmark.run("a", batched = false, cycles = 1) { false }
        benchmark.run("b", batched = false, cycles = 1) { false }

        assertEquals(1, io.commands.count { it == "STI" })
        benchmark.reset()
        benchmark.run("c", batched = false, cycles = 1) { false }
        assertEquals(2, io.commands.count { it == "STI" })
    }

    @Test
    fun batchedCyclesSendOneLineEachWayAndEndWithBatchingOff() {
        batching()

        val outcome = benchmark.run("bench_batched", batched = true, cycles = 2) { false }

        assertEquals(SwitchBenchmark.OK, outcome)
        assertEquals("true", io.event("guided_bench_batch_reply")!!["setupOk"])
        assertEquals(2, io.commands.count { it == SwitchBenchmark.BATCH_SETUP })
        assertEquals(2, io.commands.count { it == SwitchBenchmark.BATCH_RESTORE })
        assertEquals(listOf("STBC 0", "<leave>"), io.commands.takeLast(2))
        assertEquals("only the end restores the separate way", 1, io.commands.count { it == "<leave>" })
        assertEquals("A6|OK|OK|OK|OK|OK|OK|OK", io.event("guided_bench_batch_reply")!!["replies"])
        assertEquals(1, io.all("guided_bench_batch_reply").size)
        assertTrue(io.all("guided_bench").all { it["fellBack"] == "false" && it["mode"] == "batched" })
        assertTrue(SwitchBenchmark.BATCH_SETUP.startsWith("ATDPN|STCMM 0|"))
        assertTrue(SwitchBenchmark.BATCH_SETUP.endsWith("|ATS1|STM"))
    }

    @Test
    fun firmwareWithoutBatchingIsSkippedFromThenOn() {
        io.replies["STBC 1"] = "?\r\r>"

        assertEquals(SwitchBenchmark.UNSUPPORTED, benchmark.run("one", batched = true, cycles = 5) { false })
        val commandsAfterFirst = io.commands.size
        assertEquals(SwitchBenchmark.UNSUPPORTED, benchmark.run("two", batched = true, cycles = 5) { false })

        assertEquals(commandsAfterFirst, io.commands.size)
        assertEquals("false", io.event("guided_bench_summary")!!["supported"])
        assertFalse(io.commands.contains(SwitchBenchmark.BATCH_SETUP))
    }

    @Test
    fun aShortBatchReplyFallsBackToTheSeparateRestore() {
        batching()
        // An error part-way through ends a batch early.
        io.replies[SwitchBenchmark.BATCH_RESTORE] = "OK|OK|OK|?\r\r>"

        val outcome = benchmark.run("bench_batched", batched = true, cycles = 1) { false }

        assertEquals("true", io.event("guided_bench")!!["fellBack"])
        assertEquals("restore_fallback", io.event("guided_bench")!!["failure"])
        assertEquals(SwitchBenchmark.PARTIAL, outcome)
        assertEquals("the fallback, then the end", 2, io.commands.count { it == "<leave>" })
    }

    @Test
    fun aBatchedSetupThatNeverAcknowledgesIsNotTimedAsAListen() {
        batching()
        io.preamble = { listOf("A6|OK|?") }

        val outcome = benchmark.run("bench_batched", batched = true, cycles = 1) { false }

        val cycle = io.event("guided_bench")!!
        assertEquals("-1", cycle["firstFrameMs"])
        assertEquals("true", cycle["fellBack"])
        assertEquals("setup_failed", cycle["failure"])
        assertEquals(SwitchBenchmark.PARTIAL, outcome)
    }

    @Test
    fun aBatchWhoseLastSetupCommandFailsIsNotATrial() {
        batching()
        // As many replies as the setup has, but the last is an error: STM never ran.
        io.preamble = { listOf("A6|OK|OK|OK|OK|OK|OK|?") }

        val outcome = benchmark.run("bench_batched", batched = true, cycles = 2) { false }

        assertEquals(SwitchBenchmark.PARTIAL, outcome)
        assertTrue(io.all("guided_bench").all { it["failure"] == "setup_failed" })
        val summary = io.event("guided_bench_summary")!!
        assertEquals("0", summary["validCycles"])
        assertEquals("setup_failed:2", summary["failures"])
        assertEquals("no medians from failed trials", "-1", summary["medianTotalMs"])
        // Every batch that went wrong logs its replies.
        assertEquals(2, io.all("guided_bench_batch_reply").size)
        assertEquals("false", io.event("guided_bench_batch_reply")!!["setupOk"])
    }

    @Test
    fun aSeparateSetupThatFailsIsNotATrial() {
        bus.enterFails = "STP 61"

        val outcome = benchmark.run("bench", batched = false, cycles = 2) { false }

        assertEquals(SwitchBenchmark.PARTIAL, outcome)
        assertEquals(0, io.commands.count { it == SwcanListenRunner.MONITOR_COMMAND })
        assertEquals("setup_failed:2", io.event("guided_bench_summary")!!["failures"])
    }

    @Test
    fun aStopWithoutAPromptIsNotATrial() {
        io.stopPrompt = false

        benchmark.run("bench", batched = false, cycles = 1) { false }

        assertEquals("no_stop_prompt", io.event("guided_bench")!!["failure"])
    }

    @Test
    fun aDroppedLinkLeavesTheAdapterStateUnknown() {
        batching()
        io.dropLinkAt = 500L

        assertThrows(IOException::class.java) { benchmark.run("bench_batched", batched = true, cycles = 2) { false } }

        assertEquals("bench_batched", io.event("guided_bus_state_unknown")!!["step"])
    }

    @Test
    fun aBugMidBlockStillEndsBatchingAndRestoresHs() {
        batching()
        io.crashAt = 500L

        assertThrows(IllegalStateException::class.java) {
            benchmark.run("bench_batched", batched = true, cycles = 2) { false }
        }

        assertEquals(listOf("STBC 0", "<leave>"), io.commands.takeLast(2))
    }

    @Test
    fun aRestoreThatFailsReinitialisesAndEndsTheBlock() {
        bus.leaveResults += false

        val outcome = benchmark.run("bench", batched = false, cycles = 5) { false }

        assertEquals(SwitchBenchmark.RESTORE_FAILED, outcome)
        assertEquals(1, io.reinitCount)
        assertEquals("0", io.event("guided_bench_summary")!!["cycles"])
    }

    @Test
    fun hsThatStopsAnsweringEndsTheBlock() {
        io.replies["010D"] = "NO DATA\r\r>"

        val outcome = benchmark.run("bench", batched = false, cycles = 10) { false }

        assertEquals(SwitchBenchmark.HS_FAILED, outcome)
        assertEquals(2, io.all("guided_bench").size)
        assertTrue(io.all("guided_bench").all { it["failure"] == "hs_no_reply" })
    }

    @Test
    fun aCancelEndsTheBlockBetweenCycles() {
        var asked = 0

        benchmark.run("bench", batched = false, cycles = 10) { ++asked > 2 }

        assertEquals(2, io.all("guided_bench").size)
    }
}
