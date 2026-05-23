package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.volttracker.obdpoc.data.ObdLocalStore;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Verifies that when {@link com.volttracker.obdpoc.data.ObdLocalStore#finalizeSession} throws,
 * {@link SessionRecorder} routes the failure through {@code lifecycleExecutor} and records a {@code
 * persist_failure} status event so the failure is observable rather than silently swallowed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SessionRecorderLifecycleFailureTest {

    @Test
    public void finalizeSessionFailureIsRecordedAsPersistFailureEvent()
            throws InterruptedException {
        RecordingStore store = new RecordingStore();
        File logsDir = new File(System.getProperty("java.io.tmpdir"), "sr-lifecycle-test");
        logsDir.mkdirs();
        Object lock = new Object();
        SessionRecorder recorder = new SessionRecorder(lock, new ObdSessionLog(logsDir), store);

        recorder.openSession(ObdLocalStore.MODE_OBD, "AA:BB:CC:DD:EE:FF", "Test", 1000L);
        // openSession also calls closeSession("",...) on the prior (empty) session — that
        // is a no-op because activeSessionId is 0 at that point.
        recorder.closeSession("disconnected", "test detail", "supported", 7);
        recorder.shutdown();
        // shutdown drains both executors, so by the time we get here the lifecycle write
        // (and its persist_failure follow-up) have completed.

        assertEquals(
                "finalizeSession should have been attempted once", 1, store.finalizeCalls.get());

        // After finalize threw, lifecycleAsync must record a persist_failure event.
        List<RecordedEvent> persistFailures = store.eventsWithKind("persist_failure");
        assertEquals(
                "expected exactly one persist_failure event after finalize threw",
                1,
                persistFailures.size());
        RecordedEvent failure = persistFailures.get(0);
        assertEquals("persist_failure", failure.kind);
        assertEquals("finalizeSession", failure.detail);
        assertTrue("persist_failure event should be flagged blocked", failure.blocked);
        assertNotNull("payload carries diagnostic context", failure.payload);
        assertEquals("finalizeSession", failure.payload.optString("op"));
    }

    /**
     * Subclass of {@link ObdLocalStore} that:
     *
     * <ul>
     *   <li>throws on every {@link #finalizeSession} call (the "failure" under test), and
     *   <li>captures {@link #recordEvent} calls so the test can assert that a {@code
     *       persist_failure} row was queued through the lifecycle path.
     * </ul>
     */
    private static final class RecordingStore extends ObdLocalStore {
        final AtomicInteger finalizeCalls = new AtomicInteger();
        final List<RecordedEvent> events = new ArrayList<>();

        RecordingStore() {
            super(RuntimeEnvironment.getApplication());
        }

        @Override
        public long startSession(
                String mode, String adapterAddress, String adapterName, long startedAtMs) {
            // Return a non-zero id so closeSession enters the finalize branch.
            return 42L;
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
            throw new RuntimeException("simulated finalize failure");
        }

        @Override
        public long recordEvent(
                long sessionId,
                String kind,
                String state,
                String detail,
                boolean blocked,
                JSONObject payload) {
            synchronized (events) {
                events.add(new RecordedEvent(sessionId, kind, state, detail, blocked, payload));
            }
            return events.size();
        }

        List<RecordedEvent> eventsWithKind(String kind) {
            List<RecordedEvent> out = new ArrayList<>();
            synchronized (events) {
                for (RecordedEvent e : events) {
                    if (kind.equals(e.kind)) {
                        out.add(e);
                    }
                }
            }
            return out;
        }
    }

    private static final class RecordedEvent {
        final long sessionId;
        final String kind;
        final String state;
        final String detail;
        final boolean blocked;
        final JSONObject payload;

        RecordedEvent(
                long sessionId,
                String kind,
                String state,
                String detail,
                boolean blocked,
                JSONObject payload) {
            this.sessionId = sessionId;
            this.kind = kind;
            this.state = state;
            this.detail = detail;
            this.blocked = blocked;
            this.payload = payload;
        }
    }

    // Robolectric's main looper is a paused-idle scheduler by default — but our executors are
    // real JVM threads with awaitTermination, so we don't need to drive the looper. Provided as
    // a guardrail in case the test runner config changes.
    @SuppressWarnings("unused")
    private static void awaitBriefly(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
