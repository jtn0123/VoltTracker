package com.volttracker.obdpoc.data

import org.json.JSONObject
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
 * Optional local-only generator for large, privacy-safe SQLite fixtures. Skipped unless
 * `-PvoltTrackerSyntheticDbPath=/path/to/out.db` is supplied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyntheticDatabaseGeneratorTest {
    @Test
    fun generateSyntheticDatabase() {
        val outputPath = System.getProperty(OUTPUT_PROPERTY)?.trim().orEmpty()
        assumeTrue(
            "Set -PvoltTrackerSyntheticDbPath=/path/to/${VoltTrackerDb.DATABASE_NAME} to generate a synthetic DB.",
            outputPath.isNotEmpty(),
        )
        val sessions = intProperty(SESSIONS_PROPERTY, DEFAULT_SESSIONS).coerceAtLeast(1)
        val samplesPerSession = intProperty(SAMPLES_PROPERTY, DEFAULT_SAMPLES_PER_SESSION).coerceAtLeast(1)
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(VoltTrackerDb.DATABASE_NAME)
        val store = ObdLocalStore(context)
        try {
            seedDriveHistory(store, sessions, samplesPerSession)
            store.checkpoint()
        } finally {
            store.close()
        }

        val source = context.getDatabasePath(VoltTrackerDb.DATABASE_NAME)
        val output = File(outputPath)
        output.parentFile?.mkdirs()
        Files.copy(source.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val payload =
            JSONObject()
                .put("outputPath", output.absolutePath)
                .put("sessions", sessions)
                .put("samplesPerSession", samplesPerSession)
                .put("telemetryRows", sessions.toLong() * samplesPerSession.toLong())
                .put("bytes", output.length())
                .put("databaseVersion", VoltTrackerDb.DATABASE_VERSION)
        println("$RESULT_PREFIX ${payload.toString(2)}")
    }

    private fun seedDriveHistory(
        store: ObdLocalStore,
        sessionCount: Int,
        samplesPerSession: Int,
    ) {
        val baseMs = 1_720_000_000_000L
        for (sessionIndex in 0 until sessionCount) {
            val startedAtMs = baseMs + sessionIndex * 3_600_000L
            val sessionId = store.startSession("obd", "02:00:00:00:00:01", "Synthetic LA Adapter", startedAtMs)
            for (sampleIndex in 0 until samplesPerSession) {
                val atMs = startedAtMs + sampleIndex * 1_000L
                store.recordTelemetry(sessionId, sample(sessionIndex, sampleIndex, atMs))
            }
            val endedAtMs = startedAtMs + (samplesPerSession - 1) * 1_000L
            store.finalizeSession(
                sessionId,
                "connected",
                endedAtMs,
                "4100",
                "02:00:00:00:00:01",
                "Synthetic LA Adapter",
                "obd",
                samplesPerSession,
                "synthetic",
            )
            store.materializeSession(sessionId, startedAtMs, endedAtMs)
        }
    }

    private fun sample(
        sessionIndex: Int,
        sampleIndex: Int,
        atMs: Long,
    ): JSONObject {
        val routeOffset = sessionIndex % 30
        return JSONObject()
            .put("source", "obd")
            .put("speedKph", 28 + (sampleIndex % 54))
            .put("rpm", 0)
            .put("soc", 86.0 - (sampleIndex % 160) * 0.05)
            .put("packVoltage", 354.0 + (sampleIndex % 7))
            .put("packCurrentA", -16.0 + (sampleIndex % 5))
            .put("powerKw", 5.0 + sampleIndex * 0.01)
            .put("coolantC", 72 + (sampleIndex % 18))
            .put("latitude", 34.0400 + routeOffset * 0.001 + sampleIndex * 0.00006)
            .put("longitude", -118.2600 - routeOffset * 0.001 - sampleIndex * 0.00006)
            .put("updatedAt", atMs)
    }

    private fun intProperty(
        name: String,
        defaultValue: Int,
    ): Int = System.getProperty(name)?.trim()?.toIntOrNull() ?: defaultValue

    companion object {
        const val OUTPUT_PROPERTY = "volttracker.syntheticDbPath"
        const val SESSIONS_PROPERTY = "volttracker.syntheticSessions"
        const val SAMPLES_PROPERTY = "volttracker.syntheticSamplesPerSession"
        const val RESULT_PREFIX = "[SYNTHETIC_DB_GENERATOR]"
        private const val DEFAULT_SESSIONS = 500
        private const val DEFAULT_SAMPLES_PER_SESSION = 2_000
    }
}
