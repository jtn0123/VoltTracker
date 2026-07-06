package com.volttracker.obdpoc

import com.volttracker.obdpoc.data.ObdLocalStore
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Behavior tests for [SessionRecorder]: verifies the single-thread executor contract — calls queued
 * through `telemetryExecutor` are processed in FIFO order, lifecycle writes go through a separate
 * `lifecycleExecutor`, and [SessionRecorder.shutdown] drains both before returning. The
 * lifecycle-failure path is covered separately in [SessionRecorderLifecycleFailureTest]; these tests
 * focus on the in-order persistence contract, close-then-no-persist behavior, status/PID routing,
 * and executor draining on shutdown.
 *
 * Pattern matches [SessionRecorderLifecycleFailureTest]: a [RecordingStore] subclass of
 * [ObdLocalStore] captures method calls in arrival order so the test can assert what the recorder
 * actually queued.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionRecorderTest {
    /**
     * Telemetry writes from 4 concurrent threads end up persisted in per-thread FIFO order — the
     * single-thread `telemetryExecutor` preserves the submission order of every Runnable a given
     * thread offered, even though offerings from different threads interleave.
     */
    @Test
    @Throws(InterruptedException::class)
    fun recordTelemetryPreservesPerThreadFifoOrderUnderConcurrentSubmission() {
        val threads = 4
        val writesPerThread = 25
        val store = RecordingStore()
        val recorder = newOpenRecorder(store, "sr-fifo-test")

        val startGate = CountDownLatch(1)
        val finishedSubmitting = CountDownLatch(threads)

        for (t in 0 until threads) {
            val threadId = t
            Thread(
                {
                    try {
                        startGate.await()
                    } catch (ex: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                    for (seq in 0 until writesPerThread) {
                        recorder.persistTelemetry(taggedTelemetry(threadId, seq))
                    }
                    finishedSubmitting.countDown()
                },
                "telemetry-sender-$t",
            ).start()
        }

        startGate.countDown()
        assertTrue(
            "all sender threads should have submitted in time",
            finishedSubmitting.await(AWAIT_MS, TimeUnit.MILLISECONDS),
        )
        // shutdown() drains telemetryExecutor (and lifecycleExecutor) before returning.
        recorder.shutdown()

        assertEquals(threads * writesPerThread, store.telemetryCalls.size)

        // Per-thread sequences must be strictly increasing in arrival order — this is what the
        // single-thread executor buys us. (Across threads, interleaving is fine.)
        val lastSeqByThread = HashMap<Int, Int>()
        for (rec in store.telemetryCalls) {
            val tid = rec.payload.optInt("threadId", -1)
            val seq = rec.payload.optInt("seq", -1)
            assertTrue("payload should carry threadId", tid >= 0)
            assertTrue("payload should carry seq", seq >= 0)
            val prev = lastSeqByThread[tid]
            if (prev != null) {
                assertTrue(
                    "thread $tid seq must be monotonic: prev=$prev now=$seq",
                    seq > prev,
                )
            }
            lastSeqByThread[tid] = seq
        }
        assertEquals(
            "every thread should have written all of its rows",
            threads,
            lastSeqByThread.size,
        )
        for (e in lastSeqByThread.entries) {
            assertEquals(
                "thread ${e.key} final seq",
                writesPerThread - 1,
                e.value,
            )
        }
    }

    /**
     * After `shutdown()` returns, every telemetry write submitted before `closeSession` has been
     * processed, and the finalize call on the `lifecycleExecutor` has also run. The telemetry queue
     * is processed in FIFO order, so the sequence captured by the fake store matches the submission
     * sequence.
     */
    @Test
    @Throws(InterruptedException::class)
    fun closeSessionAndPendingTelemetryBothCompleteAfterShutdown() {
        val writes = 50
        val store = RecordingStore()
        val recorder = newOpenRecorder(store, "sr-close-after-test")

        for (seq in 0 until writes) {
            recorder.persistTelemetry(taggedTelemetry(0, seq))
        }
        // Submits finalize on lifecycleExecutor; does NOT block on the telemetry queue.
        recorder.closeSession("disconnected", "test", "supported", writes)
        // shutdown() awaits termination of BOTH executors, so by the time it returns all
        // pre-shutdown work has either completed or been rejected. We assert nothing was
        // dropped: all 50 telemetry calls landed, and finalize ran exactly once.
        recorder.shutdown()

        assertEquals("all telemetry writes must be persisted", writes, store.telemetryCalls.size)
        assertEquals("finalize must run exactly once", 1, store.finalizeCalls.get())

        // Within the telemetry executor, FIFO order is preserved: seq 0..N-1 in order.
        for (i in 0 until writes) {
            val seenSeq = store.telemetryCalls[i].payload.optInt("seq", -1)
            assertEquals("telemetry FIFO order at index $i", i, seenSeq)
        }
    }

    @Test
    @Throws(InterruptedException::class)
    fun telemetryQueueOverflowRecordsDroppedEventBeforeFinalize() {
        val writes = SessionRecorder.TELEMETRY_QUEUE_CAPACITY + 50
        val store = RecordingStore()
        store.blockTelemetryWrites = true
        val recorder = newOpenRecorder(store, "sr-overflow-test")

        recorder.persistTelemetry(taggedTelemetry(0, 0))
        assertTrue(
            "first telemetry write should enter the blocked fake store",
            store.firstTelemetryEntered.await(AWAIT_MS, TimeUnit.MILLISECONDS),
        )

        for (seq in 1 until writes) {
            recorder.persistTelemetry(taggedTelemetry(0, seq))
        }

        store.releaseTelemetryWrites.countDown()
        recorder.closeSession("disconnected", "overflow-test", "supported", writes)
        recorder.shutdown()

        assertTrue(
            "overflow should drop some queued telemetry",
            store.telemetryCalls.size < writes,
        )
        val latest = store.telemetryCalls[store.telemetryCalls.size - 1]
        assertEquals(
            "discard-oldest policy should retain the newest submitted telemetry",
            writes - 1,
            latest.payload.optInt("seq", -1),
        )
        val dropped = store.onlyEvent("telemetry_dropped")
        assertTrue(
            "telemetry_dropped must be recorded before finalizeSession",
            dropped.arrivalOrder < store.finalizeArrivalOrder.get(),
        )
        assertEquals(SESSION_ID, dropped.sessionId)
        assertEquals("persist", dropped.state)
        assertFalse(dropped.blocked)
        assertTrue(dropped.payload!!.optLong("dropped") > 0L)
        assertTrue(dropped.detail!!.contains("queued telemetry writes"))
    }

    @Test
    fun telemetryPersistenceFailureIsCountedAndDoesNotCrash() {
        val store = RecordingStore()
        store.failTelemetryWrites = true
        val recorder = newOpenRecorder(store, "sr-telemetry-failure-test")

        recorder.persistTelemetry(taggedTelemetry(0, 0))
        recorder.shutdown()

        assertEquals(1L, recorder.drainFailedTelemetryCount())
        assertEquals(0, store.telemetryCalls.size)
    }

    /**
     * Telemetry submitted AFTER `closeSession` is dropped — it cannot leak into the just-closed
     * session id. `SessionRecorder.persistTelemetry` guards on `activeSessionId > 0`, which
     * `closeSession` clears synchronously under the shared lock.
     */
    @Test
    @Throws(InterruptedException::class)
    fun persistTelemetryAfterCloseIsDropped() {
        val store = RecordingStore()
        val recorder = newOpenRecorder(store, "sr-after-close-test")

        recorder.persistTelemetry(taggedTelemetry(0, 0))
        recorder.closeSession("disconnected", "before-extra-write", "supported", 1)
        // Post-close write: must be a no-op (activeSessionId == 0 by the time we return from
        // closeSession, which holds the shared lock while clearing it).
        recorder.persistTelemetry(taggedTelemetry(99, 99))
        recorder.shutdown()

        assertEquals(
            "exactly one telemetry row should be persisted (the post-close one is dropped)",
            1,
            store.telemetryCalls.size,
        )
        val only = store.telemetryCalls[0]
        assertEquals("the persisted row must be the pre-close one", SESSION_ID, only.sessionId)
        assertEquals(0, only.payload.optInt("threadId", -1))
        assertEquals(0, only.payload.optInt("seq", -1))

        // The post-close write must not have been routed to the old session id either.
        for (rec in store.telemetryCalls) {
            assertFalse(
                "post-close payload (threadId=99) must not appear in the persisted stream",
                rec.payload.optInt("threadId", -1) == 99,
            )
        }
    }

    /**
     * Status events submitted via `persistStatus` reach the store as a `recordStatus` call (which
     * delegates to `recordEvent` with kind=`"status"`) carrying the expected state + detail +
     * payload. The call is dispatched off the test thread — we observe it after `shutdown()` drains
     * the telemetry executor.
     */
    @Test
    @Throws(InterruptedException::class)
    fun persistStatusRoutesThroughTelemetryExecutor() {
        val store = RecordingStore()
        val recorder = newOpenRecorder(store, "sr-status-test")

        val payload = JSONObject()
        try {
            payload.put("note", "diagnostic")
        } catch (ex: JSONException) {
            throw AssertionError(ex)
        }
        recorder.persistStatus("connecting", "handshake", false, payload)
        recorder.shutdown()

        val statuses = store.statusCalls
        assertEquals("expected exactly one recordStatus call", 1, statuses.size)
        val s = statuses[0]
        assertEquals(SESSION_ID, s.sessionId)
        assertEquals("connecting", s.state)
        assertEquals("handshake", s.detail)
        assertFalse(s.blocked)
        assertNotNull(s.payload)
        assertEquals("diagnostic", s.payload!!.optString("note"))
        // The status executor was not the recorder thread — we can't easily prove a different
        // thread without instrumentation, but we can prove the work landed by the time
        // shutdown() returned, which is what callers actually rely on.
    }

    /**
     * `shutdown()` drains BOTH executors. Submit a healthy mix of telemetry, status, lifecycle
     * (close), and arbitrary runAsync work, then assert that every queued task ran before
     * `shutdown()` returned.
     */
    @Test
    @Throws(InterruptedException::class)
    fun shutdownDrainsBothExecutors() {
        val telemetryWrites = 10
        val runAsyncJobs = 5
        val store = RecordingStore()
        val recorder = newOpenRecorder(store, "sr-drain-test")

        val asyncRan = AtomicInteger()
        for (i in 0 until telemetryWrites) {
            recorder.persistTelemetry(taggedTelemetry(0, i))
        }
        recorder.persistStatus("connecting", "drain-test", false, JSONObject())
        for (i in 0 until runAsyncJobs) {
            recorder.runAsync { asyncRan.incrementAndGet() }
        }
        // Lifecycle work on the separate lifecycleExecutor.
        recorder.closeSession("disconnected", "drain-test", "supported", telemetryWrites)

        recorder.shutdown()

        assertEquals(
            "all telemetry writes must have run before shutdown returned",
            telemetryWrites,
            store.telemetryCalls.size,
        )
        assertEquals(
            "all runAsync jobs must have run before shutdown returned",
            runAsyncJobs,
            asyncRan.get(),
        )
        assertEquals("status call must have run", 1, store.statusCalls.size)
        assertEquals("finalize must have run", 1, store.finalizeCalls.get())
    }

    /**
     * `logCommand` routes through `persistPidObservation`, which queues a `recordPidObservation`
     * call on the telemetry executor. After shutdown, the call carries the expected command + raw
     * response captured by the fake.
     */
    @Test
    @Throws(InterruptedException::class)
    fun logCommandRoutesPidObservationThroughTelemetryExecutor() {
        val store = RecordingStore()
        val recorder = newOpenRecorder(store, "sr-pid-test")

        // 0105 = engine coolant temperature; response "41 05 7B" -> 0x7B - 40 = 83°C.
        recorder.logCommand("0105", 1_000L, 25L, "41 05 7B")
        recorder.shutdown()

        assertEquals("expected exactly one PID observation", 1, store.pidObservationCalls.size)
        val obs = store.pidObservationCalls[0]
        assertEquals(SESSION_ID, obs.sessionId)
        assertNotNull(obs.payload)
        assertEquals("0105", obs.payload!!.optString("command"))
        assertTrue(
            "raw response should be captured in the observation payload",
            obs.payload!!.optString("rawResponse").contains("41 05 7B"),
        )
        assertTrue("observedAtMs should be set", obs.observedAtMs > 0L)
    }

    @Test
    @Throws(InterruptedException::class)
    fun scanModeBatchesPidObservationsUntilShutdown() {
        val store = RecordingStore()
        val recorder = newOpenScanRecorder(store, "sr-pid-batch-test")

        recorder.logCommand("0105", 1_000L, 25L, "41 05 7B")
        recorder.logCommand("010C", 1_000L, 20L, "41 0C 1A F8")
        assertEquals("scan observations should wait for the batch flush", 0, store.pidObservationCalls.size)

        recorder.shutdown()

        assertEquals("shutdown must flush the partial scan batch", 2, store.pidObservationCalls.size)
        assertEquals("0105", store.pidObservationCalls[0].payload!!.optString("command"))
        assertEquals("010C", store.pidObservationCalls[1].payload!!.optString("command"))
        assertEquals(1, store.pidBatchCalls.get())
    }

    /**
     * Calling `shutdown()` twice is safe: a second shutdown on an already-terminated executor
     * returns cleanly without throwing. Defends against a service teardown path that may
     * double-call (e.g. `onDestroy` after a manual stop).
     */
    @Test
    @Throws(InterruptedException::class)
    fun doubleShutdownIsSafe() {
        val store = RecordingStore()
        val recorder = newOpenRecorder(store, "sr-double-shutdown-test")

        recorder.persistTelemetry(taggedTelemetry(0, 0))
        recorder.shutdown()
        // Second shutdown — must not throw or leak.
        recorder.shutdown()

        assertEquals(1, store.telemetryCalls.size)
    }

    /**
     * B1: a flapping adapter that alternates between two DISTINCT status details bypasses the
     * content+time dedupe (every `state|detail|blocked` key looks new), so without a
     * content-independent cap the events table bloats under a bad connection. Submit far more
     * alternating-detail statuses than the rolling-window cap allows and assert the cap engages —
     * the number of persisted `recordStatus` calls is bounded by `STATUS_RATE_MAX_PER_WINDOW`, not
     * by the number submitted.
     */
    @Test
    @Throws(InterruptedException::class)
    fun alternatingDetailFlappingIsThrottledByCountCap() {
        val store = RecordingStore()
        val recorder = newOpenRecorder(store, "sr-flapping-test")

        // Submit a tight flapping loop: each call has a distinct detail, so the content+time
        // dedupe never fires. All happen within the same rolling window (the test thread runs
        // far faster than STATUS_RATE_WINDOW_MS).
        val submitted = SessionRecorder.STATUS_RATE_MAX_PER_WINDOW * 4
        for (i in 0 until submitted) {
            val payload = JSONObject()
            try {
                payload.put("seq", i)
            } catch (ex: JSONException) {
                throw AssertionError(ex)
            }
            // Alternate between two distinct details AND vary by seq so no two consecutive keys
            // are identical — this is exactly the case the content+time dedupe cannot catch.
            recorder.persistStatus(
                "connecting",
                (if (i % 2 == 0) "A" else "B") + "-" + i,
                false,
                payload,
            )
        }
        recorder.shutdown()

        assertTrue(
            "flapping submitted " +
                submitted +
                " statuses but the rolling-window cap of " +
                SessionRecorder.STATUS_RATE_MAX_PER_WINDOW +
                " must bound persisted writes; got " +
                store.statusCalls.size,
            store.statusCalls.size <= SessionRecorder.STATUS_RATE_MAX_PER_WINDOW,
        )
        assertTrue(
            "the cap should still let the early transitions through",
            store.statusCalls.size >= 1,
        )
    }

    /**
     * Sanity check that the count cap does not over-throttle: a handful of genuine, distinct state
     * transitions (well under the cap) all reach the store.
     */
    @Test
    @Throws(InterruptedException::class)
    fun genuineStateTransitionsAreNotThrottledByCountCap() {
        val store = RecordingStore()
        val recorder = newOpenRecorder(store, "sr-transitions-test")

        val states = arrayOf("connecting", "connected", "scanning", "scan-complete", "disconnected")
        for (i in states.indices) {
            val payload = JSONObject()
            try {
                payload.put("seq", i)
            } catch (ex: JSONException) {
                throw AssertionError(ex)
            }
            recorder.persistStatus(states[i], "transition-$i", false, payload)
        }
        recorder.shutdown()

        assertEquals(
            "all genuine transitions (under the cap) must persist",
            states.size,
            store.statusCalls.size,
        )
    }

    /**
     * Demo runs are never written to the session summary store: they aren't real adapter
     * connections, so summarizing them would pollute the dashboard's "last connected" line (it
     * would read "Demo stream - Nm ago"). A real session still produces exactly one summary row.
     */
    @Test
    fun demoSessionsAreNotSummarized() {
        SessionSummaryStore.resetForTests()
        val filesDir =
            File(System.getProperty("java.io.tmpdir"), "summary-store-" + System.nanoTime())
        filesDir.mkdirs()
        val summary = SessionSummaryStore.getInstance(filesDir)

        // A demo session open+close must leave the summary store empty.
        val demo = newRecorderWithSummary(RecordingStore(), "rec-demo", summary)
        demo.openSession(ObdLocalStore.MODE_DEMO, "", "Demo stream", 1_000L)
        demo.closeSession("complete", "demo done", "", 5)
        demo.shutdown()
        assertTrue(
            "demo sessions must not appear in the summary store",
            summary.getRecent(5).isEmpty(),
        )

        // A real session must produce exactly one summary row carrying its adapter name.
        val real = newRecorderWithSummary(RecordingStore(), "rec-real", summary)
        real.openSession(ObdLocalStore.MODE_OBD, "AA:BB:CC:DD:EE:FF", "OBDLink MX+", 2_000L)
        real.closeSession("complete", "ok", "0100", 42)
        real.shutdown()
        val recent = summary.getRecent(5)
        assertEquals("real session must be summarized", 1, recent.size)
        assertEquals("OBDLink MX+", recent[0].adapter)
    }

    /**
     * Storage-failure sessions are exactly the ones the troubleshooter needs summaries for: when
     * the per-session `.jsonl` fails to open (the recorder logs "session_log_unavailable"), the
     * summary store must still record both the start AND the end of the session — previously
     * recordEnd was gated on the detail log being open, so those sessions silently vanished from
     * `sessions-summary.jsonl`.
     */
    @Test
    fun summaryEndIsRecordedEvenWhenTheDetailLogFailsToOpen() {
        SessionSummaryStore.resetForTests()
        val filesDir =
            File(System.getProperty("java.io.tmpdir"), "summary-store-" + System.nanoTime())
        filesDir.mkdirs()
        val summary = SessionSummaryStore.getInstance(filesDir)

        // A plain FILE where the logs directory should be makes ObdSessionLog.open() fail
        // (mkdirs fails and it isn't a directory), so the detail log never opens.
        val bogusLogsDir =
            File(System.getProperty("java.io.tmpdir"), "sr-bad-logs-" + System.nanoTime())
        assertTrue(bogusLogsDir.createNewFile())
        val sessionLog = ObdSessionLog(bogusLogsDir)
        val recorder = SessionRecorder(Any(), sessionLog, RecordingStore(), summary, null)

        recorder.openSession(ObdLocalStore.MODE_OBD, "AA:BB:CC:DD:EE:FF", "OBDLink MX+", 3_000L)
        assertFalse("precondition: the detail log must have failed to open", sessionLog.isOpen())
        recorder.closeSession("connected", "ok", "0100", 7)
        recorder.shutdown()

        val recent = summary.getRecent(5)
        assertEquals(
            "the session summary must record start AND end despite the failed detail log",
            1,
            recent.size,
        )
        assertEquals(3_000L, recent[0].startMs)
        assertTrue("endMs must be recorded", recent[0].endMs >= recent[0].startMs)
        assertEquals("OBDLink MX+", recent[0].adapter)
        assertEquals(SessionSummary.OUTCOME_SUCCESS, recent[0].outcome)
    }

    @Test
    fun outcomeForTreatsCodesClearedAsSuccess() {
        // A DTC-clear session broadcasts "codes-cleared" with no telemetry samples; a successful
        // clear must record as SUCCESS, not fall through to ABORTED.
        assertEquals(SessionSummary.OUTCOME_SUCCESS, SessionRecorder.outcomeFor("codes-cleared", 0))
        // The neighbouring success/failure states are unchanged.
        assertEquals(SessionSummary.OUTCOME_SUCCESS, SessionRecorder.outcomeFor("scan-complete", 0))
        assertEquals(SessionSummary.OUTCOME_FAILED, SessionRecorder.outcomeFor("error", 0))
        assertEquals(SessionSummary.OUTCOME_ABORTED, SessionRecorder.outcomeFor("idle", 0))
    }

    // ---- helpers ------------------------------------------------------------------

    private fun newRecorderWithSummary(
        store: RecordingStore,
        dirName: String,
        summary: SessionSummaryStore,
    ): SessionRecorder {
        val logsDir = File(System.getProperty("java.io.tmpdir"), dirName + System.nanoTime())
        logsDir.mkdirs()
        return SessionRecorder(Any(), ObdSessionLog(logsDir), store, summary, null)
    }

    private fun newOpenRecorder(
        store: RecordingStore,
        dirName: String,
    ): SessionRecorder {
        val logsDir = File(System.getProperty("java.io.tmpdir"), dirName)
        logsDir.mkdirs()
        val lock = Any()
        val recorder = SessionRecorder(lock, ObdSessionLog(logsDir), store)
        recorder.openSession(ObdLocalStore.MODE_OBD, "AA:BB:CC:DD:EE:FF", "Test", 1_000L)
        return recorder
    }

    private fun newOpenScanRecorder(
        store: RecordingStore,
        dirName: String,
    ): SessionRecorder {
        val logsDir = File(System.getProperty("java.io.tmpdir"), dirName)
        logsDir.mkdirs()
        val recorder = SessionRecorder(Any(), ObdSessionLog(logsDir), store)
        recorder.openSession(ObdLocalStore.MODE_SCAN, "AA:BB:CC:DD:EE:FF", "Test", 1_000L)
        return recorder
    }

    // ---- fake store ---------------------------------------------------------------

    /**
     * Subclass of [ObdLocalStore] that captures calls in arrival order. Each captured-call list is
     * wrapped in its own synchronized block on append so concurrent submissions from the recorder's
     * executors are safe.
     */
    private class RecordingStore : ObdLocalStore(RuntimeEnvironment.getApplication()) {
        val finalizeCalls = AtomicInteger()
        val finalizeArrivalOrder = AtomicLong()
        val telemetryCalls = ArrayList<RecordedTelemetry>()
        val statusCalls = ArrayList<RecordedStatus>()
        val pidObservationCalls = ArrayList<RecordedPidObservation>()
        val pidBatchCalls = AtomicInteger()
        val eventCalls = ArrayList<RecordedEvent>()
        val arrivalCounter = AtomicLong()

        @Volatile var blockTelemetryWrites = false

        @Volatile var failTelemetryWrites = false
        val firstTelemetryEntered = CountDownLatch(1)
        val releaseTelemetryWrites = CountDownLatch(1)

        override fun startSession(
            mode: String?,
            adapterAddress: String?,
            adapterName: String?,
            startedAtMs: Long,
        ): Long {
            // Non-zero so the recorder enters the "session is active" branches.
            return SESSION_ID
        }

        override fun recordTelemetry(
            sessionId: Long,
            sample: JSONObject?,
            capturedAtMs: Long?,
        ): Long {
            if (failTelemetryWrites) {
                throw RuntimeException("simulated telemetry write failure")
            }
            if (blockTelemetryWrites) {
                firstTelemetryEntered.countDown()
                try {
                    releaseTelemetryWrites.await(AWAIT_MS, TimeUnit.MILLISECONDS)
                } catch (ex: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            val arrival = arrivalCounter.incrementAndGet()
            synchronized(telemetryCalls) {
                telemetryCalls.add(RecordedTelemetry(sessionId, sample!!, arrival))
            }
            return arrival
        }

        override fun recordStatus(
            sessionId: Long,
            state: String?,
            detail: String?,
            blocked: Boolean,
            payload: JSONObject?,
        ): Long {
            synchronized(statusCalls) {
                statusCalls.add(RecordedStatus(sessionId, state, detail, blocked, payload))
            }
            return statusCalls.size.toLong()
        }

        override fun recordPidObservation(
            sessionId: Long,
            observation: JSONObject?,
            observedAtMs: Long,
        ): Long {
            synchronized(pidObservationCalls) {
                pidObservationCalls.add(
                    RecordedPidObservation(sessionId, observation, observedAtMs),
                )
            }
            return pidObservationCalls.size.toLong()
        }

        override fun recordPidObservations(
            sessionId: Long,
            observations: List<JSONObject>,
        ): Int {
            pidBatchCalls.incrementAndGet()
            synchronized(pidObservationCalls) {
                for (observation in observations) {
                    pidObservationCalls.add(
                        RecordedPidObservation(
                            sessionId,
                            observation,
                            observation.optLong("observedAtMs", 0L),
                        ),
                    )
                }
            }
            return observations.size
        }

        override fun finalizeSession(
            sessionId: Long,
            status: String?,
            endedAtMs: Long,
            supportedPids: String?,
            address: String?,
            adapterName: String?,
            mode: String?,
            sampleCount: Int,
            lastEventDetail: String?,
        ) {
            finalizeArrivalOrder.compareAndSet(0L, arrivalCounter.incrementAndGet())
            finalizeCalls.incrementAndGet()
        }

        override fun recordEvent(
            sessionId: Long,
            kind: String?,
            state: String?,
            detail: String?,
            blocked: Boolean,
            payload: JSONObject?,
        ): Long {
            val arrival = arrivalCounter.incrementAndGet()
            synchronized(eventCalls) {
                eventCalls.add(
                    RecordedEvent(sessionId, kind, state, detail, blocked, payload, arrival),
                )
                return eventCalls.size.toLong()
            }
        }

        fun onlyEvent(kind: String): RecordedEvent {
            val matches = ArrayList<RecordedEvent>()
            synchronized(eventCalls) {
                for (event in eventCalls) {
                    if (kind == event.kind) {
                        matches.add(event)
                    }
                }
            }
            assertEquals("expected one event of kind $kind", 1, matches.size)
            return matches[0]
        }
    }

    private class RecordedTelemetry(
        val sessionId: Long,
        val payload: JSONObject,
        val arrivalOrder: Long,
    )

    private class RecordedStatus(
        val sessionId: Long,
        val state: String?,
        val detail: String?,
        val blocked: Boolean,
        val payload: JSONObject?,
    )

    private class RecordedPidObservation(
        val sessionId: Long,
        val payload: JSONObject?,
        val observedAtMs: Long,
    )

    private class RecordedEvent(
        val sessionId: Long,
        val kind: String?,
        val state: String?,
        val detail: String?,
        val blocked: Boolean,
        val payload: JSONObject?,
        val arrivalOrder: Long,
    )

    private companion object {
        private const val SESSION_ID = 1234L
        private const val AWAIT_MS = 5_000L

        private fun taggedTelemetry(
            threadId: Int,
            seq: Int,
        ): JSONObject {
            val p = JSONObject()
            try {
                p.put("threadId", threadId)
                p.put("seq", seq)
                // Give every payload a unique timestamp so the in-order checks have something to
                // anchor against besides the (threadId, seq) tag.
                p.put("updatedAt", 10_000L + threadId * 1_000L + seq)
            } catch (ex: JSONException) {
                throw AssertionError(ex)
            }
            return p
        }
    }
}
