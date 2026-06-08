package com.volttracker.obdpoc

import com.volttracker.obdpoc.data.ObdLocalStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies that when [com.volttracker.obdpoc.data.ObdLocalStore.finalizeSession] throws,
 * [SessionRecorder] routes the failure through `lifecycleExecutor` and records a `persist_failure`
 * status event so the failure is observable rather than silently swallowed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionRecorderLifecycleFailureTest {
    @Test
    @Throws(InterruptedException::class)
    fun finalizeSessionFailureIsRecordedAsPersistFailureEvent() {
        val store = RecordingStore()
        val logsDir = File(System.getProperty("java.io.tmpdir"), "sr-lifecycle-test")
        logsDir.mkdirs()
        val lock = Any()
        val recorder = SessionRecorder(lock, ObdSessionLog(logsDir), store)

        recorder.openSession(ObdLocalStore.MODE_OBD, "AA:BB:CC:DD:EE:FF", "Test", 1000L)
        // openSession also calls closeSession("",...) on the prior (empty) session — that
        // is a no-op because activeSessionId is 0 at that point.
        recorder.closeSession("disconnected", "test detail", "supported", 7)
        recorder.shutdown()
        // shutdown drains both executors, so by the time we get here the lifecycle write
        // (and its persist_failure follow-up) have completed.

        assertEquals(
            "finalizeSession should have been attempted once",
            1,
            store.finalizeCalls.get(),
        )

        // After finalize threw, lifecycleAsync must record a persist_failure event.
        val persistFailures = store.eventsWithKind("persist_failure")
        assertEquals(
            "expected exactly one persist_failure event after finalize threw",
            1,
            persistFailures.size,
        )
        val failure = persistFailures[0]
        assertEquals("persist_failure", failure.kind)
        assertEquals("finalizeSession", failure.detail)
        assertTrue("persist_failure event should be flagged blocked", failure.blocked)
        assertNotNull("payload carries diagnostic context", failure.payload)
        assertEquals("finalizeSession", failure.payload!!.optString("op"))
    }

    /**
     * Subclass of [ObdLocalStore] that:
     *
     * - throws on every [finalizeSession] call (the "failure" under test), and
     * - captures [recordEvent] calls so the test can assert that a `persist_failure` row was queued
     *   through the lifecycle path.
     */
    private class RecordingStore : ObdLocalStore(RuntimeEnvironment.getApplication()) {
        val finalizeCalls = AtomicInteger()
        val events = ArrayList<RecordedEvent>()

        override fun startSession(
            mode: String?,
            adapterAddress: String?,
            adapterName: String?,
            startedAtMs: Long,
        ): Long {
            // Return a non-zero id so closeSession enters the finalize branch.
            return 42L
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
            finalizeCalls.incrementAndGet()
            throw RuntimeException("simulated finalize failure")
        }

        override fun recordEvent(
            sessionId: Long,
            kind: String?,
            state: String?,
            detail: String?,
            blocked: Boolean,
            payload: JSONObject?,
        ): Long {
            synchronized(events) {
                events.add(RecordedEvent(sessionId, kind, state, detail, blocked, payload))
            }
            return events.size.toLong()
        }

        fun eventsWithKind(kind: String): List<RecordedEvent> {
            val out = ArrayList<RecordedEvent>()
            synchronized(events) {
                for (e in events) {
                    if (kind == e.kind) {
                        out.add(e)
                    }
                }
            }
            return out
        }
    }

    private class RecordedEvent(
        val sessionId: Long,
        val kind: String?,
        val state: String?,
        val detail: String?,
        val blocked: Boolean,
        val payload: JSONObject?,
    )

    private companion object {
        // Robolectric's main looper is a paused-idle scheduler by default — but our executors are
        // real JVM threads with awaitTermination, so we don't need to drive the looper. Provided as
        // a guardrail in case the test runner config changes.
        @Suppress("unused")
        private fun awaitBriefly(ms: Long) {
            try {
                TimeUnit.MILLISECONDS.sleep(ms)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
