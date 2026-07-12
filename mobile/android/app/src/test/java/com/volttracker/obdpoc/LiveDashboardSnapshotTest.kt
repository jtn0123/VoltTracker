package com.volttracker.obdpoc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiveDashboardSnapshotTest {
    @Before
    fun resetSnapshot() {
        LiveDashboardSnapshot.reset()
    }

    @Test
    fun freshActiveTelemetryIsEligibleForOneResumeReplay() {
        val now = 1_000_000L
        val status = JSONObject().put("state", "connected")
        val telemetry = JSONObject().put("updatedAt", now - 5_000L).put("speedKph", 32)

        assertTrue(LiveDashboardSnapshot.shouldReplayTelemetry(status, telemetry, now))
    }

    @Test
    fun staleTelemetryIsNotMadeToLookFreshOnResume() {
        val now = 1_000_000L
        val status = JSONObject().put("state", "connected")
        val telemetry = JSONObject().put("updatedAt", now - 10_001L).put("speedKph", 112)

        assertFalse(LiveDashboardSnapshot.shouldReplayTelemetry(status, telemetry, now))
    }

    @Test
    fun inactiveSessionNeverReplaysItsLastTelemetryAsLive() {
        val now = 1_000_000L
        val status = JSONObject().put("state", "idle")
        val telemetry = JSONObject().put("updatedAt", now).put("speedKph", 112)

        assertFalse(LiveDashboardSnapshot.shouldReplayTelemetry(status, telemetry, now))
    }

    @Test
    fun publisherReturnsBackgroundCatchUpCountersWithFreshReplay() {
        val now = System.currentTimeMillis()
        LiveDashboardSnapshot.recordStatus(JSONObject().put("state", "connected"))
        LiveDashboardSnapshot.recordTelemetry(
            JSONObject()
                .put("updatedAt", now)
                .put("backgroundSampleCount", 37)
                .put("sampleGapCount", 2),
        )
        var telemetryPublishes = 0
        val publisher =
            LiveDashboardStatePublisher(
                storeTelemetry = {},
                storeStatus = {},
                publishStatus = {},
                publishAppState = {},
                publishTelemetry = { telemetryPublishes += 1 },
            )

        val result = publisher.publish()

        assertEquals(37, result.backgroundSampleCount)
        assertEquals(2, result.sampleGapCount)
        assertTrue(result.replayedFreshTelemetry)
        assertEquals(1, telemetryPublishes)
    }

    @Test
    fun historyRingKeepsTimestampedSamplesOldestFirstAndDropsUnstamped() {
        LiveDashboardSnapshot.recordTelemetry(JSONObject().put("speedKph", 5)) // no updatedAt
        LiveDashboardSnapshot.recordTelemetry(JSONObject().put("updatedAt", 100L).put("speedKph", 10))
        LiveDashboardSnapshot.recordTelemetry(JSONObject().put("updatedAt", 200L).put("speedKph", 20))
        LiveDashboardSnapshot.recordTelemetry(JSONObject().put("updatedAt", 300L).put("speedKph", 30))

        val all = LiveDashboardSnapshot.telemetryHistorySince(0L)
        assertEquals(listOf(100L, 200L, 300L), all.map { it.getLong("updatedAt") })

        val sliced = LiveDashboardSnapshot.telemetryHistorySince(100L)
        assertEquals(listOf(200L, 300L), sliced.map { it.getLong("updatedAt") })
    }

    @Test
    fun historyRingIsBoundedAndEvictsOldestFirst() {
        for (i in 1..350) {
            LiveDashboardSnapshot.recordTelemetry(JSONObject().put("updatedAt", i.toLong()))
        }
        val all = LiveDashboardSnapshot.telemetryHistorySince(0L)
        assertEquals(300, all.size)
        assertEquals(51L, all.first().getLong("updatedAt"))
        assertEquals(350L, all.last().getLong("updatedAt"))
    }

    @Test
    fun historyRingStoresIsolatedCopiesAndClearsOnReset() {
        val sample = JSONObject().put("updatedAt", 100L).put("speedKph", 10)
        LiveDashboardSnapshot.recordTelemetry(sample)
        sample.put("speedKph", 99) // caller-side mutation must not leak into the ring
        assertEquals(10, LiveDashboardSnapshot.telemetryHistorySince(0L).single().getInt("speedKph"))

        LiveDashboardSnapshot.reset()
        assertTrue(LiveDashboardSnapshot.telemetryHistorySince(0L).isEmpty())
    }

    @Test
    fun publisherBackfillsBackgroundWindowExcludingTheReplayedNewestSample() {
        val now = System.currentTimeMillis()
        LiveDashboardSnapshot.recordStatus(JSONObject().put("state", "connected"))
        // Pre-pause sample, three background samples, then the fresh newest one.
        for ((index, offset) in listOf(-40_000L, -30_000L, -20_000L, -10_000L, -1_000L).withIndex()) {
            LiveDashboardSnapshot.recordTelemetry(
                JSONObject().put("updatedAt", now + offset).put("speedKph", index * 10),
            )
        }
        val backfills = mutableListOf<JSONObject>()
        var replayedAt = 0L
        val publisher =
            LiveDashboardStatePublisher(
                storeTelemetry = {},
                storeStatus = {},
                publishStatus = {},
                publishAppState = {},
                publishTelemetry = { replayedAt = it.getLong("updatedAt") },
                publishTelemetryBackfill = { backfills.add(it) },
            )

        val result = publisher.publish(sinceUpdatedAtExclusive = now - 40_000L)

        assertEquals(1, backfills.size)
        val samples = backfills.single().getJSONArray("samples")
        // The three background samples, oldest first — the pre-pause sample is sliced out by the
        // baseline and the newest is left to the updateTelemetry replay.
        assertEquals(3, samples.length())
        assertEquals(now - 30_000L, samples.getJSONObject(0).getLong("updatedAt"))
        assertEquals(now - 10_000L, samples.getJSONObject(2).getLong("updatedAt"))
        assertEquals(now - 1_000L, replayedAt)
        assertEquals(3, result.backfilledSampleCount)
        assertTrue(result.replayedFreshTelemetry)
    }

    @Test
    fun publisherBackfillIncludesNewestSampleWhenItIsTooStaleToReplay() {
        val now = System.currentTimeMillis()
        LiveDashboardSnapshot.recordStatus(JSONObject().put("state", "connected"))
        LiveDashboardSnapshot.recordTelemetry(JSONObject().put("updatedAt", now - 60_000L))
        LiveDashboardSnapshot.recordTelemetry(JSONObject().put("updatedAt", now - 30_000L))
        val backfills = mutableListOf<JSONObject>()
        var telemetryPublishes = 0
        val publisher =
            LiveDashboardStatePublisher(
                storeTelemetry = {},
                storeStatus = {},
                publishStatus = {},
                publishAppState = {},
                publishTelemetry = { telemetryPublishes += 1 },
                publishTelemetryBackfill = { backfills.add(it) },
            )

        val result = publisher.publish(sinceUpdatedAtExclusive = 0L)

        // No fresh replay (newest is 30s old), but the history window still reaches the charts.
        assertEquals(0, telemetryPublishes)
        assertEquals(2, backfills.single().getJSONArray("samples").length())
        assertFalse(result.replayedFreshTelemetry)
        assertEquals(2, result.backfilledSampleCount)
    }

    @Test
    fun publisherNeverBackfillsAFinishedSessionIntoAFreshDashboard() {
        val now = System.currentTimeMillis()
        LiveDashboardSnapshot.recordStatus(JSONObject().put("state", "idle"))
        LiveDashboardSnapshot.recordTelemetry(JSONObject().put("updatedAt", now - 5_000L))
        val backfills = mutableListOf<JSONObject>()
        val publisher =
            LiveDashboardStatePublisher(
                storeTelemetry = {},
                storeStatus = {},
                publishStatus = {},
                publishAppState = {},
                publishTelemetry = {},
                publishTelemetryBackfill = { backfills.add(it) },
            )

        val result = publisher.publish(sinceUpdatedAtExclusive = 0L)

        assertTrue(backfills.isEmpty())
        assertEquals(0, result.backfilledSampleCount)
    }

    @Test
    fun catchUpRecordsAndClearsThePauseBaseline() {
        val catchUp = DashboardResumeCatchUp {}
        assertEquals(0L, catchUp.pauseBaselineUpdatedAt())

        catchUp.recordPause(JSONObject().put("updatedAt", 123_456L).put("backgroundSampleCount", 1))
        assertEquals(123_456L, catchUp.pauseBaselineUpdatedAt())

        catchUp.publish(LiveDashboardPublishResult(1, 0, false))
        assertEquals(0L, catchUp.pauseBaselineUpdatedAt())
    }

    @Test
    fun catchUpReceiptDistinguishesCleanBackgroundSamplesFromGaps() {
        val clean = DashboardResumeCatchUp.message(10, 2, LiveDashboardPublishResult(14, 2, true))
        assertEquals("Caught up: 4 background samples, no gaps", clean)

        val gapped = DashboardResumeCatchUp.message(14, 2, LiveDashboardPublishResult(18, 4, true))
        assertEquals("Caught up with 2 gaps — review Data health", gapped)

        assertEquals(null, DashboardResumeCatchUp.message(18, 4, LiveDashboardPublishResult(18, 4, true)))
        assertEquals(null, DashboardResumeCatchUp.message(18, 4, LiveDashboardPublishResult(20, 4, false)))
    }
}
