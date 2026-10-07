package com.volttracker.obdpoc

import com.volttracker.obdpoc.engine.ElmConnection
import com.volttracker.obdpoc.engine.GuidedCarTest
import com.volttracker.obdpoc.engine.SwcanListenRunner
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SwcanListenRunnerTest {
    private class FakeIo : SwcanListenRunner.Io {
        val commands = mutableListOf<String>()
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val replies =
            mutableMapOf(
                "STI" to "STN2255 v5.10.3\r\r>",
                "ATDPN" to "A6\r\r>",
            )
        var monitorText = "10 24 80 40 00 00 60 D9 00 FC 00 00\r0C 41 40 40 00 05 00 05\rSTOPPED\r\r>"
        var monitorPrompt = true
        var monitorCapped = false
        val dpnQueue = ArrayDeque<String>()

        /** One-off replies, used before [replies]: e.g. the monitor's leftover frames after a slow stop. */
        val replyQueue = mutableMapOf<String, ArrayDeque<String>>()
        var probeThrows = false
        var liveCycles = 0L
        var msSinceLive = 0L
        var reinitCount = 0
        var exclusiveDepth = 0
        var commandsOutsideLock = 0
        var stationary = false
        val listenMs = mutableListOf<Long>()
        var onMonitor: (Long) -> Unit = {}

        override fun isStationary(): Boolean = stationary

        override fun send(
            command: String,
            timeoutMs: Long,
        ): String {
            if (probeThrows && command == "STI") throw IOException("socket closed")
            if (exclusiveDepth == 0 && command != "STI") commandsOutsideLock += 1
            commands.add(command)
            if (command == "ATDPN" && dpnQueue.isNotEmpty()) return dpnQueue.removeFirst()
            replyQueue[command]?.removeFirstOrNull()?.let { return it }
            return replies[command] ?: "OK\r\r>"
        }

        override fun monitor(
            command: String,
            listenMs: Long,
            stopTimeoutMs: Long,
        ): ElmConnection.MonitorResult {
            commands.add(command)
            this.listenMs.add(listenMs)
            onMonitor(listenMs)
            return ElmConnection.MonitorResult(monitorText, monitorPrompt, false, monitorCapped)
        }

        override fun reinitialize() {
            reinitCount += 1
            commands.add("<reinit>")
        }

        override fun liveCycleCount(): Long = liveCycles

        override fun msSinceLiveData(): Long = msSinceLive

        override fun <T> exclusive(block: () -> T): T {
            exclusiveDepth += 1
            try {
                return block()
            } finally {
                exclusiveDepth -= 1
            }
        }

        override fun logEvent(
            event: String,
            vararg pairs: String,
        ) {
            events.add(event to pairs.toList().chunked(2).associate { it[0] to it[1] })
        }

        fun event(name: String): Map<String, String>? = events.lastOrNull { it.first == name }?.second

        fun count(command: String): Int = commands.count { it == command }
    }

    private var now = 0L
    private val io = FakeIo()

    // The startup window's own length is pinned in theFirstWindowListensLongForTheTireBroadcast;
    // here it listens as long as a regular one, so each window is one STM.
    private val policy =
        SwcanListenRunner.Policy(firstWindowDelayMs = 10_000L, intervalMs = 30_000L, startupListenMs = 1_200L)
    private val runner = SwcanListenRunner(io, policy) { now }

    private fun readyStn() {
        runner.probeAdapter()
        now += policy.firstWindowDelayMs
    }

    /** One poll cycle: HS answered (unless [hsAnswered] is false), then the loop's hook. */
    private fun cycle(hsAnswered: Boolean = true) {
        if (hsAnswered) io.liveCycles += 1
        runner.afterSample()
    }

    @Test
    fun plainElmIsNeverSwitched() {
        io.replies["STI"] = "?\r\r>"
        runner.probeAdapter()
        now += 1_000_000L
        repeat(5) { cycle() }
        assertFalse(runner.isEnabled())
        assertEquals(listOf("STI"), io.commands)
        assertEquals("false", io.event("swcan_adapter_probe")!!["stn"])
    }

    @Test
    fun windowSwitchesListensAndRestoresInOrder() {
        readyStn()
        assertTrue(runner.isEnabled())
        now -= 1
        cycle()
        assertEquals("not before the first-window delay", listOf("STI"), io.commands)
        now += 1
        cycle()
        val expected =
            listOf("STI", "ATDPN") + SwcanListenRunner.SETUP_COMMANDS + "STM" + SwcanListenRunner.RESTORE_COMMANDS +
                "ATDPN"
        assertEquals(expected, io.commands)
        assertEquals(0, io.commandsOutsideLock)
        val window = io.event("swcan_window")!!
        assertEquals("ok", window["outcome"])
        assertEquals("2", window["frames"])
        assertEquals("5", window["decoded"])
        assertEquals("0C414040 10248040", window["ids"])
        val sample = JSONObject()
        runner.appendTo(sample, now)
        assertEquals(12.6, sample.getDouble("aux12vVoltage"), 1e-9)
        assertEquals("locked", sample.getString("doorLockState"))
    }

    @Test
    fun aGuidedTestTakesThePollTurnsAndHoldsTheSession() {
        runner.guidedTest.request(GuidedCarTest.Op.START)
        assertTrue("waiting for the adapter already holds the session", runner.holdsSession())
        cycle()
        assertTrue("it waits for the adapter to be identified", io.commands.isEmpty())

        readyStn()
        // A quiet car (HS silent for 2 min) neither stops it nor gets a quiet capture.
        io.msSinceLive = 120_000L
        cycle(hsAnswered = false)

        val expected =
            listOf("STI", "ATDPN") + SwcanListenRunner.SETUP_COMMANDS + "STM" + SwcanListenRunner.RESTORE_COMMANDS +
                "ATDPN"
        assertEquals(expected, io.commands)
        assertEquals(0, io.commandsOutsideLock)
        assertEquals("intro", io.event("guided_step")!!["step"])
        assertNull("no window of the listener's own", io.event("swcan_window"))
        val sample = JSONObject()
        runner.appendTo(sample, now)
        assertEquals("what a step hears reaches the Car tab", "locked", sample.getString("doorLockState"))

        runner.resetSession()
        assertTrue("a reconnect keeps the test", runner.holdsSession())
        runner.guidedTest.request(GuidedCarTest.Op.STOP)
        cycle()
        assertFalse(runner.holdsSession())
        assertEquals("Stopped", io.event("guided_test_end")!!["ended"])
    }

    @Test
    fun onlyAdapterLocalReceiveCommandsAreEverSent() {
        // The listener must never put anything on the bus: no raw transmit, periodic message,
        // SW-CAN high-voltage wake-up, wake message, RTR, or OBD request.
        val forbidden = listOf("STPX", "STPPM", "STCSWM", "ATWM", "ATRTR", "STSL")
        val all = listOf("STI", "ATDPN", "STM") + SwcanListenRunner.SETUP_COMMANDS + SwcanListenRunner.RESTORE_COMMANDS
        for (command in all) {
            val compact = command.replace(" ", "")
            assertTrue(
                "$command is not an adapter-local AT/ST command",
                compact.startsWith("AT") || compact.startsWith("ST"),
            )
            assertTrue("$command transmits", forbidden.none { compact.startsWith(it) })
        }
        assertTrue("receive-only CAN monitoring (no ACKs)", SwcanListenRunner.SETUP_COMMANDS.contains("STCMM 0"))
        assertTrue(
            "ISO 11898 raw preset: never sends ISO-TP flow control",
            SwcanListenRunner.SETUP_COMMANDS.contains("STP 61"),
        )
        readyStn()
        repeat(4) {
            cycle()
            now += policy.intervalMs
        }
        assertTrue(io.commands.filter { it != "<reinit>" }.all { it in all })
    }

    @Test
    fun healthyHsAfterWindowKeepsListeningOnInterval() {
        readyStn()
        cycle()
        cycle() // health check passes: HS answered
        assertEquals(0, io.reinitCount)
        assertEquals(1, io.count("STM"))
        now += policy.intervalMs
        cycle()
        assertEquals(2, io.count("STM"))
        assertTrue(runner.isEnabled())
    }

    @Test
    fun hsSilentAfterWindowForcesReinitAndCountsAsTrouble() {
        readyStn()
        cycle()
        cycle(hsAnswered = false)
        assertEquals(1, io.reinitCount)
        assertEquals("no_live_data_after_window", io.event("swcan_hs_reinit")!!["reason"])
        assertTrue("one quiet cycle is trouble, not the end", runner.isEnabled())
        assertEquals(CarControlGate.Adapter.STN_UNVERIFIED, runner.controlCapability())
        now += policy.intervalMs
        cycle()
        assertEquals(2, io.count("STM"))
        cycle(hsAnswered = false)
        assertEquals(2, io.reinitCount)
        assertEquals("hs_not_restored", runner.disabledReason())
        now += policy.intervalMs
        cycle()
        assertEquals(2, io.count("STM"))
    }

    @Test
    fun aCarSwitchedOffIsNotABrokenRestore() {
        readyStn()
        // The window hears the car's power mode go to off (arb 0x121, mode 0).
        io.monitorText = "10 24 80 40 00 00 60 D9 00 FC 00 00\r10 24 20 40 00\rSTOPPED\r\r>"
        cycle()
        cycle(hsAnswered = false)
        assertEquals(0, io.reinitCount)
        assertEquals("car_off", io.event("swcan_hs_quiet")!!["reason"])
        assertTrue(runner.isEnabled())
        assertEquals(
            "car controls stay available after parking",
            CarControlGate.Adapter.READY,
            runner.controlCapability(),
        )
    }

    @Test
    fun aQuietBusGetsOneBoundedCaptureForTheShutdownBurst() {
        readyStn()
        io.onMonitor = { now += it }
        io.monitorText = "10 24 20 40 00\r0C 2F 60 40 00\rSTOPPED\r\r>"
        cycle()
        cycle(hsAnswered = false) // health check: the car is off
        io.msSinceLive = 6_000L
        cycle(hsAnswered = false)
        val cap = (policy.quietCaptureMaxMs / policy.bodyTestChunkMs).toInt()
        assertEquals("the window, then the capture up to its cap", 1 + cap, io.count("STM"))
        assertEquals("quiet", io.event("swcan_window")!!["mode"])
        assertEquals("car_off", io.event("swcan_hs_quiet")!!["reason"])
        assertEquals(
            SwcanListenRunner.RESTORE_COMMANDS,
            io.commands
                .takeLast(
                    SwcanListenRunner.RESTORE_COMMANDS.size + 1,
                ).dropLast(1),
        )
        // One capture per stretch of silence, and no health check after it.
        now += 1_000L
        cycle(hsAnswered = false)
        assertEquals(1 + cap, io.count("STM"))
        assertEquals(0, io.reinitCount)
        // HS back: the next silence gets its own capture, which ends at the first chunk that
        // hears nothing (the bus gone to sleep).
        io.msSinceLive = 0L
        cycle()
        io.monitorText = "STOPPED\r\r>"
        io.msSinceLive = 6_000L
        cycle(hsAnswered = false)
        assertEquals(1 + cap + 1, io.count("STM"))
        assertEquals("empty", io.event("swcan_window")!!["outcome"])
        assertTrue("a sleeping bus never counts toward giving up", runner.isEnabled())
    }

    @Test
    fun aQuietHsBusWithTheCarStillOnGivesTheAdapterBackFast() {
        readyStn()
        io.onMonitor = { now += it }
        io.monitorText = "10 24 20 40 02\r0C 2F 60 40 00\rSTOPPED\r\r>"
        cycle()
        cycle(hsAnswered = false) // the car says run: this silence is trouble
        assertEquals(1, io.reinitCount)
        io.msSinceLive = 6_000L
        cycle(hsAnswered = false)
        assertEquals("two probe chunks, then HS gets the adapter back", 3, io.count("STM"))
        assertEquals("car_on", io.event("swcan_hs_quiet")!!["reason"])
        assertEquals(CarControlGate.Adapter.STN_UNVERIFIED, runner.controlCapability())
    }

    @Test
    fun aCaptureThatFindsTheCarOffForgivesTheQuietWindow() {
        readyStn()
        io.onMonitor = { now += it }
        cycle() // no power mode heard in this window
        cycle(hsAnswered = false)
        assertEquals(CarControlGate.Adapter.STN_UNVERIFIED, runner.controlCapability())
        io.monitorText = "10 24 20 40 00\rSTOPPED\r\r>"
        io.msSinceLive = 6_000L
        cycle(hsAnswered = false)
        assertEquals("car_off", io.event("swcan_hs_quiet")!!["reason"])
        assertEquals(CarControlGate.Adapter.READY, runner.controlCapability())
    }

    @Test
    fun aWindowCutShortIsPartialButStillCounts() {
        readyStn()
        io.monitorCapped = true
        cycle()
        assertEquals("partial", io.event("swcan_window")!!["outcome"])
        cycle()
        assertEquals(CarControlGate.Adapter.READY, runner.controlCapability())
    }

    @Test
    fun setupFailureStillRestoresThenDisables() {
        io.replies["STP 61"] = "?\r\r>"
        readyStn()
        cycle()
        assertEquals(0, io.count("STM"))
        assertTrue(io.commands.containsAll(SwcanListenRunner.RESTORE_COMMANDS))
        assertEquals("setup_failed", io.event("swcan_window")!!["outcome"])
        assertEquals("STP 61", io.event("swcan_window")!!["failedCommand"])
        assertEquals("setup_failed", runner.disabledReason())
        assertEquals(0, io.reinitCount)
    }

    @Test
    fun restoreFailureReinitializesAndASecondOneDisables() {
        io.replies["ATSP6"] = "?\r\r>"
        readyStn()
        cycle()
        assertEquals("restore_failed", io.event("swcan_window")!!["outcome"])
        assertEquals("both passes ran", 2, io.count("ATSP6"))
        assertEquals(1, io.reinitCount)
        assertNull("one bad window is recovered, not fatal", runner.disabledReason())
        assertEquals(CarControlGate.Adapter.STN_UNVERIFIED, runner.controlCapability())
        now += policy.intervalMs
        cycle()
        assertEquals(2, io.reinitCount)
        assertEquals("restore_failed", runner.disabledReason())
        now += policy.intervalMs
        cycle()
        assertEquals(2, io.count("STM"))
    }

    @Test
    fun restoreRequiresHsProtocolBack() {
        io.dpnQueue.addAll(listOf("6\r\r>", "61\r\r>", "61\r\r>"))
        readyStn()
        cycle()
        assertEquals("restore_failed", io.event("swcan_window")!!["outcome"])
        assertEquals(1, io.reinitCount)
    }

    @Test
    fun aSlowStopIsRetriedLikeOnTheCar() {
        // 2026-10-05: the adapter kept printing queued frames after the stop byte, so the first
        // restore commands got frames instead of OK. A second pass found it back at its prompt.
        io.replyQueue["ATH0"] = ArrayDeque(listOf(""))
        io.replyQueue["ATS0"] = ArrayDeque(listOf("00 10 40 00 00 10 0A\r>"))
        io.replyQueue["ATCAF1"] = ArrayDeque(listOf("10 21 00 40 00 00\r>"))
        readyStn()
        cycle()
        assertEquals("ok", io.event("swcan_window")!!["outcome"])
        assertEquals("1", io.event("swcan_restore_retry")!!["window"])
        assertEquals(
            "the failed pass skips the protocol check; the retry ends with it",
            SwcanListenRunner.RESTORE_COMMANDS + SwcanListenRunner.RESTORE_COMMANDS + "ATDPN",
            io.commands.drop(io.commands.indexOf("STM") + 1),
        )
        assertEquals(0, io.reinitCount)
        assertTrue(runner.isEnabled())
        assertEquals(CarControlGate.Adapter.READY, runner.controlCapability())
    }

    @Test
    fun nonVoltHsProtocolIsSkippedWithoutSwitching() {
        io.replies["ATDPN"] = "A7\r\r>"
        readyStn()
        cycle()
        assertEquals("hs_protocol_A7", runner.disabledReason())
        assertEquals("skipped", io.event("swcan_window")!!["outcome"])
        assertEquals(listOf("STI", "ATDPN"), io.commands)
    }

    @Test
    fun emptyWindowsDisableAfterLimit() {
        io.monitorText = "STOPPED\r\r>"
        readyStn()
        repeat(policy.maxConsecutiveEmpty) {
            cycle()
            now += policy.intervalMs
        }
        assertEquals("no_frames", runner.disabledReason())
        assertEquals("empty", io.event("swcan_window")!!["outcome"])
        cycle()
        assertEquals(policy.maxConsecutiveEmpty, io.count("STM"))
    }

    @Test
    fun aSecondMissingStopPromptDisables() {
        io.monitorPrompt = false
        readyStn()
        cycle()
        assertEquals("no_stop_prompt", io.event("swcan_window")!!["outcome"])
        assertTrue(runner.isEnabled())
        now += policy.intervalMs
        cycle()
        assertEquals("no_stop_prompt", runner.disabledReason())
    }

    @Test
    fun sleepingHsBusSkipsTheWindow() {
        readyStn()
        io.msSinceLive = policy.maxLiveDataAgeMs + 1
        cycle()
        assertEquals(0, io.count("STM"))
        assertTrue(runner.isEnabled())
    }

    @Test
    fun probeFailureLeavesIdentityUnknownForRetry() {
        io.probeThrows = true
        runner.probeAdapter()
        assertFalse(runner.isEnabled())
        assertEquals("socket closed", io.event("swcan_adapter_probe_failed")!!["error"])
        io.probeThrows = false
        runner.probeAdapter()
        assertTrue(runner.isEnabled())
        runner.probeAdapter() // already known: no second STI
        assertEquals(1, io.count("STI"))
    }

    @Test
    fun resetSessionClearsEverything() {
        io.replies["STP 61"] = "?\r\r>"
        readyStn()
        cycle()
        assertEquals("setup_failed", runner.disabledReason())
        runner.resetSession()
        assertNull(runner.disabledReason())
        assertFalse(runner.isEnabled())
        assertEquals(0, runner.readings.size())
    }

    @Test
    fun controlCapabilityNeedsAnStnThatHeardTheCar() {
        assertEquals(CarControlGate.Adapter.UNKNOWN, runner.controlCapability())
        readyStn()
        assertEquals(CarControlGate.Adapter.STN_UNVERIFIED, runner.controlCapability())
        cycle()
        assertEquals(CarControlGate.Adapter.READY, runner.controlCapability())
        runner.resetSession()
        assertEquals(CarControlGate.Adapter.UNKNOWN, runner.controlCapability())
    }

    @Test
    fun controlCapabilityForPlainElmAndBrokenListener() {
        io.replies["STI"] = "?\r\r>"
        runner.probeAdapter()
        assertEquals(CarControlGate.Adapter.NOT_STN, runner.controlCapability())
        runner.resetSession()
        io.replies["STI"] = "STN2255 v5.10.3\r\r>"
        io.monitorPrompt = false
        readyStn()
        cycle()
        assertNull(runner.disabledReason())
        assertEquals(CarControlGate.Adapter.STN_UNVERIFIED, runner.controlCapability())
    }

    @Test
    fun aStationaryCarGetsLongerMoreFrequentWindows() {
        readyStn()
        io.stationary = true
        cycle() // the startup window
        now += policy.parkedIntervalMs
        cycle() // health check, then the first parked window
        assertEquals(listOf(policy.startupListenMs, policy.parkedListenMs), io.listenMs)
        now += policy.parkedIntervalMs
        cycle()
        assertEquals("parked windows come every parkedIntervalMs", 3, io.count("STM"))
        io.stationary = false
        now += policy.parkedIntervalMs
        cycle()
        assertEquals("a moving car with no tires yet hunts for them", policy.tireHuntListenMs, io.listenMs.last())
    }

    @Test
    fun aMovingCarHuntsForTiresUntilItHearsThem() {
        readyStn()
        cycle() // the startup window
        now += policy.tireHuntIntervalMs
        cycle()
        assertEquals(listOf(policy.startupListenMs, policy.tireHuntListenMs), io.listenMs)
        assertEquals("true", io.event("swcan_window")!!["tireHunt"])
        assertNull(io.event("swcan_tires_heard"))

        io.monitorText = "10 3D 40 40 00 00 3C 3D 3E 3F 00 00\rSTOPPED\r\r>"
        now += policy.tireHuntIntervalMs
        cycle()
        cycle()
        assertEquals("2", io.event("swcan_tires_heard")!!["huntWindows"])
        val sample = JSONObject()
        runner.appendTo(sample, now)
        assertEquals(240.0, sample.getDouble("tirePressureFlKpa"), 0.0)

        now += policy.intervalMs
        cycle()
        cycle()
        assertEquals("tires heard: back to the short listen", policy.listenMs, io.listenMs.last())
        assertEquals("false", io.event("swcan_window")!!["tireHunt"])
    }

    @Test
    fun aTireSensorTheCarFlagsInvalidKeepsTheHuntGoing() {
        readyStn()
        cycle() // the startup window
        // Front left flagged invalid (byte 0 bit 0): three of four tyres is not a reading.
        io.monitorText = "10 3D 40 40 01 00 3C 3D 3E 3F 00 00\rSTOPPED\r\r>"
        now += policy.tireHuntIntervalMs
        cycle()
        cycle()
        assertNull(io.event("swcan_tires_heard"))
        assertEquals(policy.tireHuntListenMs, io.listenMs.last())
        val sample = JSONObject()
        runner.appendTo(sample, now)
        assertEquals("fl", sample.getString("tireSensorsInvalid"))

        io.monitorText = "10 3D 40 40 00 00 3C 3D 3E 3F 00 00\rSTOPPED\r\r>"
        now += policy.tireHuntIntervalMs
        cycle()
        cycle()
        assertEquals("the second hunt window had all four", "2", io.event("swcan_tires_heard")!!["huntWindows"])
    }

    @Test
    fun theTireHuntStopsAfterItsWindowCap() {
        val capped =
            SwcanListenRunner.Policy(
                firstWindowDelayMs = 10_000L,
                intervalMs = 45_000L,
                tireHuntMaxWindows = 2,
                startupListenMs = 1_200L,
            )
        val runner = SwcanListenRunner(io, capped) { now }
        runner.probeAdapter()
        now += capped.firstWindowDelayMs

        fun twoCycles() {
            io.liveCycles += 1
            runner.afterSample()
            io.liveCycles += 1
            runner.afterSample()
        }
        // The startup window, then the two hunt windows the cap allows, 30 s apart.
        repeat(3) {
            twoCycles()
            now += capped.tireHuntIntervalMs
        }
        twoCycles()
        assertEquals("the window after the cap waits for the normal interval", 3, io.count("STM"))
        now += capped.intervalMs - capped.tireHuntIntervalMs
        twoCycles()
        assertEquals(4, io.count("STM"))
        assertEquals(
            listOf(capped.startupListenMs, capped.tireHuntListenMs, capped.tireHuntListenMs, capped.listenMs),
            io.listenMs,
        )
    }

    @Test
    fun theFirstWindowListensLongForTheTireBroadcast() {
        val startup = SwcanListenRunner.Policy(firstWindowDelayMs = 10_000L, intervalMs = 30_000L)
        val runner = SwcanListenRunner(io, startup) { now }
        runner.probeAdapter()
        now += startup.firstWindowDelayMs
        io.liveCycles += 1
        runner.afterSample()
        assertEquals("10 s in two 5 s chunks", listOf(5_000L, 5_000L), io.listenMs)
        assertEquals("startup", io.event("swcan_window")!!["mode"])
        assertEquals("ok", io.event("swcan_window")!!["outcome"])
        now += startup.tireHuntIntervalMs
        io.liveCycles += 1
        runner.afterSample()
        assertEquals("then the usual windows", startup.tireHuntListenMs, io.listenMs.last())
    }

    @Test
    fun theCarTabListensAfterEveryPollCycleWhileItsLeaseLasts() {
        readyStn()
        runner.requestBodyFocus(5_000L)
        now -= 1
        cycle()
        assertEquals("focus still waits for the startup window's delay", 0, io.count("STM"))
        now += 1
        cycle() // the startup window
        io.stationary = true
        cycle() // health check passes, then a focus window straight away
        cycle()
        assertEquals(
            "parked: 10 s windows back to back",
            listOf(policy.startupListenMs, 5_000L, 5_000L, 5_000L, 5_000L),
            io.listenMs,
        )
        assertEquals("focus", io.event("swcan_window")!!["mode"])
        io.stationary = false
        cycle()
        assertEquals(
            "moving: short windows, so the trip keeps its samples",
            policy.focusMovingListenMs,
            io.listenMs.last(),
        )

        now += 5_001L
        val heard = io.listenMs.size
        cycle()
        cycle()
        assertEquals("the lease ran out: back to the interval", heard, io.listenMs.size)
        assertEquals(0, io.reinitCount)
        assertTrue(runner.isEnabled())
    }

    @Test
    fun aFocusLeaseIsCappedAndAZeroOneEndsIt() {
        readyStn()
        cycle() // the startup window
        runner.requestBodyFocus(30_000L)
        runner.requestBodyFocus(0L)
        cycle()
        assertEquals("a 0 lease ends focus at once", 1, io.count("STM"))
        runner.requestBodyFocus(10 * 60_000L)
        now += policy.focusMaxLeaseMs + 1
        cycle()
        assertEquals("a lease never runs past the cap", "tire_hunt", io.event("swcan_window")!!["mode"])
    }

    @Test
    fun focusWindowsLogOnlyTheBodyFrames() {
        val rawPolicy =
            SwcanListenRunner.Policy(
                firstWindowDelayMs = 10_000L,
                startupListenMs = 1_200L,
                logRawWindows = true,
            )
        val raw = SwcanListenRunner(io, rawPolicy) { now }
        raw.probeAdapter()
        now += rawPolicy.firstWindowDelayMs
        io.liveCycles += 1
        raw.afterSample() // the startup window logs everything it heard
        assertEquals("window", io.event("swcan_raw")!!["mode"])
        // A window frame (arb 0x325) between the 12 V monitor and a lock frame: only the body
        // frames are kept, re-printed with their ids.
        io.monitorText = "10 24 80 40 00 00 60 D9 00 FC 00 00\r10 64 A0 40 28 2D\r0C 41 40 40 00 05 00 05\rSTOPPED\r\r>"
        raw.requestBodyFocus(30_000L)
        io.liveCycles += 1
        raw.afterSample()
        val logged = io.event("swcan_raw")!!
        assertEquals("focus", logged["mode"])
        assertEquals("10 64 A0 40 28 2D\r0C 41 40 40 00 05 00 05", logged["text"])
        val before = io.events.count { it.first == "swcan_raw" }
        io.monitorText = "10 24 80 40 00 00 60 D9 00 FC 00 00\rSTOPPED\r\r>"
        io.liveCycles += 1
        raw.afterSample()
        assertEquals("no body frame: nothing logged", before, io.events.count { it.first == "swcan_raw" })
    }

    @Test
    fun aBodyTestListensInChunksLogsEveryFrameAndRestores() {
        readyStn()
        io.onMonitor = { now += it }
        runner.requestBodyTest(12_000L)
        cycle()
        assertEquals("12 s in 5 s chunks", 3, io.count("STM"))
        assertEquals(0, io.commandsOutsideLock)
        assertEquals(
            SwcanListenRunner.RESTORE_COMMANDS,
            io.commands
                .takeLast(
                    SwcanListenRunner.RESTORE_COMMANDS.size + 1,
                ).dropLast(1),
        )
        val raw = io.events.filter { it.first == "swcan_raw" }
        assertEquals(3, raw.size)
        assertTrue(raw.all { it.second["mode"] == "body_test" && it.second["text"]!!.contains("0C 41 40 40") })
        val done = io.event("body_test_done")!!
        assertEquals("ok", done["outcome"])
        assertEquals("6", done["frames"])
        val sample = JSONObject()
        runner.appendTo(sample, now)
        assertEquals("locked", sample.getString("doorLockState"))
    }

    @Test
    fun regularWindowsLogRawFramesOnlyWhenAsked() {
        readyStn()
        cycle()
        assertNull("windows carry ids only by default", io.event("swcan_raw"))

        val rawPolicy =
            SwcanListenRunner.Policy(
                firstWindowDelayMs = 10_000L,
                startupListenMs = 1_200L,
                logRawWindows = true,
            )
        val raw = SwcanListenRunner(io, rawPolicy) { now }
        raw.probeAdapter()
        now += rawPolicy.firstWindowDelayMs
        io.liveCycles += 1
        raw.afterSample()
        val logged = io.event("swcan_raw")!!
        assertEquals("window", logged["mode"])
        assertEquals("1", logged["chunk"])
        assertEquals("10 24 80 40 00 00 60 D9 00 FC 00 00\r0C 41 40 40 00 05 00 05", logged["text"])
    }

    @Test
    fun rawLogsNeverHoldTheSensitiveFrames() {
        val rawPolicy =
            SwcanListenRunner.Policy(
                firstWindowDelayMs = 10_000L,
                startupListenMs = 1_200L,
                logRawWindows = true,
            )
        val raw = SwcanListenRunner(io, rawPolicy) { now }
        raw.probeAdapter()
        now += rawPolicy.firstWindowDelayMs
        // A door frame, a location frame (arb 0x155) and the fuel frame (arb 0x3B2).
        io.monitorText = "0C 2F 60 40 01\r10 2A A0 97 01 02 03 04\r10 76 40 97 08 11 22 33\rSTOPPED\r\r>"
        io.liveCycles += 1
        raw.afterSample()
        assertEquals("0C 2F 60 40 01\r10 76 40 97 08", io.event("swcan_raw")!!["text"])
        runner.probeAdapter()
        now += policy.firstWindowDelayMs
        runner.requestBodyTest(5_000L)
        cycle()
        assertEquals("body_test", io.event("swcan_raw")!!["mode"])
        assertEquals("0C 2F 60 40 01\r10 76 40 97 08", io.event("swcan_raw")!!["text"])
    }

    @Test
    fun aBodyTestOffAnObdLinkIsLoggedNotRun() {
        io.replies["STI"] = "?\r\r>"
        runner.probeAdapter()
        runner.requestBodyTest(60_000L)
        cycle()
        assertEquals(0, io.count("STM"))
        assertEquals("not_stn", io.event("body_test_unavailable")!!["reason"])
    }

    @Test
    fun aBodyTestWithAStalledClockStillEnds() {
        readyStn()
        runner.requestBodyTest(10_000L)
        cycle()
        assertEquals(3, io.count("STM"))
    }
}
