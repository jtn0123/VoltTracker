package com.volttracker.obdpoc

import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-local handoff from the foreground OBD service to the dashboard Activity.
 *
 * The Activity intentionally unregisters its broadcast receiver while paused, so broadcasts sent
 * during a drive in the background are not available for replay when the user returns. The service
 * owns the authoritative hot sample/status and records each one here before broadcasting it. On
 * resume (and on a fresh WebView handshake), MainActivity copies these immutable snapshots into its
 * own bridge state and publishes the newest values plus a bounded history backfill so the live
 * charts rebuild the background window instead of jumping from the pre-background frame straight
 * to the current sample.
 *
 * This is deliberately memory-only: the foreground service and Activity live in the same process,
 * and SQLite remains the durable source for completed sessions/routes. [JsonSnapshot] provides the
 * cross-thread publication and defensive-copy guarantees required by the poll thread/UI thread
 * handoff; the history ring makes the same guarantee by storing isolated copies under its own lock.
 */
internal object LiveDashboardSnapshot {
    private val telemetry = JsonSnapshot()
    private val status = JsonSnapshot()

    // Recent-sample ring for the resume backfill. The largest dashboard chart window is 240
    // samples (the SOC trace), so 300 covers every chart plus margin; anything older would be
    // evicted from the JS-side bounded histories immediately after replay anyway.
    private const val TELEMETRY_HISTORY_CAP = 300
    private val historyLock = Any()
    private val history = ArrayDeque<JSONObject>()

    fun reset() {
        telemetry.set(JSONObject())
        status.set(JSONObject())
        synchronized(historyLock) { history.clear() }
    }

    fun recordTelemetry(payload: JSONObject) {
        telemetry.set(payload)
        // Only timestamped samples enter the ring: the dashboard slices and dedupes the backfill
        // by updatedAt, so an unstamped sample could neither be ordered nor deduped over there.
        if (payload.optLong("updatedAt", 0L) > 0L) {
            val copy = MainActivityUtils.parseJson(payload.toString())
            synchronized(historyLock) {
                history.addLast(copy)
                while (history.size > TELEMETRY_HISTORY_CAP) history.removeFirst()
            }
        }
    }

    fun recordStatus(payload: JSONObject) {
        status.set(payload)
    }

    fun latestTelemetry(): JSONObject = telemetry.get()

    fun latestStatus(): JSONObject = status.get()

    /**
     * Samples recorded after [sinceUpdatedAtExclusive], oldest first. Callers must treat the
     * returned objects as read-only (they are the ring's own isolated copies).
     */
    fun telemetryHistorySince(sinceUpdatedAtExclusive: Long): List<JSONObject> =
        synchronized(historyLock) {
            history.filter { it.optLong("updatedAt", 0L) > sinceUpdatedAtExclusive }
        }

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
    private val publishTelemetryBackfill: (JSONObject) -> Unit = {},
) {
    /**
     * Pulls and publishes one coherent resume/ready refresh, so every tab derives from the same
     * newest service state. While a session is live, the ring history newer than
     * [sinceUpdatedAtExclusive] is replayed first as a `backfillTelemetry` batch so the charts,
     * session distance, and live route rebuild the background window in order; a genuinely fresh
     * newest sample then advances the live tiles by one point via the normal `updateTelemetry`
     * path (and is kept out of the batch so it cannot land in the histories twice). An old newest
     * sample is left to setAppState's timestamp-aware stale handling instead of being made to
     * look newly received.
     */
    fun publish(sinceUpdatedAtExclusive: Long = 0L): LiveDashboardPublishResult {
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
        var backfilled = 0
        // Backfill shares replay's connected-state gate (a finished session's tail must not be
        // resurrected into a fresh dashboard) but not its freshness bound — history is history.
        if (MainActivityUtils.isConnectedState(latestStatus.optString("state", ""))) {
            val latestUpdatedAt = latestTelemetry.optLong("updatedAt", 0L)
            val samples =
                LiveDashboardSnapshot
                    .telemetryHistorySince(sinceUpdatedAtExclusive)
                    .filterNot { replayed && it.optLong("updatedAt", 0L) == latestUpdatedAt }
            if (samples.isNotEmpty()) {
                publishTelemetryBackfill(JSONObject().put("samples", JSONArray(samples)))
                backfilled = samples.size
            }
        }
        if (replayed) {
            publishTelemetry(latestTelemetry)
        }
        return LiveDashboardPublishResult(
            backgroundSampleCount = latestTelemetry.optInt("backgroundSampleCount", 0),
            sampleGapCount = latestTelemetry.optInt("sampleGapCount", 0),
            replayedFreshTelemetry = replayed,
            backfilledSampleCount = backfilled,
        )
    }
}

internal data class LiveDashboardPublishResult(
    val backgroundSampleCount: Int,
    val sampleGapCount: Int,
    val replayedFreshTelemetry: Boolean,
    val backfilledSampleCount: Int = 0,
)

internal class DashboardResumeCatchUp(
    private val publishToast: (String) -> Unit,
) {
    private var pausedBackgroundSampleCount: Int? = null
    private var pausedSampleGapCount: Int? = null
    private var pausedTelemetryUpdatedAt = 0L

    fun recordPause(telemetry: JSONObject) {
        pausedBackgroundSampleCount = telemetry.optInt("backgroundSampleCount", 0)
        pausedSampleGapCount = telemetry.optInt("sampleGapCount", 0)
        pausedTelemetryUpdatedAt = telemetry.optLong("updatedAt", 0L)
    }

    /**
     * Newest sample timestamp the dashboard had before pausing — the exclusive lower bound for
     * the resume backfill. 0 when no pause was recorded (fresh handshake), which asks for the
     * whole ring so a recreated WebView rebuilds its charts too.
     */
    fun pauseBaselineUpdatedAt(): Long = pausedTelemetryUpdatedAt

    fun publish(result: LiveDashboardPublishResult) {
        val sampleBaseline = pausedBackgroundSampleCount ?: return
        val gapBaseline = pausedSampleGapCount ?: 0
        pausedBackgroundSampleCount = null
        pausedSampleGapCount = null
        pausedTelemetryUpdatedAt = 0L
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
