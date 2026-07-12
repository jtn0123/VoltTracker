package com.volttracker.obdpoc

import android.bluetooth.BluetoothDevice
import com.volttracker.obdpoc.engine.ElmConnection
import com.volttracker.obdpoc.engine.EngineHost
import com.volttracker.obdpoc.engine.ObdPollingEngine
import com.volttracker.obdpoc.service.ObdService
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
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Behavior tests for the B3 extended reconnect tier: after the fast reconnect budget
 * ([ObdProbes.MAX_RECONNECT_ATTEMPTS]) exhausts mid-drive, [ObdPollingEngine] must keep trying on
 * the low-power [ExtendedReconnectTier] cadence instead of permanently giving up (silent data
 * loss), while non-driving exhaustion keeps the pre-B3 prompt stop.
 *
 * Mirrors the [ObdPollingEngineTest] harness: a [TestObdPollingEngine] subclass bypasses the two
 * Bluetooth seams and a [FakeElmConnection] scripts adapter responses; tests inject an
 * [ExtendedReconnectTier] with a short interval / controlled window so no test sleeps a real
 * extended interval.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30]) // sdk < S so service.hasBluetoothConnectPermission() short-circuits to true.
class ObdPollingEngineExtendedReconnectTest {
    private lateinit var service: ObdService
    private lateinit var fake: FakeElmConnection

    @Before
    fun setUp() {
        service = Robolectric.setupService(ObdService::class.java)
        service.activeName = "Test Adapter"
        service.sessionStartedAtMs = System.currentTimeMillis()
        fake = FakeElmConnection()
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

    // ---- exhaustion during driving enters the extended tier -------------------------

    @Test
    fun midDriveExhaustionEntersExtendedTierAndKeepsRetrying() {
        val engine = newEngine(ExtendedReconnectTier(intervalMs = 1L, windowMs = 900_000L))
        scriptDrivingSamples()
        val dropped = AtomicBoolean(false)
        val cycles = AtomicInteger(0)
        fake.afterCommand("ATSH7DF") {
            if (cycles.incrementAndGet() >= 2) {
                dropped.set(true)
            }
        }
        fake.transactInterceptor =
            TransactInterceptor { _ ->
                if (dropped.get()) {
                    throw IOException("simulated tunnel drop")
                }
                null
            }
        // Old behavior gave up for good at MAX+1 opens; require clearly more before stopping.
        val targetOpens = ObdProbes.MAX_RECONNECT_ATTEMPTS + 5
        engine.afterOpen =
            Runnable {
                if (engine.openCount.get() >= targetOpens) {
                    service.running.set(false)
                }
            }

        openSession(engine)
        runEngineUntilFinished { engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false) }

        assertTrue("engine must record driving samples before the drop", engine.sampleCount() >= 2)
        assertTrue(
            "extended tier must keep attempting past the fast budget " +
                "(opens=${engine.openCount.get()}, old cap=${ObdProbes.MAX_RECONNECT_ATTEMPTS + 1})",
            engine.openCount.get() >= targetOpens,
        )
        val log = latestObdLogText()
        assertTrue("extended tier entry must be logged", log.contains("\"event\":\"extended_reconnect_started\""))
        assertTrue("extended attempts must be logged", log.contains("\"event\":\"extended_reconnect_attempt\""))
        assertTrue(
            "the foreground notification must reflect the waiting-to-reconnect state",
            log.contains("Waiting to reconnect to Test Adapter..."),
        )
        assertFalse(
            "the session must not report permanent exhaustion while the extended window is open",
            log.contains("\"event\":\"reconnect_exhausted\""),
        )
    }

    // ---- the extended tier gives up cleanly after its window ------------------------

    @Test
    fun extendedTierGivesUpAfterTheWindowWithTheExistingExhaustionReporting() {
        // A zero-length window expires immediately after entry, so the very first extended-tier
        // check reports exhaustion; the window arithmetic itself is covered by the fake-clock
        // ExtendedReconnectTierTest.
        val engine = newEngine(ExtendedReconnectTier(intervalMs = 1L, windowMs = 0L))
        scriptDrivingSamples()
        val dropped = AtomicBoolean(false)
        val cycles = AtomicInteger(0)
        fake.afterCommand("ATSH7DF") {
            if (cycles.incrementAndGet() >= 2) {
                dropped.set(true)
            }
        }
        fake.transactInterceptor =
            TransactInterceptor { _ ->
                if (dropped.get()) {
                    throw IOException("simulated tunnel drop")
                }
                null
            }

        openSession(engine)
        runEngineUntilFinished { engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false) }

        assertEquals(
            "an expired extended window must not attempt beyond the fast budget",
            ObdProbes.MAX_RECONNECT_ATTEMPTS + 1,
            engine.openCount.get(),
        )
        assertFalse("the session must be stopped after the window expires", service.running.get())
        val log = latestObdLogText()
        assertTrue(log.contains("\"event\":\"extended_reconnect_started\""))
        assertTrue(
            "window expiry must be logged before the exhaustion report",
            log.contains("\"event\":\"extended_reconnect_exhausted\""),
        )
        assertTrue(
            "the existing exhaustion reporting must still run",
            log.contains("\"event\":\"reconnect_exhausted\""),
        )
        assertTrue(
            log.contains(
                "Lost the adapter link and could not reconnect after ${ObdProbes.MAX_RECONNECT_ATTEMPTS} tries.",
            ),
        )
    }

    // ---- an ACL wake-up triggers an immediate attempt --------------------------------

    @Test
    fun requestImmediateRetryWakesTheExtendedWaitForAnImmediateAttempt() {
        // Real 60s interval: without the ACL-style wake-up each extended attempt would sleep a
        // minute and the engine could never reach the target open count inside the test timeout.
        val engine =
            newEngine(
                ExtendedReconnectTier(
                    intervalMs = ExtendedReconnectTier.RETRY_INTERVAL_MS,
                    windowMs = 900_000L,
                ),
            )
        scriptDrivingSamples()
        val dropped = AtomicBoolean(false)
        val cycles = AtomicInteger(0)
        fake.afterCommand("ATSH7DF") {
            if (cycles.incrementAndGet() >= 2) {
                dropped.set(true)
            }
        }
        fake.transactInterceptor =
            TransactInterceptor { _ ->
                if (dropped.get()) {
                    throw IOException("simulated tunnel drop")
                }
                null
            }
        val targetOpens = ObdProbes.MAX_RECONNECT_ATTEMPTS + 4
        engine.afterOpen =
            Runnable {
                if (engine.openCount.get() >= targetOpens) {
                    service.running.set(false)
                }
            }
        // Simulates the service-lifetime ACL-connected hook: keep signalling "the adapter is
        // back" so every extended wait ends immediately.
        val signaller =
            Thread({
                while (service.running.get()) {
                    engine.requestImmediateRetry()
                    try {
                        Thread.sleep(10L)
                    } catch (ex: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            }, "acl-signaller")
        signaller.isDaemon = true

        openSession(engine)
        signaller.start()
        runEngineUntilFinished { engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false) }
        signaller.join(1_000L)

        assertTrue(
            "ACL wake-ups must drive immediate extended attempts despite the 60s interval " +
                "(opens=${engine.openCount.get()})",
            engine.openCount.get() >= targetOpens,
        )
        val log = latestObdLogText()
        assertTrue(log.contains("\"event\":\"extended_reconnect_started\""))
        assertTrue(log.contains("\"event\":\"extended_reconnect_attempt\""))
    }

    // ---- RECOVERY: a successful reconnect while the tier is armed --------------------

    @Test
    fun reconnectSuccessWhileTierIsArmedLogsRecoveryDisarmsAndKeepsSampling() {
        val tier = ExtendedReconnectTier(intervalMs = 1L, windowMs = 900_000L)
        val engine = newEngine(tier)
        scriptDrivingSamples()
        val dropped = AtomicBoolean(false)
        val cycles = AtomicInteger(0)
        fake.afterCommand("ATSH7DF") {
            val n = cycles.incrementAndGet()
            if (n == 2) {
                dropped.set(true) // tunnel drop after two live cycles
            }
            if (n >= 4) {
                service.running.set(false) // two more post-recovery cycles, then stop cleanly
            }
        }
        fake.transactInterceptor =
            TransactInterceptor { _ ->
                if (dropped.get()) {
                    throw IOException("simulated tunnel drop")
                }
                null
            }
        // Let the fast budget exhaust (arming the tier) and a couple of extended attempts run,
        // then the adapter comes back: from this open on, the scripted connect succeeds.
        engine.afterOpen =
            Runnable {
                if (engine.openCount.get() >= ObdProbes.MAX_RECONNECT_ATTEMPTS + 3) {
                    dropped.set(false)
                }
            }

        openSession(engine)
        runEngineUntilFinished { engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false) }

        val log = latestObdLogText()
        assertTrue(
            "the tier must have armed before the recovery",
            log.contains("\"event\":\"extended_reconnect_started\""),
        )
        assertTrue(
            "a successful reconnect while the tier is armed must log the recovery event",
            log.contains("\"event\":\"extended_reconnect_recovered\""),
        )
        assertFalse("the recovery must disarm the extended tier", tier.active)
        assertEquals("the recovery must clear the tier's attempt counter", 0, tier.attemptCount)
        assertTrue(
            "the session must keep sampling after the recovery (got ${engine.sampleCount()})",
            engine.sampleCount() >= 3,
        )
        assertFalse(
            "a recovered session must not report exhaustion",
            log.contains("\"event\":\"reconnect_exhausted\""),
        )
    }

    @Test
    fun aSecondDropAfterRecoveryReArmsTheExtendedTierCleanly() {
        val tier = ExtendedReconnectTier(intervalMs = 1L, windowMs = 900_000L)
        val engine = newEngine(tier)
        scriptDrivingSamples()
        val dropped = AtomicBoolean(false)
        val cycles = AtomicInteger(0)
        fake.afterCommand("ATSH7DF") {
            val n = cycles.incrementAndGet()
            if (n == 2 || n == 4) {
                dropped.set(true) // first drop after cycle 2, second after cycle 4
            }
        }
        fake.transactInterceptor =
            TransactInterceptor { _ ->
                if (dropped.get()) {
                    throw IOException("simulated tunnel drop")
                }
                null
            }
        val firstRecoveryAtOpens = ObdProbes.MAX_RECONNECT_ATTEMPTS + 3
        val stopAtOpens = firstRecoveryAtOpens + ObdProbes.MAX_RECONNECT_ATTEMPTS + 4
        engine.afterOpen =
            Runnable {
                val opens = engine.openCount.get()
                if (opens == firstRecoveryAtOpens) {
                    dropped.set(false) // first recovery: the adapter is back
                }
                if (opens >= stopAtOpens) {
                    service.running.set(false) // stop mid-way through the SECOND extended tier
                }
            }

        openSession(engine)
        runEngineUntilFinished { engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false) }

        val log = latestObdLogText()
        val armings = log.split("\"event\":\"extended_reconnect_started\"").size - 1
        assertEquals("the second mid-drive drop must re-arm the tier after a recovery", 2, armings)
        // Ordering, not just presence: the recovery must sit between the two armings — a
        // reversed sequence (two armings, then one recovery) would otherwise pass vacuously.
        val recoveredAt = log.indexOf("\"event\":\"extended_reconnect_recovered\"")
        val secondArmingAt = log.lastIndexOf("\"event\":\"extended_reconnect_started\"")
        assertTrue("a recovery event must be logged", recoveredAt >= 0)
        assertTrue(
            "the first drop must have recovered before the second arming",
            recoveredAt < secondArmingAt,
        )
        assertFalse(
            "no exhaustion may be reported while both extended windows are still open",
            log.contains("\"event\":\"reconnect_exhausted\""),
        )
    }

    @Test
    fun extendedTierAttemptsUseTheFixedIntervalPathNotExponentialBackoff() {
        val tier = ExtendedReconnectTier(intervalMs = 1L, windowMs = 900_000L)
        val engine = newEngine(tier)
        scriptDrivingSamples()
        val dropped = AtomicBoolean(false)
        val cycles = AtomicInteger(0)
        fake.afterCommand("ATSH7DF") {
            if (cycles.incrementAndGet() >= 2) {
                dropped.set(true)
            }
        }
        fake.transactInterceptor =
            TransactInterceptor { _ ->
                if (dropped.get()) {
                    throw IOException("simulated tunnel drop")
                }
                null
            }
        engine.afterOpen =
            Runnable {
                if (engine.openCount.get() >= ObdProbes.MAX_RECONNECT_ATTEMPTS + 5) {
                    service.running.set(false)
                }
            }

        openSession(engine)
        runEngineUntilFinished { engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false) }

        val log = latestObdLogText()
        val armedAt = log.indexOf("\"event\":\"extended_reconnect_started\"")
        assertTrue("the tier must arm once the fast budget exhausts", armedAt >= 0)
        val afterArming = log.substring(armedAt)
        val extendedAttempts = afterArming.split("\"event\":\"extended_reconnect_attempt\"").size - 1
        assertTrue(
            "attempts while the tier is armed must flow through the fixed-interval path " +
                "(got $extendedAttempts extended attempts)",
            extendedAttempts >= 3,
        )
        assertFalse(
            "no fast-tier retry (a \"reconnect\" event carrying a computeBackoffMs backoff) may " +
                "run while the tier is armed",
            afterArming.contains("\"event\":\"reconnect\""),
        )
        assertFalse(
            "the still-open window must not report exhaustion",
            afterArming.contains("\"event\":\"reconnect_exhausted\""),
        )
    }

    // ---- exhaustion while NOT driving keeps the pre-B3 prompt stop -------------------

    @Test
    fun exhaustionWhileParkedStopsPromptlyWithoutTheExtendedTier() {
        // Interval/window generous enough that entering the tier by mistake would hang the run
        // past the harness timeout — the assertion below would then see extra opens.
        val engine = newEngine(ExtendedReconnectTier(intervalMs = 1L, windowMs = 900_000L))
        scriptParkedSamples()
        val dropped = AtomicBoolean(false)
        val cycles = AtomicInteger(0)
        fake.afterCommand("ATSH7DF") {
            if (cycles.incrementAndGet() >= 2) {
                dropped.set(true)
            }
        }
        fake.transactInterceptor =
            TransactInterceptor { _ ->
                if (dropped.get()) {
                    throw IOException("simulated adapter power-down")
                }
                null
            }

        openSession(engine)
        runEngineUntilFinished { engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false) }

        assertEquals(
            "a parked-session drop must stop at the fast budget, exactly as before B3",
            ObdProbes.MAX_RECONNECT_ATTEMPTS + 1,
            engine.openCount.get(),
        )
        assertFalse("the session must be stopped", service.running.get())
        val log = latestObdLogText()
        assertFalse(
            "the extended tier must NOT arm for a parked session (battery: parking lot / asleep car)",
            log.contains("\"event\":\"extended_reconnect_started\""),
        )
        assertTrue(
            "the pre-B3 exhaustion reporting must run",
            log.contains("\"event\":\"reconnect_exhausted\""),
        )
    }

    // ---- helpers --------------------------------------------------------------------

    /** Speed 40 km/h + ~1000 rpm classify as driving_gas — an actively-driving session. */
    private fun scriptDrivingSamples() {
        fake.defaultResponse = ">"
        fake.responses["0100"] = "41 00 00 00 00 00>"
        fake.responses["ATRV"] = "13.8V\r>"
        fake.responses["010D"] = "41 0D 28\r>"
        fake.responses["010C"] = "41 0C 0F A0\r>"
    }

    /** Speed 0, rpm 0, low aux voltage classify as parked (weak confidence). */
    private fun scriptParkedSamples() {
        fake.defaultResponse = ">"
        fake.responses["0100"] = "41 00 00 00 00 00>"
        fake.responses["ATRV"] = "12.1V\r>"
        fake.responses["010D"] = "41 0D 00\r>"
        fake.responses["010C"] = "41 0C 00 00\r>"
    }

    private fun newEngine(tier: ExtendedReconnectTier): TestObdPollingEngine {
        val engine = TestObdPollingEngine(service, tier)
        engine.setConnectionForTest(fake)
        return engine
    }

    /** Mirrors what ObdService does before submitting runBluetoothLoop. */
    private fun openSession(engine: TestObdPollingEngine) {
        service.running.set(true)
        engine.beginSession("")
        service.recorder.openSession(
            "obd",
            "AA:BB:CC:DD:EE:FF",
            service.activeName,
            service.sessionStartedAtMs,
        )
    }

    private fun runEngineUntilFinished(engineEntrypoint: Runnable) {
        val worker = Thread(engineEntrypoint, "engine-test-worker")
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

    private fun latestObdLogText(): String {
        val logDir = File(service.filesDir, "obd-logs")
        val latest = File(logDir, "latest.txt").readText().trim()
        return File(logDir, latest).readText()
    }

    private companion object {
        private const val ENGINE_JOIN_TIMEOUT_MS = 10_000L
    }

    /**
     * Subclass of [ObdPollingEngine] that bypasses the Bluetooth seams (see the
     * [ObdPollingEngineTest] file docs) and injects the test-controlled [ExtendedReconnectTier].
     */
    private class TestObdPollingEngine(
        service: EngineHost,
        tier: ExtendedReconnectTier,
    ) : ObdPollingEngine(service, LoopSleeper { true }, tier) {
        val openCount = AtomicInteger()

        @Volatile
        var afterOpen: Runnable? = null

        override fun isBluetoothReady(): Boolean = true // bypass the real BluetoothAdapter

        override fun openBluetoothSocket(address: String?) {
            openCount.incrementAndGet()
            afterOpen?.run()
            // No-op: the engine's connection field already points at the scripted fake.
        }
    }

    /** In-memory stand-in for [ElmConnection] scripting adapter responses. */
    private class FakeElmConnection : ElmConnection() {
        val commandLog: MutableList<String> = Collections.synchronizedList(ArrayList())
        val responses = LinkedHashMap<String, String>()

        @Volatile
        var defaultResponse = ">"

        @Volatile
        var transactInterceptor: TransactInterceptor? = null

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
            val intercepted = transactInterceptor?.handle(command)
            val response = intercepted ?: responses[command] ?: defaultResponse
            afterCommandHooks[command]?.run()
            return response
        }

        override fun sendEscape(settleMs: Long) {
            // No-op: the fake never gets a hung prompt.
        }

        override fun close() {
            // No-op: nothing real to release.
        }
    }

    private fun interface TransactInterceptor {
        @Throws(IOException::class)
        fun handle(command: String): String?
    }
}
