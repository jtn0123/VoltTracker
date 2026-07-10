package com.volttracker.obdpoc

import android.util.Log
import com.volttracker.obdpoc.data.ObdLocalStore
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the async-persistence machinery extracted from [SessionRecorder].
 */
class ObdPersistenceWorker(
    private val localStore: ObdLocalStore?,
    private val shutdownDrainTimeoutMs: Long = TimeUnit.SECONDS.toMillis(SHUTDOWN_DRAIN_TIMEOUT_SECONDS),
    private val shutdownForceTimeoutMs: Long = SHUTDOWN_FORCE_TIMEOUT_MS,
) {
    private val droppedTelemetryTasks = AtomicLong()
    private val failedTelemetryTasks = AtomicLong()

    private val telemetryExecutor: ExecutorService? =
        if (localStore == null) {
            null
        } else {
            ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                LinkedBlockingQueue(TELEMETRY_QUEUE_CAPACITY),
                DiscardOldestUnlessShutdownPolicy(droppedTelemetryTasks),
            )
        }

    private val lifecycleExecutor: ExecutorService? =
        if (localStore == null) {
            null
        } else {
            ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                LinkedBlockingQueue(LIFECYCLE_QUEUE_CAPACITY),
                ThreadPoolExecutor.AbortPolicy(),
            )
        }

    private class DiscardOldestUnlessShutdownPolicy(
        private val droppedCount: AtomicLong,
    ) : RejectedExecutionHandler {
        override fun rejectedExecution(
            r: Runnable,
            executor: ThreadPoolExecutor,
        ) {
            if (executor.isShutdown) {
                throw RejectedExecutionException("telemetry executor shut down")
            }
            if (executor.queue.poll() != null) {
                val dropped = droppedCount.incrementAndGet()
                Log.w(AppPrefs.LOG_TAG, "telemetry queue full; dropped oldest telemetry task #$dropped")
            }
            executor.execute(r)
        }
    }

    fun drainDroppedTelemetryCount(): Long = droppedTelemetryTasks.getAndSet(0L)

    fun drainFailedTelemetryCount(): Long = failedTelemetryTasks.getAndSet(0L)

    fun submitTelemetry(task: Runnable) {
        val executor = telemetryExecutor ?: return
        try {
            executor.execute {
                try {
                    task.run()
                } catch (ex: RuntimeException) {
                    val failed = failedTelemetryTasks.incrementAndGet()
                    Log.w(AppPrefs.LOG_TAG, "telemetry persistence failed #$failed", ex)
                }
            }
        } catch (ex: RejectedExecutionException) {
            val failed = failedTelemetryTasks.incrementAndGet()
            Log.w(AppPrefs.LOG_TAG, "telemetry persistence rejected #$failed", ex)
        }
    }

    fun submitLifecycle(
        task: Runnable,
        sessionId: Long,
        op: String,
    ) {
        val executor = lifecycleExecutor ?: return
        try {
            executor.execute {
                try {
                    task.run()
                } catch (ex: RuntimeException) {
                    Log.e(AppPrefs.LOG_TAG, "session lifecycle persist failed", ex)
                    recordPersistFailure(sessionId, op, ex)
                }
            }
        } catch (ex: RejectedExecutionException) {
            Log.e(AppPrefs.LOG_TAG, "lifecycle executor rejected $op; running inline", ex)
            try {
                task.run()
            } catch (inner: RuntimeException) {
                Log.e(AppPrefs.LOG_TAG, "inline lifecycle fallback failed for $op", inner)
                recordPersistFailure(sessionId, op, inner)
            }
        }
    }

    fun awaitTelemetryDrain() {
        val executor = telemetryExecutor ?: return
        try {
            val marker: Future<*> = executor.submit { }
            marker.get(30, TimeUnit.SECONDS)
        } catch (ex: RejectedExecutionException) {
            try {
                executor.awaitTermination(30, TimeUnit.SECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (ex: ExecutionException) {
            Log.w(AppPrefs.LOG_TAG, "telemetry drain marker failed", ex.cause)
        } catch (ex: TimeoutException) {
            Log.w(AppPrefs.LOG_TAG, "telemetry drain timed out", ex)
        }
    }

    private fun recordPersistFailure(
        sessionId: Long,
        op: String,
        cause: RuntimeException,
    ) {
        val store = localStore ?: return
        try {
            val payload = JSONObject()
            try {
                payload.put("op", op)
                payload.put("exception", cause.javaClass.name)
                payload.put("message", ObdElmDecode.safeMessage(cause))
                payload.put("updatedAt", System.currentTimeMillis())
            } catch (ignored: JSONException) {
                // Local values are safe.
            }
            store.recordEvent(sessionId, "persist_failure", "", op, true, payload)
        } catch (ex: RuntimeException) {
            Log.e(AppPrefs.LOG_TAG, "recording persist_failure also failed", ex)
        }
    }

    data class ShutdownResult(
        val telemetryTerminated: Boolean,
        val lifecycleTerminated: Boolean,
    ) {
        val fullyTerminated: Boolean
            get() = telemetryTerminated && lifecycleTerminated
    }

    fun shutdown(): ShutdownResult {
        val telemetry = telemetryExecutor
        val lifecycle = lifecycleExecutor
        if (telemetry == null || lifecycle == null) return ShutdownResult(true, true)
        telemetry.shutdown()
        lifecycle.shutdown()
        val telemetryTerminated = terminateExecutor("telemetry", telemetry)
        val lifecycleTerminated = terminateExecutor("lifecycle", lifecycle)
        return ShutdownResult(telemetryTerminated, lifecycleTerminated)
    }

    private fun terminateExecutor(
        name: String,
        executor: ExecutorService,
    ): Boolean {
        try {
            if (executor.awaitTermination(shutdownDrainTimeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)) {
                return true
            }
        } catch (ex: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
            Log.e(AppPrefs.LOG_TAG, "$name persistence shutdown interrupted", ex)
            return executor.isTerminated
        }
        val discarded = executor.shutdownNow().size
        Log.e(
            AppPrefs.LOG_TAG,
            "$name persistence shutdown timed out; interrupted worker and discarded $discarded queued tasks",
        )
        try {
            if (executor.awaitTermination(shutdownForceTimeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)) {
                return true
            }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.e(AppPrefs.LOG_TAG, "$name persistence force-stop interrupted", ex)
        }
        Log.e(AppPrefs.LOG_TAG, "$name persistence worker is still alive after force-stop")
        return executor.isTerminated
    }

    companion object {
        const val TELEMETRY_QUEUE_CAPACITY: Int = 2000
        const val SHUTDOWN_DRAIN_TIMEOUT_SECONDS: Long = 30L
        const val SHUTDOWN_FORCE_TIMEOUT_MS: Long = 1_000L
        const val LIFECYCLE_QUEUE_CAPACITY: Int = 64
    }
}
