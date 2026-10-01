package com.volttracker.obdpoc.data

import android.content.Context
import android.database.SQLException
import android.util.Log

/** Finalizes database sessions stranded as active when the previous service process died. */
object ObdSessionRecovery {
    /**
     * Runs [attempt] (normally [recover]) at service start without letting it crash the service.
     * A locked or damaged database or a full disk is logged and skipped: throwing out of
     * `onCreate` would kill the service on every start, and the stranded rows are simply retried
     * next time. Returns the number of sessions recovered, or null when recovery failed.
     */
    fun recoverSafely(attempt: () -> Int): Int? =
        try {
            attempt().also { recovered ->
                if (recovered > 0) Log.w(LOG_TAG, "recovered $recovered sessions interrupted by process death")
            }
        } catch (e: SQLException) {
            Log.e(LOG_TAG, "session recovery failed; will retry on next start", e)
            null
        }

    /**
     * Uses the newest persisted child-row timestamp (clamped to recovery time), not the current
     * wall clock, so downtime after a crash cannot inflate a trip by hours or days.
     */
    fun recover(
        context: Context,
        recoveredAtMs: Long = System.currentTimeMillis(),
    ): Int =
        VoltTrackerDb(context).use { helper ->
            helper.writableDatabase.compileStatement(RECOVERY_SQL).use { statement ->
                statement.bindLong(1, recoveredAtMs)
                statement.bindString(2, ObdLocalStore.STATUS_INTERRUPTED)
                statement.bindString(3, ObdLocalStore.STATUS_ACTIVE)
                statement.executeUpdateDelete()
            }
        }

    private const val LOG_TAG = "ObdSessionRecovery"

    private val RECOVERY_SQL =
        """
        UPDATE ${VoltTrackerDb.TABLE_SESSIONS}
        SET ended_at_ms = MAX(
                started_at_ms,
                MIN(
                    ?,
                    MAX(
                        COALESCE((
                            SELECT MAX(captured_at_ms)
                            FROM ${VoltTrackerDb.TABLE_TELEMETRY}
                            WHERE session_id = ${VoltTrackerDb.TABLE_SESSIONS}._id
                        ), started_at_ms),
                        COALESCE((
                            SELECT MAX(captured_at_ms)
                            FROM ${VoltTrackerDb.TABLE_LOCATION_SAMPLES}
                            WHERE session_id = ${VoltTrackerDb.TABLE_SESSIONS}._id
                        ), started_at_ms),
                        COALESCE((
                            SELECT MAX(occurred_at_ms)
                            FROM ${VoltTrackerDb.TABLE_EVENTS}
                            WHERE session_id = ${VoltTrackerDb.TABLE_SESSIONS}._id
                        ), started_at_ms),
                        COALESCE((
                            SELECT MAX(observed_at_ms)
                            FROM ${VoltTrackerDb.TABLE_PID_OBSERVATIONS}
                            WHERE session_id = ${VoltTrackerDb.TABLE_SESSIONS}._id
                        ), started_at_ms)
                    )
                )
            ),
            status = ?
        WHERE status = ?
        """.trimIndent()
}
