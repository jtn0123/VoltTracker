package com.volttracker.obdpoc

import android.content.Context
import android.content.Intent
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.ui.diag.SignalHistoryUiState
import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import java.io.Writer
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/** Short-lived database reads and streaming exports, off the UI thread and isolated from demo data. */
internal class ComposeSignalHistory(
    private val context: Context,
    private val executor: ExecutorService,
    private val store: LiveUiStateStore,
    private val post: (Runnable) -> Unit,
    private val share: (Intent) -> Unit,
    private val message: (String) -> Unit,
    private val openStore: () -> ObdLocalStore,
) {
    private val generation = AtomicInteger()

    var reader: (Long?) -> SignalHistoryUiState = { requested ->
        withStore { db ->
            val sessions = db.signalHistory.sessions()
            val selected = requested?.takeIf { id -> sessions.any { it.id == id } } ?: sessions.firstOrNull()?.id
            SignalHistoryUiState(sessions, selected?.let(db.signalHistory::read), loading = false)
        }
    }

    var csvWriter: (Long, Writer) -> Int = { sessionId, writer ->
        withStore {
            it.signalHistory.writeCsv(sessionId, writer)
        }
    }

    fun load(
        sessionId: Long? =
            store.state.value.signalHistory.recording
                ?.session
                ?.id,
    ) {
        val token = generation.incrementAndGet()
        val demo = store.state.value.settings.demoActive
        val previous =
            store.state.value.signalHistory
                .takeIf { it.demo == demo } ?: SignalHistoryUiState(demo = demo)
        store.onSignalHistory(previous.copy(loading = true, exporting = false, error = null))
        try {
            executor.execute {
                val loaded =
                    try {
                        if (demo) SignalHistoryUiState.demo() else reader(sessionId)
                    } catch (_: RuntimeException) {
                        previous.copy(
                            loading = false,
                            exporting = false,
                            error = "Couldn't read recorded signals. Try refreshing.",
                        )
                    }
                post(
                    Runnable {
                        if (token == generation.get() &&
                            demo == store.state.value.settings.demoActive
                        ) {
                            store.onSignalHistory(loaded)
                        }
                    },
                )
            }
        } catch (_: RejectedExecutionException) {
            store.onSignalHistory(
                previous.copy(loading = false, exporting = false, error = "Couldn't start reading recorded signals."),
            )
        }
    }

    fun export() {
        val state = store.state.value.signalHistory
        val session = state.recording?.session ?: return
        if (state.loading || state.exporting || state.demo || store.state.value.settings.demoActive) return
        val token = generation.get()
        store.onSignalHistory(state.copy(exporting = true))
        try {
            executor.execute {
                val intent =
                    try {
                        TripExportShareIntent
                            .writeDiagnosticCsv(context, session.startedAtMs) { csvWriter(session.id, it) }
                            ?.let { TripExportShareIntent.buildShareIntent(context, it) }
                    } catch (_: RuntimeException) {
                        null
                    }
                post(
                    Runnable {
                        if (token == generation.get() && !store.state.value.settings.demoActive) {
                            store.onSignalHistory(
                                store.state.value.signalHistory
                                    .copy(exporting = false),
                            )
                            if (intent == null) {
                                message("Couldn't export recorded signals.")
                            } else {
                                try {
                                    share(Intent.createChooser(intent, "Share diagnostic CSV"))
                                } catch (_: RuntimeException) {
                                    message("Couldn't open sharing.")
                                }
                            }
                        }
                    },
                )
            }
        } catch (_: RejectedExecutionException) {
            store.onSignalHistory(state.copy(exporting = false))
            message("Couldn't start the export.")
        }
    }

    fun dispose() {
        generation.incrementAndGet()
    }

    private fun <T> withStore(read: (ObdLocalStore) -> T): T {
        val owner = DatabaseOperationLease.tryRegisterPersistence() ?: error("A database operation is in progress.")
        return owner.use { openStore().use(read) }
    }
}
