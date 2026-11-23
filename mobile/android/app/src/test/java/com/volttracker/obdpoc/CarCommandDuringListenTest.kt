package com.volttracker.obdpoc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.volttracker.obdpoc.engine.CarControlEngineIo
import com.volttracker.obdpoc.engine.CarControlRunner
import com.volttracker.obdpoc.engine.CarControlSession
import com.volttracker.obdpoc.engine.ElmConnection
import com.volttracker.obdpoc.engine.ObdPollingEngine
import com.volttracker.obdpoc.engine.SwcanListenRunner
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A car command issued from the Car tab while it holds body focus, the way the poll loop runs it:
 * the real SW-CAN listener, car-control runner, gate and session, all on one fake clock that bus
 * switching, monitoring, the HS read and the parked poll sleep advance by realistic amounts. With
 * default policies a parked focus window listens 10 s, twice the gate's limit on sample age.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CarCommandDuringListenTest {
    /** An OBDLink on a parked car that answers everything; each exchange costs time on [now]. */
    private inner class ParkedAdapter : SwcanListenRunner.Io {
        val commands = mutableListOf<String>()
        val events = mutableListOf<String>()
        val chunkMs = mutableListOf<Long>()
        var liveCycles = 0L
        var liveAtMs = 0L

        /** Runs as a monitor chunk starts, at that moment on the clock. */
        var onMonitor: () -> Unit = {}

        /** Runs as each command is sent, at that moment on the clock. */
        var onSend: (String) -> Unit = {}

        /** How long the adapter takes to print its prompt after the stop byte. */
        var stopMs = STOP_MS

        override fun send(
            command: String,
            timeoutMs: Long,
        ): String {
            onSend(command)
            commands += command
            now += SEND_MS
            return when (command) {
                "STI" -> "STN2255 v5.10.3\r\r>"
                "ATDPN" -> "A6\r\r>"
                else -> "OK\r\r>"
            }
        }

        override fun monitor(
            command: String,
            listenMs: Long,
            stopTimeoutMs: Long,
        ): ElmConnection.MonitorResult {
            commands += command
            chunkMs += listenMs
            onMonitor()
            now += listenMs + stopMs
            return ElmConnection.MonitorResult(BROADCASTS, true, false, false)
        }

        override fun reinitialize() {
            commands += "<reinit>"
        }

        override fun liveCycleCount(): Long = liveCycles

        override fun msSinceLiveData(): Long = now - liveAtMs

        override fun <T> exclusive(block: () -> T): T = block()

        override fun isStationary(): Boolean = true

        override fun isInPark(): Boolean = true

        override fun logEvent(
            event: String,
            vararg pairs: String,
        ) {
            events += event
        }

        fun windows(): Int = events.count { it == "swcan_window" }

        fun transmits(): List<String> = commands.filter { it.startsWith("STPX") }
    }

    private var now = 1_000_000L
    private val adapter = ParkedAdapter()
    private val session: CarControlSession
    private val listener =
        SwcanListenRunner(
            adapter,
            SwcanListenRunner.Policy(),
            guidedClock = { now },
            carCommandPending = { session.hasPendingCommand() },
        ) { now }
    private val settings =
        CarControlSettings {
            ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(
                AppPrefs.FILE,
                Context.MODE_PRIVATE,
            )
        }
    private val runner: CarControlRunner

    init {
        val io =
            CarControlEngineIo(
                adapter = adapter,
                listener = listener,
                settings = settings,
                sleep = { ms ->
                    now += ms
                    true
                },
                clock = { now },
            )
        runner = CarControlRunner(io, CarControlRunner.Policy(), CarControlGate()) { now }
        session = CarControlSession(runner, { _, ex -> throw ex }) { now }
    }

    @Before
    fun setUp() {
        CarControlAuth.resetForTest()
        assertTrue(settings.enable("2468"))
    }

    @After
    fun tearDown() {
        CarControlAuth.resetForTest()
    }

    @Test
    fun aCommandQueuedWhileTheCarTabListensIsSentOnTheNextCycle() =
        session.whileLive {
            carTabOpen()
            val windows = adapter.windows()
            // The owner confirms between two cycles, while the loop sleeps.
            request(CarCommand.LOCK)
            pollCycle()
            val result = runner.lastResult()!!
            assertEquals(result.detail, "confirmed", result.outcome)
            assertSentOnlyAllowlistedFrames()
            assertEquals("no listen window in the cycle that ran the command", windows, adapter.windows())
            pollCycle()
            assertEquals("the Car tab's windows carry on afterwards", windows + 1, adapter.windows())
        }

    @Test
    fun aCommandQueuedMidWindowCutsTheWindowShortAndIsSent() =
        session.whileLive {
            carTabOpen()
            val chunks = adapter.chunkMs.size
            var requested = false
            adapter.onMonitor = {
                if (!requested) {
                    requested = true
                    request(CarCommand.LOCK)
                }
            }
            pollCycle()
            assertTrue(requested)
            assertEquals("the 10 s window stops after its first 5 s chunk", chunks + 1, adapter.chunkMs.size)
            assertTrue("nothing sent from inside the listen window", adapter.transmits().isEmpty())
            pollCycle()
            val result = runner.lastResult()!!
            assertEquals(result.detail, "confirmed", result.outcome)
            assertSentOnlyAllowlistedFrames()
        }

    @Test
    fun aCommandGoesBeforeARequestedBodyTest() =
        session.whileLive {
            carTabOpen()
            listener.requestBodyTest(30_000L)
            request(CarCommand.LOCK)
            pollCycle()
            val result = runner.lastResult()!!
            assertEquals(result.detail, "confirmed", result.outcome)
            assertFalse("the body test waits for the next cycle", "body_test_start" in adapter.events)
            pollCycle()
            assertTrue("body_test_start" in adapter.events)
        }

    @Test
    fun aStaleSampleIsStillRefusedAndNothingIsSent() =
        session.whileLive {
            carTabOpen()
            request(CarCommand.LOCK)
            liveSample()
            // The loop stalls after the sample, past the gate's limit on sample age.
            now += CarControlGate.Policy().maxSampleAgeMs + 1
            session.afterSample(listener)
            val result = runner.lastResult()!!
            assertEquals("refused", result.outcome)
            assertEquals(CarControlGate.Block.NO_LIVE_DATA.message, result.detail)
            assertTrue(adapter.transmits().isEmpty())
            assertFalse(session.hasPendingCommand())
        }

    @Test
    fun aSlowStopPromptStillLeavesTimeToSendTheCommand() =
        session.whileLive {
            carTabOpen()
            // Every chunk's prompt comes only at the stop timeout, and the request lands just after
            // the window checked for one, as it starts switching to SW-CAN.
            adapter.stopMs = SwcanListenRunner.Policy().stopTimeoutMs
            var requested = false
            adapter.onSend = { command ->
                if (!requested && command == SwcanListenRunner.SETUP_COMMANDS.first()) {
                    requested = true
                    request(CarCommand.LOCK)
                }
            }
            pollCycle()
            assertTrue(requested)
            pollCycle()
            val result = runner.lastResult()!!
            assertEquals(result.detail, "confirmed", result.outcome)
            assertSentOnlyAllowlistedFrames()
        }

    @Test
    fun aQuietHsCycleAfterAWindowStillRefusesTheCommand() =
        session.whileLive {
            carTabOpen()
            adapter.onMonitor = {
                adapter.onMonitor = {}
                request(CarCommand.LOCK)
            }
            pollCycle()
            // The next HS read gets no answers, yet the carried-forward sample still looks fresh.
            pollCycle(answered = false)
            val result = runner.lastResult()!!
            assertEquals("refused", result.outcome)
            assertEquals(CarControlGate.Block.SWCAN_UNVERIFIED.message, result.detail)
            assertTrue(adapter.transmits().isEmpty())
            assertTrue("the listener re-initialized the adapter", "<reinit>" in adapter.commands)
            // HS answering again shows the window's restore had broken it: no car controls after that.
            pollCycle()
            assertEquals(CarControlGate.Adapter.STN_UNVERIFIED, listener.controlCapability())
        }

    @Test
    fun aReinitAfterACommandHoldsOffTheListenerForThatCycle() =
        session.whileLive {
            carTabOpen()
            request(CarCommand.LOCK)
            pollCycle()
            assertEquals("confirmed", runner.lastResult()!!.outcome)
            val chunks = adapter.chunkMs.size
            pollCycle(answered = false)
            assertEquals(listOf("<reinit>"), adapter.commands.filter { it == "<reinit>" })
            assertEquals("no window or quiet capture after the re-init", chunks, adapter.chunkMs.size)
            pollCycle()
            assertTrue("the Car tab's windows carry on afterwards", adapter.chunkMs.size > chunks)
        }

    /** Connects, lets the startup window hear the car, then opens the Car tab and runs a focus window. */
    private fun carTabOpen() {
        listener.probeAdapter()
        now += SwcanListenRunner.Policy().firstWindowDelayMs
        pollCycle() // the startup window
        assertEquals(CarControlGate.Adapter.READY, listener.controlCapability())
        pollCycle() // the first focus window
        assertEquals(2, adapter.windows())
        assertTrue(adapter.transmits().isEmpty())
    }

    /** The owner confirms [command] with the PIN dialog and the screen asks the engine to send it. */
    private fun request(command: CarCommand) {
        CarControlAuth.confirm(command, now)
        session.request(command)
        assertTrue(session.hasPendingCommand())
    }

    /**
     * One pass of the poll loop on a parked car: read, broadcast, the after-sample step, sleep. When
     * the HS read is not [answered], the loop still hands over a sample carrying the last values.
     */
    private fun pollCycle(answered: Boolean = true) {
        val sample = liveSample(answered)
        session.afterSample(listener)
        now += session.nextPollDelayMs(ObdPollingEngine.pollIntervalMs(sample))
    }

    /** An HS read, its sample handed to the gate; the Car tab keeps its lease. */
    private fun liveSample(answered: Boolean = true): JSONObject {
        listener.requestBodyFocus(FOCUS_LEASE_MS)
        now += HS_READ_MS
        if (answered) {
            adapter.liveCycles += 1
            adapter.liveAtMs = now
        }
        val sample =
            JSONObject()
                .put("speedKph", 0.0)
                .put("speedKphStaleMs", 0L)
                .put("prndlState", VoltGear.PARK)
                .put("prndlStateStaleMs", 0L)
                .put("vehicleState", "parked")
        session.appendTo(sample)
        return sample
    }

    private fun assertSentOnlyAllowlistedFrames() {
        val sent = adapter.transmits()
        assertFalse(sent.isEmpty())
        sent.forEach { assertTrue("non-allowlisted transmit: $it", CarControlFrames.isAllowedTransmit(it)) }
    }

    private companion object {
        /** Each adapter exchange; a window's switch to SW-CAN and back costs about 2 s. */
        const val SEND_MS = 150L

        /** The adapter's usual prompt after the stop byte. */
        const val STOP_MS = 500L

        /** One HS poll cycle's PID reads. */
        const val HS_READ_MS = 1_000L

        /** What the Car tab's pinger asks for. */
        const val FOCUS_LEASE_MS = 45_000L

        /** The 12 V monitor and a door-lock broadcast saying the doors are locked. */
        const val BROADCASTS = "10 24 80 40 00 00 60 D9 00 FC 00 00\r0C 41 40 40 00 01 00 07\rSTOPPED\r\r>"
    }
}
