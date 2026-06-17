package com.volttracker.obdpoc.data

import android.content.ContentValues
import com.volttracker.obdpoc.StorageSummaryJson
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for the storage-summary and insights projections in [ObdStoreReports]
 * (exposed via [ObdLocalStore.getStorageSummaryRecord] and [ObdLocalStore.getInsightsJson]). Asserts
 * only the keys the source actually writes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObdStoreReportsDbTest {
    private lateinit var store: ObdLocalStore

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        store = ObdLocalStore(context)
        store.clearAllData()
    }

    @After
    fun tearDown() {
        store.close()
    }

    private fun sample(
        speedKph: Int,
        soc: Double,
        lat: Double,
        lng: Double,
        atMs: Long,
    ): JSONObject {
        val sample = JSONObject()
        sample.put("source", "obd")
        sample.put("speedKph", speedKph)
        sample.put("rpm", 1500)
        sample.put("soc", soc)
        sample.put("latitude", lat)
        sample.put("longitude", lng)
        sample.put("updatedAt", atMs)
        return sample
    }

    // ---- empty store ---------------------------------------------------------------

    @Test
    fun emptyStoreSummaryHasZeroCountsAndKnownKeys() {
        val summary = StorageSummaryJson.build(store.getStorageSummaryRecord())
        assertNotNull(summary)

        // The keys the dashboard serializer writes from the typed storage summary record.
        assertEquals(0, summary.optInt("sessionCount"))
        assertEquals(0L, summary.optLong("sampleCount"))
        assertEquals(0L, summary.optLong("rawTelemetryCount"))
        assertEquals(0L, summary.optLong("emptyTelemetryCount"))
        assertEquals(0L, summary.optLong("eventCount"))
        assertEquals(0L, summary.optLong("adapterCount"))
        assertEquals(0L, summary.optLong("pidObservationCount"))
        assertEquals(0L, summary.optLong("diagnosticCodeCount"))
        assertEquals(0L, summary.optLong("locationSampleCount"))
        // The database header itself takes some bytes once opened.
        assertTrue("databaseBytes should be reported", summary.has("databaseBytes"))
    }

    @Test
    fun emptyStoreInsightsHasZeroCountsAndKnownKeys() {
        val insights = store.getInsightsJson()
        assertNotNull(insights)

        // The keys we know insightsJson() writes — see ObdStoreTrips#insightsJson.
        assertEquals(0, insights.optInt("tripCount"))
        assertEquals(0.0, insights.optDouble("totalDistanceMeters", -1.0), 0.001)
        assertEquals(0L, insights.optLong("totalDriveMs"))
        assertEquals(0.0, insights.optDouble("longestTripMeters", -1.0), 0.001)
        assertEquals(0.0, insights.optDouble("avgTripDistanceMeters", -1.0), 0.001)
        assertEquals(0, insights.optInt("maxSpeedKph"))
        assertEquals(0, insights.optInt("gpsTripCount"))
        assertEquals(0L, insights.optLong("firstTripAtMs"))
        assertEquals(0L, insights.optLong("lastTripAtMs"))
        assertEquals(0L, insights.optLong("sessionCount"))
        assertEquals(0L, insights.optLong("sampleCount"))
        assertEquals(0L, insights.optLong("locationSampleCount"))
    }

    // ---- after seeded data ---------------------------------------------------------

    @Test
    fun summaryReflectsSeededSessionAndTelemetry() {
        val id = store.startSession("obd", "00:11", "Adapter")
        store.recordTelemetry(id, sample(40, 50.0, 32.70, -117.10, 1000L))
        store.recordTelemetry(id, sample(50, 51.0, 32.71, -117.10, 2000L))
        store.recordTelemetry(id, sample(60, 52.0, 32.72, -117.10, 3000L))

        val summary = StorageSummaryJson.build(store.getStorageSummaryRecord())
        assertEquals(1, summary.optInt("sessionCount"))
        assertEquals(3L, summary.optLong("sampleCount"))
        assertEquals(3L, summary.optLong("rawTelemetryCount"))
        assertEquals(0L, summary.optLong("emptyTelemetryCount"))
        // Latest-session breadcrumbs only populate after at least one session exists.
        assertEquals(id, summary.optLong("lastSessionId"))
        assertEquals("obd", summary.optString("lastMode"))
        assertEquals("Adapter", summary.optString("lastAdapter"))
    }

    @Test
    fun targetedStorageProjectionsExposeCountsDiagnosticsAndDomainSummaries() {
        val id = store.startSession("scan", "00:11", "Adapter")
        store.recordTelemetry(id, sample(40, 50.0, 32.70, -117.10, 1000L))
        store.recordDiagnosticCode(id, diagnosticCode("P25A2", "stored", 1100L))

        val counts = store.projections().storageCounts()
        assertEquals(1L, counts.optLong("sessionCount"))
        assertEquals(1L, counts.optLong("sampleCount"))
        assertEquals(id, counts.optLong("lastSessionId"))

        val diagnostics = store.projections().diagnosticsSummary(5)
        assertEquals(1L, diagnostics.optLong("diagnosticCodeCount"))
        assertEquals(1L, diagnostics.getJSONObject("statusCounts").optLong("stored"))
        assertEquals("P25A2", diagnostics.getJSONArray("latestDiagnosticCodes").getJSONObject(0).optString("dtc"))

        val charge = store.projections().chargeSummary()
        assertTrue(charge.has("chargeSessionCount"))

        val battery = store.projections().batterySummary()
        assertTrue(battery.has("snapshotCount"))

        val routes = store.getRecentRoutesJson(4, 120)
        assertNotNull(routes)
    }

    @Test
    fun latestVehicleProjectionSurfacesRedactedVinAndName() {
        store.upsertVehicleFromVin("1G1ZD5ST8JF202020")

        // Lightweight projection used on the OBD connect handshake: same redacted-VIN/name shape as
        // the storage-summary record's latestVehicle slot, via a single last_seen_ms DESC query.
        val latest = store.projections().latestVehicle()
        assertEquals("…2020", latest.optString("vin"))
        assertEquals("Chevrolet", latest.optString("name"))
        assertTrue(latest.optLong("vehicleId") > 0)
    }

    @Test
    fun latestVehicleProjectionIsEmptyWhenNoVehiclesRecorded() {
        // No upsert → vehicles table empty → projection returns an empty object (not null).
        val latest = store.projections().latestVehicle()
        assertEquals(0, latest.length())
    }

    @Test
    fun latestTelemetryIncludesStoredPackVoltageAndCurrent() {
        val id = store.startSession("obd", "00:11", "Adapter")
        val sample = sample(40, 50.0, 32.70, -117.10, 1000L)
        sample.put("packVoltage", 352.4)
        sample.put("packCurrentA", -8.25)
        store.recordTelemetry(id, sample)

        val summary = StorageSummaryJson.build(store.getStorageSummaryRecord())
        val overviewLatest =
            summary.getJSONObject("overview").getJSONObject("latestTelemetry")
        assertEquals(352.4, overviewLatest.optDouble("packVoltage"), 0.001)
        assertEquals(-8.25, overviewLatest.optDouble("packCurrentA"), 0.001)

        val batteryLatest =
            summary.getJSONObject("batterySummary").getJSONObject("latestTelemetry")
        assertEquals(352.4, batteryLatest.optDouble("packVoltage"), 0.001)
        assertEquals(-8.25, batteryLatest.optDouble("packCurrentA"), 0.001)
    }

    @Test
    fun freshCapacityTelemetryCreatesBatterySnapshotForTrend() {
        val id = store.startSession("obd", "00:11", "Adapter")
        val sample = sample(40, 50.0, 32.70, -117.10, 1000L)
        sample.put("capacityAh", 51.7)
        sample.put("capacityAhStaleMs", 0L)
        sample.put("sohPct", 99.4)
        sample.put("packVoltage", 355.0)
        sample.put("packCurrentA", -4.0)
        sample.put("powerKw", -1.4)
        sample.put("batteryTemp", 24.0)
        store.recordTelemetry(id, sample)

        val battery = store.projections().batterySummary()
        assertEquals(1L, battery.optLong("snapshotCount"))
        val latest = battery.getJSONObject("latestBatterySnapshot")
        assertEquals(51.7, latest.optDouble("capacityAh"), 0.001)
        assertEquals(99.4, latest.optDouble("sohPct"), 0.001)
        assertEquals(355.0, latest.optDouble("packVoltage"), 0.001)
        assertEquals(24.0, latest.optDouble("batteryTempC"), 0.001)
    }

    @Test
    fun staleCapacityTelemetryDoesNotSpamBatterySnapshots() {
        val id = store.startSession("obd", "00:11", "Adapter")
        val sample = sample(40, 50.0, 32.70, -117.10, 1000L)
        sample.put("capacityAh", 51.7)
        sample.put("capacityAhStaleMs", 12_000L)
        store.recordTelemetry(id, sample)

        assertEquals(0L, store.projections().batterySummary().optLong("snapshotCount"))
    }

    @Test
    fun insightsReportsRightMaxSpeedAndDistance() {
        val id = store.startSession("obd", "00:11", "Adapter")
        store.recordTelemetry(id, sample(40, 50.0, 32.70, -117.10, 1000L))
        store.recordTelemetry(id, sample(85, 51.0, 32.71, -117.10, 2000L))
        store.recordTelemetry(id, sample(62, 52.0, 32.72, -117.10, 3000L))
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 4000L, "")

        val insights = store.getInsightsJson()
        assertEquals(1, insights.optInt("tripCount"))
        assertEquals(85, insights.optInt("maxSpeedKph"))
        // ~2.2 km along latitude — distance is computed from telemetry-fallback GPS points.
        val total = insights.optDouble("totalDistanceMeters", 0.0)
        assertTrue("total distance ~2 km should be reported, got $total", total > 2000.0)
        assertEquals(total, insights.optDouble("longestTripMeters"), 0.001)
        assertEquals(1, insights.optInt("gpsTripCount"))
    }

    @Test
    fun insightsAggregatesMaxAcrossMultipleSessions() {
        val first = store.startSession("obd", "00:11", "Adapter A")
        store.recordTelemetry(first, sample(40, 50.0, 32.70, -117.10, 1000L))
        store.recordTelemetry(first, sample(50, 51.0, 32.71, -117.10, 2000L))
        store.finishSession(first, ObdLocalStore.STATUS_COMPLETE, 3000L, "")

        val second = store.startSession("obd", "00:22", "Adapter B")
        store.recordTelemetry(second, sample(95, 60.0, 33.00, -118.00, 10000L))
        store.recordTelemetry(second, sample(80, 61.0, 33.01, -118.00, 11000L))
        store.finishSession(second, ObdLocalStore.STATUS_COMPLETE, 12000L, "")

        val insights = store.getInsightsJson()
        assertEquals(2, insights.optInt("tripCount"))
        assertEquals("max should be taken across all trips", 95, insights.optInt("maxSpeedKph"))
    }

    @Test
    fun summaryPreservesNullChargeAndBatteryFields() {
        val context = RuntimeEnvironment.getApplication()
        val helper = VoltTrackerDb(context)
        try {
            val db = helper.writableDatabase
            val charge = ContentValues()
            charge.put("started_at_ms", 1000L)
            charge.put("ended_at_ms", 30 * 60_000L)
            charge.putNull("charger_type")
            charge.putNull("start_soc")
            charge.putNull("end_soc")
            charge.put("power_kw", 3.7)
            charge.put("energy_kwh", 1.2)
            charge.putNull("confidence")
            charge.put("created_at_ms", 1000L)
            db.insertOrThrow(VoltTrackerDb.TABLE_CHARGE_SESSIONS, null, charge)

            val battery = ContentValues()
            battery.put("captured_at_ms", 2000L)
            battery.putNull("soc")
            battery.putNull("capacity_ah")
            battery.putNull("soh_pct")
            battery.putNull("pack_voltage")
            battery.putNull("pack_current_a")
            battery.putNull("pack_power_kw")
            battery.putNull("battery_temp_c")
            battery.put("created_at_ms", 2000L)
            db.insertOrThrow(VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS, null, battery)
        } finally {
            helper.close()
        }

        val summary = StorageSummaryJson.build(store.getStorageSummaryRecord())
        val latestCharge = summary.getJSONObject("chargeSummary").getJSONObject("latest")
        assertEquals(30 * 60_000L, latestCharge.getLong("endedAtMs"))
        assertEquals(JSONObject.NULL, latestCharge.get("chargerType"))
        assertEquals(JSONObject.NULL, latestCharge.get("startSoc"))
        assertEquals(JSONObject.NULL, latestCharge.get("endSoc"))
        assertEquals(3.7, latestCharge.getDouble("powerKw"), 0.001)
        assertEquals(1.2, latestCharge.getDouble("energyKwh"), 0.001)
        assertEquals(JSONObject.NULL, latestCharge.get("confidence"))

        val latestBattery =
            summary.getJSONObject("batterySummary").getJSONObject("latestBatterySnapshot")
        assertEquals(JSONObject.NULL, latestBattery.get("soc"))
        assertEquals(JSONObject.NULL, latestBattery.get("capacityAh"))
        assertEquals(JSONObject.NULL, latestBattery.get("sohPct"))
        assertEquals(JSONObject.NULL, latestBattery.get("packVoltage"))
        assertEquals(JSONObject.NULL, latestBattery.get("packCurrentA"))
        assertEquals(JSONObject.NULL, latestBattery.get("packPowerKw"))
        assertEquals(JSONObject.NULL, latestBattery.get("batteryTempC"))
    }

    @Test
    fun summaryListsRecentChargeSessionsNewestFirst() {
        val context = RuntimeEnvironment.getApplication()
        val helper = VoltTrackerDb(context)
        try {
            val db = helper.writableDatabase
            db.insertOrThrow(
                VoltTrackerDb.TABLE_CHARGE_SESSIONS,
                null,
                chargeRow(1_000L, "level1", 20.0, 55.0, 1.4, 4.0),
            )
            db.insertOrThrow(
                VoltTrackerDb.TABLE_CHARGE_SESSIONS,
                null,
                chargeRow(9_000L, "level2", 40.0, 90.0, 7.2, 9.6),
            )
        } finally {
            helper.close()
        }

        val charge =
            StorageSummaryJson
                .build(store.getStorageSummaryRecord())
                .getJSONObject("chargeSummary")
        assertEquals(2, charge.getInt("chargeSessionCount"))

        val recent = charge.getJSONArray("recentSessions")
        assertEquals(2, recent.length())

        // Newest first: the level2 session started at 9_000 leads.
        val newest = recent.getJSONObject(0)
        assertEquals(9_000L, newest.getLong("startedAtMs"))
        assertEquals("level2", newest.getString("chargerType"))
        assertEquals(40.0, newest.getDouble("startSoc"), 0.001)
        assertEquals(90.0, newest.getDouble("endSoc"), 0.001)
        assertEquals(9.6, newest.getDouble("energyKwh"), 0.001)

        assertEquals(1_000L, recent.getJSONObject(1).getLong("startedAtMs"))
    }

    @Test
    fun chargeSummaryExcludesRowsThatOverlapMovingTelemetry() {
        val sessionId = store.startSession("obd", "00:11", "Adapter")
        store.recordTelemetry(sessionId, sample(55, 70.0, 32.70, -117.10, 1_000L))
        val context = RuntimeEnvironment.getApplication()
        val helper = VoltTrackerDb(context)
        try {
            val db = helper.writableDatabase
            db.insertOrThrow(
                VoltTrackerDb.TABLE_CHARGE_SESSIONS,
                null,
                chargeRow(1_000L, "level2", 70.0, 70.0, 154.9, 0.2).apply {
                    put("session_id", sessionId)
                    put("ended_at_ms", 2_000L)
                },
            )
            db.insertOrThrow(
                VoltTrackerDb.TABLE_CHARGE_SESSIONS,
                null,
                chargeRow(9_000L, "level2", 40.0, 90.0, 7.2, 9.6),
            )
        } finally {
            helper.close()
        }

        val charge =
            StorageSummaryJson
                .build(store.getStorageSummaryRecord())
                .getJSONObject("chargeSummary")

        assertEquals(1, charge.getInt("chargeSessionCount"))
        assertEquals(7.2, charge.getDouble("maxPowerKw"), 0.001)
        val recent = charge.getJSONArray("recentSessions")
        assertEquals(1, recent.length())
        assertEquals(9_000L, recent.getJSONObject(0).getLong("startedAtMs"))
    }

    @Test
    fun chargeSummaryDropsStationaryObservedRowsWithoutSocGain() {
        val context = RuntimeEnvironment.getApplication()
        val helper = VoltTrackerDb(context)
        try {
            helper.writableDatabase.insertOrThrow(
                VoltTrackerDb.TABLE_CHARGE_SESSIONS,
                null,
                chargeRow(1_000L, "level2", 15.0, 15.0, 3.7, 0.12).apply {
                    put("ended_at_ms", 3 * 60_000L)
                },
            )
        } finally {
            helper.close()
        }

        val charge = store.projections().chargeSummary()
        assertEquals(0, charge.getInt("chargeSessionCount"))
        assertEquals(0, charge.getJSONArray("recentSessions").length())
    }

    @Test
    fun chargeSummaryDropsTinyObservedSocFragments() {
        val context = RuntimeEnvironment.getApplication()
        val helper = VoltTrackerDb(context)
        try {
            helper.writableDatabase.insertOrThrow(
                VoltTrackerDb.TABLE_CHARGE_SESSIONS,
                null,
                chargeRow(1_000L, "level2", 76.0, 78.0, 3.2, 0.35).apply {
                    put("ended_at_ms", 9 * 60_000L)
                },
            )
        } finally {
            helper.close()
        }

        val charge = store.projections().chargeSummary()
        assertEquals(0, charge.getInt("chargeSessionCount"))
        assertEquals(0, charge.getJSONArray("recentSessions").length())
    }

    @Test
    fun chargeSummaryInfersChargeBetweenDriveSessionsFromSocGain() {
        val first = store.startSession("obd", "00:11", "Adapter")
        store.recordTelemetry(first, sample(40, 15.0, 32.70, -117.10, 1_000L))
        store.recordTelemetry(first, sample(42, 15.0, 32.71, -117.10, 2_000L))
        store.finishSession(first, ObdLocalStore.STATUS_COMPLETE, 3_000L, "")

        val second = store.startSession("obd", "00:11", "Adapter", 8 * 3_600_000L)
        store.recordTelemetry(second, sample(35, 95.0, 32.71, -117.10, 8 * 3_600_000L + 1_000L))
        store.recordTelemetry(second, sample(38, 94.0, 32.72, -117.10, 8 * 3_600_000L + 2_000L))
        store.finishSession(second, ObdLocalStore.STATUS_COMPLETE, 8 * 3_600_000L + 3_000L, "")

        val charge = store.projections().chargeSummary()
        assertEquals(1, charge.getInt("chargeSessionCount"))
        val latest = charge.getJSONObject("latest")
        assertEquals("inferred", latest.getString("chargerType"))
        assertEquals(15.0, latest.getDouble("startSoc"), 0.001)
        assertEquals(95.0, latest.getDouble("endSoc"), 0.001)
        assertTrue(latest.getDouble("energyKwh") > 10.0)
    }

    @Test
    fun chargeSummaryInfersChargeBetweenDriveWindowsInsideLongSession() {
        val id = store.startSession("obd", "00:11", "Adapter")
        store.recordTelemetry(id, sample(40, 15.0, 32.70, -117.10, 1_000L))
        store.recordTelemetry(id, sample(42, 15.0, 32.71, -117.10, 2_000L))
        val morning = 8 * 3_600_000L
        store.recordTelemetry(id, sample(35, 95.0, 32.71, -117.10, morning + 1_000L))
        store.recordTelemetry(id, sample(38, 94.0, 32.72, -117.10, morning + 2_000L))
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, morning + 3_000L, "")

        val charge = store.projections().chargeSummary()
        assertEquals(1, charge.getInt("chargeSessionCount"))
        val recent = charge.getJSONArray("recentSessions")
        assertEquals("inferred", recent.getJSONObject(0).getString("chargerType"))
        assertEquals(15.0, recent.getJSONObject(0).getDouble("startSoc"), 0.001)
        assertEquals(95.0, recent.getJSONObject(0).getDouble("endSoc"), 0.001)
    }

    private fun chargeRow(
        startedAtMs: Long,
        chargerType: String,
        startSoc: Double,
        endSoc: Double,
        powerKw: Double,
        energyKwh: Double,
    ): ContentValues {
        val row = ContentValues()
        row.put("started_at_ms", startedAtMs)
        row.put("ended_at_ms", startedAtMs + 3_600_000L)
        row.put("charger_type", chargerType)
        row.put("start_soc", startSoc)
        row.put("end_soc", endSoc)
        row.put("power_kw", powerKw)
        row.put("energy_kwh", energyKwh)
        row.put("confidence", 0.9)
        row.put("created_at_ms", startedAtMs)
        return row
    }

    private fun diagnosticCode(
        dtc: String,
        status: String,
        seenAtMs: Long,
    ): JSONObject =
        JSONObject()
            .put("dtc", dtc)
            .put("status", status)
            .put("statusLabel", "Stored/current")
            .put("moduleKey", "generic-obd")
            .put("moduleName", "ECM / powertrain (generic OBD-II)")
            .put("seenAtMs", seenAtMs)
            .put("rawResponse", "43 25 A2 00 00")
}
