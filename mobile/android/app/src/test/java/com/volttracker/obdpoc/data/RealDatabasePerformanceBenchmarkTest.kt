package com.volttracker.obdpoc.data

import com.volttracker.obdpoc.StorageSummaryJson
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Optional local-only benchmark for a real VoltTracker SQLite database. It is skipped unless
 * `-PvoltTrackerRealDbPath=/path/to/volttracker_obd_poc.db` is supplied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealDatabasePerformanceBenchmarkTest {
    private var store: ObdLocalStore? = null

    @After
    fun tearDown() {
        store?.close()
    }

    @Test
    fun realDatabaseDashboardReadsReportTimings() {
        val sourcePath = System.getProperty(REAL_DB_PROPERTY)?.trim().orEmpty()
        assumeTrue(
            "Set -PvoltTrackerRealDbPath=/path/to/${VoltTrackerDb.DATABASE_NAME} to run this local benchmark.",
            sourcePath.isNotEmpty(),
        )
        val source = File(sourcePath)
        assumeTrue("Real database does not exist: $sourcePath", source.isFile)

        val context = RuntimeEnvironment.getApplication()
        val target = context.getDatabasePath(VoltTrackerDb.DATABASE_NAME)
        copyDatabaseSnapshot(source, target)

        lateinit var localStore: ObdLocalStore
        val openMs =
            measureMs {
                localStore = ObdLocalStore(context)
            }
        store = localStore

        var overview = JSONObject()
        val overviewMs =
            measureMs {
                overview = StorageSummaryJson.buildOverview(localStore.getStorageOverviewRecord())
            }
        var detailsBytes = 0
        val detailsMs =
            measureMs {
                detailsBytes = localStore.getStorageDetailsJson().toString().length
            }
        var tripsBytes = 0
        var routeKey: String? = null
        val tripsMs =
            measureMs {
                val trips = localStore.getTripsJson(TRIP_LIMIT)
                tripsBytes = trips.toString().length
                if (trips.length() > 0) {
                    routeKey = trips.getJSONObject(0).optString("id").takeIf { it.isNotBlank() }
                }
            }
        var routeBytes = 0
        val routeMs =
            routeKey?.let { key ->
                measureMs {
                    routeBytes =
                        localStore.routes
                            .getTripRouteJson(key)
                            .toString()
                            .length
                }
            }
        var insightsBytes = 0
        val insightsMs =
            measureMs {
                insightsBytes = localStore.getInsightsJson().toString().length
            }
        var sohBytes = 0
        val sohMs =
            measureMs {
                sohBytes =
                    localStore.routes
                        .getBatterySohHistoryJson()
                        .toString()
                        .length
            }

        val result =
            JSONObject()
                .put("sourcePath", source.absolutePath)
                .put("sourceBytes", source.length())
                .put("databaseName", VoltTrackerDb.DATABASE_NAME)
                .put("databaseVersion", VoltTrackerDb.DATABASE_VERSION)
                .put("overview", overview)
                .put(
                    "timingsMs",
                    JSONObject()
                        .put("open", openMs)
                        .put("overview", overviewMs)
                        .put("details", detailsMs)
                        .put("trips", tripsMs)
                        .put("route", routeMs ?: JSONObject.NULL)
                        .put("insights", insightsMs)
                        .put("batterySohHistory", sohMs),
                ).put(
                    "payloadBytes",
                    JSONObject()
                        .put("details", detailsBytes)
                        .put("trips", tripsBytes)
                        .put("route", routeBytes)
                        .put("insights", insightsBytes)
                        .put("batterySohHistory", sohBytes),
                )
        println("$RESULT_PREFIX ${result.toString(2)}")
    }

    private fun copyDatabaseSnapshot(
        source: File,
        target: File,
    ) {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(VoltTrackerDb.DATABASE_NAME)
        target.parentFile?.mkdirs()
        deleteIfExists(target)
        deleteIfExists(File(target.absolutePath + "-wal"))
        deleteIfExists(File(target.absolutePath + "-shm"))
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        copySidecarIfPresent(source, target, "-wal")
        copySidecarIfPresent(source, target, "-shm")
    }

    private fun copySidecarIfPresent(
        source: File,
        target: File,
        suffix: String,
    ) {
        val sidecar = File(source.absolutePath + suffix)
        if (sidecar.isFile) {
            Files.copy(
                sidecar.toPath(),
                File(target.absolutePath + suffix).toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun deleteIfExists(file: File) {
        if (file.exists()) {
            check(file.delete()) { "Could not delete ${file.absolutePath}" }
        }
    }

    private fun measureMs(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000L
    }

    companion object {
        const val REAL_DB_PROPERTY = "volttracker.realDbPath"
        const val RESULT_PREFIX = "[REAL_DB_BENCHMARK]"
        private const val TRIP_LIMIT = 120
    }
}
