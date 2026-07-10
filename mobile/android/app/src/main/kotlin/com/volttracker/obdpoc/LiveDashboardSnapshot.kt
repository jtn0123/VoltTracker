package com.volttracker.obdpoc

import org.json.JSONObject

/**
 * Process-local last-value handoff from the foreground OBD service to the dashboard Activity.
 *
 * The Activity intentionally unregisters its broadcast receiver while paused, so broadcasts sent
 * during a drive in the background are not available for replay when the user returns. The service
 * owns the authoritative hot sample/status and records each one here before broadcasting it. On
 * resume (and on a fresh WebView handshake), MainActivity copies these immutable snapshots into its
 * own bridge state and publishes only the newest values instead of its pre-background frame.
 *
 * This is deliberately memory-only: the foreground service and Activity live in the same process,
 * and SQLite remains the durable source for completed sessions/routes. [JsonSnapshot] provides the
 * cross-thread publication and defensive-copy guarantees required by the poll thread/UI thread
 * handoff.
 */
internal object LiveDashboardSnapshot {
    private val telemetry = JsonSnapshot()
    private val status = JsonSnapshot()

    fun reset() {
        telemetry.set(JSONObject())
        status.set(JSONObject())
    }

    fun recordTelemetry(payload: JSONObject) {
        telemetry.set(payload)
    }

    fun recordStatus(payload: JSONObject) {
        status.set(payload)
    }

    fun latestTelemetry(): JSONObject = telemetry.get()

    fun latestStatus(): JSONObject = status.get()

    /** True when replaying [latestTelemetry] will represent a genuinely current active sample. */
    fun shouldReplayTelemetry(
        latestStatus: JSONObject,
        latestTelemetry: JSONObject,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!MainActivityUtils.isConnectedState(latestStatus.optString("state", "")) ||
            latestTelemetry.length() == 0
        ) {
            return false
        }
        val updatedAt = latestTelemetry.optLong("updatedAt", 0L)
        val ageMs = nowMs - updatedAt
        return updatedAt > 0L && ageMs in 0L..TELEMETRY_REPLAY_MAX_AGE_MS
    }

    private const val TELEMETRY_REPLAY_MAX_AGE_MS = 10_000L
}

/** Publishes the shared service snapshot through the Activity-owned dashboard seams. */
internal class LiveDashboardStatePublisher(
    private val storeTelemetry: (JSONObject) -> Unit,
    private val storeStatus: (JSONObject) -> Unit,
    private val publishStatus: (JSONObject) -> Unit,
    private val publishAppState: () -> Unit,
    private val publishTelemetry: (JSONObject) -> Unit,
) {
    /**
     * Pulls and publishes one coherent resume/ready refresh, so every tab derives from the same
     * newest service state. A genuinely fresh sample also advances the live charts/route by one
     * point; an old sample is left to setAppState's timestamp-aware stale handling instead of being
     * made to look newly received.
     */
    fun publish(): LiveDashboardPublishResult {
        val latestTelemetry = LiveDashboardSnapshot.latestTelemetry()
        if (latestTelemetry.length() > 0) {
            storeTelemetry(latestTelemetry)
        }
        val latestStatus = LiveDashboardSnapshot.latestStatus()
        if (latestStatus.length() > 0) {
            storeStatus(latestStatus)
            publishStatus(latestStatus)
        }
        publishAppState()
        val replayed = LiveDashboardSnapshot.shouldReplayTelemetry(latestStatus, latestTelemetry)
        if (replayed) {
            publishTelemetry(latestTelemetry)
        }
        return LiveDashboardPublishResult(
            backgroundSampleCount = latestTelemetry.optInt("backgroundSampleCount", 0),
            sampleGapCount = latestTelemetry.optInt("sampleGapCount", 0),
            replayedFreshTelemetry = replayed,
        )
    }
}

internal data class LiveDashboardPublishResult(
    val backgroundSampleCount: Int,
    val sampleGapCount: Int,
    val replayedFreshTelemetry: Boolean,
)

internal class DashboardResumeCatchUp(
    private val publishToast: (String) -> Unit,
) {
    private var pausedBackgroundSampleCount: Int? = null
    private var pausedSampleGapCount: Int? = null

    fun recordPause(telemetry: JSONObject) {
        pausedBackgroundSampleCount = telemetry.optInt("backgroundSampleCount", 0)
        pausedSampleGapCount = telemetry.optInt("sampleGapCount", 0)
    }

    fun publish(result: LiveDashboardPublishResult) {
        val sampleBaseline = pausedBackgroundSampleCount ?: return
        val gapBaseline = pausedSampleGapCount ?: 0
        pausedBackgroundSampleCount = null
        pausedSampleGapCount = null
        message(sampleBaseline, gapBaseline, result)?.let(publishToast)
    }

    companion object {
        fun message(
            sampleBaseline: Int,
            gapBaseline: Int,
            result: LiveDashboardPublishResult,
        ): String? {
            val captured = result.backgroundSampleCount - sampleBaseline
            if (!result.replayedFreshTelemetry || captured <= 0) return null
            val gaps = maxOf(0, result.sampleGapCount - gapBaseline)
            return if (gaps == 0) {
                "Caught up: $captured background ${if (captured == 1) "sample" else "samples"}, no gaps"
            } else {
                "Caught up with $gaps ${if (gaps == 1) "gap" else "gaps"} — review Data health"
            }
        }
    }
}
