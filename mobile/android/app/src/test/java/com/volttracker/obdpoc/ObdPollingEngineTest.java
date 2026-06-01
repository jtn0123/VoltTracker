package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.bluetooth.BluetoothDevice;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Behavior tests for {@link ObdPollingEngine}: the connect / init / poll / reconnect state machine
 * that drives a Bluetooth OBD session.
 *
 * <p>The engine is exercised through three minimal seams added to production code:
 *
 * <ul>
 *   <li>{@link ObdPollingEngine} is no longer {@code final}, so this test subclasses it as {@code
 *       TestObdPollingEngine} and overrides {@link ObdPollingEngine#isBluetoothReady} and {@link
 *       ObdPollingEngine#openBluetoothSocket} to bypass the system {@code BluetoothAdapter}. That
 *       sidesteps a JaCoCo-vs-JVM-proxy instrumentation crash that hits the moment Robolectric's
 *       {@code ShadowBluetoothAdapter} touches a generated proxy on JDK 24.
 *   <li>{@link ObdPollingEngine#setConnectionForTest(ElmConnection)} — swaps the real {@link
 *       ElmConnection} for a {@link FakeElmConnection} that scripts adapter responses instead of
 *       reading from an RFCOMM socket.
 *   <li>{@link ElmConnection} is no longer {@code final}, so {@code FakeElmConnection} can subclass
 *       it and override {@link ElmConnection#open}, {@link ElmConnection#transact}, {@link
 *       ElmConnection#sendEscape} and {@link ElmConnection#close}.
 * </ul>
 *
 * <p>The {@link ObdService} itself is brought up via {@link Robolectric#setupService}, which calls
 * {@code onCreate} so the engine's collaborators ({@code recorder}, {@code activeName}, {@code
 * running}, {@code ioLock}, etc.) are live and observable.
 *
 * <p>Tests targeting the live-poll loop sleep through one 850 ms inter-sample tick by design — the
 * loop's own {@code Thread.sleep(850)} is part of the contract being verified.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30) // sdk < S so service.hasBluetoothConnectPermission() short-circuits to true.
public class ObdPollingEngineTest {

    /** Cap on how long any single test will wait for the engine worker thread to finish. */
    private static final long ENGINE_JOIN_TIMEOUT_MS = 10_000L;

    /** Cap on how long we'll spin waiting for a counter / latch in a test. */
    private static final long POLL_WAIT_TIMEOUT_MS = 8_000L;

    private ObdService service;
    private TestObdPollingEngine engine;
    private FakeElmConnection fake;

    @Before
    public void setUp() {
        service = Robolectric.setupService(ObdService.class);
        // Match what ObdService.onStartCommand does for ACTION_CONNECT: name the adapter and
        // mark the session as just-started so sessionMs computations make sense.
        service.activeName = "Test Adapter";
        service.sessionStartedAtMs = System.currentTimeMillis();

        fake = new FakeElmConnection();
        engine = new TestObdPollingEngine(service, fake);
        engine.setConnectionForTest(fake);
    }

    @After
    public void tearDown() {
        if (service != null) {
            service.running.set(false);
            // Drain the recorder so background lifecycle writes do not leak across tests.
            try {
                service.onDestroy();
            } catch (RuntimeException ignored) {
                // onDestroy stops the foreground service which was never started here; safe.
            }
        }
    }

    // ---- 1. Clean connect → 1 sample → clean close ---------------------------------

    @Test
    public void cleanConnectThenSingleSampleThenStop() throws Exception {
        // Script every command the engine sends so transact() never throws and the prompt is
        // always present. After the first sample's *last* OBD command (ATSH7DF restores the
        // broadcast header) we flip running to false so the loop exits cleanly.
        fake.defaultResponse = ">"; // ELM prompt-only reply is harmless for AT setup
        fake.responses.put("0100", "41 00 00 00 00 00>");
        fake.responses.put("ATRV", "13.8V\r>");
        fake.responses.put("010D", "41 0D 28\r>");
        fake.responses.put("010C", "41 0C 0F A0\r>");
        fake.afterCommand("ATSH7DF", () -> service.running.set(false));

        openSession();
        runEngineUntilFinished(() -> engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false));

        // Engine opened a session, took at least one sample, then closed cleanly.
        assertTrue("engine should poll at least one sample before stop", engine.sampleCount() >= 1);
        assertTrue("openBluetoothSocket must have been called", engine.openCount.get() >= 1);
        assertTrue("ATZ must be issued during initializeElm327", fake.commandLog.contains("ATZ"));
        assertTrue("0100 capability probe must run during init", fake.commandLog.contains("0100"));
        assertTrue(
                "engine must restore the broadcast header (ATSH7DF) at end of cycle",
                fake.commandLog.contains("ATSH7DF"));
        // closeSocket runs in the finally block of runBluetoothLoop.
        assertTrue("connection must be closed at session end", fake.closeCalls.get() >= 1);
    }

    // ---- 2. Mid-session drop → backoff → reconnect → session continues -------------

    @Test
    public void midSessionDropTriggersReconnectAndSessionContinues() throws Exception {
        // First connect: poll once, then on the second ATRV throw IOException. The loop's catch
        // block flips into backoff and re-enters connectAndInitialize. Second connect: poll once
        // more, then stop. Session id must NOT change across the reconnect.
        fake.defaultResponse = ">";
        fake.responses.put("0100", "41 00 00 00 00 00>");
        fake.responses.put("ATRV", "13.7V\r>");
        fake.responses.put("010D", "41 0D 1E\r>");
        fake.responses.put("010C", "41 0C 0B B8\r>");

        AtomicInteger atrvCalls = new AtomicInteger();
        fake.transactInterceptor =
                command -> {
                    if ("ATRV".equals(command)) {
                        int call = atrvCalls.incrementAndGet();
                        if (call == 2) {
                            throw new IOException("simulated socket drop");
                        }
                    }
                    return null; // fall through to scripted responses
                };
        // After the second successful cycle's ATSH7DF, stop running so we exit cleanly.
        fake.afterCommand(
                "ATSH7DF",
                () -> {
                    if (atrvCalls.get() >= 2) {
                        service.running.set(false);
                    }
                });

        openSession();
        long sessionIdDuringRun = service.recorder.activeSessionId();
        runEngineUntilFinished(() -> engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false));

        assertTrue("session must have been opened (id > 0)", sessionIdDuringRun > 0);
        assertTrue(
                "engine must reconnect after the drop (openBluetoothSocket called >= 2 times),"
                        + " got "
                        + engine.openCount.get(),
                engine.openCount.get() >= 2);
        assertTrue(
                "engine must collect 2 samples across the reconnect, got " + engine.sampleCount(),
                engine.sampleCount() >= 2);
        // Session id continuity: closeSession was NOT called between the drop and the next
        // open, so the recorder's active session id stayed the same throughout the run. We
        // verified above that it was non-zero during the run; assert no second openSession
        // happened by checking that the recorder did not bump the id mid-way.
        assertEquals(
                "session id must remain stable across mid-session drops",
                sessionIdDuringRun,
                engine.sessionIdSeenAtFirstReconnect.get());
    }

    // ---- 3. Never-connected timeout -------------------------------------------------

    @Test
    public void neverConnectedExhaustsRetriesWithoutOpeningASample() throws Exception {
        // Every call to openBluetoothSocket throws — adapter unreachable. Once we've observed
        // enough attempts to know the engine is on the retry path, flip running to false so
        // the loop exits at the !service.running.get() check inside the catch block, *before*
        // sleeping the configured initial-connect backoff for the full duration.
        engine.openShouldThrow = true;
        engine.afterOpen =
                () -> {
                    int n = engine.openCount.get();
                    if (n >= 3) {
                        // Three failed attempts: enough to prove the retry loop is active.
                        service.running.set(false);
                    }
                };

        openSession();
        runEngineUntilFinished(() -> engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false));

        assertTrue(
                "engine must retry connect at least 3 times, got " + engine.openCount.get(),
                engine.openCount.get() >= 3);
        assertEquals(
                "no telemetry samples should be recorded when adapter never connects",
                0,
                engine.sampleCount());
        // No successful transact ever happened, so the command log should be empty.
        assertTrue(
                "no commands should have been transacted, got " + fake.commandLog,
                fake.commandLog.isEmpty());
    }

    // ---- 4. stop() mid-poll ---------------------------------------------------------

    @Test
    public void stopMidPollExitsCleanlyAndClosesSocket() throws Exception {
        // Engine connects, starts polling. We flip running to false while the engine is partway
        // through reading a cycle: that lets pollUntilStoppedOrBroken finish the in-flight
        // readObdSample (sample 1), then exit on the next while-check.
        fake.defaultResponse = ">";
        fake.responses.put("0100", "41 00 00 00 00 00>");
        fake.responses.put("ATRV", "12.9V\r>");
        fake.responses.put("010D", "41 0D 32\r>");

        CountDownLatch firstSampleStarted = new CountDownLatch(1);
        fake.afterCommand(
                "ATRV",
                () -> {
                    // Signal we are inside a poll cycle, then trigger the stop.
                    firstSampleStarted.countDown();
                    service.running.set(false);
                });

        openSession();
        runEngineUntilFinished(() -> engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false));

        assertEquals(
                "test never observed the in-flight ATRV — engine did not enter poll loop",
                0L,
                firstSampleStarted.getCount());
        // Engine should have completed the in-flight sample then bailed without taking
        // additional samples on the next iteration.
        assertEquals("expected exactly 1 in-flight sample to finish", 1, engine.sampleCount());
        assertTrue(
                "connection must be closed on stop (close called >= 1), got "
                        + fake.closeCalls.get(),
                fake.closeCalls.get() >= 1);
    }

    @Test
    public void rawTranscriptIsCappedWithTruncationMarker() {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < ObdPollingEngine.RAW_TRANSCRIPT_MAX_CHARS + 50; i++) {
            raw.append('x');
        }

        String bounded = ObdPollingEngine.boundedRawTranscript(raw);

        assertTrue(
                "bounded raw transcript should include truncation marker",
                bounded.endsWith("... [truncated 50 chars]"));
        assertEquals(
                ObdPollingEngine.RAW_TRANSCRIPT_MAX_CHARS + "... [truncated 50 chars]".length(),
                bounded.length());
    }

    // ---- 5. Init throws → treated as a connect failure ------------------------------

    @Test
    public void initFailureIsTreatedAsConnectFailureAndRetried() throws Exception {
        // openBluetoothSocket() succeeds, but the very first init command (ATZ) throws —
        // simulating an adapter that accepts the socket then drops it during reset. The engine
        // catches IOException out of initializeElm327, increments attempt, and retries the
        // whole connect+init. We stop after a couple of attempts to keep the test fast.
        AtomicInteger atzCalls = new AtomicInteger();
        fake.transactInterceptor =
                command -> {
                    if ("ATZ".equals(command)) {
                        int n = atzCalls.incrementAndGet();
                        if (n <= 2) {
                            throw new IOException("simulated ELM hangup on " + command);
                        }
                    }
                    return null;
                };
        fake.afterCommand(
                "ATZ",
                () -> {
                    if (atzCalls.get() >= 2) {
                        service.running.set(false);
                    }
                });

        openSession();
        runEngineUntilFinished(() -> engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false));

        assertTrue(
                "engine should retry the connect+init at least twice, got open="
                        + engine.openCount.get(),
                engine.openCount.get() >= 2);
        assertTrue(
                "ATZ should be attempted on each connect, got " + atzCalls.get(),
                atzCalls.get() >= 2);
        assertEquals(
                "no telemetry sample should land before init succeeds", 0, engine.sampleCount());
    }

    // ---- 6. Demo mode runs DemoPollingLoop without touching ElmConnection -----------

    @Test
    public void demoLoopEmitsSamplesWithoutUsingElmConnection() throws Exception {
        // Demo mode is a synthetic stream. It must NOT poke the real adapter even by accident —
        // that would mean a refactor coupled the demo path back to BT IO.
        service.activeName = "Demo stream";
        openSession();

        // Stop the demo loop after one synthetic sample so the test stays fast. DemoPollingLoop
        // sleeps 1000 ms between samples and calls incrementSampleCount() at the top of each
        // cycle, so a watcher that flips running once sampleCount() > 0 gets us out after the
        // first sample's broadcast.
        Thread watcher =
                new Thread(
                        () -> {
                            long deadline = System.currentTimeMillis() + POLL_WAIT_TIMEOUT_MS;
                            while (System.currentTimeMillis() < deadline) {
                                if (engine.sampleCount() >= 1) {
                                    service.running.set(false);
                                    return;
                                }
                                try {
                                    Thread.sleep(20);
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                            }
                        },
                        "demo-stop-watcher");
        watcher.setDaemon(true);
        watcher.start();
        runEngineUntilFinished(engine::runDemoLoop);
        watcher.join(1000);

        assertTrue(
                "demo loop must emit at least 1 sample, got " + engine.sampleCount(),
                engine.sampleCount() >= 1);
        assertEquals("demo loop must never call openBluetoothSocket()", 0, engine.openCount.get());
        assertTrue(
                "demo loop must never transact any AT/OBD commands, got " + fake.commandLog,
                fake.commandLog.isEmpty());
    }

    // ---- Mode-01 multi-PID batching (probe + per-cycle batch + per-adapter fallback) ----

    @Test
    public void mode01BatchProbeRunsDuringInit() throws Exception {
        fake.defaultResponse = ">";
        fake.responses.put("0100", "41 00 00 00 00 00>");
        fake.responses.put("010D0C", "41 0D 28 41 0C 0F A0\r>");
        fake.responses.put("010D0C041149", "41 0D 28 41 0C 0F A0 41 04 80 41 11 33 41 49 7F\r>");
        fake.afterCommand("ATSH7DF", () -> service.running.set(false));

        openSession();
        runEngineUntilFinished(() -> engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false));

        assertTrue(
                "the multi-PID capability probe (010D0C) must run during init",
                fake.commandLog.contains("010D0C"));
    }

    @Test
    public void supportedAdapterPollsTier1AsOneBatchedCommand() throws Exception {
        fake.defaultResponse = ">";
        fake.responses.put("0100", "41 00 00 00 00 00>");
        // Probe returns both PIDs -> batching is supported for the session.
        fake.responses.put("010D0C", "41 0D 28 41 0C 0F A0\r>");
        fake.responses.put("010D0C041149", "41 0D 28 41 0C 0F A0 41 04 80 41 11 33 41 49 7F\r>");
        fake.afterCommand("ATSH7DF", () -> service.running.set(false));

        openSession();
        runEngineUntilFinished(() -> engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false));

        assertTrue(
                "Tier-1 PIDs must be polled as one batched command when the adapter supports it",
                fake.commandLog.contains("010D0C041149"));
        assertFalse(
                "a batched cycle must not also send the individual speed PID",
                fake.commandLog.contains("010D"));
        assertFalse(
                "a batched cycle must not also send the individual RPM PID",
                fake.commandLog.contains("010C"));
    }

    @Test
    public void unsupportedAdapterFallsBackToPerPidPolling() throws Exception {
        fake.defaultResponse = ">";
        fake.responses.put("0100", "41 00 00 00 00 00>");
        // Probe reply is missing RPM (0C), so batching stays disabled for the session.
        fake.responses.put("010D0C", "41 0D 28\r>");
        fake.responses.put("010D", "41 0D 28\r>");
        fake.responses.put("010C", "41 0C 0F A0\r>");
        fake.afterCommand("ATSH7DF", () -> service.running.set(false));

        openSession();
        runEngineUntilFinished(() -> engine.runBluetoothLoop("AA:BB:CC:DD:EE:FF", false));

        assertFalse(
                "an incomplete probe must disable batching",
                fake.commandLog.contains("010D0C041149"));
        assertTrue("fallback must poll speed per-PID", fake.commandLog.contains("010D"));
        assertTrue("fallback must poll RPM per-PID", fake.commandLog.contains("010C"));
    }

    // ---- helpers --------------------------------------------------------------------

    /** Mirrors what ObdService does before submitting runBluetoothLoop / runDemoLoop. */
    private void openSession() {
        service.running.set(true);
        engine.beginSession("");
        service.recorder.openSession(
                "obd", "AA:BB:CC:DD:EE:FF", service.activeName, service.sessionStartedAtMs);
    }

    /**
     * Runs the engine entrypoint on a worker thread (so the test thread can flip {@code running} to
     * false) and blocks until it returns or {@link #ENGINE_JOIN_TIMEOUT_MS} elapses.
     */
    private void runEngineUntilFinished(Runnable engineEntrypoint) throws InterruptedException {
        Thread worker = new Thread(engineEntrypoint, "engine-test-worker");
        worker.setDaemon(true);
        worker.start();
        worker.join(ENGINE_JOIN_TIMEOUT_MS);
        if (worker.isAlive()) {
            // Belt-and-braces: force the loop's exit condition and try one more time.
            service.running.set(false);
            worker.interrupt();
            worker.join(2_000L);
            if (worker.isAlive()) {
                fail("engine worker did not exit within " + ENGINE_JOIN_TIMEOUT_MS + " ms");
            }
        }
    }

    /**
     * Subclass of {@link ObdPollingEngine} that overrides the two BT-specific seams ({@link
     * #isBluetoothReady}, {@link #openBluetoothSocket}) so the engine's connect / init / poll /
     * reconnect logic can run without a real {@code BluetoothAdapter}.
     */
    private static final class TestObdPollingEngine extends ObdPollingEngine {
        final ObdService testService;
        final FakeElmConnection scriptedConnection;
        final AtomicInteger openCount = new AtomicInteger();
        // Session ids are long on the recorder; use AtomicLong so the comparison stays
        // exact even if the row id grows past Integer.MAX_VALUE in a long-running suite.
        final AtomicLong sessionIdSeenAtFirstReconnect = new AtomicLong();

        volatile boolean openShouldThrow;
        volatile Runnable afterOpen;

        TestObdPollingEngine(ObdService service, FakeElmConnection scriptedConnection) {
            super(service);
            this.testService = service;
            this.scriptedConnection = scriptedConnection;
        }

        @Override
        boolean isBluetoothReady() {
            return true; // bypass the real BluetoothAdapter — see file-level javadoc
        }

        @Override
        void openBluetoothSocket(String address) throws IOException {
            int n = openCount.incrementAndGet();
            // Record the active session id on the first reconnect (the second open) so the
            // mid-session-drop test can assert that the session id did NOT change.
            if (n == 2) {
                sessionIdSeenAtFirstReconnect.set(testService.recorder.activeSessionId());
            }
            if (afterOpen != null) {
                afterOpen.run();
            }
            if (openShouldThrow) {
                throw new IOException("fake openBluetoothSocket refusing");
            }
            // No-op: the engine's connection field is already pointing at the scripted fake.
        }
    }

    /**
     * In-memory stand-in for {@link ElmConnection} that scripts adapter responses and counts
     * lifecycle calls so tests can assert against the engine's observable behavior.
     */
    private static final class FakeElmConnection extends ElmConnection {

        /** Number of times {@link #close} has been called. */
        final AtomicInteger closeCalls = new AtomicInteger();

        /** Every command passed to {@link #transact}, in order. */
        final List<String> commandLog = Collections.synchronizedList(new ArrayList<>());

        /** Scripted exact-match responses, keyed on the trimmed command string. */
        final Map<String, String> responses = new LinkedHashMap<>();

        /** Response used when no entry matches in {@link #responses}. Default: ELM prompt only. */
        volatile String defaultResponse = ">";

        /**
         * Optional pre-transact hook. If it returns non-null, that string is the response. If it
         * throws IOException, transact() propagates the throw. Returning null falls through to
         * {@link #responses} / {@link #defaultResponse}.
         */
        volatile TransactInterceptor transactInterceptor;

        /** After-command hooks, one per command (the last registration wins per command). */
        private final Map<String, Runnable> afterCommandHooks = new LinkedHashMap<>();

        void afterCommand(String command, Runnable hook) {
            afterCommandHooks.put(command, hook);
        }

        @Override
        void open(BluetoothDevice device, UUID uuid, long connectTimeoutMs) {
            // No-op: TestObdPollingEngine.openBluetoothSocket overrides the whole open path so
            // this method is never reached in these tests. Kept here to satisfy the surface.
        }

        @Override
        WakeNudgeResult wakeNudge(long toleranceMs) {
            // The fake doesn't run a real RFCOMM stream, so the wake-nudge probe has nothing to
            // read. Pretend the adapter answered immediately so the engine progresses straight
            // into initializeElm327() and the connect / poll / reconnect logic under test runs.
            return new WakeNudgeResult(0L, true);
        }

        @Override
        String transact(String command, long timeoutMs, ElmConnection.KeepWaiting keepWaiting)
                throws IOException {
            commandLog.add(command);
            TransactInterceptor interceptor = transactInterceptor;
            String intercepted = interceptor == null ? null : interceptor.handle(command);
            String response;
            if (intercepted != null) {
                response = intercepted;
            } else if (responses.containsKey(command)) {
                response = responses.get(command);
            } else {
                response = defaultResponse;
            }
            Runnable hook = afterCommandHooks.get(command);
            if (hook != null) {
                hook.run();
            }
            return response;
        }

        @Override
        void sendEscape(long settleMs) {
            // No-op: production code only sends ESC to recover a hung ELM prompt; the fake
            // never gets hung.
        }

        @Override
        void close() {
            closeCalls.incrementAndGet();
        }
    }

    /**
     * Functional interface for {@link FakeElmConnection#transactInterceptor}. Defined here rather
     * than as {@code java.util.function.Function<String, String>} so it can declare {@code throws
     * IOException} — Function<> cannot.
     */
    @FunctionalInterface
    private interface TransactInterceptor {
        String handle(String command) throws IOException;
    }
}
