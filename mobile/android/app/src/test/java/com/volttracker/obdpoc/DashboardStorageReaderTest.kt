package com.volttracker.obdpoc

import android.content.Context
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.data.StorageSummaryRecord
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Covers the synchronous WebView storage reader's success, teardown, and failure JSON contracts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardStorageReaderTest {
    private lateinit var context: Context
    private var store: RecordingStore? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        store = RecordingStore(context)
    }

    @After
    fun tearDown() {
        store?.close()
    }

    @Test
    fun readsEveryDashboardProjectionAndKeepsTheTripsWindowBounded() {
        val reader = DashboardStorageReader { store }

        val summary = JSONObject(reader.storageSummaryJson())
        assertEquals("volt.db", summary.getString("database"))
        assertEquals(2L, summary.getLong("sessionCount"))

        val details = JSONObject(reader.storageDetailsJson())
        assertEquals(11, details.getInt("bytesPerSample"))

        val trips = JSONArray(reader.tripsJson())
        assertEquals(120, store!!.lastTripsLimit)
        assertEquals("trip-a", trips.getJSONObject(0).getString("routeKey"))

        val route = JSONObject(reader.tripRouteJson(" 7:1000:2000 "))
        assertEquals(" 7:1000:2000 ", store!!.lastRouteKey)
        assertEquals(3, route.getJSONArray("points").length())

        val insights = JSONObject(reader.insightsJson())
        assertEquals("steady", insights.getString("trend"))

        val currentRoute = JSONObject(reader.currentSessionRouteJson())
        assertTrue(currentRoute.getBoolean("active"))

        val soh = JSONArray(reader.batterySohHistoryJson())
        assertEquals(91.5, soh.getJSONObject(0).getDouble("sohPct"), 0.001)
    }

    @Test
    fun returnsStorageUnavailableWhenStoreIsMissingOrClosed() {
        val missing = JSONObject(DashboardStorageReader { null }.storageSummaryJson())
        assertFalse(missing.getBoolean("ok"))
        assertEquals("storage_unavailable", missing.getString("error"))

        store!!.open = false
        val closed = JSONObject(DashboardStorageReader { store }.tripsJson())
        assertFalse(closed.getBoolean("ok"))
        assertEquals("storage_unavailable", closed.getString("error"))
        assertEquals(Int.MIN_VALUE, store!!.lastTripsLimit)
    }

    @Test
    fun normalizesRuntimeFailuresToPerProjectionErrors() {
        val reader = DashboardStorageReader { store }

        store!!.throwOn = "summary"
        assertEquals("storage_summary_failed", JSONObject(reader.storageSummaryJson()).getString("error"))

        store!!.throwOn = "details"
        assertEquals("storage_details_failed", JSONObject(reader.storageDetailsJson()).getString("error"))

        store!!.throwOn = "trips"
        assertEquals("trips_read_failed", JSONObject(reader.tripsJson()).getString("error"))

        store!!.throwOn = "route"
        assertEquals("trip_route_read_failed", JSONObject(reader.tripRouteJson("7")).getString("error"))

        store!!.throwOn = "insights"
        assertEquals("insights_read_failed", JSONObject(reader.insightsJson()).getString("error"))

        store!!.throwOn = "current"
        assertEquals("current_route_read_failed", JSONObject(reader.currentSessionRouteJson()).getString("error"))

        store!!.throwOn = "soh"
        assertEquals("battery_soh_history_failed", JSONObject(reader.batterySohHistoryJson()).getString("error"))
    }

    class RecordingStore(
        context: Context,
    ) : ObdLocalStore(context) {
        var open = true
        var throwOn = ""
        var lastTripsLimit = Int.MIN_VALUE
        var lastRouteKey: String? = null

        override val isOpen: Boolean
            get() = open

        override fun getStorageOverviewRecord(): StorageSummaryRecord {
            failIf("summary")
            return StorageSummaryRecord(
                "volt.db",
                512L,
                2L,
                8L,
                7L,
                1L,
                3L,
                1L,
                4L,
                0L,
                mapOf("active" to 1L),
                6L,
                1L,
                2L,
                1L,
                1L,
                1L,
                1L,
                1L,
                null,
                emptyList(),
                emptyList(),
                emptyList(),
                null,
                null,
                null,
                JSONObject().put("health", "ok"),
                null,
                null,
                null,
                null,
            )
        }

        override fun getStorageDetailsJson(): JSONObject {
            failIf("details")
            return JSONObject().put("bytesPerSample", 11)
        }

        override fun getTripsJson(limit: Int): JSONArray {
            failIf("trips")
            lastTripsLimit = limit
            return JSONArray().put(JSONObject().put("routeKey", "trip-a"))
        }

        override fun getTripRouteJson(routeKey: String?): JSONObject {
            failIf("route")
            lastRouteKey = routeKey
            return JSONObject().put("points", JSONArray().put(1).put(2).put(3))
        }

        override fun getInsightsJson(): JSONObject {
            failIf("insights")
            return JSONObject().put("trend", "steady")
        }

        override fun getCurrentSessionRouteJson(): JSONObject {
            failIf("current")
            return JSONObject().put("active", true)
        }

        override fun getBatterySohHistoryJson(): JSONArray {
            failIf("soh")
            return JSONArray().put(JSONObject().put("sohPct", 91.5))
        }

        override fun close() {
            open = false
            super.close()
        }

        private fun failIf(target: String) {
            if (throwOn == target) {
                throw IllegalStateException("$target failed")
            }
        }
    }
}
