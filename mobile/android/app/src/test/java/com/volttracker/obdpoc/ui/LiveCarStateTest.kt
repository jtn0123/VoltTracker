package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.car.BodyGroup
import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Car tab's body readings and car-control state, folded out of the service's payloads. */
class LiveCarStateTest {
    private val store = LiveUiStateStore { 0L }

    private fun body(
        at: Long = 100_000L,
        block: JSONObject.() -> Unit = {},
    ): JSONObject =
        JSONObject()
            .put("updatedAt", at)
            .put("doorFlState", "closed")
            .put("doorFrState", "closed")
            .put("doorRlState", "closed")
            .put("doorRrState", "closed")
            .put("hoodState", "closed")
            .put("trunkState", "closed")
            .put("windowFlPct", 0)
            .put("windowFrPct", 0)
            .put("windowRlPct", 0)
            .put("windowRrPct", 0)
            .put("acState", "off")
            .put("remoteStartState", "off")
            .put("outsideTempC", 18.0)
            .put("tirePressureFlKpa", 262.0)
            .put("tirePressureStaleMs", 30_000)
            .apply(block)

    private val car get() = store.state.value.car

    @Test
    fun bodyReadingsMapToOpeningsWindowsAndClimate() {
        store.onTelemetry(body { put("doorFlState", "open").put("trunkState", "open").put("windowRlPct", 45) })
        assertEquals(listOf("Driver door", "Hatch"), car.openings?.open)
        assertEquals(listOf(0, 0, 45, 0), car.windowsPct)
        assertEquals(false, car.acOn)
        assertEquals(false, car.remoteStartOn)
        assertEquals(18.0, car.outsideTempC ?: 0.0, 0.0)
        assertEquals(100_000L, car.nowMs)
        // Tyres were heard 30 s before the sample; doors with it.
        assertEquals(70_000L, car.seenAtMs[BodyGroup.TIRES])
        assertEquals(100_000L, car.seenAtMs[BodyGroup.DOORS])
        assertNull("never heard", car.seenAtMs[BodyGroup.LOCK])
    }

    @Test
    fun anAbsentReadingKeepsTheLastOneAndAStaleOneClearsIt() {
        store.onTelemetry(body())
        store.onTelemetry(JSONObject().put("updatedAt", 101_000L))
        assertEquals(emptyList<String>(), car.openings?.open)
        assertEquals(listOf(0, 0, 0, 0), car.windowsPct)
        store.onTelemetry(
            body(at = 400_000L) {
                put("doorStatusStaleMs", 150_000).put("windowStaleMs", 150_000).put("climateStaleMs", 150_000)
            },
        )
        assertNull(car.openings)
        assertNull(car.windowsPct)
        assertNull(car.acOn)
        assertEquals("absent staleness keeps the reading", 18.0, car.outsideTempC ?: 0.0, 0.0)
        // Heard, then stale: the last time it was fresh is kept for "No reading for N min".
        assertEquals(100_000L, car.seenAtMs[BodyGroup.DOORS])
        store.onTelemetry(body(at = 401_000L) { put("outsideTempStaleMs", 150_000) })
        assertNull("a stale outside temperature is not shown", car.outsideTempC)
    }

    @Test
    fun anUnknownOrPartialReadingIsNotReportedRatherThanGuessed() {
        store.onTelemetry(body { put("hoodState", "ajar").put("acState", "auto") })
        assertNull(car.openings)
        assertNull(car.acOn)
        store.onTelemetry(body { remove("windowRrPct") })
        assertNull(car.windowsPct)
    }

    @Test
    fun theEngineGateAndLastCommandAreMapped() {
        store.onTelemetry(
            body {
                put("carControlGate", "not_in_park")
                put("carControlGateDetail", "Put the car in Park.")
                put("carControlLastCommand", "lock")
                put("carControlLastOutcome", "confirmed")
            },
        )
        assertEquals("not_in_park", car.controls.gate)
        assertEquals("Put the car in Park.", car.controls.gateDetail)
        assertEquals("lock", car.controls.lastCommand)
        assertEquals("confirmed", car.controls.lastOutcome)
        store.onTelemetry(body())
        assertNull("the gate is only reported while controls are on", car.controls.gate)
        assertEquals("the last result stays", "lock", car.controls.lastCommand)
    }

    @Test
    fun carControlBookkeepingIsNotCountedAsSignals() {
        store.onTelemetry(JSONObject().put("updatedAt", 1L).put("soc", 60))
        val before = store.state.value.drive.signalCount
        store.onTelemetry(JSONObject().put("updatedAt", 2L).put("soc", 60).put("carControlGate", "ready"))
        assertEquals(before, store.state.value.drive.signalCount)
    }

    @Test
    fun theHostStateAndDemoResultsReachTheControls() {
        store.onCarControlState(JSONObject().put("available", true).put("enabled", true).put("pinLockedOut", true))
        assertTrue(car.controls.enabled)
        assertTrue(car.controls.pinLockedOut)
        store.onCarControlState(JSONObject().put("available", false).put("enabled", true))
        assertFalse(car.controls.enabled)
        store.onDemoCarControl("flash")
        assertEquals("flash", car.controls.lastCommand)
        assertEquals("simulated", car.controls.lastOutcome)
        assertNull(car.controls.lastDetail)
    }

    @Test
    fun settingsCarryTheUnitsAndPlacardToCarAndDrive() {
        store.onSettings { it.copy(metricUnits = true, tirePlacardPsi = 41.0) }
        assertTrue(car.metricUnits)
        assertEquals(41.0, car.placardPsi, 0.0)
        assertEquals(41.0, store.state.value.drive.tirePlacardPsi, 0.0)
    }
}
