package com.volttracker.obdpoc.service

import android.util.Log
import com.volttracker.obdpoc.AppPrefs
import com.volttracker.obdpoc.DatabaseOperationLease
import com.volttracker.obdpoc.data.ObdLocalStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns one service instance's drain; restore is acknowledged only after every writer and handle closes. */
internal class ServicePersistenceTeardown(
    private val drainTimeoutMs: Long = TimeUnit.SECONDS.toMillis(30),
    private val forceTimeoutMs: Long = 1_000L,
) {
    private val started = AtomicBoolean(false)

    @Volatile private var thread: Thread? = null

    fun start(
        recorder: SessionRecorder,
        store: ObdLocalStore?,
        owner: DatabaseOperationLease.PersistenceOwner?,
        writers: List<ExecutorService>,
    ) {
        if (!started.compareAndSet(false, true)) return
        val teardown =
            Thread({
                val writersTerminated = writers.map(::awaitTermination).all { it }
                val persistence = recorder.shutdown()
                if (writersTerminated && persistence.fullyTerminated) {
                    store?.close()
                    owner?.close()
                } else {
                    // The retained owner also prevents a restore from swapping this still-open database.
                    Log.e(
                        AppPrefs.LOG_TAG,
                        "persistence workers survived service teardown; SQLite and restore guard remain open",
                    )
                }
            }, "obd-persistence-teardown")
        thread = teardown
        teardown.start()
    }

    private fun awaitTermination(writer: ExecutorService): Boolean =
        try {
            if (writer.awaitTermination(drainTimeoutMs, TimeUnit.MILLISECONDS)) {
                true
            } else {
                writer.shutdownNow()
                writer.awaitTermination(forceTimeoutMs, TimeUnit.MILLISECONDS)
            }
        } catch (ex: InterruptedException) {
            writer.shutdownNow()
            Thread.currentThread().interrupt()
            writer.isTerminated
        }

    fun await(timeoutMs: Long): Boolean {
        val active = thread ?: return true
        active.join(timeoutMs)
        return !active.isAlive
    }
}
