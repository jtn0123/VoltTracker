package com.volttracker.obdpoc

import android.content.Intent
import com.volttracker.obdpoc.signals.SignalRecording
import com.volttracker.obdpoc.signals.SignalSession
import com.volttracker.obdpoc.ui.diag.SignalHistoryUiState
import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeSignalHistoryTest {
    private val queue = QueueExecutor()
    private val posts = mutableListOf<Runnable>()
    private val shared = mutableListOf<Intent>()
    private val messages = mutableListOf<String>()
    private val store = LiveUiStateStore()
    private val controller =
        ComposeSignalHistory(
            RuntimeEnvironment.getApplication(),
            queue,
            store,
            { posts += it },
            { shared += it },
            { messages += it },
            { error("The test must not open the real database") },
        )

    @Test fun loadingAndSelectionFinishOffTheUiThreadAndOnlyNewestRequestApplies() {
        val reads = mutableListOf<Long?>()
        controller.reader = { id ->
            reads += id
            state(id ?: 1)
        }
        controller.load(1)
        controller.load(2)
        assertTrue(store.state.value.signalHistory.loading)
        assertTrue(reads.isEmpty())
        finish()
        assertEquals(listOf(1L, 2L), reads)
        assertEquals(
            2L,
            store.state.value.signalHistory.recording!!
                .session.id,
        )
        assertFalse(store.state.value.signalHistory.loading)
    }

    @Test fun demoDoesNotReadOrExportRealHistoryAndLateRealReadIsDiscarded() {
        var reads = 0
        controller.reader = {
            reads++
            state(1)
        }
        controller.load()
        store.onSettings { it.copy(demoActive = true) }
        finish()
        assertEquals(1, reads)
        assertTrue(store.state.value.signalHistory.recording == null)
        controller.load()
        finish()
        assertTrue(store.state.value.signalHistory.demo)
        controller.csvWriter = { _, _ -> error("Demo export must never read real samples") }
        controller.export()
        assertTrue(queue.tasks.isEmpty())
        assertTrue(shared.isEmpty())
    }

    @Test fun failedAndRejectedReadsLeaveRetryableState() {
        controller.reader = { error("SQLite unavailable") }
        controller.load()
        finish()
        assertFalse(store.state.value.signalHistory.loading)
        assertNotNull(store.state.value.signalHistory.error)
        queue.shutdown()
        controller.load()
        assertFalse(store.state.value.signalHistory.loading)
        assertNotNull(store.state.value.signalHistory.error)
    }

    @Test fun duplicateExportsAreIgnoredAndFailureClearsTheBusyState() {
        store.onSignalHistory(state(1))
        var exports = 0
        controller.csvWriter = { _, _ ->
            exports++
            error("Recording was removed")
        }
        controller.export()
        controller.export()
        assertEquals(1, queue.tasks.size)
        finish()
        assertEquals(1, exports)
        assertFalse(store.state.value.signalHistory.exporting)
        assertTrue(shared.isEmpty())
        assertEquals(1, messages.size)
    }

    @Test fun disposeDropsPendingCompletion() {
        controller.reader = { state(5) }
        controller.load()
        controller.dispose()
        finish()
        assertTrue(store.state.value.signalHistory.recording == null)
    }

    private fun finish() {
        while (queue.tasks.isNotEmpty()) queue.tasks.removeFirst().run()
        while (posts.isNotEmpty()) posts.removeAt(0).run()
    }

    private fun state(id: Long): SignalHistoryUiState {
        val session = SignalSession(id, 1_000, 2)
        return SignalHistoryUiState(
            listOf(session),
            SignalRecording(session, 1_000, 2_000, emptyMap()),
            loading = false,
        )
    }

    private class QueueExecutor : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        private var stopped = false

        override fun execute(command: Runnable) {
            if (stopped) throw RejectedExecutionException()
            tasks += command
        }

        override fun shutdown() {
            stopped = true
        }

        override fun shutdownNow(): List<Runnable> {
            shutdown()
            return tasks.toList().also { tasks.clear() }
        }

        override fun isShutdown(): Boolean = stopped

        override fun isTerminated(): Boolean = stopped && tasks.isEmpty()

        override fun awaitTermination(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = isTerminated
    }
}
