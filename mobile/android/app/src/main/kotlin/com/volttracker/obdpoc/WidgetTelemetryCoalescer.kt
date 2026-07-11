package com.volttracker.obdpoc

import androidx.annotation.VisibleForTesting
import org.json.JSONObject
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Coalesces the per-sample home-screen-widget side effect down to at most one [deliver] per
 * [coalesceMs] window, always delivering the newest payload (audit item B5 — extracted from
 * [ObdService] so the last-wins scheduling state lives in one tested collaborator instead of
 * three loose atomics on the service).
 *
 * Threading: [submit] runs on the telemetry (poll/IO) thread for every sample; the scheduled
 * drain runs on the [scheduler]'s thread. All shared state is held in atomics — [latest] is a
 * last-wins mailbox, [scheduled] guards the single in-flight drain, and [coalescedCount] is a
 * diagnostic counter — so no lock is needed on the hot telemetry path.
 */
class WidgetTelemetryCoalescer(
    private val coalesceMs: Long,
    private val scheduler: Scheduler,
    private val deliver: (JSONObject) -> Unit,
    private val onScheduleRejected: (RejectedExecutionException) -> Unit = {},
) {
    /**
     * Minimal scheduling seam over `ScheduledExecutorService.schedule` so tests can drive the
     * drain deterministically. May throw [RejectedExecutionException] during executor shutdown.
     */
    fun interface Scheduler {
        @Throws(RejectedExecutionException::class)
        fun schedule(
            task: Runnable,
            delayMs: Long,
        )
    }

    private val latest = AtomicReference<JSONObject?>()
    private val scheduled = AtomicBoolean(false)
    private val coalescedCount = AtomicLong()

    /**
     * Records [payload] as the newest widget snapshot and schedules a drain unless one is
     * already pending — in which case the payload simply replaces the mailbox contents and the
     * pending drain delivers it (that's the coalescing).
     */
    fun submit(payload: JSONObject) {
        latest.set(payload)
        if (!scheduled.compareAndSet(false, true)) {
            coalescedCount.incrementAndGet()
            return
        }
        try {
            scheduler.schedule(::drainLatest, coalesceMs)
        } catch (ex: RejectedExecutionException) {
            scheduled.set(false)
            onScheduleRejected(ex)
        }
    }

    private fun drainLatest() {
        val payload = latest.getAndSet(null)
        if (payload != null) {
            deliver(payload)
        }
        scheduled.set(false)
        // A sample that landed between the getAndSet above and the flag release would otherwise
        // sit in the mailbox until the next telemetry tick; reschedule it now.
        latest.get()?.let { submit(it) }
    }

    /** Drains the diagnostic "samples coalesced into a pending drain" counter. */
    @VisibleForTesting
    fun drainCoalescedCountForTest(): Long = coalescedCount.getAndSet(0L)
}
