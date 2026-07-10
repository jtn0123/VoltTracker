package com.volttracker.obdpoc

import com.volttracker.obdpoc.data.ObdLocalStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concurrency-contract tests for [ObdPersistenceWorker], the single-thread executor machinery
 * extracted from [SessionRecorder]. This is the highest concurrency-risk class in the app:
 *
 * - the telemetry executor is a single-thread [java.util.concurrent.ThreadPoolExecutor] backed by a
 *   bounded [java.util.concurrent.LinkedBlockingQueue] plus a custom `DiscardOldestUnlessShutdownPolicy`;
 * - that policy must drop the OLDEST queued task on overflow (keeping the newest), increment the
 *   dropped counter, and — critically — NOT re-execute rejected work once the executor is shutting
 *   down.
 *
 * Tests gate the single worker thread with a [CountDownLatch] (the classic "first task blocks until
 * released" trick) so the queue fills deterministically, never relying on sleeps or timing.
 *
 * The worker needs a non-null [ObdLocalStore] to construct its executors; it only ever calls into
 * the store on the lifecycle-failure path, which these telemetry tests do not exercise. We therefore
 * pass a bare real [ObdLocalStore] built on the Robolectric application context (same construction
 * approach [SessionRecorderTest]'s `RecordingStore` uses). The null-store no-op path is covered by
 * [nullStoreSubmitAwaitDrainAreSafeNoOps].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObdPersistenceWorkerTest {
    /**
     * (1) Overflow / discard-oldest semantics. Block the worker on the first task, then submit more
     * than [ObdPersistenceWorker.TELEMETRY_QUEUE_CAPACITY] additional tasks so the bounded queue
     * overflows. After releasing the worker:
     *   - `drainDroppedTelemetryCount()` is > 0 (the policy dropped oldest queued tasks), and
     *   - the NEWEST submitted task DID run (discard-oldest keeps the most recent work).
     */
    @Test
    @Throws(InterruptedException::class)
    fun overflowDiscardsOldestAndRunsNewest() {
        val worker = ObdPersistenceWorker(newStore())
        try {
            val release = CountDownLatch(1)
            val firstEntered = CountDownLatch(1)

            // First task occupies the single worker thread and blocks, so everything else queues.
            worker.submitTelemetry {
                firstEntered.countDown()
                awaitOrFail(release)
            }
            assertTrue(
                "the first telemetry task should have started on the worker thread",
                firstEntered.await(AWAIT_MS, TimeUnit.MILLISECONDS),
            )

            // Submit far more than the queue can hold while the worker is blocked. Each carries a
            // sequence number; we record which ones actually run.
            val ranSeqs = java.util.Collections.synchronizedSet(HashSet<Int>())
            val overflow = ObdPersistenceWorker.TELEMETRY_QUEUE_CAPACITY + 100
            val newestSeq = overflow - 1
            for (seq in 0 until overflow) {
                worker.submitTelemetry { ranSeqs.add(seq) }
            }

            // Let the worker drain everything that survived the overflow.
            release.countDown()
            worker.awaitTelemetryDrain()

            assertTrue(
                "overflow must have dropped at least one queued telemetry task",
                worker.drainDroppedTelemetryCount() > 0L,
            )
            assertTrue(
                "discard-oldest policy must keep the newest submitted task (seq=$newestSeq)",
                ranSeqs.contains(newestSeq),
            )
        } finally {
            worker.shutdown()
        }
    }

    /**
     * (2) A telemetry task that throws [RuntimeException] is counted via `drainFailedTelemetryCount()`
     * and does NOT kill the worker thread: a task submitted afterward still runs.
     */
    @Test
    @Throws(InterruptedException::class)
    fun throwingTaskIsCountedAndWorkerKeepsRunning() {
        val worker = ObdPersistenceWorker(newStore())
        try {
            worker.submitTelemetry { throw RuntimeException("boom") }

            val laterRan = CountDownLatch(1)
            worker.submitTelemetry { laterRan.countDown() }

            assertTrue(
                "a task submitted after a throwing one must still run (worker survives)",
                laterRan.await(AWAIT_MS, TimeUnit.MILLISECONDS),
            )
            worker.awaitTelemetryDrain()

            assertEquals(
                "the throwing task must be counted exactly once",
                1L,
                worker.drainFailedTelemetryCount(),
            )
        } finally {
            worker.shutdown()
        }
    }

    /**
     * (3) `awaitTelemetryDrain()` blocks until all queued telemetry work has completed. We submit N
     * tasks that each increment a counter and assert the counter equals N immediately after the
     * drain returns — proving the drain waited for them, not racing ahead.
     */
    @Test
    fun awaitTelemetryDrainBlocksUntilQueuedWorkCompletes() {
        val worker = ObdPersistenceWorker(newStore())
        try {
            val ran = AtomicInteger()
            val total = 500
            repeat(total) {
                worker.submitTelemetry { ran.incrementAndGet() }
            }

            worker.awaitTelemetryDrain()

            assertEquals(
                "awaitTelemetryDrain must block until every queued telemetry task has run",
                total,
                ran.get(),
            )
        } finally {
            worker.shutdown()
        }
    }

    /**
     * (4) After `shutdown()`, a freshly submitted telemetry task must NOT run. This guards the
     * `DiscardOldestUnlessShutdownPolicy` branch that throws (rather than discard-then-re-execute)
     * when the executor is shutting down — re-executing during shutdown would let work leak past
     * teardown.
     */
    @Test
    @Throws(InterruptedException::class)
    fun submitAfterShutdownDoesNotRun() {
        val worker = ObdPersistenceWorker(newStore())

        // Run one task pre-shutdown to confirm the worker was live.
        val preRan = CountDownLatch(1)
        worker.submitTelemetry { preRan.countDown() }
        assertTrue(
            "the pre-shutdown task should run",
            preRan.await(AWAIT_MS, TimeUnit.MILLISECONDS),
        )
        worker.awaitTelemetryDrain()

        worker.shutdown()

        val postRan = AtomicBoolean(false)
        worker.submitTelemetry { postRan.set(true) }
        // Drain is a no-op / rejected after shutdown, but give the (dead) executor no chance to run
        // the task by also asserting on the flag directly.
        worker.awaitTelemetryDrain()

        assertFalse(
            "no telemetry task submitted after shutdown may run",
            postRan.get(),
        )
    }

    /**
     * (5) With a null `localStore`, the executors are never created, so `submitTelemetry`,
     * `awaitTelemetryDrain`, and the drain-count accessors are safe no-ops: no exception is thrown,
     * the submitted task never runs, and both counters report 0.
     */
    @Test
    fun nullStoreSubmitAwaitDrainAreSafeNoOps() {
        val worker = ObdPersistenceWorker(null)

        val ran = AtomicBoolean(false)
        // Must not throw despite the absent executor.
        worker.submitTelemetry { ran.set(true) }
        worker.awaitTelemetryDrain()
        worker.shutdown()

        assertFalse("no-op worker must not run submitted telemetry", ran.get())
        assertEquals("dropped count must be 0 for a no-op worker", 0L, worker.drainDroppedTelemetryCount())
        assertEquals("failed count must be 0 for a no-op worker", 0L, worker.drainFailedTelemetryCount())
    }

    @Test
    fun lifecycleRuntimeFailureRecordsPersistFailureEvent() {
        val store = newStore()
        try {
            store.clearAllData()
            val sessionId = store.startSession("obd", "AA:BB:CC:DD:EE:FF", "ELM327", 1_000L)
            val worker = ObdPersistenceWorker(store)

            worker.submitLifecycle(
                { throw IllegalStateException("finalize blew up") },
                sessionId,
                "finalize",
            )
            worker.shutdown()

            val failure = store.getRecentEvents(sessionId, 10).firstOrNull { it.kind == "persist_failure" }
            assertNotNull("lifecycle failure should be recorded as a persist_failure event", failure)
            assertEquals("finalize", failure!!.detail)
            assertTrue("persist failure should be marked blocked", failure.blocked)
            val payload = failure.toJson()
            assertEquals("finalize", payload.optString("op"))
            assertEquals(IllegalStateException::class.java.name, payload.optString("exception"))
            assertEquals("finalize blew up", payload.optString("message"))
        } finally {
            store.close()
        }
    }

    @Test
    fun lifecycleSubmitAfterShutdownRunsInlineFallback() {
        val store = newStore()
        try {
            val worker = ObdPersistenceWorker(store)
            worker.shutdown()
            val ranInline = AtomicBoolean(false)

            worker.submitLifecycle({ ranInline.set(true) }, 0L, "post_shutdown")

            assertTrue("post-shutdown lifecycle work should run through inline fallback", ranInline.get())
        } finally {
            store.close()
        }
    }

    @Test
    fun lifecycleInlineFallbackFailureIsRecorded() {
        val store = newStore()
        try {
            store.clearAllData()
            val sessionId = store.startSession("obd", "AA:BB:CC:DD:EE:FF", "ELM327", 1_000L)
            val worker = ObdPersistenceWorker(store)
            worker.shutdown()

            worker.submitLifecycle(
                { throw IllegalArgumentException("inline failed") },
                sessionId,
                "post_shutdown",
            )

            val failure = store.getRecentEvents(sessionId, 10).firstOrNull { it.kind == "persist_failure" }
            assertNotNull("inline fallback failure should also be recorded", failure)
            assertEquals("post_shutdown", failure!!.detail)
            assertEquals(IllegalArgumentException::class.java.name, failure.toJson().optString("exception"))
        } finally {
            store.close()
        }
    }

    @Test
    fun shutdownTimeoutInterruptsWorkerAndReportsIncompleteTermination() {
        val store = newStore()
        val worker =
            ObdPersistenceWorker(
                store,
                shutdownDrainTimeoutMs = 20L,
                shutdownForceTimeoutMs = 20L,
            )
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            worker.submitTelemetry {
                entered.countDown()
                while (release.count > 0L) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        interrupted.countDown()
                        // Simulate a native/SQLite call that does not honor interruption promptly.
                    }
                }
            }
            assertTrue("blocked worker should start", entered.await(AWAIT_MS, TimeUnit.MILLISECONDS))

            val result = worker.shutdown()

            assertTrue(
                "timed-out shutdown must interrupt the worker",
                interrupted.await(AWAIT_MS, TimeUnit.MILLISECONDS),
            )
            assertFalse("an interruption-ignoring worker must be reported as alive", result.telemetryTerminated)
            assertTrue("idle lifecycle executor should terminate normally", result.lifecycleTerminated)
            assertFalse("caller must not close SQLite after an incomplete drain", result.fullyTerminated)
        } finally {
            release.countDown()
            store.close()
        }
    }

    // ---- helpers ------------------------------------------------------------------

    /**
     * A bare, real [ObdLocalStore] backed by the Robolectric application context. The telemetry
     * tests never call into it; it exists only so the worker constructs its executors (a non-null
     * store is the trigger). Mirrors how [SessionRecorderTest]'s `RecordingStore` is built.
     */
    private fun newStore(): ObdLocalStore = ObdLocalStore(RuntimeEnvironment.getApplication())

    private companion object {
        private const val AWAIT_MS = 5_000L

        private fun awaitOrFail(latch: CountDownLatch) {
            try {
                if (!latch.await(AWAIT_MS, TimeUnit.MILLISECONDS)) {
                    throw AssertionError("latch was not released within $AWAIT_MS ms")
                }
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AssertionError("interrupted while awaiting latch", ex)
            }
        }
    }
}
