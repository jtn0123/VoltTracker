package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Charge-session summary + export computation extracted from [ObdStoreReports]. Owns the
 * observed/inferred charge detection, the within-session SOC-gain scan, the drive/SOC boundary
 * derivation, and the per-session charge-rollup cache (G2). Every entry point takes an explicit
 * [SQLiteDatabase] handle, so this carries no instance state of its own.
 */
internal object ObdStoreChargeSummary {
    @Throws(JSONException::class)
    fun summaryJson(db: SQLiteDatabase): JSONObject {
        val rows = chargeSummaryRows(db)
        return JSONObject()
            .put("chargeSessionCount", rows.size)
            .put(
                "chargingHintCount",
                ObdStoreSupport.countRowsWhere(
                    db,
                    VoltTrackerDb.TABLE_TELEMETRY,
                    "charge_transition_hint = 1",
                    null,
                ),
            ).put("maxPowerKw", maxChargePowerKw(rows))
            .put("latest", if (rows.isEmpty()) JSONObject() else chargeSummaryRowJson(rows[0]))
            .put("recentSessions", chargeSummaryRowsJson(rows, 12))
    }

    @Throws(JSONException::class)
    fun exportRowsJson(
        db: SQLiteDatabase,
        limit: Int,
    ): JSONArray = chargeSummaryRowsJson(chargeSummaryRows(db), limit)

    private val CHARGE_SESSION_COLUMNS =
        arrayOf(
            "_id",
            "started_at_ms",
            "ended_at_ms",
            "charger_type",
            "start_soc",
            "end_soc",
            "power_kw",
            "energy_kwh",
            "confidence",
        )

    private fun chargeSessionColumns(alias: String): String =
        CHARGE_SESSION_COLUMNS.joinToString(", ") {
            "$alias.$it AS $it"
        }

    private fun plausibleChargeWhere(alias: String): String =
        "NOT EXISTS (" +
            "SELECT 1 FROM ${VoltTrackerDb.TABLE_TELEMETRY} t " +
            "WHERE t.session_id = $alias.session_id " +
            "AND t.captured_at_ms >= $alias.started_at_ms " +
            "AND t.captured_at_ms <= COALESCE($alias.ended_at_ms, $alias.started_at_ms) " +
            "AND t.speed_kph IS NOT NULL AND t.speed_kph >= $CHARGE_DRIVING_SPEED_KPH" +
            ") AND NOT EXISTS (" +
            "SELECT 1 FROM ${VoltTrackerDb.TABLE_LOCATION_SAMPLES} l " +
            "WHERE l.session_id = $alias.session_id " +
            "AND l.captured_at_ms >= $alias.started_at_ms " +
            "AND l.captured_at_ms <= COALESCE($alias.ended_at_ms, $alias.started_at_ms) " +
            "AND l.speed_mps IS NOT NULL AND l.speed_mps >= $CHARGE_DRIVING_SPEED_MPS" +
            ")"

    private const val MIN_INFERRED_CHARGE_SOC_GAIN = 8.0
    private const val MIN_INFERRED_CHARGE_GAP_MS = 10L * 60_000L
    private const val MIN_OBSERVED_CHARGE_SOC_GAIN = 5.0
    private const val MIN_OBSERVED_CHARGE_DURATION_MS = 10L * 60_000L
    private const val MIN_OBSERVED_CHARGE_ENERGY_KWH = 1.0
    private const val MIN_OBSERVED_ENERGY_DURATION_MS = 20L * 60_000L
    private const val ESTIMATED_USABLE_BATTERY_KWH = 14.0
    private const val CHARGE_DRIVING_SPEED_KPH = 5
    private const val CHARGE_DRIVING_SPEED_MPS = CHARGE_DRIVING_SPEED_KPH / 3.6

    // Bump to invalidate cached per-session charge rollups (G2): a logic change in the
    // within-session SOC scan or the drive/SOC-boundary detection makes old blobs stale, so
    // raising this forces a one-time recompute-and-rewrite on the next storage-summary read.
    private const val CHARGE_ROLLUP_CACHE_VERSION = 1

    // Keeps an IN (...) list under SQLite's bind-argument limit (the rollup-version bind shares
    // the budget, so this leaves ample headroom below the 999/1000 ceiling).
    private const val SQLITE_BIND_CHUNK = 500

    private data class ChargeSummaryRow(
        val id: Any,
        val startedAtMs: Long,
        val endedAtMs: Long?,
        val chargerType: String?,
        val startSoc: Double?,
        val endSoc: Double?,
        val powerKw: Double?,
        val energyKwh: Double?,
        val confidence: Double?,
    )

    private data class DriveSocBoundary(
        val sessionId: Long,
        val startedAtMs: Long,
        val endedAtMs: Long,
        val startSoc: Double,
        val endSoc: Double,
    )

    private data class SocDriveSample(
        val sessionId: Long,
        val atMs: Long,
        val soc: Double,
    )

    private fun boxedOrNull(value: Number?): Any = value ?: JSONObject.NULL

    private fun chargeSummaryRows(db: SQLiteDatabase): List<ChargeSummaryRow> {
        val inferred = inferredChargeRows(db)
        val observed =
            observedChargeRows(db)
                .filterNot { observed ->
                    inferred.any { inferredRow -> isInsideInferredCharge(observed, inferredRow) }
                }
        return (inferred + observed).sortedWith(
            compareByDescending<ChargeSummaryRow> { it.startedAtMs }.thenByDescending { it.endedAtMs ?: 0L },
        )
    }

    private fun observedChargeRows(db: SQLiteDatabase): List<ChargeSummaryRow> {
        val rows = ArrayList<ChargeSummaryRow>()
        db
            .rawQuery(
                "SELECT ${chargeSessionColumns("c")} FROM ${VoltTrackerDb.TABLE_CHARGE_SESSIONS} c " +
                    "WHERE ${plausibleChargeWhere("c")} ORDER BY c.started_at_ms DESC",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val row = chargeSessionRow(cursor)
                    if (isMeaningfulObservedCharge(row)) {
                        rows.add(row)
                    }
                }
            }
        return rows
    }

    private fun isMeaningfulObservedCharge(row: ChargeSummaryRow): Boolean {
        val durationMs = (row.endedAtMs ?: row.startedAtMs) - row.startedAtMs
        val startSoc = row.startSoc
        val endSoc = row.endSoc
        if (startSoc != null &&
            endSoc != null &&
            endSoc - startSoc >= MIN_OBSERVED_CHARGE_SOC_GAIN &&
            durationMs >= MIN_OBSERVED_CHARGE_DURATION_MS
        ) {
            return true
        }
        val energyKwh = row.energyKwh
        val powerKw = row.powerKw
        return energyKwh != null &&
            powerKw != null &&
            energyKwh >= MIN_OBSERVED_CHARGE_ENERGY_KWH &&
            powerKw > 0.0 &&
            durationMs >= MIN_OBSERVED_ENERGY_DURATION_MS
    }

    private fun inferredChargeRows(db: SQLiteDatabase): List<ChargeSummaryRow> {
        val rows = ArrayList<ChargeSummaryRow>()
        rows.addAll(inferredChargeRowsBetweenDriveBoundaries(db))
        rows.addAll(inferredChargeRowsWithinSessions(db))
        return mergeInferredChargeRows(rows)
    }

    private fun inferredChargeRowsBetweenDriveBoundaries(db: SQLiteDatabase): List<ChargeSummaryRow> {
        val boundaries = meaningfulDriveSocBoundaries(db)
        if (boundaries.size < 2) {
            return emptyList()
        }
        val rows = ArrayList<ChargeSummaryRow>()
        var previous = boundaries[0]
        for (i in 1 until boundaries.size) {
            val current = boundaries[i]
            val socGain = current.startSoc - previous.endSoc
            val gapMs = current.startedAtMs - previous.endedAtMs
            if (socGain >= MIN_INFERRED_CHARGE_SOC_GAIN && gapMs >= MIN_INFERRED_CHARGE_GAP_MS) {
                rows.add(
                    ChargeSummaryRow(
                        "inferred:${previous.sessionId}:${previous.endedAtMs}:${current.sessionId}:${current.startedAtMs}",
                        previous.endedAtMs,
                        current.startedAtMs,
                        "inferred",
                        previous.endSoc,
                        current.startSoc,
                        null,
                        (socGain / 100.0) * ESTIMATED_USABLE_BATTERY_KWH,
                        0.7,
                    ),
                )
            }
            previous = current
        }
        return rows
    }

    private fun inferredChargeRowsWithinSessions(db: SQLiteDatabase): List<ChargeSummaryRow> {
        val rows = ArrayList<ChargeSummaryRow>()
        for (contribution in sessionChargeContributions(db)) {
            rows.addAll(contribution.withinSessionRows)
        }
        return rows
    }

    /**
     * Computes this session's within-session inferred-charge rows directly from its telemetry
     * (the intra-session SOC-gain scan). Pure function of one session's rows — cached per
     * finalized session in [sessionChargeContributions].
     */
    private fun computeWithinSessionRows(
        db: SQLiteDatabase,
        session: ObdSessionRecord,
    ): List<ChargeSummaryRow> {
        val rows = ArrayList<ChargeSummaryRow>()
        var previous: SocDriveSample? = null
        db
            .rawQuery(
                "SELECT captured_at_ms, soc FROM ${VoltTrackerDb.TABLE_TELEMETRY} " +
                    "WHERE session_id = ? AND soc IS NOT NULL AND speed_kph IS NOT NULL " +
                    "AND speed_kph >= ? ORDER BY captured_at_ms ASC",
                arrayOf(session.id.toString(), CHARGE_DRIVING_SPEED_KPH.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val current = SocDriveSample(session.id, cursor.getLong(0), cursor.getDouble(1))
                    val last = previous
                    if (last != null) {
                        maybeInferredChargeRow(last, current)?.let { rows.add(it) }
                    }
                    previous = current
                }
            }
        return rows
    }

    private fun maybeInferredChargeRow(
        previous: SocDriveSample,
        current: SocDriveSample,
    ): ChargeSummaryRow? {
        val socGain = current.soc - previous.soc
        val gapMs = current.atMs - previous.atMs
        if (socGain < MIN_INFERRED_CHARGE_SOC_GAIN || gapMs < MIN_INFERRED_CHARGE_GAP_MS) {
            return null
        }
        return ChargeSummaryRow(
            "inferred:${previous.sessionId}:${previous.atMs}:${current.sessionId}:${current.atMs}",
            previous.atMs,
            current.atMs,
            "inferred",
            previous.soc,
            current.soc,
            null,
            (socGain / 100.0) * ESTIMATED_USABLE_BATTERY_KWH,
            0.7,
        )
    }

    private fun mergeInferredChargeRows(rows: List<ChargeSummaryRow>): List<ChargeSummaryRow> {
        if (rows.size < 2) {
            return rows
        }
        val merged = ArrayList<ChargeSummaryRow>()
        for (row in rows.sortedWith(compareBy<ChargeSummaryRow> { it.startedAtMs }.thenBy { it.endedAtMs ?: 0L })) {
            val existingIndex = merged.indexOfFirst { existing -> chargeIntervalsOverlap(existing, row) }
            if (existingIndex < 0) {
                merged.add(row)
                continue
            }
            if (socGain(row) > socGain(merged[existingIndex])) {
                merged[existingIndex] = row
            }
        }
        return merged
    }

    private fun chargeIntervalsOverlap(
        left: ChargeSummaryRow,
        right: ChargeSummaryRow,
    ): Boolean {
        val leftEnd = left.endedAtMs ?: left.startedAtMs
        val rightEnd = right.endedAtMs ?: right.startedAtMs
        return left.startedAtMs <= rightEnd && right.startedAtMs <= leftEnd
    }

    private fun socGain(row: ChargeSummaryRow): Double {
        val start = row.startSoc ?: return 0.0
        val end = row.endSoc ?: return 0.0
        return end - start
    }

    private fun meaningfulDriveSocBoundaries(db: SQLiteDatabase): List<DriveSocBoundary> {
        val boundaries = ArrayList<DriveSocBoundary>()
        for (contribution in sessionChargeContributions(db)) {
            boundaries.addAll(contribution.boundaries)
        }
        return boundaries.sortedBy { it.startedAtMs }
    }

    /**
     * Computes this session's meaningful drive/SOC boundaries directly from its drive windows
     * and telemetry. Pure function of one session's rows — cached per finalized session in
     * [sessionChargeContributions]. The caller applies the final sort across all sessions.
     */
    private fun computeBoundaries(
        db: SQLiteDatabase,
        session: ObdSessionRecord,
    ): List<DriveSocBoundary> {
        val boundaries = ArrayList<DriveSocBoundary>()
        for (window in DriveWindowDetector.windowsForSession(db, session)) {
            val points =
                ObdStoreRouteProjection.routePointsForSessionJson(
                    db,
                    session.id,
                    1000,
                    window.startedAtMs,
                    window.endedAtMs,
                )
            val distanceMeters = ObdStoreSupport.distanceMeters(points)
            val maxSpeed = ObdSessionClassifier.maxSpeedKphForWindow(db, session.id, window)
            if (!ObdSessionClassifier.isMeaningfulTrip(points.length(), distanceMeters, maxSpeed)) {
                continue
            }
            val startSoc = socForWindow(db, session.id, window, "ASC") ?: continue
            val endSoc = socForWindow(db, session.id, window, "DESC") ?: continue
            boundaries.add(DriveSocBoundary(session.id, window.startedAtMs, window.endedAtMs, startSoc, endSoc))
        }
        return boundaries
    }

    private fun socForWindow(
        db: SQLiteDatabase,
        sessionId: Long,
        window: DriveWindowDetector.DriveWindow,
        order: String,
    ): Double? =
        db
            .rawQuery(
                "SELECT soc FROM ${VoltTrackerDb.TABLE_TELEMETRY} " +
                    "WHERE session_id = ? AND captured_at_ms >= ? AND captured_at_ms <= ? " +
                    "AND soc IS NOT NULL ORDER BY captured_at_ms $order LIMIT 1",
                arrayOf(sessionId.toString(), window.startedAtMs.toString(), window.endedAtMs.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getDouble(0) else null
            }

    /**
     * Per-session contribution to the inferred-charge scan (G2): this session's within-session
     * inferred-charge rows plus its meaningful drive/SOC boundaries. Both are pure functions of
     * one finalized session's raw rows, so they are cached per session in `charge_session_rollups`
     * and reassembled by the cross-session merge/sort, which is what makes the cache transparent.
     */
    private class SessionChargeContribution(
        val withinSessionRows: List<ChargeSummaryRow>,
        val boundaries: List<DriveSocBoundary>,
    )

    /**
     * Enumerates every OBD session with useful telemetry in oldest-first order and returns each
     * one's charge contribution. The qualifying-session query is intentionally one SQL pass rather
     * than the old bounded session read plus per-session COUNT(*) filter, so whole-history charge
     * summaries do not inherit the recent-session cap or an N+1 query pattern. For a finalized
     * session cached at [CHARGE_ROLLUP_CACHE_VERSION] the cached blob is served; otherwise the
     * contribution is computed, and — only for finalized sessions — persisted before being returned.
     * The active (not-yet-finalized) session is always computed live and never cached.
     */
    private fun sessionChargeContributions(db: SQLiteDatabase): List<SessionChargeContribution> {
        val sessions = ObdStoreSupport.getObdSessionsWithUsefulTelemetry(db)
        if (sessions.isEmpty()) {
            return emptyList()
        }
        val cached = cachedChargeContributions(db, sessions.map { it.id })
        val contributions = ArrayList<SessionChargeContribution>(sessions.size)
        for (session in sessions) {
            val finalized = session.endedAtMs > 0
            val hit = if (finalized) cached[session.id] else null
            if (hit != null) {
                contributions.add(hit)
                continue
            }
            val computed =
                SessionChargeContribution(
                    computeWithinSessionRows(db, session),
                    computeBoundaries(db, session),
                )
            if (finalized) {
                writeChargeRollup(db, session.id, computed)
            }
            contributions.add(computed)
        }
        return contributions
    }

    /**
     * Loads cached charge contributions for [sessionIds] that are still at the current
     * [CHARGE_ROLLUP_CACHE_VERSION], parsing each stored blob back into a contribution. A blob
     * that fails to parse is skipped (treated as a miss), so the session recomputes and rewrites
     * a fresh row instead of poisoning the projection.
     */
    private fun cachedChargeContributions(
        db: SQLiteDatabase,
        sessionIds: List<Long>,
    ): Map<Long, SessionChargeContribution> {
        val result = HashMap<Long, SessionChargeContribution>()
        for (chunk in sessionIds.chunked(SQLITE_BIND_CHUNK)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val args = (chunk.map { it.toString() } + CHARGE_ROLLUP_CACHE_VERSION.toString()).toTypedArray()
            db
                .rawQuery(
                    "SELECT session_id, charge_json FROM ${VoltTrackerDb.TABLE_CHARGE_SESSION_ROLLUPS} " +
                        "WHERE session_id IN ($placeholders) AND rollup_version = ?",
                    args,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val sessionId = cursor.getLong(0)
                        parseChargeContribution(cursor.getString(1))?.let { result[sessionId] = it }
                    }
                }
        }
        return result
    }

    private fun writeChargeRollup(
        db: SQLiteDatabase,
        sessionId: Long,
        contribution: SessionChargeContribution,
    ) {
        val values =
            ContentValues().apply {
                put("session_id", sessionId)
                put("rollup_version", CHARGE_ROLLUP_CACHE_VERSION)
                put("computed_at_ms", System.currentTimeMillis())
                put("charge_json", serializeChargeContribution(contribution).toString())
            }
        db.insertWithOnConflict(
            VoltTrackerDb.TABLE_CHARGE_SESSION_ROLLUPS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    @Throws(JSONException::class)
    private fun serializeChargeContribution(contribution: SessionChargeContribution): JSONObject {
        val rows = JSONArray()
        for (row in contribution.withinSessionRows) {
            rows.put(serializeChargeRow(row))
        }
        val boundaries = JSONArray()
        for (boundary in contribution.boundaries) {
            boundaries.put(serializeBoundary(boundary))
        }
        return JSONObject().put("rows", rows).put("boundaries", boundaries)
    }

    private fun parseChargeContribution(json: String?): SessionChargeContribution? {
        if (json.isNullOrEmpty()) {
            return null
        }
        return try {
            val obj = JSONObject(json)
            val rowsArray = obj.getJSONArray("rows")
            val rows = ArrayList<ChargeSummaryRow>(rowsArray.length())
            for (i in 0 until rowsArray.length()) {
                rows.add(parseChargeRow(rowsArray.getJSONObject(i)))
            }
            val boundariesArray = obj.getJSONArray("boundaries")
            val boundaries = ArrayList<DriveSocBoundary>(boundariesArray.length())
            for (i in 0 until boundariesArray.length()) {
                boundaries.add(parseBoundary(boundariesArray.getJSONObject(i)))
            }
            SessionChargeContribution(rows, boundaries)
        } catch (ex: JSONException) {
            null
        }
    }

    @Throws(JSONException::class)
    private fun serializeChargeRow(row: ChargeSummaryRow): JSONObject =
        JSONObject()
            .put("id", row.id)
            .put("startedAtMs", row.startedAtMs)
            .put("endedAtMs", row.endedAtMs ?: JSONObject.NULL)
            .put("chargerType", row.chargerType ?: JSONObject.NULL)
            .put("startSoc", row.startSoc ?: JSONObject.NULL)
            .put("endSoc", row.endSoc ?: JSONObject.NULL)
            .put("powerKw", row.powerKw ?: JSONObject.NULL)
            .put("energyKwh", row.energyKwh ?: JSONObject.NULL)
            .put("confidence", row.confidence ?: JSONObject.NULL)

    @Throws(JSONException::class)
    private fun parseChargeRow(obj: JSONObject): ChargeSummaryRow =
        ChargeSummaryRow(
            obj.get("id"),
            obj.getLong("startedAtMs"),
            if (obj.isNull("endedAtMs")) null else obj.getLong("endedAtMs"),
            if (obj.isNull("chargerType")) null else obj.getString("chargerType"),
            if (obj.isNull("startSoc")) null else obj.getDouble("startSoc"),
            if (obj.isNull("endSoc")) null else obj.getDouble("endSoc"),
            if (obj.isNull("powerKw")) null else obj.getDouble("powerKw"),
            if (obj.isNull("energyKwh")) null else obj.getDouble("energyKwh"),
            if (obj.isNull("confidence")) null else obj.getDouble("confidence"),
        )

    @Throws(JSONException::class)
    private fun serializeBoundary(boundary: DriveSocBoundary): JSONObject =
        JSONObject()
            .put("sessionId", boundary.sessionId)
            .put("startedAtMs", boundary.startedAtMs)
            .put("endedAtMs", boundary.endedAtMs)
            .put("startSoc", boundary.startSoc)
            .put("endSoc", boundary.endSoc)

    @Throws(JSONException::class)
    private fun parseBoundary(obj: JSONObject): DriveSocBoundary =
        DriveSocBoundary(
            obj.getLong("sessionId"),
            obj.getLong("startedAtMs"),
            obj.getLong("endedAtMs"),
            obj.getDouble("startSoc"),
            obj.getDouble("endSoc"),
        )

    private fun isInsideInferredCharge(
        observed: ChargeSummaryRow,
        inferred: ChargeSummaryRow,
    ): Boolean {
        val observedEnd = observed.endedAtMs ?: observed.startedAtMs
        val inferredEnd = inferred.endedAtMs ?: inferred.startedAtMs
        return observed.startedAtMs >= inferred.startedAtMs && observedEnd <= inferredEnd
    }

    private fun maxChargePowerKw(rows: List<ChargeSummaryRow>): Double {
        var max = 0.0
        for (row in rows) {
            val power = row.powerKw
            if (power != null && !power.isNaN() && !power.isInfinite() && power > max) {
                max = power
            }
        }
        return max
    }

    private fun chargeSessionRow(cursor: Cursor): ChargeSummaryRow {
        val chargerType = cursor.getString(cursor.getColumnIndexOrThrow("charger_type"))
        return ChargeSummaryRow(
            cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
            cursor.getLong(cursor.getColumnIndexOrThrow("started_at_ms")),
            ObdStoreSupport.nullableLongBoxed(cursor, "ended_at_ms"),
            if (chargerType == null) null else ObdStoreSupport.clean(chargerType),
            ObdStoreSupport.nullableDoubleBoxed(cursor, "start_soc"),
            ObdStoreSupport.nullableDoubleBoxed(cursor, "end_soc"),
            ObdStoreSupport.nullableDoubleBoxed(cursor, "power_kw"),
            ObdStoreSupport.nullableDoubleBoxed(cursor, "energy_kwh"),
            ObdStoreSupport.nullableDoubleBoxed(cursor, "confidence"),
        )
    }

    @Throws(JSONException::class)
    private fun chargeSummaryRowJson(row: ChargeSummaryRow): JSONObject =
        JSONObject()
            .put("id", row.id)
            .put("startedAtMs", row.startedAtMs)
            .put("endedAtMs", boxedOrNull(row.endedAtMs))
            .put("chargerType", row.chargerType ?: JSONObject.NULL)
            .put("startSoc", boxedOrNull(row.startSoc))
            .put("endSoc", boxedOrNull(row.endSoc))
            .put("powerKw", boxedOrNull(row.powerKw))
            .put("energyKwh", boxedOrNull(row.energyKwh))
            .put("confidence", boxedOrNull(row.confidence))

    @Throws(JSONException::class)
    private fun chargeSummaryRowsJson(
        rows: List<ChargeSummaryRow>,
        limit: Int,
    ): JSONArray {
        val out = JSONArray()
        val count = minOf(maxOf(1, limit), rows.size)
        for (i in 0 until count) {
            out.put(chargeSummaryRowJson(rows[i]))
        }
        return out
    }
}
