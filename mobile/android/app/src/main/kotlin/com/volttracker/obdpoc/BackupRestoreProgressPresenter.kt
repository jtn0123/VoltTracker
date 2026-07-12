package com.volttracker.obdpoc

import kotlin.math.ceil

/**
 * Publishes backup/restore progress to the dashboard on behalf of [BackupController]: the
 * modal progress card ([show]/[hide]) plus the throttled worker-side [Emitter] that turns
 * [DataBackup] / [com.volttracker.obdpoc.data.DatabaseMerger] progress callbacks into
 * UI-thread updates with the percent + ETA math.
 *
 * Extracted verbatim from [BackupController] (LargeClass ratchet); behavior is identical.
 * The cancellation gate is a lambda because the controller owns the disposed flag and the
 * emitter must also observe the worker thread's interrupt state at call time.
 */
class BackupRestoreProgressPresenter(
    private val activity: MainActivity,
    private val isCancelled: () -> Boolean,
) {
    fun show(
        title: String,
        detail: String,
        busy: Boolean = true,
        tone: String = "busy",
        phase: String? = null,
        bytesDone: Long = -1L,
        bytesTotal: Long = -1L,
        rowsDone: Long = -1L,
        rowsTotal: Long = -1L,
        percent: Int = -1,
        etaSeconds: Long = -1L,
        operation: String? = null,
    ) {
        val resolvedPercent =
            if (percent >= 0) {
                percent.coerceIn(0, 100)
            } else if (!busy && tone == "ok") {
                100
            } else {
                progressPercent(bytesDone, bytesTotal, rowsDone, rowsTotal)
            }
        activity.publishRestoreProgress(
            true,
            busy,
            title,
            detail,
            tone,
            phase,
            bytesDone,
            bytesTotal,
            rowsDone,
            rowsTotal,
            resolvedPercent,
            etaSeconds,
            operation,
        )
    }

    fun hide() {
        activity.publishRestoreProgress(false, false, null, null, "idle", null, -1L, -1L, -1L, -1L, -1, -1L, null)
    }

    fun emitter(
        title: String,
        fallbackDetail: String,
        operation: String,
    ): Emitter = Emitter(title, fallbackDetail, operation)

    inner class Emitter internal constructor(
        private val title: String,
        private val fallbackDetail: String,
        private val operation: String,
    ) {
        private val startedAtMs = System.currentTimeMillis()
        private var lastPublishedAtMs = 0L

        fun onDataBackupProgress(snapshot: DataBackup.ProgressSnapshot) {
            publish(
                phase = snapshot.phase,
                detail = snapshot.detail,
                bytesDone = snapshot.bytesDone,
                bytesTotal = snapshot.bytesTotal,
                rowsDone = snapshot.rowsDone,
                rowsTotal = snapshot.rowsTotal,
            )
        }

        fun onMergeProgress(
            phase: String,
            rowsDone: Long,
            rowsTotal: Long,
        ) {
            publish(
                phase = phase,
                detail = fallbackDetail,
                rowsDone = rowsDone,
                rowsTotal = rowsTotal,
            )
        }

        private fun publish(
            phase: String?,
            detail: String?,
            bytesDone: Long = -1L,
            bytesTotal: Long = -1L,
            rowsDone: Long = -1L,
            rowsTotal: Long = -1L,
        ) {
            if (isCancelled()) return
            val now = System.currentTimeMillis()
            val percent = progressPercent(bytesDone, bytesTotal, rowsDone, rowsTotal)
            val complete = percent >= 100
            if (lastPublishedAtMs > 0L &&
                now - lastPublishedAtMs < PROGRESS_UPDATE_INTERVAL_MS &&
                !complete
            ) {
                return
            }
            lastPublishedAtMs = now
            val etaSeconds = estimateEtaSeconds(bytesDone, bytesTotal, rowsDone, rowsTotal, startedAtMs, now)
            activity.runOnUiThread {
                if (isCancelled()) return@runOnUiThread
                show(
                    title,
                    detail ?: fallbackDetail,
                    phase = phase,
                    bytesDone = bytesDone,
                    bytesTotal = bytesTotal,
                    rowsDone = rowsDone,
                    rowsTotal = rowsTotal,
                    percent = percent,
                    etaSeconds = etaSeconds,
                    operation = operation,
                )
            }
        }
    }

    companion object {
        private const val PROGRESS_UPDATE_INTERVAL_MS = 250L

        private fun progressUnits(
            bytesDone: Long,
            bytesTotal: Long,
            rowsDone: Long,
            rowsTotal: Long,
        ): LongArray? =
            if (bytesTotal > 0L && bytesDone >= 0L) {
                longArrayOf(bytesDone, bytesTotal)
            } else if (rowsTotal > 0L && rowsDone >= 0L) {
                longArrayOf(rowsDone, rowsTotal)
            } else {
                null
            }

        private fun progressPercent(
            bytesDone: Long,
            bytesTotal: Long,
            rowsDone: Long,
            rowsTotal: Long,
        ): Int {
            val (done, total) = progressUnits(bytesDone, bytesTotal, rowsDone, rowsTotal) ?: return -1
            return ((done.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        }

        private fun estimateEtaSeconds(
            bytesDone: Long,
            bytesTotal: Long,
            rowsDone: Long,
            rowsTotal: Long,
            startedAtMs: Long,
            nowMs: Long,
        ): Long {
            val (done, total) = progressUnits(bytesDone, bytesTotal, rowsDone, rowsTotal) ?: return -1L
            if (done >= total) {
                return 0L
            }
            val elapsedMs = nowMs - startedAtMs
            if (done <= 0L || elapsedMs < 500L) {
                return -1L
            }
            val unitsPerMs = done.toDouble() / elapsedMs.toDouble()
            if (unitsPerMs <= 0.0) {
                return -1L
            }
            val remainingMs = (total - done).toDouble() / unitsPerMs
            return ceil(remainingMs / 1000.0).toLong().coerceAtLeast(1L)
        }
    }
}
