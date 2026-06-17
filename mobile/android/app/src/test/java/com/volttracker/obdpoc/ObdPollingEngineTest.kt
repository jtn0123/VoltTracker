package com.volttracker.obdpoc

import android.bluetooth.BluetoothDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Behavior tests for [ObdPollingEngine]: the connect / init / poll / reconnect state machine that
 * drives a Bluetooth OBD session.
 *
 * The engine is exercised through three minimal seams added to production code:
 *
 * - [ObdPollingEngine] is no longer `final`, so this test subclasses it as [TestObdPollingEngine]
 *   and overrides [ObdPollingEngine.isBluetoothReady] and [ObdPollingEngine.openBluetoothSocket] to
 *   bypass the system `BluetoothAdapter`. That sidesteps a JaCoCo-vs-JVM-proxy instrumentation crash
 *   that hits the moment Robolectric's `ShadowBluetoothAdapter` touches a generated proxy on JDK 24.
 * - [ObdPollingEngine.setConnectionForTest] — swaps the real [ElmConnection] for a
 *   [FakeElmConnection] that scripts adapter responses instead of reading from an RFCOMM socket.
 * - [ElmConnection] is no longer `final`, so [FakeElmConnection] can subclass it and override
 *   [ElmConnection.open], [ElmConnection.transact], [ElmConnection.sendEscape] and
 *   [ElmConnection.close].
 *
 * The [ObdService] itself is brought up via [Robolectric.setupService], which calls `onCreate` so
 * the engine's collaborators (`recorder`, `activeName`, `running`, `ioLock`, etc.) are live and
 * observable.
 *
 * Tests targeting the live-poll loop inject a no-op sleeper, so they verify the sleep request
 * without spending wall-clock time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30]) // sdk < S so service.hasBluetoothConnectPermission() short-circuits to true.
class ObdPollingEngineTest {
    private lateinit var service: ObdService
    private lateinit var engine: TestObdPollingEngine
    private lateinit var fake: FakeElmConnection

    @Before
    fun setUp() {
        service = Robolectric.setupService(ObdService::class.java)
        // Match what ObdService.onStartCommand does for ACTION_CONNECT: name the adapter and
        // mark the session as just-started so sessionMs computations make sense.
        service.activeName = "Test Adapter"
        service.sessionStartedAtMs = System.currentTimeMillis()

        fake = FakeElmConnection()
        engine = TestObdPollingEngine(service, fake)
        engine.setConnectionForTest(fake)
    }

    @After
    fun tearDown() {
        service.running.set(false)
        // Drain the recorder so background lifecycle writes do not leak across tests.
        try {
            service.onDestroy()
        } catch (ignored: RuntimeException) {
            // onDestroy stops the foreground service which was never started here; safe.
        }
    }

    // ---- 1. Clean connect → 1 sample → clean close ---------------------------------

    @Test
    fun cleanConnectThenSingleSampleThenStop() {
        // Script every command the engine sends so transact() never throws and the prompt is
        // always present. After the first sample's *last* OBD command (ATSH7DF restores the
        // broadcast header) we flip running to false so the loop exits cleanly.
        fake.defaultResponse = ">" // ELM prompt-only reply is harmless for AT setup
        fake.responses["0100"] = "41 00 00 00 00 00>"
        fake.responses["ATRV"] = "13.8V\r>"
        fake.responses["010D"] = "41 0D 28\r>"
        fake.responses["010C"] = "41 0C 0F A0\r>"
        fake.afterCommand("ATSH7DF") { service.running.set(false) }

        openSession()
        runEngineUntilFinished { engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false) }

        // Engine opened a session, took at least one sample, then closed cleanly.
        assertTrue("engine should poll at least one sample before stop", engine.sampleCount() >= 1)
        assertTrue("openBluetoothSocket must have been called", engine.openCount.get() >= 1)
        assertTrue("ATZ must be issued during initializeElm327", fake.commandLog.contains("ATZ"))
        assertTrue("0100 capability probe must run during init", fake.commandLog.contains("0100"))
        assertTrue(
            "engine must restore the broadcast header (ATSH7DF) at end of cycle",
            fake.commandLog.contains("ATSH7DF"),
        )
        // closeSocket runs in the finally block of runBluetoothLoop.
        assertTrue("connection must be closed at session end", fake.closeCalls.get() >= 1)
    }

    @Test
    fun pinnedProtocolFastPathSkipsTheAutoSearch() {
        // Init now pins ATSP6 (the Volt's only CAN protocol) before the first 0100, so when the car
        // answers we never pay the ~4.8 s ATSP0 auto-search. Verify ATSP6 precedes 0100 and ATSP0 is
        // never sent on the happy path.
        fake.defaultResponse = ">"
        fake.responses["0100"] = "41 00 00 00 00 00>"
        fake.responses["ATRV"] = "13.8V\r>"
        fake.responses["010D"] = "41 0D 28\r>"
        fake.responses["010C"] = "41 0C 0F A0\r>"
        fake.afterCommand("ATSH7DF") { service.running.set(false) }

        openSession()
        runEngineUntilFinished { engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false) }

        assertTrue("ATSP6 pin must be issued during init", fake.commandLog.contains("ATSP6"))
        assertFalse(
            "ATSP0 auto-search must be skipped when the pinned protocol answers",
            fake.commandLog.contains("ATSP0"),
        )
        assertTrue(
            "ATSP6 must be sent before the 0100 capability probe",
            fake.commandLog.indexOf("ATSP6") < fake.commandLog.indexOf("0100"),
        )
        assertTrue("init must still complete and poll a sample", engine.sampleCount() >= 1)
    }

    @Test
    fun pinnedProtocolMissFallsBackToAutoSearch() {
        // If the pinned ATSP6 probe returns no 4100 answer (wrong adapter/vehicle, or NO DATA), init
        // must fall back to ATSP0 auto-detect and retry 0100 — connection robustness is unchanged,
        // only the happy-path latency improves.
        val zeroOneHundredCalls = AtomicInteger(0)
        fake.defaultResponse = ">"
        fake.responses["ATRV"] = "13.8V\r>"
        fake.responses["010D"] = "41 0D 28\r>"
        fake.responses["010C"] = "41 0C 0F A0\r>"
        fake.transactInterceptor =
            TransactInterceptor { command ->
                if (command == "0100") {
                    // First 0100 (right after the ATSP6 pin) returns NO DATA; the post-ATSP0 retry answers.
                    if (zeroOneHundredCalls.getAndIncrement() == 0) "NO DATA>" else "41 00 00 00 00 00>"
                } else {
                    null
                }
            }
        fake.afterCommand("ATSH7DF") { service.running.set(false) }

        openSession()
        runEngineUntilFinished { engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false) }

        assertTrue("ATSP6 pin is still attempted first", fake.commandLog.contains("ATSP6"))
        assertTrue("ATSP0 auto-search must run when the pin misses", fake.commandLog.contains("ATSP0"))
        assertTrue(
            "ATSP0 fallback must come after the first 0100 probe",
            fake.commandLog.indexOf("ATSP0") > fake.commandLog.indexOf("0100"),
        )
        assertTrue("init must recover and poll a sample after the fallback", engine.sampleCount() >= 1)
    }

    @Test
    fun openBluetoothSocketRejectsInvalidAddressAsIOException() {
        val realEngine = ObdPollingEngine(service)
        val ex =
            assertThrows(IOException::class.java) {
                realEngine.openBluetoothSocket("not-a-mac-address")
            }
        assertTrue(ex.message!!.contains("Invalid Bluetooth adapter address"))
    }

    // ---- 2. Mid-session drop → backoff → reconnect → session continues -------------

    @Test
    fun midSessionDropTriggersReconnectAndSessionContinues() {
        // First connect: poll once, then on the second ATRV throw IOException. The loop's catch
        // block flips into backoff and re-enters connectAndInitialize. Second connect: poll once
        // more, then stop. Session id must NOT change across the reconnect.
        fake.defaultResponse = ">"
        fake.responses["0100"] = "41 00 00 00 00 00>"
        fake.responses["ATRV"] = "13.7V\r>"
        fake.responses["010D"] = "41 0D 1E\r>"
        fake.responses["010C"] = "41 0C 0B B8\r>"

        val atrvCalls = AtomicInteger()
        fake.transactInterceptor =
            TransactInterceptor { command ->
                if ("ATRV" == command) {
                    val call = atrvCalls.incrementAndGet()
                    if (call == 2) {
                        throw IOException("simulated socket drop")
                    }
                }
                null // fall through to scripted responses
            }
        // After the second successful cycle's ATSH7DF, stop running so we exit cleanly.
        fake.afterCommand("ATSH7DF") {
            if (atrvCalls.get() >= 2) {
                service.running.set(false)
            }
        }

        openSession()
        val sessionIdDuringRun = service.recorder.activeSessionId()
        runEngineUntilFinished { engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false) }

        assertTrue("session must have been opened (id > 0)", sessionIdDuringRun > 0)
        assertTrue(
            "engine must reconnect after the drop (openBluetoothSocket called >= 2 times)," +
                " got " + engine.openCount.get(),
            engine.openCount.get() >= 2,
        )
        assertTrue(
            "engine must collect 2 samples across the reconnect, got " + engine.sampleCount(),
            engine.sampleCount() >= 2,
        )
        // Session id continuity: closeSession was NOT called between the drop and the next
        // open, so the recorder's active session id stayed the same throughout the run. We
        // verified above that it was non-zero during the run; assert no second openSession
        // happened by checking that the recorder did not bump the id mid-way.
        assertEquals(
            "session id must remain stable across mid-session drops",
            sessionIdDuringRun,
            engine.sessionIdSeenAtFirstReconnect.get(),
        )
    }

    // ---- 3. Never-connected timeout -------------------------------------------------

    @Test
    fun neverConnectedExhaustsRetriesWithoutOpeningASample() {
        // Every call to openBluetoothSocket throws — adapter unreachable. Once we've observed
        // enough attempts to know the engine is on the retry path, flip running to false so
        // the loop exits at the !service.running.get() check inside the catch block, *before*
        // sleeping the configured initial-connect backoff for the full duration.
        engine.openShouldThrow = true
        engine.afterOpen =
            Runnable {
                val n = engine.openCount.get()
                if (n >= 3) {
                    // Three failed attempts: enough to prove the retry loop is active.
                    service.running.set(false)
                }
            }

        openSession()
        runEngineUntilFinished { engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false) }

        assertTrue(
            "engine must retry connect at least 3 times, got " + engine.openCount.get(),
            engine.openCount.get() >= 3,
        )
        assertEquals(
            "no telemetry samples should be recorded when adapter never connects",
            0,
            engine.sampleCount(),
        )
        // No successful transact ever happened, so the command log should be empty.
        assertTrue(
            "no commands should have been transacted, got " + fake.commandLog,
            fake.commandLog.isEmpty(),
        )
    }

    // ---- 4. stop() mid-poll ---------------------------------------------------------

    @Test
    fun stopMidPollExitsCleanlyAndClosesSocket() {
        // Engine connects, starts polling. We flip running to false while the engine is partway
        // through reading a cycle: that lets pollUntilStoppedOrBroken finish the in-flight
        // readObdSample (sample 1), then exit on the next while-check.
        fake.defaultResponse = ">"
        fake.responses["0100"] = "41 00 00 00 00 00>"
        fake.responses["ATRV"] = "12.9V\r>"
        fake.responses["010D"] = "41 0D 32\r>"

        val firstSampleStarted = CountDownLatch(1)
        fake.afterCommand("ATRV") {
            // Signal we are inside a poll cycle, then trigger the stop.
            firstSampleStarted.countDown()
            service.running.set(false)
        }

        openSession()
        runEngineUntilFinished { engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false) }

        assertEquals(
            "test never observed the in-flight ATRV — engine did not enter poll loop",
            0L,
            firstSampleStarted.count,
        )
        // Engine should have completed the in-flight sample then bailed without taking
        // additional samples on the next iteration.
        assertEquals("expected exactly 1 in-flight sample to finish", 1, engine.sampleCount())
        assertTrue(
            "connection must be closed on stop (close called >= 1), got " + fake.closeCalls.get(),
            fake.closeCalls.get() >= 1,
        )
    }

    // ---- adaptive poll cadence (G3) -------------------------------------------------

    @Test
    fun pollCadenceRelaxesWhenParkedOrChargingAndStaysFastWhenDriving() {
        // Parked / charging / plugged are slow-changing -> the relaxed cadence.
        for (state in listOf("parked", "charging", "plugged")) {
            val sample = org.json.JSONObject().put("vehicleState", state)
            assertEquals(
                "$state must use the relaxed cadence",
                ObdPollingEngine.IDLE_POLL_INTERVAL_MS,
                ObdPollingEngine.pollIntervalMs(sample),
            )
        }
        // Driving (EV or gas) keeps the responsive cadence.
        for (state in listOf("driving_ev", "driving_gas")) {
            val sample = org.json.JSONObject().put("vehicleState", state)
            assertEquals(
                "$state must keep the fast cadence",
                ObdPollingEngine.DRIVE_POLL_INTERVAL_MS,
                ObdPollingEngine.pollIntervalMs(sample),
            )
        }
    }

    @Test
    fun pollCadenceStaysFastForReadyUnknownOrAbsentState() {
        // READY (engine on but stationary — about to move), unknown, and a missing state all stay
        // responsive: we only relax for clearly slow-changing states.
        assertEquals(
            ObdPollingEngine.DRIVE_POLL_INTERVAL_MS,
            ObdPollingEngine.pollIntervalMs(org.json.JSONObject().put("vehicleState", "ready")),
        )
        assertEquals(
            ObdPollingEngine.DRIVE_POLL_INTERVAL_MS,
            ObdPollingEngine.pollIntervalMs(org.json.JSONObject().put("vehicleState", "unknown")),
        )
        assertEquals(
            "a sample with no vehicleState defaults to the responsive cadence",
            ObdPollingEngine.DRIVE_POLL_INTERVAL_MS,
            ObdPollingEngine.pollIntervalMs(org.json.JSONObject()),
        )
    }

    @Test
    fun pollCadenceStaysFastWhenMovingEvenIfStateLabelSaysParked() {
        // A measurable road speed overrides a stale/disagreeing state label, so a car that's moving
        // is always polled responsively.
        val sample =
            org.json
                .JSONObject()
                .put("vehicleState", "parked")
                .put("speedKph", 40.0)
        assertEquals(
            ObdPollingEngine.DRIVE_POLL_INTERVAL_MS,
            ObdPollingEngine.pollIntervalMs(sample),
        )
    }

    @Test
    fun vehicleSleepGiveUpWaitsForTheIdleWindowAndSkipsActiveDriving() {
        // Below the idle window: never give up, even while parked.
        assertEquals(false, ObdPollingEngine.shouldEndForVehicleSleep(0L, "parked"))
        assertEquals(
            false,
            ObdPollingEngine.shouldEndForVehicleSleep(ObdPollingEngine.VEHICLE_SLEEP_GIVE_UP_MS - 1, "parked"),
        )
        // Past the window while parked / plugged / unknown / absent: end the asleep session.
        for (state in listOf("parked", "plugged", "charging", "unknown", "")) {
            assertEquals(
                "silent + $state past the window must end the session",
                true,
                ObdPollingEngine.shouldEndForVehicleSleep(ObdPollingEngine.VEHICLE_SLEEP_GIVE_UP_MS, state),
            )
        }
        // Never give up while the car still reports active driving, no matter how long the stall.
        for (state in listOf("driving_ev", "driving_gas", "ready")) {
            assertEquals(
                "$state must keep the session alive through a stall",
                false,
                ObdPollingEngine.shouldEndForVehicleSleep(ObdPollingEngine.VEHICLE_SLEEP_GIVE_UP_MS * 5, state),
            )
        }
    }

    @Test
    fun vehicleOffDisconnectNeedsAConnectedSessionAndAStoppedState() {
        for (state in listOf("parked", "plugged", "charging")) {
            assertEquals(
                "a drop while $state after connecting is the adapter sleeping, not a fault",
                true,
                ObdPollingEngine.isVehicleOffDisconnect(true, state),
            )
        }
        assertEquals(
            "a drop that never connected is a real connect failure",
            false,
            ObdPollingEngine.isVehicleOffDisconnect(false, "parked"),
        )
        assertEquals(
            "a drop while driving is a real mid-drive disconnect",
            false,
            ObdPollingEngine.isVehicleOffDisconnect(true, "driving_ev"),
        )
        assertEquals(false, ObdPollingEngine.isVehicleOffDisconnect(true, "unknown"))
    }

    @Test
    fun rawTranscriptIsCappedWithTruncationMarker() {
        val raw = StringBuilder()
        repeat(ObdPollingEngine.RAW_TRANSCRIPT_MAX_CHARS + 50) {
            raw.append('x')
        }

        val bounded = ObdPollingEngine.boundedRawTranscript(raw)

        assertTrue(
            "bounded raw transcript should include truncation marker",
            bounded.endsWith("... [truncated 50 chars]"),
        )
        assertEquals(
            ObdPollingEngine.RAW_TRANSCRIPT_MAX_CHARS + "... [truncated 50 chars]".length,
            bounded.length,
        )
    }

    // ---- 5. Init throws → treated as a connect failure ------------------------------

    @Test
    fun initFailureIsTreatedAsConnectFailureAndRetried() {
        // openBluetoothSocket() succeeds, but the very first init command (ATZ) throws —
        // simulating an adapter that accepts the socket then drops it during reset. The engine
        // catches IOException out of initializeElm327, increments attempt, and retries the
        // whole connect+init. We stop after a couple of attempts to keep the test fast.
        val atzCalls = AtomicInteger()
        fake.transactInterceptor =
            TransactInterceptor { command ->
                if ("ATZ" == command) {
                    val n = atzCalls.incrementAndGet()
                    if (n <= 2) {
                        throw IOException("simulated ELM hangup on $command")
                    }
                }
                null
            }
        fake.afterCommand("ATZ") {
            if (atzCalls.get() >= 2) {
                service.running.set(false)
            }
        }

        openSession()
        runEngineUntilFinished { engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false) }

        assertTrue(
            "engine should retry the connect+init at least twice, got open=" +
                engine.openCount.get(),
            engine.openCount.get() >= 2,
        )
        assertTrue(
            "ATZ should be attempted on each connect, got " + atzCalls.get(),
            atzCalls.get() >= 2,
        )
        assertEquals(
            "no telemetry sample should land before init succeeds",
            0,
            engine.sampleCount(),
        )
    }

    // ---- 6. Demo mode runs DemoPollingLoop without touching ElmConnection -----------

    @Test
    fun demoLoopEmitsSamplesWithoutUsingElmConnection() {
        // Demo mode is a synthetic stream. It must NOT poke the real adapter even by accident —
        // that would mean a refactor coupled the demo path back to BT IO.
        service.activeName = "Demo stream"
        openSession()

        // Stop the demo loop after one synthetic sample so the test stays fast. DemoPollingLoop
        // sleeps 1000 ms between samples and calls incrementSampleCount() at the top of each
        // cycle, so a watcher that flips running once sampleCount() > 0 gets us out after the
        // first sample's broadcast.
        val watcher =
            Thread(
                {
                    val deadline = System.currentTimeMillis() + POLL_WAIT_TIMEOUT_MS
                    while (System.currentTimeMillis() < deadline) {
                        if (engine.sampleCount() >= 1) {
                            service.running.set(false)
                            return@Thread
                        }
                        try {
                            Thread.sleep(20)
                        } catch (ex: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@Thread
                        }
                    }
                },
                "demo-stop-watcher",
            )
        watcher.isDaemon = true
        watcher.start()
        runEngineUntilFinished(engine::runDemoLoop)
        watcher.join(1000)

        assertTrue(
            "demo loop must emit at least 1 sample, got " + engine.sampleCount(),
            engine.sampleCount() >= 1,
        )
        assertEquals(
            "demo loop must never call openBluetoothSocket()",
            0,
            engine.openCount.get(),
        )
        assertTrue(
            "demo loop must never transact any AT/OBD commands, got " + fake.commandLog,
            fake.commandLog.isEmpty(),
        )
    }

    @Test
    fun tpmsScanSkipsKnownRejectedTpmsDiscoveryCommands() {
        fake.defaultResponse = ">"
        fake.responses["0100"] = "41 00 00 00 00 00>"
        fake.responses["ATRV"] = "13.8V\r>"

        openSession()
        runEngineUntilFinished { engine.runTpmsScanLoop("AA:BB:CC:DD:EE:FF") }

        assertFalse("rejected TPMS 7E0 header must not be selected", fake.commandLog.contains("ATSH7E0"))
        assertFalse("rejected TPMS receiver header must not be selected", fake.commandLog.contains("ATSH760"))
        assertFalse("rejected front-left TPMS candidate must not be probed", fake.commandLog.contains("22248E"))
        assertFalse("rejected receiver-slot TPMS candidate must not be probed", fake.commandLog.contains("224051"))
        assertFalse("TPMS-only scan must not read stored DTCs", fake.commandLog.contains("03"))
        assertFalse("TPMS-only scan must not read pending DTCs", fake.commandLog.contains("07"))
        assertFalse("TPMS-only scan must not read permanent DTCs", fake.commandLog.contains("0A"))
        assertFalse(
            "TPMS-only scan must not read freeze-frame index",
            fake.commandLog.contains("0200"),
        )
        assertTrue(
            "init pins CAN protocol 6 (ISO 15765-4) up front to skip the auto-search, even for a TPMS scan",
            fake.commandLog.contains("ATSP6"),
        )
        assertFalse(
            "TPMS-only scan must not cycle CAN protocol 7",
            fake.commandLog.contains("ATSP7"),
        )
        assertFalse(
            "TPMS-only scan must not cycle CAN protocol 8",
            fake.commandLog.contains("ATSP8"),
        )
    }

    // ---- Mode-01 multi-PID batching (probe + per-cycle batch + per-adapter fallback) ----

    @Test
    fun mode01BatchProbeRunsDuringInit() {
        fake.defaultResponse = ">"
        fake.responses["0100"] = "41 00 00 00 00 00>"
        fake.responses["010D0C"] = "41 0D 28 41 0C 0F A0\r>"
        fake.responses["010D0C49"] = "41 0D 28 41 0C 0F A0 41 49 7F\r>"
        fake.afterCommand("ATSH7DF") { service.running.set(false) }

        openSession()
        runEngineUntilFinished { engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false) }

        assertTrue(
            "the multi-PID capability probe (010D0C) must run during init",
            fake.commandLog.contains("010D0C"),
        )
    }

    @Test
    fun liveConnectReadsVinDuringInit() {
        fake.defaultResponse = ">"
        fake.responses["0100"] = "41 00 00 00 00 00>"
        fake.responses["0902"] = mode09VinResponse("1G1ZD5ST8JF202020")
        fake.afterCommand("ATSH7DF") { service.running.set(false) }

        openSession()
        runEngineUntilFinished { engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false) }

        assertTrue(
            "live init must request VIN via Mode 09 PID 02",
            fake.commandLog.contains("0902"),
        )
        assertEquals("…2020", engine.redactedVin())
    }

    @Test
    fun liveConnectSkipsVinProbeWhenVehicleIsAlreadyStored() {
        fake.defaultResponse = ">"
        fake.responses["0100"] = "41 00 00 00 00 00>"
        fake.afterCommand("ATSH7DF") { service.running.set(false) }
        service.localStore!!.upsertVehicleFromVin("1G1ZD5ST8JF202020")

        openSession()
        runEngineUntilFinished { engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false) }

        assertFalse(
            "stored vehicle identity should skip another 0902 VIN probe",
            fake.commandLog.contains("0902"),
        )
        assertEquals("…2020", engine.redactedVin())
    }

    @Test
    fun supportedAdapterPollsHotLaneAsOneBatchedCommand() {
        fake.defaultResponse = ">"
        fake.responses["0100"] = "41 00 00 00 00 00>"
        // Probe returns both PIDs -> batching is supported for the session.
        fake.responses["010D0C"] = "41 0D 28 41 0C 0F A0\r>"
        fake.responses["010D0C49"] = "41 0D 28 41 0C 0F A0 41 49 7F\r>"
        fake.afterCommand("ATSH7DF") { service.running.set(false) }

        openSession()
        runEngineUntilFinished { engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false) }

        assertTrue(
            "hot-lane PIDs must be polled as one batched command when the adapter supports it",
            fake.commandLog.contains("010D0C49"),
        )
        assertFalse(
            "a batched cycle must not also send the individual speed PID",
            fake.commandLog.contains("010D"),
        )
        assertFalse(
            "a batched cycle must not also send the individual RPM PID",
            fake.commandLog.contains("010C"),
        )
    }

    @Test
    fun unsupportedAdapterFallsBackToPerPidPolling() {
        fake.defaultResponse = ">"
        fake.responses["0100"] = "41 00 00 00 00 00>"
        // Probe reply is missing RPM (0C), so batching stays disabled for the session.
        fake.responses["010D0C"] = "41 0D 28\r>"
        fake.responses["010D"] = "41 0D 28\r>"
        fake.responses["010C"] = "41 0C 0F A0\r>"
        fake.afterCommand("ATSH7DF") { service.running.set(false) }

        openSession()
        runEngineUntilFinished { engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false) }

        assertFalse(
            "an incomplete probe must disable batching",
            fake.commandLog.contains("010D0C49"),
        )
        assertTrue("fallback must poll speed per-PID", fake.commandLog.contains("010D"))
        assertTrue("fallback must poll RPM per-PID", fake.commandLog.contains("010C"))
    }

    // ---- helpers --------------------------------------------------------------------

    /** Mirrors what ObdService does before submitting runBluetoothLoop / runDemoLoop. */
    private fun openSession() {
        service.running.set(true)
        engine.beginSession("")
        service.recorder.openSession(
            "obd",
            "AA:BB:CC:DD:EE:FF",
            service.activeName,
            service.sessionStartedAtMs,
        )
    }

    /**
     * Runs the engine entrypoint on a worker thread (so the test thread can flip `running` to false)
     * and blocks until it returns or [ENGINE_JOIN_TIMEOUT_MS] elapses.
     */
    private fun runEngineUntilFinished(engineEntrypoint: Runnable) {
        val worker = Thread(engineEntrypoint, "engine-test-worker")
        worker.isDaemon = true
        worker.start()
        worker.join(ENGINE_JOIN_TIMEOUT_MS)
        if (worker.isAlive) {
            // Belt-and-braces: force the loop's exit condition and try one more time.
            service.running.set(false)
            worker.interrupt()
            worker.join(2_000L)
            if (worker.isAlive) {
                fail("engine worker did not exit within $ENGINE_JOIN_TIMEOUT_MS ms")
            }
        }
    }

    private companion object {
        /** Cap on how long any single test will wait for the engine worker thread to finish. */
        private const val ENGINE_JOIN_TIMEOUT_MS = 10_000L

        /** Cap on how long we'll spin waiting for a counter / latch in a test. */
        private const val POLL_WAIT_TIMEOUT_MS = 8_000L

        private fun mode09VinResponse(vin: String): String {
            val hex = StringBuilder("490201")
            for (c in vin.toCharArray()) {
                hex.append("%02X".format(c.code))
            }
            return hex.append("\r>").toString()
        }
    }

    /**
     * Subclass of [ObdPollingEngine] that overrides the two BT-specific seams ([isBluetoothReady],
     * [openBluetoothSocket]) so the engine's connect / init / poll / reconnect logic can run without
     * a real `BluetoothAdapter`.
     */
    private class TestObdPollingEngine(
        private val testService: EngineHost,
        val scriptedConnection: FakeElmConnection,
    ) : ObdPollingEngine(testService, LoopSleeper { true }) {
        val openCount = AtomicInteger()

        // Session ids are long on the recorder; use AtomicLong so the comparison stays
        // exact even if the row id grows past Integer.MAX_VALUE in a long-running suite.
        val sessionIdSeenAtFirstReconnect = AtomicLong()

        @Volatile
        var openShouldThrow = false

        @Volatile
        var afterOpen: Runnable? = null

        override fun isBluetoothReady(): Boolean = true // bypass the real BluetoothAdapter — see file-level docs

        override fun openBluetoothSocket(address: String?) {
            val n = openCount.incrementAndGet()
            // Record the active session id on the first reconnect (the second open) so the
            // mid-session-drop test can assert that the session id did NOT change.
            if (n == 2) {
                sessionIdSeenAtFirstReconnect.set(testService.recorder.activeSessionId())
            }
            afterOpen?.run()
            if (openShouldThrow) {
                throw IOException("fake openBluetoothSocket refusing")
            }
            // No-op: the engine's connection field is already pointing at the scripted fake.
        }
    }

    /**
     * In-memory stand-in for [ElmConnection] that scripts adapter responses and counts lifecycle
     * calls so tests can assert against the engine's observable behavior.
     */
    private class FakeElmConnection : ElmConnection() {
        /** Number of times [close] has been called. */
        val closeCalls = AtomicInteger()

        /** Every command passed to [transact], in order. */
        val commandLog: MutableList<String> = Collections.synchronizedList(ArrayList())

        /** Scripted exact-match responses, keyed on the trimmed command string. */
        val responses = LinkedHashMap<String, String>()

        /** Response used when no entry matches in [responses]. Default: ELM prompt only. */
        @Volatile
        var defaultResponse = ">"

        /**
         * Optional pre-transact hook. If it returns non-null, that string is the response. If it
         * throws IOException, transact() propagates the throw. Returning null falls through to
         * [responses] / [defaultResponse].
         */
        @Volatile
        var transactInterceptor: TransactInterceptor? = null

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
            // No-op: TestObdPollingEngine.openBluetoothSocket overrides the whole open path so
            // this method is never reached in these tests. Kept here to satisfy the surface.
        }

        override fun wakeNudge(toleranceMs: Long): WakeNudgeResult {
            // The fake doesn't run a real RFCOMM stream, so the wake-nudge probe has nothing to
            // read. Pretend the adapter answered immediately so the engine progresses straight
            // into initializeElm327() and the connect / poll / reconnect logic under test runs.
            return WakeNudgeResult(0L, true)
        }

        override fun transact(
            command: String,
            timeoutMs: Long,
            keepWaiting: KeepWaiting,
        ): String {
            commandLog.add(command)
            val interceptor = transactInterceptor
            val intercepted = interceptor?.handle(command)
            val response: String =
                if (intercepted != null) {
                    intercepted
                } else if (responses.containsKey(command)) {
                    responses[command]!!
                } else {
                    defaultResponse
                }
            afterCommandHooks[command]?.run()
            return response
        }

        override fun sendEscape(settleMs: Long) {
            // No-op: production code only sends ESC to recover a hung ELM prompt; the fake
            // never gets hung.
        }

        override fun close() {
            closeCalls.incrementAndGet()
        }
    }

    /**
     * Functional interface for [FakeElmConnection.transactInterceptor]. Defined here rather than as
     * `java.util.function.Function<String, String>` so it can declare `throws IOException` —
     * Function<> cannot.
     */
    private fun interface TransactInterceptor {
        @Throws(IOException::class)
        fun handle(command: String): String?
    }
}
