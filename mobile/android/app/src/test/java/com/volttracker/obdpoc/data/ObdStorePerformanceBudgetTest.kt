package com.volttracker.obdpoc.data

import com.volttracker.obdpoc.StorageSummaryJson
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Seeded JVM performance guard for dashboard storage reads. Budgets are intentionally generous so
 * they catch accidental unbounded scans/N+1 regressions without trying to be a device benchmark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObdStorePerformanceBudgetTest {
    private lateinit var store: ObdLocalStore

    @Before
    fun setUp() {
        store = ObdLocalStore(RuntimeEnvironment.getApplication())
        store.clearAllData()
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun seededDashboardStorageReadsStayInsideJvmBudgets() {
        seedDriveHistory()

        // Warm the SQLite helper and generated query plans before measuring the steady-state gate.
        StorageSummaryJson.buildOverview(store.getStorageOverviewRecord())

        val overviewMs =
            measureMs {
                StorageSummaryJson.buildOverview(store.getStorageOverviewRecord())
            }
        val detailsMs = measureMs { store.getStorageDetailsJson() }
        val tripsMs = measureMs { store.getTripsJson(40) }
        val newestRouteKey = store.getTripsJson(1).getJSONObject(0).getString("id")
        val routeMs = measureMs { store.getTripRouteJson(newestRouteKey) }

        println(
            "[G1 storage budget] seededSessions=$SEEDED_SESSION_COUNT samplesPerSession=$SAMPLES_PER_SESSION " +
                "overviewMs=$overviewMs detailsMs=$detailsMs tripsMs=$tripsMs routeMs=$routeMs",
        )
        assertBudget("storage overview", overviewMs, OVERVIEW_BUDGET_MS)
        assertBudget("lazy storage details", detailsMs, DETAILS_BUDGET_MS)
        assertBudget("trips list", tripsMs, TRIPS_BUDGET_MS)
        assertBudget("single route", routeMs, ROUTE_BUDGET_MS)
    }

    private fun seedDriveHistory() {
        val baseMs = 1_720_000_000_000L
        for (sessionIndex in 0 until SEEDED_SESSION_COUNT) {
            val startedAtMs = baseMs + sessionIndex * 3_600_000L
            val sessionId = store.startSession("obd", "00:11:22:33:44:55", "Budget Adapter", startedAtMs)
            for (sampleIndex in 0 until SAMPLES_PER_SESSION) {
                val atMs = startedAtMs + sampleIndex * 1_000L
                store.recordTelemetry(sessionId, sample(sessionIndex, sampleIndex, atMs))
            }
            val endedAtMs = startedAtMs + (SAMPLES_PER_SESSION - 1) * 1_000L
            store.finalizeSession(
                sessionId,
                "connected",
                endedAtMs,
                "4100",
                "00:11:22:33:44:55",
                "Budget Adapter",
                "obd",
                SAMPLES_PER_SESSION,
                "seeded",
            )
            store.materializeSession(sessionId, startedAtMs, endedAtMs)
        }
    }

    private fun sample(
        sessionIndex: Int,
        sampleIndex: Int,
        atMs: Long,
    ): JSONObject =
        JSONObject()
            .put("source", "obd")
            .put("speedKph", 36 + (sampleIndex % 28))
            .put("rpm", 0)
            .put("soc", 82.0 - sessionIndex * 0.2 - sampleIndex * 0.01)
            .put("packVoltage", 356.0)
            .put("packCurrentA", -18.0 + (sampleIndex % 5))
            .put("powerKw", 6.0 + sampleIndex * 0.03)
            .put("latitude", 32.70 + sessionIndex * 0.003 + sampleIndex * 0.00008)
            .put("longitude", -117.10 - sampleIndex * 0.00008)
            .put("updatedAt", atMs)

    private fun measureMs(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000L
    }

    private fun assertBudget(
        label: String,
        actualMs: Long,
        budgetMs: Long,
    ) {
        assertTrue("$label read took ${actualMs}ms, over JVM budget ${budgetMs}ms", actualMs <= budgetMs)
    }

    private companion object {
        const val SEEDED_SESSION_COUNT = 18
        const val SAMPLES_PER_SESSION = 90
        const val OVERVIEW_BUDGET_MS = 1_500L
        const val DETAILS_BUDGET_MS = 4_000L
        const val TRIPS_BUDGET_MS = 1_500L
        const val ROUTE_BUDGET_MS = 1_500L
    }
}
