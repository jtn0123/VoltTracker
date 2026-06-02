package com.volttracker.obdpoc;

import static com.volttracker.obdpoc.ObdElmDecode.safeMessage;

import android.util.Log;
import com.volttracker.obdpoc.data.ObdLocalStore;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Owns the async-persistence machinery extracted from {@link SessionRecorder}: the bounded
 * telemetry/lifecycle queues, their single-thread executors, the discard-oldest backpressure
 * policy, the dropped-task counter, and the drain/shutdown plumbing. {@code SessionRecorder}
 * decides <em>what</em> to persist (reading mutable session state under its lock); this worker
 * decides <em>how</em> it runs.
 *
 * <p>This is a behavior-preserving extraction — the executor configuration, rejection semantics,
 * drain ordering, and failure-visibility paths match what previously lived inline in {@code
 * SessionRecorder} exactly.
 */
final class ObdPersistenceWorker {

    // Telemetry queue cap: 2000 entries × ~200 bytes/row ≈ ~400 KB worst-case backlog.
    // Generous for normal driving (1 Hz × 30 minutes = 1800 rows) yet bounded so a stalled
    // SQLite writer cannot grow the process heap during long scan-mode sessions or under
    // battery-saver-induced I/O lag. On overflow we keep the most recent telemetry and drop
    // the oldest queued row — telemetry loss is acceptable here; OOM is not.
    static final int TELEMETRY_QUEUE_CAPACITY = 2000;
    // Lifecycle queue cap: very small (lifecycle events fire a handful of times per session
    // — startSession / closeSession / finalizeSession). If we ever overflow this, the existing
    // inline-run fallback at submitLifecycle handles the RejectedExecutionException.
    static final int LIFECYCLE_QUEUE_CAPACITY = 64;

    private final ObdLocalStore localStore;
    private final AtomicLong droppedTelemetryTasks = new AtomicLong();

    // telemetryExecutor: high-volume telemetry/event/observation writes. Failures are
    // intentionally swallowed — these rows are diagnostic-only, never block OBD polling.
    // DiscardOldestUnlessShutdown: under sustained backpressure during normal operation,
    // drop the oldest queued telemetry rather than block the poll thread or grow the queue
    // without bound. AFTER shutdown, behave like AbortPolicy so awaitTelemetryDrain's
    // RejectedExecutionException catch path runs immediately instead of waiting for a marker
    // that will never be processed. Without the shutdown-aware branch, shutdown ↔ lifecycle
    // ordering races can leave finalize waiting behind a marker that cannot run.
    private final ExecutorService telemetryExecutor =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(TELEMETRY_QUEUE_CAPACITY),
                    new DiscardOldestUnlessShutdownPolicy(droppedTelemetryTasks));
    // lifecycleExecutor: session lifecycle writes (closeSession / finalizeSession).
    // Failures are surfaced via Log.e AND a persist_failure status_events row, so a
    // failed finalize cannot silently leave a session marked active forever.
    // AbortPolicy: lifecycle drops are NOT acceptable — the inline-run fallback at
    // submitLifecycle catches RejectedExecutionException and runs the task on the
    // caller thread instead, so the finalize still lands.
    private final ExecutorService lifecycleExecutor =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(LIFECYCLE_QUEUE_CAPACITY),
                    new ThreadPoolExecutor.AbortPolicy());

    ObdPersistenceWorker(ObdLocalStore localStore) {
        this.localStore = localStore;
    }

    // Custom telemetry rejection handler: DiscardOldestPolicy semantics in normal operation,
    // AbortPolicy semantics post-shutdown. See the telemetryExecutor comment for why.
    private static final class DiscardOldestUnlessShutdownPolicy
            implements RejectedExecutionHandler {
        private final AtomicLong droppedCount;

        DiscardOldestUnlessShutdownPolicy(AtomicLong droppedCount) {
            this.droppedCount = droppedCount;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) {
                throw new RejectedExecutionException("telemetry executor shut down");
            }
            if (executor.getQueue().poll() != null) {
                long dropped = droppedCount.incrementAndGet();
                Log.w(
                        MainActivity.TAG,
                        "telemetry queue full; dropped oldest telemetry task #" + dropped);
            }
            executor.execute(r);
        }
    }

    /**
     * Reads and clears the dropped-telemetry counter (used to record a telemetry_dropped event).
     */
    long drainDroppedTelemetryCount() {
        return droppedTelemetryTasks.getAndSet(0L);
    }

    /** Runs {@code task} on the telemetry executor — off the OBD poll and main threads. */
    void submitTelemetry(Runnable task) {
        try {
            telemetryExecutor.execute(
                    () -> {
                        try {
                            task.run();
                        } catch (RuntimeException ignored) {
                            // Persistence is diagnostic; never interrupt OBD polling for it.
                        }
                    });
        } catch (RejectedExecutionException ignored) {
        }
    }

    // Lifecycle writes (session finalize) get visibility on failure: a Log.e plus a
    // best-effort status_events row with kind=persist_failure so the next launch can see
    // that the session was supposed to be finalized but wasn't.
    //
    // If the lifecycle executor itself rejects the task (e.g. shutdown raced with a final
    // close), we fall back to a synchronous in-thread run: dropping the finalize silently
    // would leave the session row marked active forever.
    void submitLifecycle(Runnable task, long sessionId, String op) {
        try {
            lifecycleExecutor.execute(
                    () -> {
                        try {
                            task.run();
                        } catch (RuntimeException ex) {
                            Log.e(MainActivity.TAG, "session lifecycle persist failed", ex);
                            recordPersistFailure(sessionId, op, ex);
                        }
                    });
        } catch (RejectedExecutionException ex) {
            Log.e(MainActivity.TAG, "lifecycle executor rejected " + op + "; running inline", ex);
            try {
                task.run();
            } catch (RuntimeException inner) {
                Log.e(MainActivity.TAG, "inline lifecycle fallback failed for " + op, inner);
                recordPersistFailure(sessionId, op, inner);
            }
        }
    }

    /**
     * Submits a no-op to {@link #telemetryExecutor} and waits for it to run. Because the executor
     * is single-threaded with FIFO ordering, by the time our marker task runs every
     * previously-submitted telemetry write has already finished. Used before finalize/materialize
     * so those code paths see the full session in the database.
     */
    void awaitTelemetryDrain() {
        try {
            Future<?> marker = telemetryExecutor.submit(() -> {});
            marker.get(30, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ex) {
            // Submit was rejected because the executor entered shutdown. That tells us OUR
            // marker won't run — it does NOT tell us the previously-queued telemetry tasks
            // have finished. shutdown() is orderly: already-queued tasks continue executing
            // until the worker drains them. So we explicitly wait for termination (which
            // returns immediately if the executor has already terminated) before letting
            // finalize/materialize read from the database.
            try {
                telemetryExecutor.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ex) {
            Log.w(MainActivity.TAG, "telemetry drain marker failed", ex.getCause());
        } catch (TimeoutException ex) {
            Log.w(MainActivity.TAG, "telemetry drain timed out", ex);
        }
    }

    // Best-effort: this itself can throw if the database is the source of the trouble,
    // so we catch and log without escalating.
    private void recordPersistFailure(long sessionId, String op, RuntimeException cause) {
        if (localStore == null) {
            return;
        }
        try {
            JSONObject payload = new JSONObject();
            try {
                payload.put("op", op);
                payload.put("exception", cause.getClass().getName());
                payload.put("message", safeMessage(cause));
                payload.put("updatedAt", System.currentTimeMillis());
            } catch (JSONException ignored) {
                // Local values are safe.
            }
            localStore.recordEvent(sessionId, "persist_failure", "", op, true, payload);
        } catch (RuntimeException ex) {
            Log.e(MainActivity.TAG, "recording persist_failure also failed", ex);
        }
    }

    /** Drains pending database writes and shuts both recording executors down. */
    void shutdown() {
        telemetryExecutor.shutdown();
        lifecycleExecutor.shutdown();
        try {
            telemetryExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        try {
            lifecycleExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
