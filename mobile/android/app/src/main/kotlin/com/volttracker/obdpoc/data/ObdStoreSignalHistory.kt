package com.volttracker.obdpoc.data

import com.volttracker.obdpoc.signals.DiagnosticCsv
import com.volttracker.obdpoc.signals.SignalRecording
import com.volttracker.obdpoc.signals.SignalRecordingBuilder
import com.volttracker.obdpoc.signals.SignalSession
import org.json.JSONException
import org.json.JSONObject
import java.io.Writer

/** Reads original telemetry, independent of GPS routes and display projections. No schema change. */
class ObdStoreSignalHistory internal constructor(
    private val helper: VoltTrackerDb,
) {
    fun sessions(): List<SignalSession> {
        val result = mutableListOf<SignalSession>()
        helper.readableDatabase
            .rawQuery(
                "SELECT s._id, s.started_at_ms, " +
                    "(SELECT COUNT(*) FROM ${VoltTrackerDb.TABLE_TELEMETRY} t " +
                    "WHERE t.session_id=s._id) AS samples " +
                    "FROM ${VoltTrackerDb.TABLE_SESSIONS} s WHERE s.mode='obd' " +
                    "AND EXISTS (SELECT 1 FROM ${VoltTrackerDb.TABLE_TELEMETRY} t WHERE t.session_id=s._id) " +
                    "ORDER BY s.started_at_ms DESC, s._id DESC LIMIT 50",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    result += SignalSession(id, cursor.getLong(1), cursor.getInt(2))
                }
            }
        return result
    }

    fun read(sessionId: Long): SignalRecording? {
        val session = sessions().firstOrNull { it.id == sessionId } ?: return null
        val bounds = bounds(sessionId) ?: return null
        val builder = SignalRecordingBuilder(session, bounds.first, bounds.second)
        forEachSample(sessionId, bounds.third, builder::append)
        return builder.build()
    }

    /** Full-resolution CSV; the chart's point bound never limits the exported rows. */
    fun writeCsv(
        sessionId: Long,
        writer: Writer,
    ): Int {
        check(sessions().any { it.id == sessionId }) { "That recording is no longer available." }
        val bounds = bounds(sessionId) ?: return 0
        val csv = DiagnosticCsv(writer)
        var count = 0
        forEachSample(sessionId, bounds.third) { atMs, sample ->
            csv.append(atMs, sample)
            count += 1
        }
        return count
    }

    private fun bounds(sessionId: Long): Triple<Long, Long, Long>? =
        helper.readableDatabase
            .rawQuery(
                "SELECT MIN(captured_at_ms), MAX(captured_at_ms), MAX(_id) " +
                    "FROM ${VoltTrackerDb.TABLE_TELEMETRY} WHERE session_id=?",
                arrayOf(sessionId.toString()),
            ).use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) {
                    null
                } else {
                    Triple(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))
                }
            }

    private fun forEachSample(
        sessionId: Long,
        maxId: Long,
        accept: (Long, JSONObject) -> Unit,
    ) {
        helper.readableDatabase
            .rawQuery(
                "SELECT captured_at_ms, json FROM ${VoltTrackerDb.TABLE_TELEMETRY} " +
                    "WHERE session_id=? AND _id<=? ORDER BY captured_at_ms, _id",
                arrayOf(sessionId.toString(), maxId.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    check(!Thread.currentThread().isInterrupted) { "Reading cancelled." }
                    val sample =
                        try {
                            JSONObject(cursor.getString(1))
                        } catch (ex: JSONException) {
                            throw IllegalStateException("A recorded sample could not be read.", ex)
                        }
                    accept(cursor.getLong(0), sample)
                }
            }
    }
}
