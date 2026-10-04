package com.volttracker.obdpoc

import com.volttracker.obdpoc.engine.ElmConnection
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
        val dpnQueue = ArrayDeque<String>()
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
            return ElmConnection.MonitorResult(monitorText, monitorPrompt, false, false)
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
    private val policy = SwcanListenRunner.Policy(firstWindowDelayMs = 10_000L, intervalMs = 30_000L)
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
    fun hsSilentAfterWindowForcesReinitAndDisables() {
        readyStn()
        cycle()
        cycle(hsAnswered = false)
        assertEquals(1, io.reinitCount)
        assertEquals("hs_not_restored", runner.disabledReason())
        assertEquals("no_live_data_after_window", io.event("swcan_hs_reinit")!!["reason"])
        now += policy.intervalMs
        cycle()
        assertEquals(1, io.count("STM"))
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
    fun restoreFailureReinitializesAdapter() {
        io.replies["ATSP6"] = "?\r\r>"
        readyStn()
        cycle()
        assertEquals("restore_failed", io.event("swcan_window")!!["outcome"])
        assertEquals(1, io.reinitCount)
        assertEquals("restore_failed", runner.disabledReason())
    }

    @Test
    fun restoreRequiresHsProtocolBack() {
        io.dpnQueue.addAll(listOf("6\r\r>", "61\r\r>"))
        readyStn()
        cycle()
        assertEquals("restore_failed", runner.disabledReason())
        assertEquals(1, io.reinitCount)
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
    fun missingStopPromptDisables() {
        io.monitorPrompt = false
        readyStn()
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
        assertEquals("no_stop_prompt", runner.disabledReason())
        assertEquals(CarControlGate.Adapter.STN_UNVERIFIED, runner.controlCapability())
    }

    @Test
    fun aStationaryCarGetsLongerMoreFrequentWindows() {
        readyStn()
        io.stationary = true
        cycle()
        assertEquals(listOf(policy.parkedListenMs), io.listenMs)
        now += policy.parkedIntervalMs
        cycle()
        cycle()
        assertEquals("parked windows come every parkedIntervalMs", 2, io.count("STM"))
        io.stationary = false
        now += policy.parkedIntervalMs
        cycle()
        cycle()
        assertEquals("a moving car with no tires yet hunts for them", policy.tireHuntListenMs, io.listenMs.last())
    }

    @Test
    fun aMovingCarHuntsForTiresUntilItHearsThem() {
        readyStn()
        cycle()
        assertEquals(listOf(policy.tireHuntListenMs), io.listenMs)
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
    fun tireShapedFramesAreLoggedAsCandidatesUntilTheTiresAreHeard() {
        readyStn()
        val candidate = "10 3D 60 60 00 11 41 42 41 43 00 00\r"
        io.monitorText = candidate.repeat(3) + "STOPPED\r\r>"
        cycle()
        val logged = io.event("swcan_tire_candidates")!!
        assertEquals("1", logged["window"])
        assertEquals("103D6060@2 260/264/260/268kPa n3", logged["candidates"])

        // The same layout on the next window isn't logged again.
        now += policy.tireHuntIntervalMs
        cycle()
        cycle()
        assertEquals(1, io.events.count { it.first == "swcan_tire_candidates" })

        // Once the known tire frame is heard there is nothing left to learn.
        io.monitorText = "10 3D 40 40 00 00 3C 3D 3E 3F 00 00\r" + "10 3D 20 BC 00 11 41 42 41 43 00 00\r".repeat(3) +
            "STOPPED\r\r>"
        now += policy.tireHuntIntervalMs
        cycle()
        cycle()
        assertEquals(1, io.events.count { it.first == "swcan_tire_candidates" })
    }

    @Test
    fun theTireHuntStopsAfterItsWindowCap() {
        val capped =
            SwcanListenRunner.Policy(
                firstWindowDelayMs = 10_000L,
                intervalMs = 45_000L,
                tireHuntMaxWindows = 2,
            )
        val runner = SwcanListenRunner(io, capped) { now }
        runner.probeAdapter()
        now += capped.firstWindowDelayMs
        repeat(3) {
            io.liveCycles += 1
            runner.afterSample()
            io.liveCycles += 1
            runner.afterSample()
            now += capped.tireHuntIntervalMs
        }
        assertEquals("the third window waits for the normal interval", 2, io.count("STM"))
        now += capped.intervalMs - capped.tireHuntIntervalMs
        io.liveCycles += 1
        runner.afterSample()
        io.liveCycles += 1
        runner.afterSample()
        assertEquals(3, io.count("STM"))
        assertEquals(
            listOf(capped.tireHuntListenMs, capped.tireHuntListenMs, capped.listenMs),
            io.listenMs,
        )
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

        val rawPolicy = SwcanListenRunner.Policy(firstWindowDelayMs = 10_000L, logRawWindows = true)
        val raw = SwcanListenRunner(io, rawPolicy) { now }
        raw.probeAdapter()
        now += rawPolicy.firstWindowDelayMs
        io.liveCycles += 1
        raw.afterSample()
        val logged = io.event("swcan_raw")!!
        assertEquals("window", logged["mode"])
        assertEquals("1", logged["chunk"])
        assertEquals(io.monitorText, logged["text"])
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
