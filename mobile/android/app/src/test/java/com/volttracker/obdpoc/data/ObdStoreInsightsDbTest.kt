package com.volttracker.obdpoc.data

import android.content.ContentValues
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Efficiency by speed band and cell drift, read by [ObdStoreInsights] from a real schema. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObdStoreInsightsDbTest {
    private lateinit var helper: VoltTrackerDb
    private lateinit var insights: ObdStoreInsights
    private var sessionId = 0L

    @Before
    fun setUp() {
        helper = VoltTrackerDb(RuntimeEnvironment.getApplication(), DB_NAME)
        insights = ObdStoreInsights(helper)
        sessionId =
            helper.writableDatabase.insertOrThrow(
                VoltTrackerDb.TABLE_SESSIONS,
                null,
                ContentValues().apply {
                    put("mode", "obd")
                    put("started_at_ms", 0L)
                    put("status", "complete")
                    put("created_at_ms", 0L)
                },
            )
    }

    @After
    fun tearDown() {
        helper.close()
        RuntimeEnvironment.getApplication().deleteDatabase(DB_NAME)
    }

    @Test
    fun electricEfficiencyIsGroupedInTenMphBands() {
        // 72 km/h = 44.7 mph (the 40s band) at 10 kW: 4.47 mi/kWh.
        samples(40, 72, 10.0, "driving_ev", fromMs = 1_000L)
        // Regen counts against drive: 20 kW out, then 10 kW back in the 20s band (48 km/h ≈ 29.8 mph).
        samples(20, 48, 20.0, "driving_ev", fromMs = 100_000L)
        samples(20, 48, -10.0, "driving_ev", fromMs = 200_000L)
        // Too few samples, under 10 mph, gas, and outside the window: all left out.
        samples(10, 100, 12.0, "driving_ev", fromMs = 300_000L)
        samples(40, 12, 3.0, "driving_ev", fromMs = 400_000L)
        samples(40, 72, 30.0, "driving_gas", fromMs = 500_000L)
        samples(40, 72, 30.0, "driving_ev", fromMs = 9_000_000L)

        val bands = insights.efficiencyBySpeedJson(0L, 1_000_000L)

        assertEquals(2, bands.length())
        assertEquals(20, bands.getJSONObject(0).getInt("mph"))
        // 40 samples × 48 km/h over a net 20 × 20 − 20 × 10 = 200 kW-samples.
        assertEquals(40 * 48 * 0.621371 / 200.0, bands.getJSONObject(0).getDouble("miPerKwh"), 1e-6)
        assertEquals(40, bands.getJSONObject(1).getInt("mph"))
        assertEquals(72 * 0.621371 / 10.0, bands.getJSONObject(1).getDouble("miPerKwh"), 1e-6)
        assertEquals(40, bands.getJSONObject(1).getInt("samples"))
    }

    @Test
    fun aCellSinkingBelowThePackMeanIsReported() {
        // Two weeks apart, cell 3 went from 3.75 mV to 21 mV under the mean (the mean includes it).
        probe(atMs = 0L, low = 0.005)
        probe(atMs = 14 * DAY, low = 0.028)

        val drift = insights.cellDriftJson()

        assertEquals(3, drift.getInt("cell"))
        assertEquals(21, drift.getInt("belowMeanMv"))
        assertEquals(17, drift.getInt("driftMv"))
        assertEquals(14, drift.getInt("days"))
    }

    @Test
    fun oneLowReadingOrASteadyCellIsNotATrend() {
        probe(atMs = 0L, low = 0.020)
        assertFalse("no older probe to compare", insights.cellDriftJson().has("cell"))
        probe(atMs = 3 * DAY, low = 0.030)
        assertFalse("under a week apart", insights.cellDriftJson().has("cell"))
        probe(atMs = 10 * DAY, low = 0.022)
        assertFalse("low, but no lower than a week before", insights.cellDriftJson().has("cell"))
    }

    private fun samples(
        count: Int,
        speedKph: Int,
        powerKw: Double,
        state: String,
        fromMs: Long,
    ) {
        val db = helper.writableDatabase
        repeat(count) { i ->
            db.insertOrThrow(
                VoltTrackerDb.TABLE_TELEMETRY,
                null,
                ContentValues().apply {
                    put("session_id", sessionId)
                    put("captured_at_ms", fromMs + i * 1_000L)
                    put("vehicle_state", state)
                    put("speed_kph", speedKph)
                    put("power_kw", powerKw)
                    put("json", "{}")
                },
            )
        }
    }

    /** A 4-cell probe at 3.9 V with cell 3 [low] volts under the others. */
    private fun probe(
        atMs: Long,
        low: Double,
    ) {
        val db = helper.writableDatabase
        val id =
            db.insertOrThrow(
                VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS,
                null,
                ContentValues().apply {
                    put("captured_at_ms", atMs)
                    put("created_at_ms", atMs)
                },
            )
        listOf(3.9, 3.9, 3.9 - low, 3.9).forEachIndexed { i, v ->
            db.insertOrThrow(
                VoltTrackerDb.TABLE_CELL_SNAPSHOTS,
                null,
                ContentValues().apply {
                    put("battery_snapshot_id", id)
                    put("cell_index", i + 1)
                    put("voltage", v)
                },
            )
        }
    }

    private companion object {
        const val DB_NAME = "insights-test.db"
        const val DAY = 86_400_000L
    }
}
