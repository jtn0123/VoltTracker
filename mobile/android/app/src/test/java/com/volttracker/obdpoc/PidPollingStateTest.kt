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
