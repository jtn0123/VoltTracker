package com.volttracker.obdpoc.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Read-only aggregates for the Insights tab that the trip rows can't answer: electric efficiency
 * by speed, and whether one cell of the pack is drifting low across the full-pack cell probes.
 */
class ObdStoreInsights internal constructor(
    private val helper: VoltTrackerDb,
) {
    /**
     * Electric efficiency in 10-mph bands over `[sinceMs, untilMs)`: `[{mph, miPerKwh, samples}]`
     * with `mph` the band's lower edge (10, 20, …), from the `driving_ev` samples that logged pack
     * power. Samples are evenly spaced within a session, so Σ speed / Σ power is miles per kWh.
     * Net power counts regen against drive. Bands with too few samples, or an implausible ratio,
     * are left out; below 10 mph is left out altogether (creeping says little about a speed).
     */
    fun efficiencyBySpeedJson(
        sinceMs: Long,
        untilMs: Long,
    ): JSONArray {
        val out = JSONArray()
        helper.readableDatabase
            .rawQuery(
                "SELECT CAST(speed_kph * $MPH_PER_KPH / $BAND_MPH AS INTEGER) AS band, COUNT(*), " +
                    "SUM(speed_kph), SUM(power_kw) FROM ${VoltTrackerDb.TABLE_TELEMETRY} " +
                    "WHERE captured_at_ms >= ? AND captured_at_ms < ? AND vehicle_state = 'driving_ev' " +
                    "AND speed_kph > 0 AND power_kw IS NOT NULL GROUP BY band ORDER BY band",
                arrayOf(sinceMs.toString(), untilMs.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val band = cursor.getInt(0)
                    val samples = cursor.getInt(1)
                    val powerKw = cursor.getDouble(3)
                    if (band < 1 || samples < MIN_BAND_SAMPLES || powerKw <= 0.0) continue
                    val miPerKwh = cursor.getDouble(2) * MPH_PER_KPH / powerKw
                    if (miPerKwh > MAX_MI_PER_KWH) continue
                    out.put(
                        JSONObject()
                            .put("mph", band * BAND_MPH)
                            .put("miPerKwh", miPerKwh)
                            .put("samples", samples),
                    )
                }
            }
        return out
    }

    /**
     * The cell most below the pack mean in the latest full-pack probe, when it sits at least
     * 15 mV low AND has sunk at least 10 mV further than in the newest probe a week or more
     * older: `{cell, belowMeanMv, driftMv, days, capturedAtMs}`. Empty otherwise — one low
     * reading, or no older probe to compare with, is not a trend.
     */
    fun cellDriftJson(): JSONObject {
        val snapshots = cellSnapshots()
        val latest = snapshots.firstOrNull() ?: return JSONObject()
        val baseline = snapshots.firstOrNull { it.second <= latest.second - MIN_TREND_MS } ?: return JSONObject()
        val now = deviationsMv(latest.first)
        val then = deviationsMv(baseline.first)
        val (cell, below) = now.minByOrNull { it.value }?.toPair() ?: return JSONObject()
        val before = then[cell] ?: return JSONObject()
        val drift = before - below
        if (-below < MIN_BELOW_MEAN_MV || drift < MIN_DRIFT_MV) return JSONObject()
        return JSONObject()
            .put("cell", cell)
            .put("belowMeanMv", (-below).roundToInt())
            .put("driftMv", drift.roundToInt())
            .put("days", ((latest.second - baseline.second) / DAY_MS).toInt())
            .put("capturedAtMs", latest.second)
    }

    /** (snapshot id, captured at) of every probe that stored cells, newest first. */
    private fun cellSnapshots(): List<Pair<Long, Long>> =
        helper.readableDatabase
            .rawQuery(
                "SELECT b._id, b.captured_at_ms FROM ${VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS} b " +
                    "WHERE EXISTS (SELECT 1 FROM ${VoltTrackerDb.TABLE_CELL_SNAPSHOTS} c " +
                    "WHERE c.battery_snapshot_id = b._id) ORDER BY b.captured_at_ms DESC",
                null,
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getLong(1)) }
            }

    /** Each cell's voltage less the probe's mean, in mV. */
    private fun deviationsMv(snapshotId: Long): Map<Int, Double> {
        val volts =
            helper.readableDatabase
                .rawQuery(
                    "SELECT cell_index, voltage FROM ${VoltTrackerDb.TABLE_CELL_SNAPSHOTS} " +
                        "WHERE battery_snapshot_id = ? AND voltage IS NOT NULL",
                    arrayOf(snapshotId.toString()),
                ).use { cursor ->
                    buildMap { while (cursor.moveToNext()) put(cursor.getInt(0), cursor.getDouble(1)) }
                }
        if (volts.isEmpty()) return emptyMap()
        val mean = volts.values.average()
        return volts.mapValues { (_, v) -> (v - mean) * MV_PER_V }
    }

    private companion object {
        const val MPH_PER_KPH = 0.621371
        const val BAND_MPH = 10
        const val MIN_BAND_SAMPLES = 30
        const val MAX_MI_PER_KWH = 15.0
        const val MIN_TREND_MS = 7 * 86_400_000L
        const val DAY_MS = 86_400_000L
        const val MIN_BELOW_MEAN_MV = 15.0
        const val MIN_DRIFT_MV = 10.0
        const val MV_PER_V = 1000.0
    }
}
