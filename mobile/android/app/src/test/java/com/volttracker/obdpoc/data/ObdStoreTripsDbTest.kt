package com.volttracker.obdpoc.data

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
 * Robolectric coverage for the trip-aggregation projection in [ObdStoreTrips], exercised
 * through [ObdLocalStore.getTripsJson]. Sessions are seeded with synthetic GPS
 * telemetry; distance is verified against the haversine sum.
 *
 * Reading `ObdStoreTrips.tripsJson`: a session only appears in the trips JSON when at
 * least one useful telemetry row exists for it. The route geometry that drives
 * `distanceMeters` is sourced from the `location_samples` table when it has rows for that
 * session, and falls back to telemetry rows that carry lat/lng — which is what these tests rely on
 * so they can drive both readings in one call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObdStoreTripsDbTest {
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

    // ---- empty store ---------------------------------------------------------------

    @Test
    fun emptyStoreReturnsEmptyTripsArray() {
        val trips = store.getTripsJson(40)
        assertNotNull(trips)
        assertEquals(0, trips.length())
    }

    // ---- session shape -------------------------------------------------------------

    @Test
    fun sessionWithNoUsefulTelemetryIsNotReportedAsATrip() {
        // A session that never logged any telemetry was a failed connection, not a trip;
        // the production code (ObdStoreTrips#tripJson) returns null in that case.
        val id = store.startSession("obd", "00:11", "Adapter")
        store.finishSession(id, ObdLocalStore.STATUS_ERROR, 2000L, "")

        val trips = store.getTripsJson(40)
        assertEquals(0, trips.length())
    }

    @Test
    fun sourceOnlyTelemetryIsNotReportedAsATrip() {
        val id = store.startSession("obd", "00:11", "Adapter")
        val emptyPoll = JSONObject()
        emptyPoll.put("source", "obd")
        emptyPoll.put("raw", "NO DATA")
        emptyPoll.put("updatedAt", 1000L)
        store.recordTelemetry(id, emptyPoll)
        store.finishSession(id, ObdLocalStore.STATUS_ERROR, 2000L, "")

        assertEquals(0, store.getTripsJson(40).length())
        assertEquals(
            0L,
            StorageSummaryJson.build(store.getStorageSummaryRecord()).optLong("sampleCount"),
        )
    }

    @Test
    fun multiSampleSessionReportsHaversineDistance() {
        val id = store.startSession("obd", "00:11", "Adapter")
        // Three points along the same longitude — ~1.1 km between each consecutive lat step.
        val route =
            arrayOf(
                doubleArrayOf(32.7000, -117.1000),
                doubleArrayOf(32.7100, -117.1000),
                doubleArrayOf(32.7200, -117.1000),
            )
        store.recordTelemetry(id, gpsSample(40, route[0][0], route[0][1], 1000L))
        store.recordTelemetry(id, gpsSample(55, route[1][0], route[1][1], 2000L))
        store.recordTelemetry(id, gpsSample(60, route[2][0], route[2][1], 3000L))
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 4000L, "")

        val expected =
            haversineMeters(route[0][0], route[0][1], route[1][0], route[1][1]) +
                haversineMeters(route[1][0], route[1][1], route[2][0], route[2][1])

        val trips = store.getTripsJson(40)
        assertEquals(1, trips.length())
        val trip = trips.getJSONObject(0)
        // distanceMeters should match the haversine sum to within a meter (no smoothing
        // or projection differences should creep in for a 2 km synthetic route).
        assertEquals(expected, trip.optDouble("distanceMeters"), 1.0)
        assertEquals(60, trip.optInt("maxSpeedKph"))
        assertEquals(3, trip.optInt("sampleCount"))
        assertTrue("3 GPS points should render as a route", trip.optBoolean("hasRoute"))
    }

    @Test
    fun zeroMovementSessionIsNotReportedAsATrip() {
        val id = store.startSession("obd", "00:11", "Adapter")
        // All samples at the exact same coordinate — distance must be 0.
        store.recordTelemetry(id, gpsSample(0, 32.70, -117.10, 1000L))
        store.recordTelemetry(id, gpsSample(0, 32.70, -117.10, 2000L))
        store.recordTelemetry(id, gpsSample(0, 32.70, -117.10, 3000L))
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 4000L, "")

        assertEquals(0, store.getTripsJson(40).length())
        assertEquals(0, StorageSummaryJson.build(store.getStorageSummaryRecord()).getJSONArray("recentRoutes").length())
        assertEquals(0, store.getInsightsJson().optInt("tripCount"))
    }

    @Test
    fun tinyTwoPointStationaryRouteIsNotReportedAsATrip() {
        val id = store.startSession("obd", "00:11", "Adapter")
        store.recordTelemetry(id, gpsSample(0, 32.70000, -117.10000, 1000L))
        store.recordTelemetry(id, gpsSample(0, 32.70001, -117.10000, 2000L))
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 3000L, "")

        assertEquals(0, store.getTripsJson(40).length())
        assertEquals(0, StorageSummaryJson.build(store.getStorageSummaryRecord()).getJSONArray("recentRoutes").length())
        assertEquals(0, store.getInsightsJson().optInt("tripCount"))
    }

    @Test
    fun markedTripIsHiddenFromTripAndMapProjections() {
        val id = store.startSession("obd", "00:11", "Adapter")
        store.recordTelemetry(id, gpsSample(35, 32.70000, -117.10000, 1000L))
        store.recordTelemetry(id, gpsSample(42, 32.71000, -117.10000, 2000L))
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 3000L, "")

        val routeKey =
            StorageSummaryJson
                .build(store.getStorageSummaryRecord())
                .getJSONArray("recentRoutes")
                .getJSONObject(0)
                .getJSONObject("session")
                .getString("id")

        assertTrue(store.markTripNotTrip(routeKey))
        assertEquals(0, store.getTripsJson(40).length())
        assertEquals(0, StorageSummaryJson.build(store.getStorageSummaryRecord()).getJSONArray("recentRoutes").length())
    }

    // ---- M4 trip labels ------------------------------------------------------------

    @Test
    fun setTripLabelPersistsAndAppearsInTripJson() {
        val id = store.startSession("obd", "00:11", "Adapter")
        store.recordTelemetry(id, gpsSample(35, 32.70000, -117.10000, 1000L))
        store.recordTelemetry(id, gpsSample(42, 32.71000, -117.10000, 2000L))
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 3000L, "")

        val routeKey = store.getTripsJson(40).getJSONObject(0).getString("id")
        // Trips with no label carry an empty-string label, never a missing key.
        assertEquals("", store.getTripsJson(40).getJSONObject(0).optString("label", "MISSING"))

        assertTrue(store.setTripLabel(routeKey, "Commute home"))

        val trip = store.getTripsJson(40).getJSONObject(0)
        assertEquals(routeKey, trip.getString("id"))
        assertEquals("Commute home", trip.optString("label"))
    }

    @Test
    fun setTripLabelClearsWithEmptyLabel() {
        val id = store.startSession("obd", "00:11", "Adapter")
        store.recordTelemetry(id, gpsSample(35, 32.70000, -117.10000, 1000L))
        store.recordTelemetry(id, gpsSample(42, 32.71000, -117.10000, 2000L))
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 3000L, "")

        val routeKey = store.getTripsJson(40).getJSONObject(0).getString("id")
        assertTrue(store.setTripLabel(routeKey, "Road trip"))
        assertEquals("Road trip", store.getTripsJson(40).getJSONObject(0).optString("label"))

        // An empty label clears the entry — the later (clearing) event wins over the earlier one.
        assertTrue(store.setTripLabel(routeKey, ""))
        assertEquals("", store.getTripsJson(40).getJSONObject(0).optString("label", "MISSING"))

        // Re-labeling after a clear works (latest event wins).
        assertTrue(store.setTripLabel(routeKey, "Weekend"))
        assertEquals("Weekend", store.getTripsJson(40).getJSONObject(0).optString("label"))
    }

    @Test
    fun setTripLabelRejectsUnparseableRouteKey() {
        assertEquals(false, store.setTripLabel(null, "x"))
        assertEquals(false, store.setTripLabel("", "x"))
        assertEquals(false, store.setTripLabel("not-a-route-key", "x"))
    }

    // ---- M4 trip favorites ---------------------------------------------------------

    @Test
    fun setTripFavoritePersistsAndAppearsInTripJson() {
        val id = store.startSession("obd", "00:11", "Adapter")
        store.recordTelemetry(id, gpsSample(35, 32.70000, -117.10000, 1000L))
        store.recordTelemetry(id, gpsSample(42, 32.71000, -117.10000, 2000L))
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 3000L, "")

        val routeKey = store.getTripsJson(40).getJSONObject(0).getString("id")
        // Trips default to not-favorite, never a missing key.
        assertEquals(false, store.getTripsJson(40).getJSONObject(0).optBoolean("favorite", true))

        assertTrue(store.setTripFavorite(routeKey, true))

        val trip = store.getTripsJson(40).getJSONObject(0)
        assertEquals(routeKey, trip.getString("id"))
        assertTrue("favorite must be stamped onto the trip JSON", trip.optBoolean("favorite", false))
    }

    @Test
    fun setTripFavoriteClearsWithFalse() {
        val id = store.startSession("obd", "00:11", "Adapter")
        store.recordTelemetry(id, gpsSample(35, 32.70000, -117.10000, 1000L))
        store.recordTelemetry(id, gpsSample(42, 32.71000, -117.10000, 2000L))
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 3000L, "")

        val routeKey = store.getTripsJson(40).getJSONObject(0).getString("id")
        assertTrue(store.setTripFavorite(routeKey, true))
        assertTrue(store.getTripsJson(40).getJSONObject(0).optBoolean("favorite", false))

        // The later (un-favorite) event wins over the earlier favorite.
        assertTrue(store.setTripFavorite(routeKey, false))
        assertEquals(false, store.getTripsJson(40).getJSONObject(0).optBoolean("favorite", true))

        // Re-favoriting after a clear works (latest event wins).
        assertTrue(store.setTripFavorite(routeKey, true))
        assertTrue(store.getTripsJson(40).getJSONObject(0).optBoolean("favorite", false))
    }

    @Test
    fun setTripFavoriteRejectsUnparseableRouteKey() {
        assertEquals(false, store.setTripFavorite(null, true))
        assertEquals(false, store.setTripFavorite("", true))
        assertEquals(false, store.setTripFavorite("not-a-route-key", true))
    }

    // ---- M6 bulk all-trips export bundle --------------------------------------------

    @Test
    fun allTripsForExportBundlesEachTripWithIdLabelAndRoute() {
        val id = store.startSession("obd", "00:11", "Adapter")
        store.recordTelemetry(id, gpsSample(35, 32.70000, -117.10000, 1000L))
        store.recordTelemetry(id, gpsSample(42, 32.71000, -117.10000, 2000L))
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 3000L, "")

        val routeKey = store.getTripsJson(40).getJSONObject(0).getString("id")
        assertTrue(store.setTripLabel(routeKey, "Commute home"))

        val bundle = store.getAllTripsForExportJson(40, 500)
        assertEquals("one trip in the bundle", 1, bundle.length())
        val item = bundle.getJSONObject(0)
        assertEquals(routeKey, item.getString("tripId"))
        assertEquals("Commute home", item.getString("label"))
        // The route carries the GPS points the CSV serializer reads.
        assertTrue("bundled route has points", item.getJSONObject("route").getJSONArray("points").length() >= 2)
    }

    @Test
    fun allTripsForExportIsEmptyWhenNoTrips() {
        assertEquals(0, store.getAllTripsForExportJson(40, 500).length())
    }

    @Test
    fun hidingAPostSplitTripByItsListIdAlsoHidesItFromTheMapProjection() {
        // Post-split windows start at "last inactive sample + 1ms", where no telemetry row exists,
        // so the LIST id (point-clipped times) always differs from the detector's window key. The
        // map projection must honor the clipped id too, or a hidden trip keeps rendering as a route.
        val startMs = 2_000_000L
        val minuteMs = 60_000L
        val id = store.startSession("obd", "00:11", "Adapter", startMs)
        addMovingTelemetryRun(id, startMs, 0, 5, 32.7000)
        addStoppedTelemetryRun(id, startMs, 6, 12, 32.7100)
        addMovingTelemetryRun(id, startMs, 13, 18, 32.7200)
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, startMs + 19 * minuteMs, "")

        val trips = store.getTripsJson(40)
        assertEquals(2, trips.length())
        val newest = trips.getJSONObject(0)
        // Sanity: the list id is point-clipped (13m), not the window-key start (12m + 1ms).
        assertEquals(startMs + 13 * minuteMs, newest.optLong("startedAtMs"))
        assertEquals(2, store.getRecentRoutesJson(8, 500).length())

        assertTrue(store.markTripNotTrip(newest.getString("id")))

        assertEquals(1, store.getTripsJson(40).length())
        val routesAfter = store.getRecentRoutesJson(8, 500)
        assertEquals(
            "a trip hidden by its list id must disappear from the map projection too",
            1,
            routesAfter.length(),
        )
        // The surviving route is the FIRST window, not the hidden one.
        val remainingPoints = routesAfter.getJSONObject(0).getJSONArray("points")
        assertEquals(startMs, remainingPoints.getJSONObject(0).optLong("atMs"))
    }

    @Test
    fun activeTripCachePruneKeepsTheJustInsertedEntryUnderPressure() {
        // Active (unfinished) session with one meaningful drive window.
        val id = store.startSession("obd", "00:11", "Adapter", 1000L)
        store.recordTelemetry(id, gpsSample(35, 32.7000, -117.1000, 1000L))
        store.recordTelemetry(id, gpsSample(42, 32.7100, -117.1000, 2000L))

        // Push the active-trip cache over capacity with same-session keys so the size-pressure
        // prune fires when tripsJson inserts the freshly computed trip.
        val cache = activeTripCacheOf(store)
        val cachedTripCtor =
            Class
                .forName("com.volttracker.obdpoc.data.ObdStoreTrips\$CachedTrip")
                .getDeclaredConstructor(String::class.java, Long::class.javaPrimitiveType)
        cachedTripCtor.isAccessible = true
        // Future-dated createdAtMs so the TTL sweep cannot evict the dummies before the
        // size-pressure prune runs, even on a slow test machine.
        val createdAtMs = System.currentTimeMillis() + 60_000L
        synchronized(cache) {
            for (i in 0 until 64) {
                cache["$id:dummy:$i"] = cachedTripCtor.newInstance("{}", createdAtMs)
            }
        }

        assertEquals(1, store.getTripsJson(40).length())

        synchronized(cache) {
            assertEquals(
                "the prune must evict the dummies but keep the entry tripJson just inserted",
                1,
                cache.size,
            )
            val survivor = cache.keys.iterator().next()
            assertTrue("survivor must be the real cache key, got $survivor", survivor.startsWith("$id:"))
            assertTrue("survivor must not be a dummy entry, got $survivor", !survivor.contains("dummy"))
        }
    }

    @Test
    fun concurrentProjectionReadsOnAnActiveSessionShareTheCacheSafely() {
        // Mirrors production: the WebView JS-bridge thread (getTrips/getInsights) and the OBD
        // polling thread (storage summary -> totalDistanceMeters) hit the active-trip cache
        // concurrently. The cache must be safe to read/write/iterate from multiple threads.
        val id = store.startSession("obd", "00:11", "Adapter", 1000L)
        store.recordTelemetry(id, gpsSample(35, 32.7000, -117.1000, 1000L))
        store.recordTelemetry(id, gpsSample(42, 32.7100, -117.1000, 2000L))
        // Left unfinished on purpose — only active sessions exercise the cache.

        val threads = 4
        val iterations = 25
        val firstError =
            java.util.concurrent.atomic
                .AtomicReference<Throwable>()
        val gate = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(threads)
        repeat(threads) {
            Thread {
                try {
                    gate.await()
                    for (i in 0 until iterations) {
                        store.getTripsJson(40)
                        store.getInsightsJson()
                    }
                } catch (ex: Throwable) {
                    firstError.compareAndSet(null, ex)
                } finally {
                    done.countDown()
                }
            }.start()
        }
        gate.countDown()
        assertTrue(
            "concurrent readers should finish in time",
            done.await(30, java.util.concurrent.TimeUnit.SECONDS),
        )
        val error = firstError.get()
        if (error != null) {
            throw AssertionError("concurrent projection reads must not fail", error)
        }
    }

    @Test
    fun tripDurationExcludesSustainedInactiveTail() {
        val startMs = 1_000_000L
        val minuteMs = 60_000L
        val id = store.startSession("obd", "00:11", "Adapter", startMs)
        val startLat = 32.7000
        for (i in 0..5) {
            store.recordTelemetry(
                id,
                gpsSample(35, startLat + i * 0.002, -117.1000, startMs + i * minuteMs),
            )
        }
        for (i in 6..16) {
            store.recordTelemetry(
                id,
                inactiveGpsSample(startLat + 5 * 0.002, -117.1000, startMs + i * minuteMs),
            )
        }
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, startMs + 17 * minuteMs, "")

        val trips = store.getTripsJson(40)

        assertEquals(1, trips.length())
        val trip = trips.getJSONObject(0)
        assertEquals(startMs, trip.optLong("startedAtMs"))
        assertEquals(startMs + 5 * minuteMs, trip.optLong("endedAtMs"))
        assertEquals(5 * minuteMs, trip.optLong("durationMs"))
    }

    @Test
    fun getTripsSplitsOneSessionIntoMultipleDriveWindows() {
        val startMs = 2_000_000L
        val minuteMs = 60_000L
        val id = store.startSession("obd", "00:11", "Adapter", startMs)
        addMovingTelemetryRun(id, startMs, 0, 5, 32.7000)
        addStoppedTelemetryRun(id, startMs, 6, 12, 32.7100)
        addMovingTelemetryRun(id, startMs, 13, 18, 32.7200)
        addStoppedTelemetryRun(id, startMs, 19, 23, 32.7300)
        addMovingTelemetryRun(id, startMs, 24, 29, 32.7400)
        addStoppedTelemetryRun(id, startMs, 30, 40, 32.7500)
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, startMs + 41 * minuteMs, "")

        val trips = store.getTripsJson(40)

        assertEquals(3, trips.length())
        val newest = trips.getJSONObject(0)
        val middle = trips.getJSONObject(1)
        val oldest = trips.getJSONObject(2)
        assertEquals(startMs + 24 * minuteMs, newest.optLong("startedAtMs"))
        assertEquals(startMs + 29 * minuteMs, newest.optLong("endedAtMs"))
        assertEquals(startMs + 13 * minuteMs, middle.optLong("startedAtMs"))
        assertEquals(startMs + 18 * minuteMs, middle.optLong("endedAtMs"))
        assertEquals(startMs, oldest.optLong("startedAtMs"))
        assertEquals(startMs + 5 * minuteMs, oldest.optLong("endedAtMs"))
        assertEquals(id, newest.optLong("sessionId"))

        val route = store.getTripRouteJson(newest.optString("id"))
        val points = route.optJSONArray("points")
        assertNotNull(points)
        assertTrue(points!!.length() >= 2)
        assertEquals(startMs + 24 * minuteMs, points.getJSONObject(0).optLong("atMs"))
        assertEquals(
            startMs + 29 * minuteMs,
            points.getJSONObject(points.length() - 1).optLong("atMs"),
        )
    }

    @Test
    fun telemetryOnlyTripDurationUsesSampleBounds() {
        val id = store.startSession("obd", "00:11", "Adapter", 1000L)
        store.recordTelemetry(id, obdSampleNoGps(30, 2000L))
        store.recordTelemetry(id, obdSampleNoGps(35, 3000L))
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 1_000_000L, "")

        val trips = store.getTripsJson(40)

        assertEquals(1, trips.length())
        val trip = trips.getJSONObject(0)
        assertEquals(2000L, trip.optLong("startedAtMs"))
        assertEquals(3000L, trip.optLong("endedAtMs"))
        assertEquals(1000L, trip.optLong("durationMs"))
        assertEquals(0, trip.optInt("pointCount"))
    }

    @Test
    fun getTripsSortsSplitWindowsGloballyBeforeApplyingLimit() {
        val baseMs = 3_000_000L
        val minuteMs = 60_000L
        val sessionA = store.startSession("obd", "00:11", "Adapter A", baseMs)
        store.recordTelemetry(sessionA, gpsSample(35, 32.7000, -117.1000, baseMs))
        store.recordTelemetry(sessionA, gpsSample(35, 32.7020, -117.1000, baseMs + minuteMs))
        store.recordTelemetry(
            sessionA,
            inactiveGpsSample(32.7020, -117.1000, baseMs + 2 * minuteMs),
        )
        store.recordTelemetry(
            sessionA,
            inactiveGpsSample(32.7020, -117.1000, baseMs + 7 * minuteMs),
        )
        store.recordTelemetry(sessionA, gpsSample(35, 32.7200, -117.1000, baseMs + 10 * minuteMs))
        store.recordTelemetry(sessionA, gpsSample(35, 32.7220, -117.1000, baseMs + 11 * minuteMs))
        store.finishSession(sessionA, ObdLocalStore.STATUS_COMPLETE, baseMs + 12 * minuteMs, "")

        val sessionB = store.startSession("obd", "00:22", "Adapter B", baseMs - 1000L)
        store.recordTelemetry(sessionB, gpsSample(35, 33.0000, -118.0000, baseMs + 9 * minuteMs))
        store.recordTelemetry(
            sessionB,
            gpsSample(35, 33.0020, -118.0000, baseMs + 9 * minuteMs + 30_000L),
        )
        store.finishSession(sessionB, ObdLocalStore.STATUS_COMPLETE, baseMs + 10 * minuteMs, "")

        val trips = store.getTripsJson(2)

        assertEquals(2, trips.length())
        assertEquals(sessionA, trips.getJSONObject(0).optLong("sessionId"))
        assertEquals(baseMs + 11 * minuteMs, trips.getJSONObject(0).optLong("endedAtMs"))
        assertEquals(sessionB, trips.getJSONObject(1).optLong("sessionId"))
        assertEquals(baseMs + 9 * minuteMs + 30_000L, trips.getJSONObject(1).optLong("endedAtMs"))
    }

    @Test
    fun twoSessionsBothAppearInTripsJson() {
        val first = store.startSession("obd", "00:11", "Adapter A")
        store.recordTelemetry(first, gpsSample(40, 32.70, -117.10, 1000L))
        store.recordTelemetry(first, gpsSample(50, 32.71, -117.10, 2000L))
        store.finishSession(first, ObdLocalStore.STATUS_COMPLETE, 3000L, "")

        val second = store.startSession("obd", "00:22", "Adapter B")
        store.recordTelemetry(second, gpsSample(60, 33.00, -118.00, 10000L))
        store.recordTelemetry(second, gpsSample(70, 33.01, -118.00, 11000L))
        store.finishSession(second, ObdLocalStore.STATUS_COMPLETE, 12000L, "")

        val trips = store.getTripsJson(40)
        assertEquals(2, trips.length())

        // Recent sessions come back DESC by started_at; surface that both trip ids land here.
        var sawFirst = false
        var sawSecond = false
        for (i in 0 until trips.length()) {
            val tripId = trips.getJSONObject(i).optLong("sessionId")
            if (tripId == first) sawFirst = true
            if (tripId == second) sawSecond = true
        }
        assertTrue("first session must appear in trips JSON", sawFirst)
        assertTrue("second session must appear in trips JSON", sawSecond)
    }

    @Test
    fun getTripsJsonRespectsTheLimitArgument() {
        // Three real driving sessions; ask for only one.
        for (i in 0 until 3) {
            val id = store.startSession("obd", "00:0$i", "Adapter $i")
            store.recordTelemetry(id, gpsSample(30, 32.70 + (i * 0.01), -117.10, 1000L + i))
            store.recordTelemetry(id, gpsSample(40, 32.71 + (i * 0.01), -117.10, 2000L + i))
            store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 3000L + i, "")
        }
        val trips = store.getTripsJson(1)
        assertEquals(1, trips.length())
    }

    @Test
    fun longSessionRouteIsDownsampledAcrossFullTimespan() {
        // Regression: before fix, a session with > 500 GPS samples only surfaced its last 500
        // points on the dashboard map, hiding the beginning of every long drive.
        val id = store.startSession("obd", "00:11", "Adapter")
        val totalSamples = 2000
        val firstAtMs = 1000L
        val lastAtMs = firstAtMs + (totalSamples - 1L)
        for (i in 0 until totalSamples) {
            val lat = 32.70 + (i * 0.00001)
            // Alternating ~47 m lateral offset keeps every sample a genuine corner, so the
            // shape-preserving simplifier (which collapses collinear runs) keeps the polyline
            // dense — this test pins timespan coverage, not geometry simplification.
            val lng = -117.10 + (i * 0.00001) + (i % 2) * 0.0005
            store.recordTelemetry(id, gpsSample(40, lat, lng, firstAtMs + i))
        }
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, lastAtMs + 1L, "")

        val summary = StorageSummaryJson.build(store.getStorageSummaryRecord())
        val routes = summary.optJSONArray("recentRoutes")
        assertNotNull(routes)
        assertEquals(1, routes!!.length())

        val points = routes.getJSONObject(0).optJSONArray("points")
        assertNotNull(points)
        // Bounded — never more than the cap.
        assertTrue(
            "downsampled points must not exceed 500 (was " + points!!.length() + ")",
            points.length() <= 500,
        )
        // Dense enough to render a meaningful polyline.
        assertTrue(
            "downsampled points must keep at least 100 samples (was " + points.length() + ")",
            points.length() >= 100,
        )

        // Crucially: the first sample is the START of the drive, the last is the END — proving
        // the renderer no longer truncates from one end.
        val firstSampleMs = points.getJSONObject(0).optLong("atMs")
        val lastSampleMs = points.getJSONObject(points.length() - 1).optLong("atMs")
        assertEquals(
            "downsampled route must include the very first GPS fix",
            firstAtMs,
            firstSampleMs,
        )
        assertEquals(
            "downsampled route must include the very last GPS fix",
            lastAtMs,
            lastSampleMs,
        )
    }

    @Test
    fun shortSessionRouteReturnsEveryPoint() {
        // No downsampling when the session has fewer points than the cap — every fix lands on
        // the map exactly once.
        val id = store.startSession("obd", "00:11", "Adapter")
        val route =
            arrayOf(
                doubleArrayOf(32.70, -117.10),
                doubleArrayOf(32.71, -117.10),
                doubleArrayOf(32.72, -117.10),
                doubleArrayOf(32.73, -117.10),
            )
        for (i in route.indices) {
            store.recordTelemetry(id, gpsSample(40, route[i][0], route[i][1], 1000L + i))
        }
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 5000L, "")

        val summary = StorageSummaryJson.build(store.getStorageSummaryRecord())
        val points =
            summary.getJSONArray("recentRoutes").getJSONObject(0).getJSONArray("points")
        assertEquals(
            "every GPS fix in a short session must survive",
            route.size,
            points.length(),
        )
    }

    @Test
    fun telemetryGpsBackfillsPartialLocationRoute() {
        val id = store.startSession("obd", "00:11", "Adapter")
        store.recordLocationSample(id, 900L, "gps", 0.0, 0.0, null, null, null, null, null, null)
        store.recordTelemetry(id, gpsSample(40, 32.70, -117.10, 1000L))
        store.recordTelemetry(id, gpsSample(42, 32.71, -117.11, 2000L))
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 3000L, "")

        val points =
            StorageSummaryJson
                .build(store.getStorageSummaryRecord())
                .getJSONArray("recentRoutes")
                .getJSONObject(0)
                .getJSONArray("points")
        assertEquals(2, points.length())
        assertEquals(32.70, points.getJSONObject(0).optDouble("lat"), 0.001)
        assertEquals(-117.11, points.getJSONObject(1).optDouble("lng"), 0.001)
    }

    @Test
    fun routePointsKeepUnknownLocationScalarsNull() {
        val id = store.startSession("obd", "00:11", "Adapter")
        store.recordLocationSample(
            id,
            1000L,
            "gps",
            32.70,
            -117.10,
            null,
            null,
            null,
            null,
            null,
            null,
        )
        store.recordLocationSample(
            id,
            2000L,
            "gps",
            32.71,
            -117.11,
            null,
            null,
            null,
            null,
            null,
            null,
        )
        store.recordTelemetry(id, gpsSample(40, 32.70, -117.10, 1000L))
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 3000L, "")

        val point =
            StorageSummaryJson
                .build(store.getStorageSummaryRecord())
                .getJSONArray("recentRoutes")
                .getJSONObject(0)
                .getJSONArray("points")
                .getJSONObject(0)
        assertTrue(point.isNull("accuracyM"))
        assertTrue(point.isNull("speedMps"))
        assertTrue(point.isNull("altM"))
    }

    private fun addMovingTelemetryRun(
        sessionId: Long,
        baseMs: Long,
        startMinute: Int,
        endMinute: Int,
        startLat: Double,
    ) {
        val minuteMs = 60_000L
        for (minute in startMinute..endMinute) {
            store.recordTelemetry(
                sessionId,
                gpsSample(
                    35,
                    startLat + (minute - startMinute) * 0.002,
                    -117.1000,
                    baseMs + minute * minuteMs,
                ),
            )
        }
    }

    private fun addStoppedTelemetryRun(
        sessionId: Long,
        baseMs: Long,
        startMinute: Int,
        endMinute: Int,
        lat: Double,
    ) {
        val minuteMs = 60_000L
        for (minute in startMinute..endMinute) {
            store.recordTelemetry(
                sessionId,
                inactiveGpsSample(lat, -117.1000, baseMs + minute * minuteMs),
            )
        }
    }

    companion object {
        /** Reflects the private active-trip cache out of [ObdLocalStore] -> [ObdStoreTrips]. */
        @Suppress("UNCHECKED_CAST")
        private fun activeTripCacheOf(store: ObdLocalStore): HashMap<String, Any> {
            val tripsField = ObdLocalStore::class.java.getDeclaredField("trips")
            tripsField.isAccessible = true
            val trips = tripsField.get(store)
            val cacheField = trips.javaClass.getDeclaredField("activeTripCache")
            cacheField.isAccessible = true
            return cacheField.get(trips) as HashMap<String, Any>
        }

        /**
         * Builds a telemetry sample with GPS, speed and a source marker so the row is counted as
         * "useful" by [ObdStoreSupport.isUsefulTelemetry].
         */
        private fun gpsSample(
            speedKph: Int,
            lat: Double,
            lng: Double,
            atMs: Long,
        ): JSONObject {
            val sample = JSONObject()
            sample.put("source", "obd")
            sample.put("speedKph", speedKph)
            sample.put("rpm", 1500)
            sample.put("latitude", lat)
            sample.put("longitude", lng)
            sample.put("accuracyM", 5.0)
            sample.put("updatedAt", atMs)
            return sample
        }

        private fun inactiveGpsSample(
            lat: Double,
            lng: Double,
            atMs: Long,
        ): JSONObject {
            val sample = JSONObject()
            sample.put("source", "obd")
            sample.put("speedKph", 0)
            sample.put("rpm", 0)
            sample.put("voltage", 0.0)
            sample.put("powerKw", 0.0)
            sample.put("latitude", lat)
            sample.put("longitude", lng)
            sample.put("accuracyM", 5.0)
            sample.put("updatedAt", atMs)
            return sample
        }

        private fun obdSampleNoGps(
            speedKph: Int,
            atMs: Long,
        ): JSONObject {
            val sample = JSONObject()
            sample.put("source", "obd")
            sample.put("speedKph", speedKph)
            sample.put("rpm", 1500)
            sample.put("updatedAt", atMs)
            return sample
        }

        /** Haversine distance between two lat/lng points, in meters. Mirrors the production formula. */
        private fun haversineMeters(
            lat1: Double,
            lng1: Double,
            lat2: Double,
            lng2: Double,
        ): Double {
            val earthMeters = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a =
                Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0) +
                    Math.cos(Math.toRadians(lat1)) *
                    Math.cos(Math.toRadians(lat2)) *
                    Math.sin(dLng / 2.0) *
                    Math.sin(dLng / 2.0)
            return earthMeters * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a))
        }
    }
}
