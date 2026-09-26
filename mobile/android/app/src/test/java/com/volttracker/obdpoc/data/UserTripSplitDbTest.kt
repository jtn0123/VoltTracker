package com.volttracker.obdpoc.data

import com.volttracker.obdpoc.data.ParkStopDrives.Companion.HALF_MINUTE_MS
import com.volttracker.obdpoc.data.ParkStopDrives.Companion.minute
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * "Split trip here" through the real SQLite layer: a user split point at an in-trip Park stop
 * re-cuts the trip list, the map windows and saved trip segments; merging back restores one trip;
 * labels and favorites follow the documented first-half-inherits rule; and legacy sessions are
 * refused and untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserTripSplitDbTest {
    private lateinit var store: ObdLocalStore
    private lateinit var drives: ParkStopDrives

    @Before
    fun setUp() {
        store = ObdLocalStore(RuntimeEnvironment.getApplication())
        store.clearAllData()
        drives = ParkStopDrives(store)
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun splittingAtAParkStopMakesTwoTripsAndTheFirstKeepsTheLabelAndFavorite() {
        val id = recordShortParkDrive(minute(0))
        val other = recordShortParkDrive(minute(100))
        val key = singleTripKey(id)
        val otherKey = singleTripKey(other)
        store.tripEdits.setTripLabel(key, "Commute")
        store.tripEdits.setTripFavorite(key, true)
        store.tripEdits.setTripLabel(otherKey, "Gym")
        val stop = parkStop(key)

        val outcome = checkNotNull(store.tripEdits.splitTripAtStop(key, stop.startMs, stop.endMs))

        assertFalse(outcome.merged)
        assertEquals("$id:${stop.startMs}:${stop.endMs}", outcome.splitKey)
        val trips = tripsFor(id)
        assertEquals(2, trips.size)
        assertEquals(outcome.routeKeys.toSet(), trips.map { it.getString("id") }.toSet())
        val (first, second) = trips.sortedBy { it.getLong("startedAtMs") }
        assertEquals(outcome.routeKeys[0], first.getString("id"))
        assertEquals("Commute", first.getString("label"))
        assertTrue(first.getBoolean("favorite"))
        assertEquals("", second.getString("label"))
        assertFalse(second.getBoolean("favorite"))

        val windows = drives.windows(id)
        assertEquals(2, windows.size)
        assertEquals(stop.startMs, windows[0].endedAtMs)
        assertEquals(stop.endMs + 1L, windows[1].startedAtMs)

        val otherTrip = tripsFor(other).single()
        assertEquals("another trip's edits are untouched", "Gym", otherTrip.getString("label"))
        assertEquals(otherKey, otherTrip.getString("id"))
    }

    @Test
    fun routePayloadsNameTheSplitEachHalfCanMergeAcross() {
        val id = recordShortParkDrive(minute(0))
        val key = singleTripKey(id)
        val stop = parkStop(key)
        assertFalse(store.routes.getTripRouteJson(key).has("userSplitAfter"))

        val outcome = checkNotNull(store.tripEdits.splitTripAtStop(key, stop.startMs, stop.endMs))

        val firstRoute = store.routes.getTripRouteJson(outcome.routeKeys[0])
        val secondRoute = store.routes.getTripRouteJson(outcome.routeKeys[1])
        assertEquals(outcome.splitKey, firstRoute.getJSONObject("userSplitAfter").getString("key"))
        assertFalse(firstRoute.has("userSplitBefore"))
        assertEquals(outcome.splitKey, secondRoute.getJSONObject("userSplitBefore").getString("key"))
        assertEquals(stop.startMs, secondRoute.getJSONObject("userSplitBefore").getLong("startMs"))
        assertFalse(secondRoute.has("userSplitAfter"))
        assertFalse("the split stop is a trip boundary now, not an in-trip stop", firstRoute.has("parkStops"))
    }

    @Test
    fun mergingBackRestoresOneTripWithTheFirstHalfsLabelAndKeepsTheSecondHalfsForAReSplit() {
        val id = recordShortParkDrive(minute(0))
        val key = singleTripKey(id)
        store.tripEdits.setTripLabel(key, "Commute")
        val stop = parkStop(key)
        val split = checkNotNull(store.tripEdits.splitTripAtStop(key, stop.startMs, stop.endMs))
        val (firstKey, secondKey) = split.routeKeys
        store.tripEdits.setTripLabel(firstKey, "Morning run")
        store.tripEdits.setTripLabel(secondKey, "Errand")
        store.tripEdits.setTripFavorite(secondKey, true)

        val merged = checkNotNull(store.tripEdits.mergeTripSplit(split.splitKey))

        assertTrue(merged.merged)
        assertEquals(listOf(key), merged.routeKeys)
        val trip = tripsFor(id).single()
        assertEquals(key, trip.getString("id"))
        assertEquals("a rename made while split survives the merge", "Morning run", trip.getString("label"))
        assertFalse(trip.getBoolean("favorite"))
        assertEquals(1, drives.windows(id).size)
        assertNull("no split left to merge", store.tripEdits.mergeTripSplit(split.splitKey))

        val again = checkNotNull(store.tripEdits.splitTripAtStop(key, stop.startMs, stop.endMs))
        assertEquals(split.routeKeys, again.routeKeys)
        val second = tripsFor(id).single { it.getString("id") == secondKey }
        assertEquals("Errand", second.getString("label"))
        assertTrue(second.getBoolean("favorite"))
    }

    @Test
    fun savedTripSegmentsAreReCutOnSplitAndMerge() {
        val id = recordShortParkDrive(minute(0))
        store.materializeSession(id, minute(0), minute(25))
        val key = singleTripKey(id)
        store.tripEdits.setTripLabel(key, "Commute")
        assertEquals(1, segmentCount(id))
        val stop = parkStop(key)

        val split = checkNotNull(store.tripEdits.splitTripAtStop(key, stop.startMs, stop.endMs))
        assertEquals(2, segmentCount(id))
        assertEquals("Commute", firstSegmentLabel(id))

        checkNotNull(store.tripEdits.mergeTripSplit(split.splitKey))
        assertEquals(1, segmentCount(id))
        assertEquals("Commute", firstSegmentLabel(id))
    }

    @Test
    fun legacySessionsAreRefusedAndKeepTheirTrip() {
        val legacy = recordShortParkDrive(minute(0), legacy = true)
        val key = singleTripKey(legacy)
        val before = drives.windows(legacy).map { it.routeKey() }

        assertNull(store.tripEdits.splitTripAtStop(key, minute(10), minute(14) + HALF_MINUTE_MS))

        assertEquals(before, drives.windows(legacy).map { it.routeKey() })
        assertEquals(key, tripsFor(legacy).single().getString("id"))
        assertEquals(0, splitEventCount())
        assertFalse(store.routes.getTripRouteJson(key).has("userSplitAfter"))
    }

    @Test
    fun onlyAGenuineParkStopOfThatTripCanBeASplitPoint() {
        val id = recordShortParkDrive(minute(0))
        val key = singleTripKey(id)
        val stop = parkStop(key)

        assertNull(store.tripEdits.splitTripAtStop(key, stop.startMs, stop.endMs + 1L))
        assertNull(store.tripEdits.splitTripAtStop(key, minute(3), minute(4)))
        assertNull(store.tripEdits.splitTripAtStop("not-a-key", stop.startMs, stop.endMs))
        assertNull(store.tripEdits.splitTripAtStop("999:${stop.startMs}:${stop.endMs}", stop.startMs, stop.endMs))
        assertNull(store.tripEdits.mergeTripSplit("$id:1:2"))
        assertNull(store.tripEdits.mergeTripSplit(null))

        assertEquals(0, splitEventCount())
        assertEquals(1, tripsFor(id).size)
    }

    // ---- helpers -------------------------------------------------------------------------

    /** 10 min driving, a 5-min Park stop, 10 min driving: one gear-aware trip with one in-trip stop. */
    private fun recordShortParkDrive(
        baseMs: Long,
        legacy: Boolean = false,
    ): Long = drives.record(baseMs, parkFromMinute = 10, parkToMinute = 15, endMinute = 25, legacy = legacy)

    private fun tripsFor(sessionId: Long): List<JSONObject> {
        val trips = store.getTripsJson(50)
        return (0 until trips.length()).map { trips.getJSONObject(it) }.filter { it.getLong("sessionId") == sessionId }
    }

    private fun singleTripKey(sessionId: Long): String = tripsFor(sessionId).single().getString("id")

    private fun parkStop(routeKey: String): StopSpan {
        val stops = store.routes.getTripRouteJson(routeKey).getJSONArray("parkStops")
        assertEquals(1, stops.length())
        val stop = stops.getJSONObject(0)
        return StopSpan(stop.getLong("startMs"), stop.getLong("endMs"))
    }

    private fun segmentCount(sessionId: Long): Int =
        drives.withDb { db ->
            ObdStoreSupport
                .countRowsWhere(db, VoltTrackerDb.TABLE_TRIP_SEGMENTS, "session_id = ?", arrayOf(sessionId.toString()))
                .toInt()
        }

    private fun firstSegmentLabel(sessionId: Long): String? =
        drives.withDb { db ->
            db
                .rawQuery(
                    "SELECT label FROM ${VoltTrackerDb.TABLE_TRIP_SEGMENTS} WHERE session_id = ? " +
                        "ORDER BY started_at_ms LIMIT 1",
                    arrayOf(sessionId.toString()),
                ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
        }

    private fun splitEventCount(): Int =
        drives.withDb { db ->
            ObdStoreSupport
                .countRowsWhere(db, VoltTrackerDb.TABLE_EVENTS, "kind = ?", arrayOf(ObdTripSplits.EVENT_KIND))
                .toInt()
        }

    private class StopSpan(
        val startMs: Long,
        val endMs: Long,
    )
}
