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
}
