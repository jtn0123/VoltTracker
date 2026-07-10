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
    fun catchUpReceiptDistinguishesCleanBackgroundSamplesFromGaps() {
        val clean = DashboardResumeCatchUp.message(10, 2, LiveDashboardPublishResult(14, 2, true))
        assertEquals("Caught up: 4 background samples, no gaps", clean)

        val gapped = DashboardResumeCatchUp.message(14, 2, LiveDashboardPublishResult(18, 4, true))
        assertEquals("Caught up with 2 gaps — review Data health", gapped)

        assertEquals(null, DashboardResumeCatchUp.message(18, 4, LiveDashboardPublishResult(18, 4, true)))
        assertEquals(null, DashboardResumeCatchUp.message(18, 4, LiveDashboardPublishResult(20, 4, false)))
    }
}
