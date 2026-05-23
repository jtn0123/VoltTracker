package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.volttracker.obdpoc.data.ObdLocalStore;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Behavior tests for {@link SessionRecorder}: verifies the single-thread executor contract — calls
 * queued through {@code telemetryExecutor} are processed in FIFO order, lifecycle writes go through
 * a separate {@code lifecycleExecutor}, and {@link SessionRecorder#shutdown()} drains both before
 * returning. The lifecycle-failure path is covered separately in {@link
 * SessionRecorderLifecycleFailureTest}; these tests focus on the in-order persistence contract,
 * close-then-no-persist behavior, status/PID routing, and executor draining on shutdown.
 *
 * <p>Pattern matches {@code SessionRecorderLifecycleFailureTest}: a {@link RecordingStore} subclass
 * of {@link ObdLocalStore} captures method calls in arrival order so the test can assert what the
 * recorder actually queued.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SessionRecorderTest {

    private static final long SESSION_ID = 1234L;
    private static final long AWAIT_MS = 5_000L;

    /**
     * Telemetry writes from 4 concurrent threads end up persisted in per-thread FIFO order — the
     * single-thread {@code telemetryExecutor} preserves the submission order of every Runnable a
     * given thread offered, even though offerings from different threads interleave.
     */
    @Test
    public void recordTelemetryPreservesPerThreadFifoOrderUnderConcurrentSubmission()
            throws InterruptedException {
        final int threads = 4;
        final int writesPerThread = 25;
        final RecordingStore store = new RecordingStore();
        final SessionRecorder recorder = newOpenRecorder(store, "sr-fifo-test");

        final CountDownLatch startGate = new CountDownLatch(1);
        final CountDownLatch finishedSubmitting = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            new Thread(
                            () -> {
                                try {
                                    startGate.await();
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                                for (int seq = 0; seq < writesPerThread; seq++) {
                                    recorder.persistTelemetry(taggedTelemetry(threadId, seq));
                                }
                                finishedSubmitting.countDown();
                            },
                            "telemetry-sender-" + t)
                    .start();
        }

        startGate.countDown();
        assertTrue(
                "all sender threads should have submitted in time",
                finishedSubmitting.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        // shutdown() drains telemetryExecutor (and lifecycleExecutor) before returning.
        recorder.shutdown();

        assertEquals(threads * writesPerThread, store.telemetryCalls.size());

        // Per-thread sequences must be strictly increasing in arrival order — this is what the
        // single-thread executor buys us. (Across threads, interleaving is fine.)
        Map<Integer, Integer> lastSeqByThread = new HashMap<>();
        for (RecordedTelemetry rec : store.telemetryCalls) {
            int tid = rec.payload.optInt("threadId", -1);
            int seq = rec.payload.optInt("seq", -1);
            assertTrue("payload should carry threadId", tid >= 0);
            assertTrue("payload should carry seq", seq >= 0);
            Integer prev = lastSeqByThread.get(tid);
            if (prev != null) {
                assertTrue(
                        "thread " + tid + " seq must be monotonic: prev=" + prev + " now=" + seq,
                        seq > prev);
            }
            lastSeqByThread.put(tid, seq);
        }
        assertEquals(
                "every thread should have written all of its rows",
                threads,
                lastSeqByThread.size());
        for (Map.Entry<Integer, Integer> e : lastSeqByThread.entrySet()) {
            assertEquals(
                    "thread " + e.getKey() + " final seq",
                    writesPerThread - 1,
                    e.getValue().intValue());
        }
    }

    /**
     * After {@code shutdown()} returns, every telemetry write submitted before {@code closeSession}
     * has been processed, and the finalize call on the {@code lifecycleExecutor} has also run. The
     * telemetry queue is processed in FIFO order, so the sequence captured by the fake store
     * matches the submission sequence.
     */
    @Test
    public void closeSessionAndPendingTelemetryBothCompleteAfterShutdown()
            throws InterruptedException {
        final int writes = 50;
        final RecordingStore store = new RecordingStore();
        final SessionRecorder recorder = newOpenRecorder(store, "sr-close-after-test");

        for (int seq = 0; seq < writes; seq++) {
            recorder.persistTelemetry(taggedTelemetry(0, seq));
        }
        // Submits finalize on lifecycleExecutor; does NOT block on the telemetry queue.
        recorder.closeSession("disconnected", "test", "supported", writes);
        // shutdown() awaits termination of BOTH executors, so by the time it returns all
        // pre-shutdown work has either completed or been rejected. We assert nothing was
        // dropped: all 50 telemetry calls landed, and finalize ran exactly once.
        recorder.shutdown();

        assertEquals("all telemetry writes must be persisted", writes, store.telemetryCalls.size());
        assertEquals("finalize must run exactly once", 1, store.finalizeCalls.get());

        // Within the telemetry executor, FIFO order is preserved: seq 0..N-1 in order.
        for (int i = 0; i < writes; i++) {
            int seenSeq = store.telemetryCalls.get(i).payload.optInt("seq", -1);
            assertEquals("telemetry FIFO order at index " + i, i, seenSeq);
        }
    }

    /**
     * Telemetry submitted AFTER {@code closeSession} is dropped — it cannot leak into the
     * just-closed session id. {@code SessionRecorder.persistTelemetry} guards on {@code
     * activeSessionId &gt; 0}, which {@code closeSession} clears synchronously under the shared
     * lock.
     */
    @Test
    public void persistTelemetryAfterCloseIsDropped() throws InterruptedException {
        final RecordingStore store = new RecordingStore();
        final SessionRecorder recorder = newOpenRecorder(store, "sr-after-close-test");

        recorder.persistTelemetry(taggedTelemetry(0, 0));
        recorder.closeSession("disconnected", "before-extra-write", "supported", 1);
        // Post-close write: must be a no-op (activeSessionId == 0 by the time we return from
        // closeSession, which holds the shared lock while clearing it).
        recorder.persistTelemetry(taggedTelemetry(99, 99));
        recorder.shutdown();

        assertEquals(
                "exactly one telemetry row should be persisted (the post-close one is dropped)",
                1,
                store.telemetryCalls.size());
        RecordedTelemetry only = store.telemetryCalls.get(0);
        assertEquals("the persisted row must be the pre-close one", SESSION_ID, only.sessionId);
        assertEquals(0, only.payload.optInt("threadId", -1));
        assertEquals(0, only.payload.optInt("seq", -1));

        // The post-close write must not have been routed to the old session id either.
        for (RecordedTelemetry rec : store.telemetryCalls) {
            assertFalse(
                    "post-close payload (threadId=99) must not appear in the persisted stream",
                    rec.payload.optInt("threadId", -1) == 99);
        }
    }

    /**
     * Status events submitted via {@code persistStatus} reach the store as a {@code recordStatus}
     * call (which delegates to {@code recordEvent} with kind={@code "status"}) carrying the
     * expected state + detail + payload. The call is dispatched off the test thread — we observe it
     * after {@code shutdown()} drains the telemetry executor.
     */
    @Test
    public void persistStatusRoutesThroughTelemetryExecutor() throws InterruptedException {
        final RecordingStore store = new RecordingStore();
        final SessionRecorder recorder = newOpenRecorder(store, "sr-status-test");

        JSONObject payload = new JSONObject();
        try {
            payload.put("note", "diagnostic");
        } catch (org.json.JSONException ex) {
            throw new AssertionError(ex);
        }
        recorder.persistStatus("connecting", "handshake", false, payload);
        recorder.shutdown();

        List<RecordedStatus> statuses = store.statusCalls;
        assertEquals("expected exactly one recordStatus call", 1, statuses.size());
        RecordedStatus s = statuses.get(0);
        assertEquals(SESSION_ID, s.sessionId);
        assertEquals("connecting", s.state);
        assertEquals("handshake", s.detail);
        assertFalse(s.blocked);
        assertNotNull(s.payload);
        assertEquals("diagnostic", s.payload.optString("note"));
        // The status executor was not the recorder thread — we can't easily prove a different
        // thread without instrumentation, but we can prove the work landed by the time
        // shutdown() returned, which is what callers actually rely on.
    }

    /**
     * {@code shutdown()} drains BOTH executors. Submit a healthy mix of telemetry, status,
     * lifecycle (close), and arbitrary runAsync work, then assert that every queued task ran before
     * {@code shutdown()} returned.
     */
    @Test
    public void shutdownDrainsBothExecutors() throws InterruptedException {
        final int telemetryWrites = 10;
        final int runAsyncJobs = 5;
        final RecordingStore store = new RecordingStore();
        final SessionRecorder recorder = newOpenRecorder(store, "sr-drain-test");

        final AtomicInteger asyncRan = new AtomicInteger();
        for (int i = 0; i < telemetryWrites; i++) {
            recorder.persistTelemetry(taggedTelemetry(0, i));
        }
        recorder.persistStatus("connecting", "drain-test", false, new JSONObject());
        for (int i = 0; i < runAsyncJobs; i++) {
            recorder.runAsync(asyncRan::incrementAndGet);
        }
        // Lifecycle work on the separate lifecycleExecutor.
        recorder.closeSession("disconnected", "drain-test", "supported", telemetryWrites);

        recorder.shutdown();

        assertEquals(
                "all telemetry writes must have run before shutdown returned",
                telemetryWrites,
                store.telemetryCalls.size());
        assertEquals(
                "all runAsync jobs must have run before shutdown returned",
                runAsyncJobs,
                asyncRan.get());
        assertEquals("status call must have run", 1, store.statusCalls.size());
        assertEquals("finalize must have run", 1, store.finalizeCalls.get());
    }

    /**
     * {@code logCommand} routes through {@code persistPidObservation}, which queues a {@code
     * recordPidObservation} call on the telemetry executor. After shutdown, the call carries the
     * expected command + raw response captured by the fake.
     */
    @Test
    public void logCommandRoutesPidObservationThroughTelemetryExecutor()
            throws InterruptedException {
        final RecordingStore store = new RecordingStore();
        final SessionRecorder recorder = newOpenRecorder(store, "sr-pid-test");

        // 0105 = engine coolant temperature; response "41 05 7B" -> 0x7B - 40 = 83°C.
        recorder.logCommand("0105", 1_000L, 25L, "41 05 7B");
        recorder.shutdown();

        assertEquals("expected exactly one PID observation", 1, store.pidObservationCalls.size());
        RecordedPidObservation obs = store.pidObservationCalls.get(0);
        assertEquals(SESSION_ID, obs.sessionId);
        assertNotNull(obs.payload);
        assertEquals("0105", obs.payload.optString("command"));
        assertTrue(
                "raw response should be captured in the observation payload",
                obs.payload.optString("rawResponse").contains("41 05 7B"));
        assertTrue("observedAtMs should be set", obs.observedAtMs > 0L);
    }

    /**
     * Calling {@code shutdown()} twice is safe: a second shutdown on an already-terminated executor
     * returns cleanly without throwing. Defends against a service teardown path that may
     * double-call (e.g. {@code onDestroy} after a manual stop).
     */
    @Test
    public void doubleShutdownIsSafe() throws InterruptedException {
        final RecordingStore store = new RecordingStore();
        final SessionRecorder recorder = newOpenRecorder(store, "sr-double-shutdown-test");

        recorder.persistTelemetry(taggedTelemetry(0, 0));
        recorder.shutdown();
        // Second shutdown — must not throw or leak.
        recorder.shutdown();

        assertEquals(1, store.telemetryCalls.size());
    }

    // ---- helpers ------------------------------------------------------------------

    private SessionRecorder newOpenRecorder(RecordingStore store, String dirName) {
        File logsDir = new File(System.getProperty("java.io.tmpdir"), dirName);
        logsDir.mkdirs();
        Object lock = new Object();
        SessionRecorder recorder = new SessionRecorder(lock, new ObdSessionLog(logsDir), store);
        recorder.openSession(ObdLocalStore.MODE_OBD, "AA:BB:CC:DD:EE:FF", "Test", 1_000L);
        return recorder;
    }

    private static JSONObject taggedTelemetry(int threadId, int seq) {
        JSONObject p = new JSONObject();
        try {
            p.put("threadId", threadId);
            p.put("seq", seq);
            // Give every payload a unique timestamp so the in-order checks have something to
            // anchor against besides the (threadId, seq) tag.
            p.put("updatedAt", 10_000L + threadId * 1_000L + seq);
        } catch (org.json.JSONException ex) {
            throw new AssertionError(ex);
        }
        return p;
    }

    // ---- fake store ---------------------------------------------------------------

    /**
     * Subclass of {@link ObdLocalStore} that captures calls in arrival order. Each captured-call
     * list is wrapped in its own synchronized block on append so concurrent submissions from the
     * recorder's executors are safe.
     */
    private static final class RecordingStore extends ObdLocalStore {
        final AtomicInteger finalizeCalls = new AtomicInteger();
        final List<RecordedTelemetry> telemetryCalls = new ArrayList<>();
        final List<RecordedStatus> statusCalls = new ArrayList<>();
        final List<RecordedPidObservation> pidObservationCalls = new ArrayList<>();
        final AtomicLong arrivalCounter = new AtomicLong();

        RecordingStore() {
            super(RuntimeEnvironment.getApplication());
        }

        @Override
        public long startSession(
                String mode, String adapterAddress, String adapterName, long startedAtMs) {
            // Non-zero so the recorder enters the "session is active" branches.
            return SESSION_ID;
        }

        @Override
        public long recordTelemetry(long sessionId, JSONObject sample) {
            long arrival = arrivalCounter.incrementAndGet();
            synchronized (telemetryCalls) {
                telemetryCalls.add(new RecordedTelemetry(sessionId, sample, arrival));
            }
            return arrival;
        }

        @Override
        public long recordStatus(
                long sessionId, String state, String detail, boolean blocked, JSONObject payload) {
            synchronized (statusCalls) {
                statusCalls.add(new RecordedStatus(sessionId, state, detail, blocked, payload));
            }
            return statusCalls.size();
        }

        @Override
        public long recordPidObservation(
                long sessionId, JSONObject observation, long observedAtMs) {
            synchronized (pidObservationCalls) {
                pidObservationCalls.add(
                        new RecordedPidObservation(sessionId, observation, observedAtMs));
            }
            return pidObservationCalls.size();
        }

        @Override
        public void finalizeSession(
                long sessionId,
                String status,
                long endedAtMs,
                String supportedPids,
                String address,
                String adapterName,
                String mode,
                int sampleCount,
                String lastEventDetail) {
            finalizeCalls.incrementAndGet();
        }

        @Override
        public long recordEvent(
                long sessionId,
                String kind,
                String state,
                String detail,
                boolean blocked,
                JSONObject payload) {
            // Recorder's logEvent path (session_start, session_end, etc.) lands here. Tests
            // don't currently assert on these — they're allowed to flow through. Returning a
            // benign id keeps the production path happy.
            return 1L;
        }
    }

    private static final class RecordedTelemetry {
        final long sessionId;
        final JSONObject payload;
        final long arrivalOrder;

        RecordedTelemetry(long sessionId, JSONObject payload, long arrivalOrder) {
            this.sessionId = sessionId;
            this.payload = payload;
            this.arrivalOrder = arrivalOrder;
        }
    }

    private static final class RecordedStatus {
        final long sessionId;
        final String state;
        final String detail;
        final boolean blocked;
        final JSONObject payload;

        RecordedStatus(
                long sessionId, String state, String detail, boolean blocked, JSONObject payload) {
            this.sessionId = sessionId;
            this.state = state;
            this.detail = detail;
            this.blocked = blocked;
            this.payload = payload;
        }
    }

    private static final class RecordedPidObservation {
        final long sessionId;
        final JSONObject payload;
        final long observedAtMs;

        RecordedPidObservation(long sessionId, JSONObject payload, long observedAtMs) {
            this.sessionId = sessionId;
            this.payload = payload;
            this.observedAtMs = observedAtMs;
        }
    }
}
