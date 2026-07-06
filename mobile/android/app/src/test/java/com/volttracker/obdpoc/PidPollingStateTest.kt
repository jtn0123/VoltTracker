package com.volttracker.obdpoc

import com.volttracker.obdpoc.PidSchedule.PidSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * Behavior tests for [PidPollingState]: carry-forward age capping and generalized Mode-01 same-cycle
 * batching beyond the hot lane.
 *
 * The state object is driven through a [CapturingEngine] subclass of [ObdPollingEngine] that scripts
 * adapter responses and records every command issued, so a test can assert exactly which round-trips
 * the schedule produced. A [ObdService] is brought up via [Robolectric.setupService] so
 * `service.recorder` (used for the batch-disabled event) and `service.ioLock` are live.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PidPollingStateTest {
    private lateinit var service: ObdService
    private lateinit var engine: CapturingEngine
    private lateinit var state: PidPollingState

    @Before
    fun setUp() {
        service = Robolectric.setupService(ObdService::class.java)
        service.activeName = "Test Adapter"
        service.sessionStartedAtMs = System.currentTimeMillis()
        service.running.set(true)
        engine = CapturingEngine(service)
        state = PidPollingState(service, engine)
    }

    @After
    fun tearDown() {
        service.running.set(false)
        try {
            service.onDestroy()
        } catch (ignored: RuntimeException) {
            // Foreground service was never started here; safe to ignore.
        }
    }

    @Test
    fun initialCycleUsesHotFirstSampleProfileOnly() {
        val dueCommands = state.dueForCurrentCycle().map { it.command }

        assertEquals(PidSchedule.FIRST_SAMPLE_COMMANDS, dueCommands)
        assertTrue("first sample must include speed", dueCommands.contains("010D"))
        assertTrue("first sample must include HV current", dueCommands.contains("222414"))
        assertFalse("first sample must not spend time on adapter voltage", dueCommands.contains("ATRV"))
        assertFalse("first sample must not spend time on deep odometer", dueCommands.contains("01A6"))
    }

    // ---- B2: carry-forward age cap --------------------------------------------------

    @Test
    fun carryForwardValueIsServedWhileFresh() {
        var nowMs = 1_000L
        state.setClockForTesting { nowMs }
        engine.responses["010D"] = "41 0D 28\r>"

        // Poll speed once on cycle 0.
        state.runScheduledPolls(specs("010D"), StringBuilder())

        // Well within the ceiling: value is still served.
        nowMs += PidPollingState.CARRY_FORWARD_MAX_AGE_MS - 1
        assertNotNull("a fresh carry-forward value must still be served", state.lastRaw("010D"))
    }

    @Test
    fun staleCarryForwardValueIsDroppedPastTheCeiling() {
        var nowMs = 1_000L
        state.setClockForTesting { nowMs }
        engine.responses["010D"] = "41 0D 28\r>"

        state.runScheduledPolls(specs("010D"), StringBuilder())
        assertNotNull("value should be present immediately after polling", state.lastRaw("010D"))

        // Advance the clock past the carry-forward ceiling: the stalled value must be dropped so
        // the dashboard renders "--" rather than a frozen number.
        nowMs += PidPollingState.CARRY_FORWARD_MAX_AGE_MS + 1
        assertNull(
            "a stale carry-forward value must be dropped past the cap",
            state.lastRaw("010D"),
        )
        // Eagerly cleared, so a subsequent read is still null even without re-advancing.
        assertNull("the dropped value must stay dropped", state.lastRaw("010D"))
    }

    @Test
    fun slowLaneValueIsServedAcrossItsScheduledCadence() {
        var nowMs = 1_000L
        state.setClockForTesting { nowMs }
        engine.responses["221154"] = "62 11 54 60\r>"

        state.runScheduledPolls(specs("221154"), StringBuilder())
        assertNotNull("value should be present immediately after polling", state.lastRaw("221154"))

        nowMs += PidPollingState.CARRY_FORWARD_MAX_AGE_MS + 1
        assertNotNull(
            "slow scheduled values must not blink out before their next normal poll",
            state.lastRaw("221154"),
        )

        nowMs = 1_000L + PidPollingState.carryForwardMaxAgeMsFor("221154") + 1
        assertNull(
            "slow values still expire after their command-specific cap",
            state.lastRaw("221154"),
        )
    }

    @Test
    fun carryForwardBudgetIncludesIdleCycleIoHeadroom() {
        // A long-period 7E4 PID (here 2243AF precise SOC, 24-cycle lane) is re-read periodCycles
        // cycles later; each idle cycle costs the post-cycle IDLE_POLL_INTERVAL_MS sleep PLUS that
        // cycle's I/O. Budgeting only the bare sleep aged the value out a beat before its next read
        // (cell-balance / SOC panels blanking at idle), so the per-cycle budget must now leave I/O
        // headroom beyond periodCycles * IDLE_POLL_INTERVAL_MS.
        val periodCycles = 24
        val budget = PidPollingState.carryForwardMaxAgeMsFor("2243AF")
        assertTrue(
            "carry-forward must exceed the bare idle-sleep budget, got $budget",
            budget > periodCycles * ObdPollingEngine.IDLE_POLL_INTERVAL_MS,
        )
    }

    @Test
    fun resetClearsCarryForwardTracking() {
        var nowMs = 1_000L
        state.setClockForTesting { nowMs }
        engine.responses["010D"] = "41 0D 28\r>"
        state.runScheduledPolls(specs("010D"), StringBuilder())

        state.reset()
        // After reset there is no tracked value and no set-time, so lastRaw is null regardless of
        // how much time has elapsed.
        nowMs += PidPollingState.CARRY_FORWARD_MAX_AGE_MS * 10
        assertNull(state.lastRaw("010D"))
    }

    @Test
    fun staleMsForReportsLastSuccessfulPollAge() {
        var nowMs = 1_000L
        state.setClockForTesting { nowMs }
        engine.responses["222429"] = "62 24 29 58 06\r>"

        state.runScheduledPolls(specs("222429"), StringBuilder())

        nowMs += 250L
        assertEquals(250L, state.staleMsFor("222429", nowMs))
        assertNull(
            "unknown commands should not report stale age",
            state.staleMsFor("222414", nowMs),
        )
    }

    // ---- Generalized Mode-01 same-cycle batching ------------------------------------

    @Test
    fun multiTierSameCycleMode01PidsAreBatchedIntoOneRequest() {
        state.setMode01BatchSupported(true)
        // Load (0104, warm lane) and SOC (015B, slow lane) fall due together on some broadcast
        // cycles. They are NOT in the hot-lane batch set, so the generalized path must group them
        // into a single 01 04 5B request rather than two separate per-PID reads.
        engine.responses["01045B"] = "41 04 80 41 5B 80\r>"

        val due = specs("0104", "015B")
        state.runScheduledPolls(due, StringBuilder())

        assertTrue(
            "load + SOC must be requested as one batched command, got ${engine.commandLog}",
            engine.commandLog.contains("01045B"),
        )
        assertFalse(
            "a batched extra cycle must not also send load per-PID",
            engine.commandLog.contains("0104"),
        )
        assertFalse(
            "a batched extra cycle must not also send SOC per-PID",
            engine.commandLog.contains("015B"),
        )
        // Both values are carried forward from the single batched response.
        assertNotNull(state.lastRaw("0104"))
        assertNotNull(state.lastRaw("015B"))
    }

    @Test
    fun incompleteExtraBatchFallsBackToPerPidWithoutDisablingTier1() {
        state.setMode01BatchSupported(true)
        // The batched reply is missing the SOC (5B) frame, so the group batch must fail and the
        // caller must poll both PIDs per-PID. The adapter capability flag must stay enabled — a
        // short reply here is a per-cycle fallback, not a permanent verdict.
        engine.responses["01045B"] = "41 04 80\r>"
        engine.responses["0104"] = "41 04 80\r>"
        engine.responses["015B"] = "41 5B 80\r>"

        val due = specs("0104", "015B")
        state.runScheduledPolls(due, StringBuilder())

        assertTrue("must attempt the batched request", engine.commandLog.contains("01045B"))
        assertTrue(
            "incomplete batch must fall back to per-PID load",
            engine.commandLog.contains("0104"),
        )
        assertTrue(
            "incomplete batch must fall back to per-PID SOC",
            engine.commandLog.contains("015B"),
        )
        // Hot-lane batching support must remain enabled.
        engine.responses["010D0C49"] = "41 0D 28 41 0C 0F A0 41 49 7F\r>"
        state.runScheduledPolls(specs("010D", "010C", "0149"), StringBuilder())
        assertTrue(
            "hot-lane batching must still engage after an extra-batch fallback",
            engine.commandLog.contains("010D0C49"),
        )
    }

    @Test
    fun aSingleHotLaneBatchMissKeepsBatchingEnabled() {
        // L9b: one incomplete hot-lane batch frame is a transient BT hiccup, not a verdict that the
        // adapter can't batch. Batching must survive it and re-engage on the next complete reply.
        state.setMode01BatchSupported(true)
        val hotLane = specs("010D", "010C", "0149")
        engine.responses["010D"] = "41 0D 28\r>"
        engine.responses["010C"] = "41 0C 0F A0\r>"
        engine.responses["0149"] = "41 49 7F\r>"

        // Cycle 1: batched reply is missing the 0C and 49 frames -> a transient miss.
        engine.responses["010D0C49"] = "41 0D 28\r>"
        state.runScheduledPolls(hotLane, StringBuilder())
        assertTrue("must attempt the hot-lane batch", engine.commandLog.contains("010D0C49"))
        assertTrue("a transient miss falls back to per-PID this cycle", engine.commandLog.contains("010D"))

        // Cycle 2: the adapter answers fully -> batching must still engage (not disabled after one miss).
        engine.commandLog.clear()
        engine.responses["010D0C49"] = "41 0D 28 41 0C 0F A0 41 49 7F\r>"
        state.runScheduledPolls(hotLane, StringBuilder())
        assertTrue(
            "one transient miss must NOT disable hot-lane batching",
            engine.commandLog.contains("010D0C49"),
        )
        assertFalse("a batched cycle must not also read speed per-PID", engine.commandLog.contains("010D"))
    }

    @Test
    fun twoConsecutiveHotLaneBatchMissesDisableBatching() {
        // L9b: a genuinely non-batching adapter still gives up quickly — two misses in a row retire
        // batching for the session.
        state.setMode01BatchSupported(true)
        val hotLane = specs("010D", "010C", "0149")
        engine.responses["010D"] = "41 0D 28\r>"
        engine.responses["010C"] = "41 0C 0F A0\r>"
        engine.responses["0149"] = "41 49 7F\r>"
        engine.responses["010D0C49"] = "41 0D 28\r>" // always incomplete

        repeat(PidPollingState.MAX_CONSECUTIVE_BATCH_MISSES) {
            state.runScheduledPolls(hotLane, StringBuilder())
        }
        engine.commandLog.clear()
        state.runScheduledPolls(hotLane, StringBuilder())

        assertFalse(
            "after the miss threshold, batching is disabled and not re-attempted",
            engine.commandLog.contains("010D0C49"),
        )
        assertTrue("disabled batching falls back to per-PID reads", engine.commandLog.contains("010D"))
    }

    @Test
    fun reEnablingBatchAfterReconnectClearsStaleMissStreak() {
        // The mode-01 batch probe re-runs on every (re)connect via setMode01BatchSupported(true), but
        // reset() (which zeroes the miss streak) only fires on a fresh session — not on an in-session
        // reconnect. So a stale miss from before the drop must be cleared here, or the first
        // post-reconnect miss would disable batching on a single strike.
        state.setMode01BatchSupported(true)
        val hotLane = specs("010D", "010C", "0149")
        engine.responses["010D"] = "41 0D 28\r>"
        engine.responses["010C"] = "41 0C 0F A0\r>"
        engine.responses["0149"] = "41 49 7F\r>"
        engine.responses["010D0C49"] = "41 0D 28\r>" // incomplete -> one miss

        // Pre-reconnect: one incomplete batch leaves a stale streak of 1 (below the disable threshold).
        state.runScheduledPolls(hotLane, StringBuilder())

        // A reconnect re-probes batch capability; this must clear the stale streak.
        state.setMode01BatchSupported(true)

        // One more miss must NOT disable batching (it would if the stale count of 1 had survived).
        state.runScheduledPolls(hotLane, StringBuilder())

        // A complete reply now must still engage batching, proving it was never disabled.
        engine.commandLog.clear()
        engine.responses["010D0C49"] = "41 0D 28 41 0C 0F A0 41 49 7F\r>"
        state.runScheduledPolls(hotLane, StringBuilder())
        assertTrue(
            "batching must survive a single post-reconnect miss, got ${engine.commandLog}",
            engine.commandLog.contains("010D0C49"),
        )
        assertFalse("a batched cycle must not also read speed per-PID", engine.commandLog.contains("010D"))
    }

    @Test
    fun aSingleExtraMode01PidIsNotBatched() {
        state.setMode01BatchSupported(true)
        engine.responses["0105"] = "41 05 7B\r>"
        // Only coolant is due (no second batchable PID), so there is nothing to batch — it is
        // polled per-PID and no multi-PID request is built.
        state.runScheduledPolls(specs("0105"), StringBuilder())
        assertTrue(engine.commandLog.contains("0105"))
        for (cmd in engine.commandLog) {
            assertFalse("a single PID must not be sent as a batch: $cmd", cmd.length > 4)
        }
    }

    @Test
    fun hotLaneBatchingUsesTheNarrowFastSet() {
        state.setMode01BatchSupported(true)
        engine.responses["010D0C49"] = "41 0D 28 41 0C 0F A0 41 49 7F\r>"
        state.runScheduledPolls(specs("010D", "010C", "0149"), StringBuilder())
        assertTrue(
            "hot-lane Mode-01 PIDs must batch into one command",
            engine.commandLog.contains("010D0C49"),
        )
        assertFalse("must not also poll speed per-PID", engine.commandLog.contains("010D"))
    }

    @Test
    fun everyNonBroadcastHeaderHasASelectableAtCommand() {
        for (header in PidSchedule.Header.entries) {
            if (header == PidSchedule.Header.BROADCAST) {
                assertNull(header.atCommand)
            } else {
                assertNotNull("non-broadcast header $header must declare an ATSH command", header.atCommand)
            }
        }
    }

    // ---- Negative-PID cache + idle-data clock ---------------------------------------

    @Test
    fun unsupportedPidIsRetiredAfterRepeatedNoDataWhileBusAlive() {
        var nowMs = 1_000L
        state.setClockForTesting { nowMs }
        engine.responses["010D"] = "41 0D 28\r>" // live speed every cycle
        engine.responses["015C"] = "NO DATA\r>" // unsupported on this car
        val due = specs("010D", "015C")

        repeat(PidPollingState.MAX_CONSECUTIVE_NO_DATA) {
            state.runScheduledPolls(due, StringBuilder())
            nowMs += 1_000
        }

        assertTrue("a PID that only ever NO-DATAs must be retired", state.isCommandDisabled("015C"))
        assertEquals(1, state.disabledCommandCount())
        assertFalse("a live PID must never be retired", state.isCommandDisabled("010D"))
        assertTrue(
            "a retired PID must drop out of the schedule",
            state.dueForCurrentCycle().none { it.command == "015C" },
        )
    }

    @Test
    fun conditionalPidIsExemptFromTheNegativeCache() {
        var nowMs = 1_000L
        state.setClockForTesting { nowMs }
        engine.responses["010D"] = "41 0D 28\r>"
        engine.responses["224373"] = "NO DATA\r>" // charging-mode PID: only answers while charging
        val due = specs("010D", "224373")

        repeat(PidPollingState.MAX_CONSECUTIVE_NO_DATA + 2) {
            state.runScheduledPolls(due, StringBuilder())
            nowMs += 1_000
        }

        assertFalse("a conditional PID must survive NO-DATA while driving", state.isCommandDisabled("224373"))
        assertEquals(0, state.disabledCommandCount())
    }

    @Test
    fun aFullyAsleepBusRetiresNothing() {
        var nowMs = 1_000L
        state.setClockForTesting { nowMs }
        // No scripted responses -> every read returns ">" which classifies as NO DATA: the whole car
        // is asleep, so no single PID may be blamed as unsupported.
        val due = specs("010D", "015C")
        repeat(10) {
            state.runScheduledPolls(due, StringBuilder())
            nowMs += 1_000
        }
        assertEquals("a bus-wide outage must not retire any PID", 0, state.disabledCommandCount())
    }

    @Test
    fun aLiveReadResetsTheNoDataStreak() {
        var nowMs = 1_000L
        state.setClockForTesting { nowMs }
        engine.responses["010D"] = "41 0D 28\r>"
        val due = specs("010D", "015C")

        engine.responses["015C"] = "NO DATA\r>"
        repeat(2) {
            state.runScheduledPolls(due, StringBuilder())
            nowMs += 1_000
        }
        // One real read clears the streak...
        engine.responses["015C"] = "41 5C 50\r>"
        state.runScheduledPolls(due, StringBuilder())
        nowMs += 1_000
        // ...so two more misses still fall short of the disable threshold.
        engine.responses["015C"] = "NO DATA\r>"
        repeat(2) {
            state.runScheduledPolls(due, StringBuilder())
            nowMs += 1_000
        }
        assertFalse("an intermittently-answering PID must not be retired", state.isCommandDisabled("015C"))
    }

    @Test
    fun msSinceLastLiveDataTracksTheLastFreshRead() {
        var nowMs = 10_000L
        state.setClockForTesting { nowMs }
        engine.responses["010D"] = "41 0D 28\r>"

        state.runScheduledPolls(specs("010D"), StringBuilder())
        assertEquals("a live read resets the idle clock", 0L, state.msSinceLastLiveData())

        nowMs += 5_000L
        assertEquals(5_000L, state.msSinceLastLiveData())

        // A fully silent cycle (015C unset -> ">" -> NO DATA) must NOT refresh the idle clock.
        state.runScheduledPolls(specs("015C"), StringBuilder())
        nowMs += 1_000L
        assertEquals("silent cycles keep the idle clock running", 6_000L, state.msSinceLastLiveData())
    }

    // ---- helpers --------------------------------------------------------------------

    private companion object {
        /** Builds the [PidSpec] list for the given commands, preserving SPECS order/header. */
        private fun specs(vararg commands: String): List<PidSpec> {
            val out = ArrayList<PidSpec>()
            for (command in commands) {
                out.add(findSpec(command))
            }
            return out
        }

        private fun findSpec(command: String): PidSpec {
            for (spec in PidSchedule.SPECS) {
                if (spec.command == command) {
                    return spec
                }
            }
            throw AssertionError("no spec for command $command")
        }
    }

    /**
     * [ObdPollingEngine] that records every command and returns scripted responses, so the test can
     * assert the exact round-trips the schedule produced without a real adapter.
     */
    private class CapturingEngine(
        service: EngineHost,
    ) : ObdPollingEngine(service) {
        val commandLog: MutableList<String> = Collections.synchronizedList(ArrayList())
        val responses = HashMap<String, String>()

        override fun sendRecoverableCommand(
            command: String?,
            timeoutMs: Long,
        ): String {
            commandLog.add(command!!)
            return responses[command] ?: ">"
        }

        override fun sendCommand(
            command: String?,
            timeoutMs: Long,
        ): String {
            commandLog.add(command!!)
            return responses[command] ?: ">"
        }
    }
}
