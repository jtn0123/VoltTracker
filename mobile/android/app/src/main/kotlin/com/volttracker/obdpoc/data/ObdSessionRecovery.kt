package com.volttracker.obdpoc.data

import android.content.Context

/** Finalizes database sessions stranded as active when the previous service process died. */
object ObdSessionRecovery {
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
