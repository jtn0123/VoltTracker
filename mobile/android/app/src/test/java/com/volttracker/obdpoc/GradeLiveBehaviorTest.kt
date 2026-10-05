package com.volttracker.obdpoc

import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeLiveBehaviorTest {
    @Test fun neverReportedReadingsStayUnknownButMeasuredZerosAreKnown() {
        val store = LiveUiStateStore()
        store.onStatus(JSONObject().put("state", "connected"))
        assertNull(store.state.value.drive.speedMph)
        assertNull(store.state.value.drive.powerKw)
        assertNull(store.state.value.drive.socPercent)
        assertNull(store.state.value.charge.socPercent)
        store.onTelemetry(JSONObject().put("speedKph", 0).put("powerKw", 0).put("soc", 0))
        assertEquals(0, store.state.value.drive.speedMph)
        assertEquals(0.0, requireNotNull(store.state.value.drive.powerKw), 0.0)
        assertEquals(0.0, requireNotNull(store.state.value.drive.socPercent), 0.0)
    }

    @Test fun missingCoreValuesExpireEvenWhenOtherPidsKeepArriving() {
        var now = 1_000L
        val store = LiveUiStateStore { now }
        store.onStatus(JSONObject().put("state", "connected"))
        store.onTelemetry(JSONObject().put("speedKph", 32).put("powerKw", 4).put("soc", 60))
        now += 5_000L
        store.onTelemetry(JSONObject().put("rpm", 100))
        assertNotNull(store.state.value.drive.speedMph)
        now += 6_000L
        store.onTelemetry(JSONObject().put("rpm", 200))
        assertNull(store.state.value.drive.speedMph)
        assertNull(store.state.value.drive.powerKw)
        assertNull(store.state.value.drive.socPercent)
    }

    @Test fun silentStreamExpiresAndANewConnectionDoesNotReuseOldSoc() {
        var now = 1_000L
        val store = LiveUiStateStore { now }
        store.onStatus(JSONObject().put("state", "connected"))
        store.onTelemetry(JSONObject().put("speedKph", 10).put("powerKw", 2).put("soc", 44))
        now += 11_000L
        store.expireCoreReadings()
        assertNull(store.state.value.drive.speedMph)
        assertNull(store.state.value.charge.socPercent)
        store.onTelemetry(JSONObject().put("soc", 44))
        store.onStatus(JSONObject().put("state", "idle"))
        store.onStatus(JSONObject().put("state", "connected"))
        assertNull(store.state.value.charge.socPercent)
    }

    @Test fun agedPayloadAndOldResumeSnapshotStayUnknown() {
        val store = LiveUiStateStore { 50_000L }
        store.onStatus(JSONObject().put("state", "connected"))
        store.onTelemetry(JSONObject().put("powerKw", 3).put("powerKwStaleMs", 20_000))
        assertNull(store.state.value.drive.powerKw)
        store.onTelemetryBackfill(
            listOf(
                JSONObject()
                    .put("updatedAt", 1_000L)
                    .put("speedKph", 20)
                    .put("soc", 40)
                    .put("displayedSoc", 80),
            ),
        )
        store.expireCoreReadings()
        assertNull(store.state.value.drive.speedMph)
        assertNull(store.state.value.drive.socPercent)
        assertNull(store.state.value.charge.displayedSocPercent)
    }

    @Test fun connectionFailureSurvivesIdleAndRecordingHealthIsIndependent() {
        val store = LiveUiStateStore()
        store.onStatus(
            JSONObject()
                .put(
                    "state",
                    "blocked",
                ).put(
                    "detail",
                    "Nearby devices permission is missing.",
                ).put("failureClass", "permission")
                .put("competingApps", "Torque"),
        )
        store.onStatus(JSONObject().put("state", "idle"))
        assertEquals(
            "Nearby devices permission is missing.",
            store.state.value.connectionFailure
                ?.detail,
        )
        assertEquals(
            "Torque",
            store.state.value.connectionFailure
                ?.competingApps,
        )
        store.onStatus(JSONObject().put("state", "connected").put("recordingWarning", "Database full."))
        assertNull(store.state.value.connectionFailure)
        assertTrue(store.state.value.drive.connected)
        assertEquals("Database full.", store.state.value.recordingWarning)
        store.onStatus(JSONObject().put("state", "connected"))
        assertEquals("Database full.", store.state.value.recordingWarning)
        store.onStatus(JSONObject().put("state", "connected").put("recordingWarning", ""))
        assertNull(store.state.value.recordingWarning)
    }
}
