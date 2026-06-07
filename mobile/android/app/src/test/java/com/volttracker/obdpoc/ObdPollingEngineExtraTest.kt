package com.volttracker.obdpoc

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Extra behavior tests for [ObdPollingEngine], covering methods the existing
 * [ObdPollingEngineTest] / [ObdPollingEngineBackoffTest] suites do not reach:
 *
 * - [ObdPollingEngine.isBluetoothReady] — exercised against the *real* production method (not the
 *   test override) by toggling the Robolectric `ShadowBluetoothAdapter`.
 * - [ObdPollingEngine.sampleGapCount] / [ObdPollingEngine.backgroundSampleCount] — driven through
 *   [ObdPollingEngine.appendSessionHealth], the public [LiveSampleReader.SampleContext] seam the
 *   live/demo loops use to fold a sample into the session-health counters.
 * - [ObdPollingEngine.sendEscape] (private) — reached via the public
 *   [ObdPollingEngine.sendRecoverableCommand], which sends the ELM escape sequence whenever a
 *   command comes back without the `>` prompt.
 * - [ObdPollingEngine.runDetailProbeLoop] — driven one full pass through the existing fake-connection
 *   harness; the observable effect is the detail-probe commands landing in the fake's command log.
 *
 * Mirrors the harness style of [ObdPollingEngineTest]: a [TestObdPollingEngine] subclass overrides
 * the two Bluetooth seams so the connect / init logic runs without a real socket, and a
 * [FakeElmConnection] scripts adapter responses. This file's fake additionally counts
 * [ElmConnection.sendEscape] calls so the escape path is observable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30]) // sdk < S so service.hasBluetoothConnectPermission() short-circuits to true.
class ObdPollingEngineExtraTest {
    private lateinit var service: ObdService
    private lateinit var engine: TestObdPollingEngine
    private lateinit var fake: FakeElmConnection

    @Before
    fun setUp() {
        service = Robolectric.setupService(ObdService::class.java)
        service.activeName = "Test Adapter"
        service.sessionStartedAtMs = System.currentTimeMillis()

        fake = FakeElmConnection()
        engine = TestObdPollingEngine(service)
        engine.setConnectionForTest(fake)
    }

    @After
    fun tearDown() {
        service.running.set(false)
        try {
            service.onDestroy()
        } catch (ignored: RuntimeException) {
            // onDestroy stops a foreground service that was never started here; safe.
        }
    }

    // ---- isBluetoothReady ----------------------------------------------------------

    @Test
    fun isBluetoothReadyTracksAdapterEnabledState() {
        // Use the *real* engine (not TestObdPollingEngine, which hard-codes the override) so the
        // production isBluetoothReady() body — BluetoothAdapters.get(...) + adapter.isEnabled — runs.
        val realEngine = ObdPollingEngine(service)
        val adapterShadow = shadowOf(BluetoothAdapter.getDefaultAdapter())

        adapterShadow.setEnabled(true)
        assertTrue("an enabled adapter must report Bluetooth ready", realEngine.isBluetoothReady())

        adapterShadow.setEnabled(false)
        assertFalse("a disabled adapter must report Bluetooth not ready", realEngine.isBluetoothReady())
    }

    // ---- sampleGapCount / backgroundSampleCount ------------------------------------

    @Test
    fun sampleGapCountIncrementsOnlyWhenInterSampleGapExceedsThreshold() {
        // SessionHealthTracker keys gap detection off each sample's "updatedAt" timestamp and the
        // 6 s live-mode expected gap. First sample seeds lastSampleAtMs (no gap recorded). A second
        // sample 6.5 s later overshoots the threshold and must bump the gap counter; a third sample
        // 1 s after that is well within budget and must not.
        openLiveSession()
        val t0 = 1_000_000L

        engine.appendSessionHealth(sampleAt(t0))
        assertEquals("the first sample establishes the baseline, no gap yet", 0, engine.sampleGapCount())

        engine.appendSessionHealth(sampleAt(t0 + 6_500L))
        assertEquals("a 6.5 s inter-sample gap exceeds the 6 s live budget", 1, engine.sampleGapCount())

        engine.appendSessionHealth(sampleAt(t0 + 7_500L))
        assertEquals("a 1 s follow-up gap is within budget and must not count", 1, engine.sampleGapCount())
    }

    @Test
    fun backgroundSampleCountCountsOnlySamplesTakenWhileBackgrounded() {
        // appInForeground is the only gate on the background counter. A foreground sample must not
        // count; flipping appInForeground=false makes subsequent samples count as background.
        openLiveSession()

        service.appInForeground = true
        engine.appendSessionHealth(sampleAt(2_000_000L))
        assertEquals("a foreground sample must not be counted as background", 0, engine.backgroundSampleCount())

        service.appInForeground = false
        engine.appendSessionHealth(sampleAt(2_001_000L))
        engine.appendSessionHealth(sampleAt(2_002_000L))
        assertEquals("two backgrounded samples must both be counted", 2, engine.backgroundSampleCount())
    }

    @Test
    fun beginSessionResetsTheHealthCounters() {
        // beginSession() must zero the counters so a fresh session does not inherit the previous
        // session's gap / background tallies.
        openLiveSession()
        service.appInForeground = false
        engine.appendSessionHealth(sampleAt(3_000_000L))
        engine.appendSessionHealth(sampleAt(3_010_000L)) // +10 s -> also a gap
        assertTrue("precondition: counters should be non-zero before reset", engine.backgroundSampleCount() >= 1)
        assertTrue("precondition: a gap should have been recorded before reset", engine.sampleGapCount() >= 1)

        engine.beginSession("")

        assertEquals("beginSession must reset the background sample counter", 0, engine.backgroundSampleCount())
        assertEquals("beginSession must reset the sample-gap counter", 0, engine.sampleGapCount())
    }

    // ---- sendEscape (via sendRecoverableCommand) -----------------------------------

    @Test
    fun sendRecoverableCommandSendsEscapeWhenResponseHasNoElmPrompt() {
        // A reply missing the '>' prompt means the ELM may be wedged mid-line, so the engine
        // recovers by sending the escape sequence. The fake records each sendEscape settle time.
        fake.responses["ATI"] = "ELM327 v1.5 NODATA" // no '>' prompt
        openLiveSession()

        val response = engine.sendRecoverableCommand("ATI", 1800)

        assertEquals("the scripted no-prompt response must be returned verbatim", "ELM327 v1.5 NODATA", response)
        assertEquals("a prompt-less response must trigger exactly one escape recovery", 1, fake.escapeCalls.get())
        assertEquals("the recovery escape must use the production 700 ms settle", 700L, fake.lastEscapeSettleMs.get())
    }

    @Test
    fun sendRecoverableCommandDoesNotSendEscapeWhenPromptIsPresent() {
        // A well-formed reply (with '>') needs no recovery, so the escape sequence must not be sent.
        fake.responses["ATI"] = "ELM327 v1.5\r>"
        openLiveSession()

        engine.sendRecoverableCommand("ATI", 1800)

        assertEquals("a prompt-terminated response must not trigger an escape", 0, fake.escapeCalls.get())
    }

    // ---- runDetailProbeLoop --------------------------------------------------------

    @Test
    fun runDetailProbeLoopRunsTheEnhancedDiscoveryPass() {
        // runDetailProbeLoop -> runBluetoothLoop(..., detailProbeStage) which, once connected and
        // initialized, hands off to TpmsDiscoveryRunner for the requested stage. That runner always
        // probes ATI/ATDP/ATDPN/ATRV and restores the broadcast header with ATSH7DF. We stop the
        // engine on that final ATSH7DF so the worker exits cleanly.
        fake.defaultResponse = ">"
        fake.responses["0100"] = "41 00 00 00 00 00>"
        fake.responses["ATI"] = "ELM327 v1.5\r>"
        fake.responses["ATDP"] = "AUTO, ISO 15765\r>"
        fake.responses["ATDPN"] = "A6\r>"
        fake.responses["ATRV"] = "13.8V\r>"
        fake.afterCommand("ATSH7DF") { service.running.set(false) }

        openLiveSession()
        runEngineUntilFinished { engine.runDetailProbeLoop("AA:BB:CC:DD:EE:FF", EnhancedPidProfiles.STAGE_TIRES) }

        assertTrue("detail probe must open the adapter socket", engine.openCount.get() >= 1)
        assertTrue("detail probe must run the adapter-identity probe (ATI)", fake.commandLog.contains("ATI"))
        assertTrue("detail probe must read the active protocol (ATDP)", fake.commandLog.contains("ATDP"))
        assertTrue("detail probe must read the protocol number (ATDPN)", fake.commandLog.contains("ATDPN"))
        assertTrue("detail probe must restore the broadcast header (ATSH7DF)", fake.commandLog.contains("ATSH7DF"))
        assertFalse("a detail probe must not read stored DTCs", fake.commandLog.contains("03"))
    }

    // ---- helpers --------------------------------------------------------------------

    /** Mirrors what [ObdService] does before submitting a live/probe loop. */
    private fun openLiveSession() {
        service.running.set(true)
        engine.beginSession("")
        service.recorder.openSession(
            "obd",
            "AA:BB:CC:DD:EE:FF",
            service.activeName,
            service.sessionStartedAtMs,
        )
    }

    /** A minimal telemetry sample carrying the timestamp [SessionHealthTracker] keys gaps off of. */
    private fun sampleAt(updatedAtMs: Long): JSONObject = JSONObject().put("updatedAt", updatedAtMs)

    /**
     * Runs the engine entrypoint on a worker thread (so the test thread can flip `running` to false)
     * and blocks until it returns or [ENGINE_JOIN_TIMEOUT_MS] elapses.
     */
    private fun runEngineUntilFinished(engineEntrypoint: Runnable) {
        val worker = Thread(engineEntrypoint, "engine-extra-test-worker")
        worker.isDaemon = true
        worker.start()
        worker.join(ENGINE_JOIN_TIMEOUT_MS)
        if (worker.isAlive) {
            service.running.set(false)
            worker.interrupt()
            worker.join(2_000L)
            if (worker.isAlive) {
                fail("engine worker did not exit within $ENGINE_JOIN_TIMEOUT_MS ms")
            }
        }
    }

    private companion object {
        private const val ENGINE_JOIN_TIMEOUT_MS = 10_000L
    }

    /**
     * Subclass of [ObdPollingEngine] that overrides the Bluetooth seams ([isBluetoothReady],
     * [openBluetoothSocket]) so the connect / init logic runs without a real `BluetoothAdapter`.
     * Identical in spirit to the harness in [ObdPollingEngineTest].
     */
    private class TestObdPollingEngine(
        testService: EngineHost,
    ) : ObdPollingEngine(testService) {
        val openCount = AtomicInteger()

        override fun isBluetoothReady(): Boolean = true // bypass the real BluetoothAdapter for loop tests

        override fun openBluetoothSocket(address: String?) {
            openCount.incrementAndGet()
            // No-op: the engine's connection field already points at the scripted fake.
        }
    }

    /**
     * In-memory stand-in for [ElmConnection] that scripts adapter responses, counts
     * [ElmConnection.sendEscape] invocations, and logs every transacted command.
     */
    private class FakeElmConnection : ElmConnection() {
        /** Number of times [sendEscape] has been called. */
        val escapeCalls = AtomicInteger()

        /** Settle time passed to the most recent [sendEscape] call. */
        val lastEscapeSettleMs = AtomicLong()

        /** Every command passed to [transact], in order. */
        val commandLog: MutableList<String> = Collections.synchronizedList(ArrayList())

        /** Scripted exact-match responses, keyed on the trimmed command string. */
        val responses = LinkedHashMap<String, String>()

        /** Response used when no entry matches in [responses]. Default: ELM prompt only. */
        @Volatile
        var defaultResponse = ">"

        /** After-command hooks, one per command (the last registration wins per command). */
        private val afterCommandHooks = LinkedHashMap<String, Runnable>()

        fun afterCommand(
            command: String,
            hook: Runnable,
        ) {
            afterCommandHooks[command] = hook
        }

        override fun `open`(
            device: BluetoothDevice,
            uuid: UUID,
            connectTimeoutMs: Long,
        ) {
            // No-op: TestObdPollingEngine.openBluetoothSocket overrides the whole open path.
        }

        override fun wakeNudge(toleranceMs: Long): WakeNudgeResult = WakeNudgeResult(0L, true)

        override fun transact(
            command: String,
            timeoutMs: Long,
            keepWaiting: KeepWaiting,
        ): String {
            commandLog.add(command)
            val response = if (responses.containsKey(command)) responses[command]!! else defaultResponse
            afterCommandHooks[command]?.run()
            return response
        }

        override fun sendEscape(settleMs: Long) {
            escapeCalls.incrementAndGet()
            lastEscapeSettleMs.set(settleMs)
        }

        override fun close() {
            // No-op: this suite does not assert on close lifecycle.
        }
    }
}
