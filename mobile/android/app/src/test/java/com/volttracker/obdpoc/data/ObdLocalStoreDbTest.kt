package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.volttracker.obdpoc.StorageSummaryJson
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Exercises the real SQLite data layer via Robolectric: writes, the on-read trip and insight
 * aggregation, and clearAllData. Previously this layer had no test coverage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObdLocalStoreDbTest {
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

    @Test
    fun startSessionShowsInStorageSummary() {
        store.startSession("obd", "00:11:22:33", "Adapter")
        assertEquals(
            1,
            StorageSummaryJson.build(store.getStorageSummaryRecord()).optInt("sessionCount"),
        )
    }

    @Test
    fun recordTelemetryCountsUsefulSamples() {
        val id = store.startSession("obd", "00:11", "Adapter")
        store.recordTelemetry(id, sample(40, 1500, 34.05, -118.25, 1000))
        store.recordTelemetry(id, sample(45, 1550, 34.06, -118.25, 2000))
        assertEquals(
            2L,
            StorageSummaryJson.build(store.getStorageSummaryRecord()).optLong("sampleCount"),
        )
    }

    @Test
    fun finishSessionTwoArgumentOverloadMarksSessionEnded() {
        val id = store.startSession("obd", "00:11", "Adapter")

        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE)

        val session = store.getSession(id)
        assertNotNull(session)
        assertEquals(ObdLocalStore.STATUS_COMPLETE, session!!.status)
        assertTrue("finishSession(status) should stamp an end time", session.endedAtMs > 0L)
    }

    @Test
    fun recordTelemetryUsesSampleUpdatedAtWhenOverrideIsOmitted() {
        val id = store.startSession("obd", "00:11", "Adapter")

        val rowId = store.recordTelemetry(id, sample(41, 1510, 34.05, -118.25, 12_345L))

        assertTrue(rowId > 0L)
        store.checkpoint()
        SQLiteDatabase.openDatabase(store.getDatabaseFile().path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db
                .rawQuery(
                    "SELECT captured_at_ms FROM " + VoltTrackerDb.TABLE_TELEMETRY + " WHERE _id = ?",
                    arrayOf(rowId.toString()),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(12_345L, cursor.getLong(0))
                }
        }
    }

    @Test
    fun emptyTelemetryIsRejected() {
        val id = store.startSession("obd", "00:11", "Adapter")
        assertEquals(-1L, store.recordTelemetry(id, JSONObject()))
        assertEquals(
            0L,
            StorageSummaryJson.build(store.getStorageSummaryRecord()).optLong("sampleCount"),
        )
    }

    @Test
    fun nullTelemetryIsRejectedWithoutCountingSamples() {
        val id = store.startSession("obd", "00:11", "Adapter")

        assertEquals(-1L, store.recordTelemetry(id, null))

        val session = store.getSession(id)
        assertNotNull(session)
        assertEquals(0, session!!.sampleCount)
        assertEquals(
            0L,
            StorageSummaryJson.build(store.getStorageSummaryRecord()).optLong("sampleCount"),
        )
    }

    @Test
    fun getTripRouteJsonReturnsTheRouteForAnyStoredSession() {
        // The Trips screen loads a selected drive's route on demand (bypassing the recent-routes
        // window) via getTripRouteJson — so any session with GPS must yield its route geometry.
        val id = store.startSession("obd", "00:11", "Adapter")
        store.recordTelemetry(id, sample(50, 1500, 34.05, -118.25, 1000))
        store.recordTelemetry(id, sample(70, 1700, 34.09, -118.25, 2000))
        store.recordTelemetry(id, sample(60, 1600, 34.13, -118.25, 3000))
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 4000, "")

        val route = store.getTripRouteJson(id)
        val session = route.optJSONObject("session")
        val points = route.optJSONArray("points")
        assertNotNull(session)
        assertNotNull(points)
        assertEquals(id, session!!.optLong("id"))
        assertTrue("route must carry >= 2 points", route.optInt("pointCount") >= 2)
        assertEquals(route.optInt("pointCount"), points!!.length())
        assertTrue("route distance should be > 0", route.optDouble("distanceMeters") > 0)
    }

    @Test
    fun getTripRouteJsonReturnsEmptyForUnknownSession() {
        // Defensive: selecting a trip whose row is gone must not throw — it returns {} so the UI
        // falls back to its "no route" state.
        val route = store.getTripRouteJson(999_999L)
        assertEquals(0, route.length())
    }

    @Test
    fun getTripsComputesDistanceAndMaxSpeed() {
        val id = store.startSession("obd", "00:11", "Adapter")
        store.recordTelemetry(id, sample(50, 1500, 34.05, -118.25, 1000))
        store.recordTelemetry(id, sample(90, 1800, 34.10, -118.25, 2000))
        store.recordTelemetry(id, sample(60, 1600, 34.15, -118.25, 3000))
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 4000, "")

        val trips = store.getTripsJson(10)
        assertEquals(1, trips.length())
        val trip = trips.getJSONObject(0)
        assertEquals(90, trip.optInt("maxSpeedKph"))
        assertTrue("route should span ~11 km", trip.optDouble("distanceMeters") > 10000)
        assertTrue(trip.optBoolean("hasRoute"))
        assertEquals(3, trip.optInt("sampleCount"))
    }

    @Test
    fun getInsightsAggregatesAcrossTrips() {
        val id = store.startSession("obd", "00:11", "Adapter")
        store.recordTelemetry(id, sample(50, 1500, 34.05, -118.25, 1000))
        store.recordTelemetry(id, sample(70, 1700, 34.06, -118.25, 2000))
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 3000, "")

        val insights = store.getInsightsJson()
        assertEquals(1, insights.optInt("tripCount"))
        assertEquals(70, insights.optInt("maxSpeedKph"))
        assertTrue(insights.optDouble("totalDistanceMeters") > 0)
    }

    @Test
    fun demoSessionsAreExcludedFromTrips() {
        val id = store.startSession("demo", "", "Demo")
        store.recordTelemetry(id, sample(30, 0, 34.05, -118.25, 1000))
        assertEquals(0, store.getTripsJson(10).length())
    }

    @Test
    fun recordPidObservationUsesObservedAtFallbackWhenOverrideIsOmitted() {
        val id = store.startSession("scan", "00:11", "Adapter")
        val observation =
            pidObservation(
                1L,
                "221154",
                "ATSH7E0",
                "1154",
                "engine oil temperature",
                "56",
                56.0,
                "C",
                "62115460",
            ).apply {
                remove("observedAtMs")
                put("observedAt", 12_345L)
            }

        val rowId = store.recordPidObservation(id, observation)

        assertTrue(rowId > 0L)
        store.checkpoint()
        SQLiteDatabase.openDatabase(store.getDatabaseFile().path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db
                .rawQuery(
                    "SELECT observed_at_ms FROM " + VoltTrackerDb.TABLE_PID_OBSERVATIONS + " WHERE _id = ?",
                    arrayOf(rowId.toString()),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(12_345L, cursor.getLong(0))
                }
        }
    }

    @Test
    fun emptyPidObservationBatchIsANoOp() {
        val id = store.startSession("scan", "00:11", "Adapter")

        assertEquals(0, store.recordPidObservations(id, emptyList()))

        store.checkpoint()
        SQLiteDatabase.openDatabase(store.getDatabaseFile().path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db
                .rawQuery(
                    "SELECT COUNT(*) FROM " + VoltTrackerDb.TABLE_PID_OBSERVATIONS + " WHERE session_id = ?",
                    arrayOf(id.toString()),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
        }
    }

    @Test
    fun recordLocationSampleUsesTimestampFallbackWhenOverrideIsOmitted() {
        val id = store.startSession("gps", "00:11", "Adapter")
        val location =
            JSONObject()
                .put("provider", "gps")
                .put("latitude", 34.05)
                .put("longitude", -118.25)
                .put("timestampMs", 23_456L)

        val rowId = store.recordLocationSample(id, location)

        assertTrue(rowId > 0L)
        store.checkpoint()
        SQLiteDatabase.openDatabase(store.getDatabaseFile().path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db
                .rawQuery(
                    "SELECT captured_at_ms FROM " + VoltTrackerDb.TABLE_LOCATION_SAMPLES + " WHERE _id = ?",
                    arrayOf(rowId.toString()),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(23_456L, cursor.getLong(0))
                }
        }
    }

    @Test
    fun invalidLocationSampleIsRejected() {
        val id = store.startSession("gps", "00:11", "Adapter")
        val missingLongitude =
            JSONObject()
                .put("provider", "gps")
                .put("latitude", 34.05)
                .put("capturedAtMs", 23_456L)

        assertEquals(-1L, store.recordLocationSample(id, missingLongitude))

        store.checkpoint()
        SQLiteDatabase.openDatabase(store.getDatabaseFile().path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db
                .rawQuery(
                    "SELECT COUNT(*) FROM " + VoltTrackerDb.TABLE_LOCATION_SAMPLES + " WHERE session_id = ?",
                    arrayOf(id.toString()),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
        }
    }

    @Test
    fun clearAllDataEmptiesTheDatabase() {
        val id = store.startSession("obd", "00:11", "Adapter")
        store.recordTelemetry(id, sample(40, 1500, 34.05, -118.25, 1000))
        store.recordDiagnosticCode(id, diagnosticCode("P25A2", "stored", 1000))
        store.clearAllData()

        val summary = StorageSummaryJson.build(store.getStorageSummaryRecord())
        assertEquals(0, summary.optInt("sessionCount"))
        assertEquals(0L, summary.optLong("sampleCount"))
        assertEquals(0L, summary.optLong("diagnosticCodeCount"))
    }

    @Test
    fun diagnosticCodesTrackFirstAndLastSeen() {
        val id = store.startSession("scan", "00:11", "Adapter")
        store.recordDiagnosticCode(id, diagnosticCode("P25A2", "stored", 1000))
        val nextId = store.startSession("scan", "00:11", "Adapter")
        store.recordDiagnosticCode(nextId, diagnosticCode("P25A2", "stored", 2000))
        store.recordDiagnosticCode(nextId, diagnosticCode("U0073", "pending", 2000))

        val summary = StorageSummaryJson.build(store.getStorageSummaryRecord())
        assertEquals(2L, summary.optLong("diagnosticCodeCount"))
        val statusCounts = summary.getJSONObject("diagnosticCodeStatusCounts")
        assertEquals(1L, statusCounts.optLong("stored"))
        assertEquals(1L, statusCounts.optLong("pending"))
        val codes = summary.getJSONArray("latestDiagnosticCodes")
        assertEquals(2, codes.length())
        var code: JSONObject? = null
        for (i in 0 until codes.length()) {
            val candidate = codes.getJSONObject(i)
            if ("P25A2" == candidate.optString("dtc")) {
                code = candidate
                break
            }
        }
        assertNotNull(code)
        assertEquals("P25A2", code!!.optString("dtc"))
        assertEquals("stored", code.optString("status"))
        assertEquals(1000L, code.optLong("firstSeenMs"))
        assertEquals(2000L, code.optLong("lastSeenMs"))
        assertEquals(2L, code.optLong("seenCount"))
    }

    @Test
    fun diagnosticCodesDoNotDoubleCountWithinOneScan() {
        val id = store.startSession("scan", "00:11", "Adapter")
        store.recordDiagnosticCode(id, diagnosticCode("U0073", "stored", 1000))
        store.recordDiagnosticCode(id, diagnosticCode("U0073", "stored", 1200))

        val code =
            StorageSummaryJson
                .build(store.getStorageSummaryRecord())
                .getJSONArray("latestDiagnosticCodes")
                .getJSONObject(0)
        assertEquals("U0073", code.optString("dtc"))
        assertEquals(1L, code.optLong("seenCount"))
        assertEquals(1200L, code.optLong("lastSeenMs"))
    }

    @Test
    fun enhancedPidObservationsUpdateFieldCapabilities() {
        val id = store.startSession("scan", "00:11", "Adapter")
        store.recordPidObservation(
            id,
            pidObservation(
                1000L,
                "221154",
                "ATSH7E0",
                "1154",
                "engine oil temperature",
                "56",
                56.0,
                "C",
                "62115460",
            ),
            1000L,
        )

        val capabilities = store.getEnhancedCapabilitiesJson(10)
        assertEquals(1, capabilities.length())
        val capability = capabilities.getJSONObject(0)
        assertEquals("221154", capability.optString("command"))
        assertEquals("ATSH7E0", capability.optString("header"))
        assertTrue(capability.optBoolean("supported"))
        assertEquals(1L, capability.optLong("responseCount"))
        assertEquals("confirmed", capability.getJSONObject("sample").optString("validationStatus"))
        assertEquals("low-risk", capability.getJSONObject("sample").optString("scanStage"))
        assertEquals("low", capability.getJSONObject("sample").optString("risk"))

        val summary = StorageSummaryJson.build(store.getStorageSummaryRecord())
        val summaryCapabilities = summary.getJSONArray("enhancedCapabilities")
        assertEquals(1, summaryCapabilities.length())
        assertEquals("221154", summaryCapabilities.getJSONObject(0).optString("command"))
        assertTrue(summaryCapabilities.getJSONObject(0).optBoolean("supported"))
        assertTrue(summary.getJSONArray("detailedSignalCatalog").length() >= 10)
    }

    @Test
    fun batchedEnhancedPidObservationsUpsertCapabilitiesOncePerLookupKey() {
        val id = store.startSession("scan", "00:11", "Adapter")

        val inserted =
            store.recordPidObservations(
                id,
                listOf(
                    pidObservation(
                        1000L,
                        "221154",
                        "ATSH7E0",
                        "1154",
                        "engine oil temperature",
                        "56",
                        56.0,
                        "C",
                        "62115460",
                    ),
                    pidObservation(
                        1100L,
                        "221154",
                        "ATSH7E0",
                        "1154",
                        "engine oil temperature",
                        "57",
                        57.0,
                        "C",
                        "62115461",
                    ),
                    pidObservation(
                        1200L,
                        "221154",
                        "ATSH7E0",
                        "1154",
                        "engine oil temperature",
                        "",
                        null,
                        "C",
                        "NO DATA",
                    ),
                ),
            )

        assertEquals(3, inserted)
        val capabilities = store.getEnhancedCapabilitiesJson(10)
        assertEquals(1, capabilities.length())
        val capability = capabilities.getJSONObject(0)
        assertEquals("221154", capability.optString("command"))
        assertTrue(capability.optBoolean("supported"))
        assertEquals(2L, capability.optLong("responseCount"))
        assertEquals(1000L, capability.optLong("firstSeenMs"))
        assertEquals(1200L, capability.optLong("lastSeenMs"))
        assertFalse(capability.getJSONObject("sample").optBoolean("positiveResponse"))

        val updated =
            store.recordPidObservations(
                id,
                listOf(
                    pidObservation(
                        2000L,
                        "221154",
                        "ATSH7E0",
                        "1154",
                        "engine oil temperature",
                        "58",
                        58.0,
                        "C",
                        "62115462",
                    ),
                ),
            )

        assertEquals(1, updated)
        val updatedCapability = store.getEnhancedCapabilitiesJson(10).getJSONObject(0)
        assertEquals(3L, updatedCapability.optLong("responseCount"))
        assertEquals(1000L, updatedCapability.optLong("firstSeenMs"))
        assertEquals(2000L, updatedCapability.optLong("lastSeenMs"))
        assertTrue(updatedCapability.getJSONObject("sample").optBoolean("positiveResponse"))
    }

    @Test
    fun enhancedCapabilityKeepsSupportAfterLaterNoData() {
        val id = store.startSession("scan", "00:11", "Adapter")
        store.recordPidObservation(
            id,
            pidObservation(
                1000L,
                "221154",
                "ATSH7E0",
                "1154",
                "engine oil temperature",
                "56",
                56.0,
                "C",
                "62115460",
            ),
            1000L,
        )
        store.recordPidObservation(
            id,
            pidObservation(
                2000L,
                "221154",
                "ATSH7E0",
                "1154",
                "engine oil temperature",
                "",
                null,
                "C",
                "NO DATA",
            ),
            2000L,
        )

        val capability = store.getEnhancedCapabilitiesJson(10).getJSONObject(0)
        assertTrue(capability.optBoolean("supported"))
        assertEquals(1L, capability.optLong("responseCount"))
        assertEquals(2000L, capability.optLong("lastSeenMs"))
        assertFalse(capability.getJSONObject("sample").optBoolean("positiveResponse"))
    }

    @Test
    fun fieldCapabilitiesResolveEachSessionsOwnAdapterKey() {
        // The writer caches sessionId -> adapter key so the capability upsert stops re-querying
        // the sessions table on every observation. Observations across two sessions with
        // different adapters must still land under their own keys, and a repeat observation in
        // one session (served from the cache) must keep upserting the same row.
        val first = store.startSession("scan", "00:11", "Adapter A")
        store.recordPidObservation(
            first,
            pidObservation(1000L, "221154", "ATSH7E0", "1154", "engine oil temperature", "56", 56.0, "C", "62115460"),
            1000L,
        )
        store.recordPidObservation(
            first,
            pidObservation(2000L, "221154", "ATSH7E0", "1154", "engine oil temperature", "57", 57.0, "C", "62115460"),
            2000L,
        )
        val second = store.startSession("scan", "00:22", "Adapter B")
        store.recordPidObservation(
            second,
            pidObservation(3000L, "221154", "ATSH7E0", "1154", "engine oil temperature", "58", 58.0, "C", "62115460"),
            3000L,
        )

        // One capability row per adapter key — the cache must not bleed session A's key into B.
        val capabilities = store.getEnhancedCapabilitiesJson(10)
        assertEquals(2, capabilities.length())
        assertTrue(store.hasRecentEnhancedCapability("00:11", "ATSH7E0", "221154", Long.MAX_VALUE / 2L))
        assertTrue(store.hasRecentEnhancedCapability("00:22", "ATSH7E0", "221154", Long.MAX_VALUE / 2L))
        // The two session-A observations were upserts into a single row.
        val counts = mutableListOf<Long>()
        for (i in 0 until capabilities.length()) {
            counts.add(capabilities.getJSONObject(i).optLong("responseCount"))
        }
        counts.sort()
        assertEquals(listOf(1L, 2L), counts)
    }

    @Test
    fun rejectedEnhancedCapabilityCanBeReadForDiscoverySkip() {
        val id = store.startSession("tpms-scan", "00:11", "Adapter")
        store.recordPidObservation(
            id,
            pidObservation(
                1000L,
                "224051",
                "ATSH760",
                "4051",
                "candidate tire receiver slot 1",
                "",
                null,
                "",
                "NO DATA",
            ),
            1000L,
        )

        assertTrue(store.hasRejectedEnhancedCapability("00:11", "ATSH760", "224051"))
        assertFalse(store.hasRejectedEnhancedCapability("00:11", "ATSH760", "224052"))
        assertTrue(
            store.hasRecentEnhancedCapability(
                "00:11",
                "ATSH760",
                "224051",
                Long.MAX_VALUE / 2L,
            ),
        )
        assertFalse(
            store.hasRecentEnhancedCapability(
                "00:11",
                "ATSH760",
                "224052",
                Long.MAX_VALUE / 2L,
            ),
        )
    }

    @Test
    fun enhancedCapabilityCanBeExportedAndDeletedIndividually() {
        val id = store.startSession("scan", "00:11", "Adapter")
        store.recordPidObservation(
            id,
            pidObservation(
                1000L,
                "221154",
                "ATSH7E0",
                "1154",
                "engine oil temperature",
                "56",
                56.0,
                "C",
                "62115460",
            ),
            1000L,
        )

        val capability = store.getEnhancedCapabilitiesJson(10).getJSONObject(0)
        val rowId = capability.optLong("id")
        assertTrue(rowId > 0L)

        val one = store.getEnhancedCapabilityExportJson(rowId)
        assertTrue(one.optBoolean("ok"))
        assertEquals("detailed-signal-log", one.optString("kind"))
        assertEquals("221154", one.getJSONObject("item").optString("command"))

        val all = store.getEnhancedCapabilitiesExportJson(10)
        assertTrue(all.optBoolean("ok"))
        assertEquals(1, all.getJSONArray("items").length())

        assertEquals(1, store.deleteEnhancedCapability(rowId))
        assertEquals(0, store.getEnhancedCapabilitiesJson(10).length())
        assertFalse(store.getEnhancedCapabilityExportJson(rowId).optBoolean("ok"))
    }

    // ---- GPS route building (the data the map renders) -----------------------------

    private fun locationSample(
        sessionId: Long,
        atMs: Long,
        lat: Double,
        lng: Double,
        accuracyM: Double?,
    ) {
        store.recordLocationSample(
            sessionId,
            atMs,
            "gps",
            lat,
            lng,
            accuracyM,
            null,
            null,
            null,
            null,
            null,
        )
    }

    @Test
    fun recordedLocationSamplesBuildARecentRoute() {
        val id = store.startSession("obd", "00:11", "Adapter")
        locationSample(id, 1000L, 34.05, -118.25, 5.0)
        locationSample(id, 2000L, 34.06, -118.25, 5.0)
        locationSample(id, 3000L, 34.07, -118.25, 5.0)
        store.recordTelemetry(id, sample(35, 1500, 34.05, -118.25, 1000L))
        store.recordTelemetry(id, sample(40, 1600, 34.06, -118.25, 2000L))
        store.recordTelemetry(id, sample(45, 1700, 34.07, -118.25, 3000L))

        val routes =
            StorageSummaryJson
                .build(store.getStorageSummaryRecord())
                .getJSONArray("recentRoutes")
        assertEquals(1, routes.length())
        val route = routes.getJSONObject(0)
        assertEquals(3, route.optInt("pointCount"))
        // ~0.02 deg of latitude is a little over 2 km
        assertTrue("route should span ~2 km", route.optDouble("distanceMeters") > 2000)
    }

    @Test
    fun aSinglePointDoesNotRenderAsARoute() {
        val id = store.startSession("obd", "00:11", "Adapter")
        locationSample(id, 1000L, 34.05, -118.25, 5.0)

        assertEquals(
            0,
            StorageSummaryJson
                .build(store.getStorageSummaryRecord())
                .getJSONArray("recentRoutes")
                .length(),
        )
    }

    @Test
    fun routePointsAreReturnedInChronologicalOrder() {
        val id = store.startSession("obd", "00:11", "Adapter")
        // inserted out of order; the route must still render oldest-first
        locationSample(id, 3000L, 34.07, -118.25, 5.0)
        locationSample(id, 1000L, 34.05, -118.25, 5.0)
        locationSample(id, 2000L, 34.06, -118.25, 5.0)
        store.recordTelemetry(id, sample(45, 1700, 34.07, -118.25, 3000L))
        store.recordTelemetry(id, sample(35, 1500, 34.05, -118.25, 1000L))
        store.recordTelemetry(id, sample(40, 1600, 34.06, -118.25, 2000L))

        val points =
            StorageSummaryJson
                .build(store.getStorageSummaryRecord())
                .getJSONArray("recentRoutes")
                .getJSONObject(0)
                .getJSONArray("points")
        assertEquals(1000L, points.getJSONObject(0).optLong("atMs"))
        assertEquals(2000L, points.getJSONObject(1).optLong("atMs"))
        assertEquals(3000L, points.getJSONObject(2).optLong("atMs"))
    }

    @Test
    fun routePointsCarryAccuracyForRendering() {
        val id = store.startSession("obd", "00:11", "Adapter")
        locationSample(id, 1000L, 34.05, -118.25, 7.5)
        locationSample(id, 2000L, 34.06, -118.25, 9.0)
        store.recordTelemetry(id, sample(35, 1500, 34.05, -118.25, 1000L))
        store.recordTelemetry(id, sample(40, 1600, 34.06, -118.25, 2000L))

        val points =
            StorageSummaryJson
                .build(store.getStorageSummaryRecord())
                .getJSONArray("recentRoutes")
                .getJSONObject(0)
                .getJSONArray("points")
        assertEquals(7.5, points.getJSONObject(0).optDouble("accuracyM"), 0.01)
        assertEquals(9.0, points.getJSONObject(1).optDouble("accuracyM"), 0.01)
    }

    @Test
    fun scalarTracksDownsampleAndKeepTheNewestSample() {
        val id = store.startSession("obd", "00:11", "Adapter")
        for (i in 0 until ObdStoreRouteProjection.MAX_TRACK_POINTS + 40) {
            val atMs = 1000L + i * 1000L
            val sample = sample(35 + (i % 10), 1200, 34.05 + i * 0.0001, -118.25, atMs)
            sample.put("soc", 80.0 - i * 0.01)
            sample.put("powerKw", 4.0 + i * 0.02)
            store.recordTelemetry(id, sample)
        }

        val route =
            StorageSummaryJson
                .build(store.getStorageSummaryRecord())
                .getJSONArray("recentRoutes")
                .getJSONObject(0)
        val socTrack = route.getJSONArray("socTrack")
        val powerTrack = route.getJSONArray("powerTrack")

        assertTrue(socTrack.length() <= ObdStoreRouteProjection.MAX_TRACK_POINTS)
        assertTrue(powerTrack.length() <= ObdStoreRouteProjection.MAX_TRACK_POINTS)
        val expectedLastMs = 1000L + (ObdStoreRouteProjection.MAX_TRACK_POINTS + 39) * 1000L
        assertEquals(expectedLastMs, socTrack.getJSONObject(socTrack.length() - 1).optLong("atMs"))
        assertEquals(expectedLastMs, powerTrack.getJSONObject(powerTrack.length() - 1).optLong("atMs"))
    }

    @Test
    fun batchedDriveWindowsMatchPerSessionDetection() {
        val splitSession = store.startSession("obd", "00:11", "Adapter", 0L)
        store.recordTelemetry(splitSession, sample(35, 1200, 34.0500, -118.2500, 0L))
        store.recordTelemetry(splitSession, sample(38, 1300, 34.0600, -118.2500, 1_000L))
        store.recordTelemetry(splitSession, sample(0, 0, 34.0601, -118.2500, 60_000L))
        store.recordTelemetry(splitSession, sample(0, 0, 34.0602, -118.2500, 421_000L))
        store.recordTelemetry(splitSession, sample(40, 1400, 34.0700, -118.2500, 480_000L))
        store.recordTelemetry(splitSession, sample(42, 1450, 34.0800, -118.2500, 540_000L))
        store.finishSession(splitSession, ObdLocalStore.STATUS_COMPLETE, 540_000L, "")

        val straightSession = store.startSession("obd", "00:22", "Adapter", 1_000_000L)
        store.recordTelemetry(straightSession, sample(30, 1100, 34.1600, -118.3200, 1_000_000L))
        store.recordTelemetry(straightSession, sample(45, 1400, 34.1700, -118.3200, 1_020_000L))
        store.finishSession(straightSession, ObdLocalStore.STATUS_COMPLETE, 1_020_000L, "")

        val sessions = store.getRecentSessions(10).filter { it.mode == ObdLocalStore.MODE_OBD }
        store.checkpoint()
        SQLiteDatabase.openDatabase(store.getDatabaseFile().path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val batched = DriveWindowDetector.windowsForSessions(db, sessions)
            for (session in sessions) {
                val oneByOne = DriveWindowDetector.windowsForSession(db, session)
                val grouped = batched[session.id]
                assertNotNull(grouped)
                assertEquals(routeKeys(oneByOne), routeKeys(grouped!!))
            }
        }
    }

    // ---- finalizeSession: session-end + adapter-summary atomicity ------------------

    @Test
    fun finalizeSessionUpdatesSessionAndAdapterSummaryInOneCall() {
        val id = store.startSession("obd", "AA:BB:CC", "Adapter X", 10_000L)

        store.finalizeSession(
            id,
            ObdLocalStore.STATUS_COMPLETE,
            20_000L,
            "0100,0120",
            "AA:BB:CC",
            "Adapter X",
            "obd",
            7,
            "polled HV pack",
        )

        // Session row was ended.
        val session = store.getSession(id)
        assertNotNull(session)
        assertEquals(ObdLocalStore.STATUS_COMPLETE, session!!.status)
        assertEquals(20_000L, session.endedAtMs)
        assertEquals("0100,0120", session.supportedPids)

        // Adapter-history row was upserted.
        val history = store.getAdapterHistory(10)
        assertEquals(1, history.size)
        val adapter = history[0]
        assertEquals("AA:BB:CC", adapter.address)
        assertEquals("Adapter X", adapter.name)
        assertEquals("obd", adapter.lastMode)
        assertEquals(ObdLocalStore.STATUS_COMPLETE, adapter.lastStatus)
        assertEquals(id, adapter.lastSessionId)
        assertEquals(7, adapter.sampleCount)
        assertEquals("0100,0120", adapter.supportedPids)
        assertEquals("polled HV pack", adapter.lastEventDetail)
    }

    @Test
    fun finalizeSessionMatchesSeparateFinishAndAdapterSummary() {
        // Run the legacy two-call path against one adapter key, and finalizeSession against
        // an independent adapter key with identical inputs. Both must produce the same end
        // state — finalizeSession is meant to be a pure atomic wrapper, not a new behavior.
        val legacyId = store.startSession("obd", "11:11", "Legacy", 1_000L)
        store.finishSession(legacyId, ObdLocalStore.STATUS_COMPLETE, 2_000L, "0100")
        store.recordAdapterSummary(
            "11:11",
            "Legacy",
            "obd",
            legacyId,
            ObdLocalStore.STATUS_COMPLETE,
            3,
            "0100",
            "trip end",
        )

        val atomicId = store.startSession("obd", "22:22", "Atomic", 1_000L)
        store.finalizeSession(
            atomicId,
            ObdLocalStore.STATUS_COMPLETE,
            2_000L,
            "0100",
            "22:22",
            "Atomic",
            "obd",
            3,
            "trip end",
        )

        val legacySession = store.getSession(legacyId)!!
        val atomicSession = store.getSession(atomicId)!!
        assertEquals(legacySession.status, atomicSession.status)
        assertEquals(legacySession.endedAtMs, atomicSession.endedAtMs)
        assertEquals(legacySession.supportedPids, atomicSession.supportedPids)

        val history = store.getAdapterHistory(10)
        assertEquals(2, history.size)
        val legacy = adapterFor(history, "11:11")
        val atomic = adapterFor(history, "22:22")
        assertNotNull(legacy)
        assertNotNull(atomic)
        assertEquals(legacy!!.sampleCount, atomic!!.sampleCount)
        assertEquals(legacy.connectCount, atomic.connectCount)
        assertEquals(legacy.lastStatus, atomic.lastStatus)
        assertEquals(legacy.lastMode, atomic.lastMode)
        assertEquals(legacy.supportedPids, atomic.supportedPids)
        assertEquals(legacy.lastEventDetail, atomic.lastEventDetail)
    }

    @Test
    fun finalizeSessionRolledBackWhenAdapterWriteThrows() {
        // The adapter-summary INSERT runs inside the same transaction as the session
        // UPDATE. If we drop the adapter_history table mid-flight the INSERT throws and
        // the surrounding transaction must roll back — leaving the session row as it was
        // before finalizeSession ran (active, no ended_at_ms).
        val id = store.startSession("obd", "33:33", "Faulty", 1_000L)
        assertEquals(ObdLocalStore.STATUS_ACTIVE, store.getSession(id)!!.status)

        // Drop the table the second write targets so insertWithOnConflict throws.
        // We reach for the helper via reflection because the test only needs to prove the
        // transaction wrapper rolls back — production code never does this.
        try {
            val helperField = ObdLocalStore::class.java.getDeclaredField("helper")
            helperField.isAccessible = true
            val helper = helperField.get(store) as VoltTrackerDb
            helper.writableDatabase
                .execSQL("DROP TABLE " + VoltTrackerDb.TABLE_ADAPTER_HISTORY)
        } catch (e: Exception) {
            throw AssertionError("setup: could not drop adapter_history", e)
        }

        var threw = false
        try {
            store.finalizeSession(
                id,
                ObdLocalStore.STATUS_COMPLETE,
                2_000L,
                "0100",
                "33:33",
                "Faulty",
                "obd",
                5,
                "should rollback",
            )
        } catch (expected: RuntimeException) {
            threw = true
        }
        assertTrue("finalizeSession should propagate the failure", threw)

        // Recreate the dropped table so getSession's helper queries don't blow up on the
        // adapter side and so tearDown's clearAllData stays well-formed.
        try {
            val helperField = ObdLocalStore::class.java.getDeclaredField("helper")
            helperField.isAccessible = true
            val helper = helperField.get(store) as VoltTrackerDb
            helper.writableDatabase
                .execSQL(
                    "CREATE TABLE " +
                        VoltTrackerDb.TABLE_ADAPTER_HISTORY +
                        " (" +
                        "adapter_key TEXT PRIMARY KEY," +
                        "address TEXT," +
                        "name TEXT," +
                        "first_seen_ms INTEGER NOT NULL," +
                        "last_seen_ms INTEGER NOT NULL," +
                        "connect_count INTEGER NOT NULL DEFAULT 0," +
                        "scan_count INTEGER NOT NULL DEFAULT 0," +
                        "demo_count INTEGER NOT NULL DEFAULT 0," +
                        "sample_count INTEGER NOT NULL DEFAULT 0," +
                        "last_session_id INTEGER," +
                        "last_mode TEXT," +
                        "last_status TEXT," +
                        "supported_pids TEXT," +
                        "last_event_detail TEXT" +
                        ")",
                )
        } catch (e: Exception) {
            throw AssertionError("teardown: could not recreate adapter_history", e)
        }

        // The transaction rolled back: the session UPDATE was undone, so status is still
        // active and ended_at_ms is still zero. If finishSession had been called outside a
        // transaction the session would now read STATUS_COMPLETE here — that's the exact
        // bug B3 is fixing.
        val after = store.getSession(id)
        assertNotNull(after)
        assertEquals(ObdLocalStore.STATUS_ACTIVE, after!!.status)
        assertEquals(0L, after.endedAtMs)
    }

    // ---- VIN → vehicles upsert (covers upsertVehicleFromVin + storageSummary.latestVehicle) ---

    @Test
    fun upsertVehicleFromVinWritesRedactedRowAndShowsInStorageSummary() {
        // Synthetic Volt-ish VIN. WMI "1G1" maps to Chevrolet; position-10 char 'J'
        // decodes to year 2018 in the current 30-year window.
        val id = store.upsertVehicleFromVin("1G1ZD5ST8JF" + "202020")

        assertTrue("upsert should return a positive row id", id > 0)
        // vehicleCount on the storage summary picks up the new row.
        assertEquals(
            1L,
            StorageSummaryJson.build(store.getStorageSummaryRecord()).optLong("vehicleCount"),
        )

        // latestVehicleJson surfaces the redacted form (last 4 chars only) + the
        // WMI-derived make / position-10 year — never the full VIN.
        val latest =
            StorageSummaryJson
                .build(store.getStorageSummaryRecord())
                .optJSONObject("latestVehicle")
        assertNotNull(latest)
        assertEquals("Chevrolet", latest!!.optString("make"))
        assertEquals("Chevrolet", latest.optString("name"))
        assertEquals(2018, latest.optInt("year"))
        assertEquals(2018, latest.optInt("modelYear"))
        assertEquals("…2020", latest.optString("vin"))
        assertTrue(latest.optLong("vehicleId") > 0)
    }

    @Test
    fun upsertVehicleFromVinIsIdempotentOnTheVinHash() {
        val firstId = store.upsertVehicleFromVin("1G1ZD5ST8JF" + "202020")
        val secondId = store.upsertVehicleFromVin("1G1ZD5ST8JF" + "202020")

        // Same VIN → same hash → updates the existing row in place rather than inserting.
        assertEquals(firstId, secondId)
        assertEquals(
            1L,
            StorageSummaryJson.build(store.getStorageSummaryRecord()).optLong("vehicleCount"),
        )
    }

    @Test
    fun upsertVehicleFromVinRejectsWrongLength() {
        assertEquals(0L, store.upsertVehicleFromVin(null))
        assertEquals(0L, store.upsertVehicleFromVin("TOOSHORT"))
        assertEquals(0L, store.upsertVehicleFromVin("ALSO_WAY_TOO_LONG_FOR_A_VIN"))
        assertEquals(
            0L,
            StorageSummaryJson.build(store.getStorageSummaryRecord()).optLong("vehicleCount"),
        )
    }

    @Test
    fun upsertVehicleFromVinWithUnknownWmiOmitsTheMakeField() {
        // "ZZZ" is not in the WMI lookup; make/display_name stay NULL.
        val id = store.upsertVehicleFromVin("ZZZ12345678901" + "234")
        assertTrue(id > 0)
        val latest =
            StorageSummaryJson
                .build(store.getStorageSummaryRecord())
                .optJSONObject("latestVehicle")
        assertNotNull(latest)
        assertEquals("", latest!!.optString("make"))
        // VIN suffix still surfaces even without manufacturer mapping.
        assertEquals("…1234", latest.optString("vin"))
    }

    @Test
    fun storageSummaryLatestVehicleIsEmptyWhenNoVehiclesRecorded() {
        // No upsert called → vehicles table is empty → latestVehicleJson returns {}.
        val latest =
            StorageSummaryJson
                .build(store.getStorageSummaryRecord())
                .optJSONObject("latestVehicle")
        assertNotNull(latest)
        assertEquals(0, latest!!.length())
    }

    // -- G1 retention -------------------------------------------------------

    @Test
    fun pruneRawDataDeletesOldTelemetryAndKeepsRecent() {
        val sessionId = store.startSession("obd", "AA:BB:CC", "Adapter")
        val now = System.currentTimeMillis()
        // Old (>90 days)
        val old = now - 91L * 86_400_000L
        store.recordTelemetry(sessionId, sample(40, 1500, 10.0, 20.0, old), old)
        // Recent (<1 day)
        val recent = now - 60_000L
        store.recordTelemetry(sessionId, sample(50, 1700, 10.1, 20.1, recent), recent)
        assertEquals(
            2,
            StorageSummaryJson
                .build(store.getStorageSummaryRecord())
                .optInt("rawTelemetryCount"),
        )

        val deleted = store.pruneRawDataOlderThan(60)

        assertTrue("expected to delete the old row", deleted >= 1)
        assertEquals(
            1,
            StorageSummaryJson
                .build(store.getStorageSummaryRecord())
                .optInt("rawTelemetryCount"),
        )
    }

    @Test
    fun pruneRawDataIsNoopForNonPositiveDays() {
        val sessionId = store.startSession("obd", "AA:BB:CC", "Adapter")
        val old = System.currentTimeMillis() - 91L * 86_400_000L
        store.recordTelemetry(sessionId, sample(40, 1500, 10.0, 20.0, old), old)

        assertEquals(0, store.pruneRawDataOlderThan(0))
        assertEquals(0, store.pruneRawDataOlderThan(-7))
        assertEquals(
            1,
            StorageSummaryJson
                .build(store.getStorageSummaryRecord())
                .optInt("rawTelemetryCount"),
        )
    }

    @Test
    fun pruneRawDataKeepsSessionRowEvenWhenAllTelemetryIsPruned() {
        val sessionId = store.startSession("obd", "AA:BB:CC", "Adapter")
        val old = System.currentTimeMillis() - 365L * 86_400_000L
        store.recordTelemetry(sessionId, sample(40, 1500, 10.0, 20.0, old), old)

        store.pruneRawDataOlderThan(30)

        // session row is summary data — must NOT be pruned by raw-data retention
        assertEquals(
            1,
            StorageSummaryJson.build(store.getStorageSummaryRecord()).optInt("sessionCount"),
        )
        assertEquals(
            0,
            StorageSummaryJson
                .build(store.getStorageSummaryRecord())
                .optInt("rawTelemetryCount"),
        )
    }

    @Test
    fun mergeFromImportsAValidDonorBackup() {
        val context = RuntimeEnvironment.getApplication()
        val donorHelper = VoltTrackerDb(context, "merge-from-donor.db")
        try {
            val donor = donorHelper.writableDatabase
            val session = ContentValues()
            session.put("mode", "obd")
            session.put("started_at_ms", 123_456L)
            session.put("status", "complete")
            session.put("sample_count", 0)
            session.put("created_at_ms", 123_456L)
            donor.insertOrThrow(VoltTrackerDb.TABLE_SESSIONS, null, session)
        } finally {
            donorHelper.close()
        }
        val donorFile = context.getDatabasePath("merge-from-donor.db")

        val result = store.mergeFrom(donorFile)

        assertTrue(result.ok)
        assertEquals(1, result.sessionsAdded)
        assertEquals(
            1,
            StorageSummaryJson.build(store.getStorageSummaryRecord()).optInt("sessionCount"),
        )
        context.deleteDatabase("merge-from-donor.db")
    }

    @Test
    fun mergeFromRejectsAMissingFile() {
        val context = RuntimeEnvironment.getApplication()
        val missing = File(context.cacheDir, "does-not-exist.db")

        val result = store.mergeFrom(missing)

        assertFalse(result.ok)
    }

    @Test
    fun mergeFromRejectsADifferentSchemaVersion() {
        val context = RuntimeEnvironment.getApplication()
        val donorFile = File(context.cacheDir, "wrong-version.db")
        val donor = SQLiteDatabase.openOrCreateDatabase(donorFile.path, null)
        try {
            donor.version = VoltTrackerDb.DATABASE_VERSION - 1
        } finally {
            donor.close()
        }

        val result = store.mergeFrom(donorFile)

        assertFalse(result.ok)
        assertTrue(result.summary().contains("different app version"))
    }

    @Test
    fun batterySohHistoryReturnsSnapshotsOldestFirst() {
        val id = store.startSession("obd", "00:11", "Adapter")
        // Capacity present + fresh → recordBatterySnapshotIfPresent writes a row.
        store.recordTelemetry(id, batterySample(1000, soh = 95.0, capacityAh = 44.0))
        store.recordTelemetry(id, batterySample(2000, soh = 94.5, capacityAh = 43.7))
        // No capacity → not a battery snapshot, must not appear in the history.
        store.recordTelemetry(id, sample(40, 1500, 34.05, -118.25, 2500))

        val history = store.getBatterySohHistoryJson()

        assertEquals(2, history.length())
        // Oldest-first for charting.
        assertEquals(1000L, history.getJSONObject(0).getLong("capturedAtMs"))
        assertEquals(95.0, history.getJSONObject(0).getDouble("sohPct"), 0.001)
        assertEquals(2000L, history.getJSONObject(1).getLong("capturedAtMs"))
        assertEquals(43.7, history.getJSONObject(1).getDouble("capacityAh"), 0.001)
    }

    @Test
    fun batterySohHistoryIsEmptyWithoutCapacityReadings() {
        val id = store.startSession("obd", "00:11", "Adapter")
        store.recordTelemetry(id, sample(40, 1500, 34.05, -118.25, 1000))

        assertEquals(0, store.getBatterySohHistoryJson().length())
    }

    @Test
    fun cellSnapshotRoundTripsThroughTheStore() {
        // 96 cells, cell 41 (index 40) unanswered.
        val voltages: MutableList<Double?> = MutableList(96) { i -> 3.9 + (i % 8) * 0.01 }
        voltages[40] = null

        val snapshotId = store.recordCellSnapshot(voltages)
        assertTrue(snapshotId > 0)

        val snapshot = store.projections().batterySummary().getJSONObject("latestCellSnapshot")
        assertEquals(95, snapshot.getInt("cellCount"))
        assertTrue(snapshot.getLong("capturedAtMs") > 0)
        val cells = snapshot.getJSONArray("cells")
        assertEquals(95, cells.length())
        // 1-based cell indexes, ascending; the missing cell 41 is simply absent.
        assertEquals(1, cells.getJSONObject(0).getInt("index"))
        assertEquals(3.9, cells.getJSONObject(0).getDouble("voltage"), 0.001)
        assertEquals(40, cells.getJSONObject(39).getInt("index"))
        assertEquals(42, cells.getJSONObject(40).getInt("index"))
    }

    @Test
    fun latestCellSnapshotWinsOverOlderOnes() {
        store.recordCellSnapshot(MutableList(96) { 3.8 })
        // battery_snapshots.captured_at_ms uses wall time; make the second probe strictly newer.
        Thread.sleep(2)
        store.recordCellSnapshot(MutableList(96) { 4.1 })

        val snapshot = store.projections().batterySummary().getJSONObject("latestCellSnapshot")
        assertEquals(96, snapshot.getInt("cellCount"))
        assertEquals(4.1, snapshot.getJSONArray("cells").getJSONObject(0).getDouble("voltage"), 0.001)
    }

    @Test
    fun cellSnapshotIsEmptyBeforeAnyProbe() {
        assertEquals(
            0,
            store
                .projections()
                .batterySummary()
                .getJSONObject("latestCellSnapshot")
                .length(),
        )
    }

    @Test
    fun emptyCellProbeDoesNotCreateSnapshot() {
        assertEquals(-1L, store.recordCellSnapshot(List(96) { null }))

        assertEquals(
            0,
            store
                .projections()
                .batterySummary()
                .getJSONObject("latestCellSnapshot")
                .length(),
        )
    }

    @Test
    fun cellProbeSnapshotsStayOutOfTheSohHistory() {
        // A cell probe writes a parent battery_snapshots row without soh/capacity; the SOH
        // trend must not pick it up as a data point.
        store.recordCellSnapshot(MutableList(96) { 3.9 })

        assertEquals(0, store.getBatterySohHistoryJson().length())
    }

    companion object {
        private fun sample(
            speedKph: Int,
            rpm: Int,
            lat: Double,
            lng: Double,
            atMs: Long,
        ): JSONObject {
            val sample = JSONObject()
            sample.put("source", "obd")
            sample.put("speedKph", speedKph)
            sample.put("rpm", rpm)
            sample.put("latitude", lat)
            sample.put("longitude", lng)
            sample.put("updatedAt", atMs)
            return sample
        }

        private fun batterySample(
            atMs: Long,
            soh: Double,
            capacityAh: Double,
        ): JSONObject {
            val sample = JSONObject()
            sample.put("source", "obd")
            sample.put("updatedAt", atMs)
            sample.put("soc", 60)
            sample.put("capacityAh", capacityAh)
            // recordBatterySnapshotIfPresent only writes when the capacity read is fresh.
            sample.put("capacityAhStaleMs", 0)
            sample.put("sohPct", soh)
            sample.put("packVoltage", 360.0)
            return sample
        }

        private fun diagnosticCode(
            dtc: String,
            status: String,
            seenAtMs: Long,
        ): JSONObject {
            val code = JSONObject()
            code.put("dtc", dtc)
            code.put("status", status)
            code.put("statusLabel", "Stored/current")
            code.put("moduleKey", "generic-obd")
            code.put("moduleName", "ECM / powertrain (generic OBD-II)")
            code.put("seenAtMs", seenAtMs)
            code.put("rawResponse", "43 25 A2 00 00")
            return code
        }

        private fun pidObservation(
            observedAtMs: Long,
            command: String,
            header: String,
            pid: String,
            name: String,
            valueText: String,
            valueNumeric: Double?,
            unit: String,
            rawResponse: String,
        ): JSONObject {
            val observation = JSONObject()
            observation.put("observedAtMs", observedAtMs)
            observation.put("command", command)
            observation.put("header", header)
            observation.put("pid", pid)
            observation.put("name", name)
            observation.put("valueText", valueText)
            if (valueNumeric != null) {
                observation.put("valueNumeric", valueNumeric)
            }
            observation.put("unit", unit)
            observation.put("rawRequest", command)
            observation.put("rawResponse", rawResponse)
            return observation
        }

        private fun routeKeys(windows: List<DriveWindowDetector.DriveWindow>): List<String> =
            windows.map { it.routeKey() }

        private fun adapterFor(
            records: List<AdapterHistoryRecord>,
            address: String,
        ): AdapterHistoryRecord? {
            for (record in records) {
                if (address == record.address) {
                    return record
                }
            }
            return null
        }
    }
}
