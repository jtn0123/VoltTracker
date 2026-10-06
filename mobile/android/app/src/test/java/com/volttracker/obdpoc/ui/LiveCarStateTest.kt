package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.car.BodyGroup
import com.volttracker.obdpoc.ui.car.CarMemory
import com.volttracker.obdpoc.ui.car.Opening
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
    fun anAbsentReadingKeepsTheLastOneAndOnlyAStaleClimateClears() {
        store.onTelemetry(body())
        store.onTelemetry(JSONObject().put("updatedAt", 101_000L))
        assertEquals(emptyList<String>(), car.openings?.open)
        assertEquals(listOf(0, 0, 0, 0), car.windowsPct)
        store.onTelemetry(
            body(at = 400_000L) {
                put("doorStatusStaleMs", 150_000).put("windowStaleMs", 150_000).put("climateStaleMs", 150_000)
            },
        )
        // Doors and windows are only sent when they change, so an old report is still the car's state.
        assertEquals(emptyList<String>(), car.openings?.open)
        assertEquals(listOf(0, 0, 0, 0), car.windowsPct)
        assertNull("climate is sent every few seconds: a stale one clears", car.acOn)
        assertEquals("absent staleness keeps the reading", 18.0, car.outsideTempC ?: 0.0, 0.0)
        // When it was heard is kept, for "As of N min ago".
        assertEquals(250_000L, car.seenAtMs[BodyGroup.DOORS])
        store.onTelemetry(body(at = 401_000L) { put("outsideTempStaleMs", 150_000) })
        assertNull("a stale outside temperature is not shown", car.outsideTempC)
    }

    @Test
    fun anUnknownReadingIsNotGuessedAndEachDoorAndWindowStandsAlone() {
        store.onTelemetry(body { put("hoodState", "ajar").put("acState", "auto") })
        assertNull("an unknown hood state is not guessed", car.openings?.states?.get(Opening.HOOD))
        assertEquals(Opening.entries.size - 1, car.openings?.states?.size)
        assertNull(car.acOn)
        store.onTelemetry(JSONObject().put("updatedAt", 101_000L).put("windowFlPct", 33).put("doorFlState", "open"))
        assertEquals("one window reports alone; the rest keep theirs", listOf(33, 0, 0, 0), car.windowsPct)
        assertEquals(listOf("Driver door"), car.openings?.open)

        val fresh = LiveUiStateStore { 0L }
        fresh.onTelemetry(JSONObject().put("updatedAt", 1L).put("windowFlPct", 0).put("hoodState", "closed"))
        assertEquals(listOf(0, null, null, null), fresh.state.value.car.windowsPct)
        assertEquals(
            mapOf(Opening.HOOD to false),
            fresh.state.value.car.openings
                ?.states,
        )
    }

    @Test
    fun oilLifeAndDashWarningsAreMapped() {
        store.onTelemetry(
            body {
                put("oilLifeRemainingPct", 69.0).put("oilLifeStaleMs", 5_000)
                put("dashWarnings", "washer_fluid_low,bulb_reverse").put("dashWarningStaleMs", 1_000)
            },
        )
        assertEquals(69, store.state.value.drive.oilLifePct)
        assertEquals(listOf("washer_fluid_low", "bulb_reverse"), car.dashWarnings)
        assertEquals(95_000L, car.seenAtMs[BodyGroup.OIL])
        assertEquals(99_000L, car.seenAtMs[BodyGroup.WARNINGS])
        store.onTelemetry(body(at = 101_000L) { put("dashWarnings", "") })
        assertEquals("none lit", emptyList<String>(), car.dashWarnings)
        store.onTelemetry(JSONObject().put("updatedAt", 102_000L))
        assertEquals("absent keeps the last report", emptyList<String>(), car.dashWarnings)
    }

    @Test
    fun aRealCarsTiresAndOilAreRememberedButTheDemosNever() {
        val tires: JSONObject.() -> Unit = {
            put("tirePressureFrKpa", 262.0).put("tirePressureRlKpa", 255.0).put("tirePressureRrKpa", 262.0)
            put("oilLifeRemainingPct", 70.0).put("oilLifeStaleMs", 0)
        }
        store.onTelemetry(body(block = tires))
        assertEquals(38.0, car.memory.tires!!.fl, 0.1)
        assertEquals("heard 30 s before the sample", 70_000L, car.memory.tiresAtMs)
        assertEquals(70, car.memory.oilLifePct)
        assertEquals(100_000L, car.memory.oilAtMs)
        store.onTelemetry(JSONObject().put("updatedAt", 200_000L).put("tirePressureFlKpa", 200.0))
        assertEquals("a partial set doesn't replace a whole one", 70_000L, car.memory.tiresAtMs)
        store.onStatus(JSONObject().put("state", "disconnected"))
        assertEquals("kept past a disconnect", 70, car.memory.oilLifePct)

        val demo = LiveUiStateStore { 0L }
        demo.onCarMemory(CarMemory(oilLifePct = 40, oilAtMs = 5L))
        demo.onStatus(JSONObject().put("state", "demo").put("adapter", "Demo stream"))
        demo.onTelemetry(
            body {
                tires(this)
                put("source", "demo")
            },
        )
        assertNull(demo.state.value.car.memory.tires)
        assertEquals(40, demo.state.value.car.memory.oilLifePct)
        demo.onStatus(JSONObject().put("state", "disconnected"))
        assertEquals("the demo ending keeps what was remembered", 40, demo.state.value.car.memory.oilLifePct)
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
    fun aSimulatedUnlockHoldsAgainstTheDemoStreamUntilTheDemoEnds() {
        store.onStatus(JSONObject().put("state", "demo").put("adapter", "Demo stream"))
        store.onTelemetry(body { put("doorLockState", "locked") })
        assertEquals(true, store.state.value.drive.locked)
        store.onDemoCarControl("unlock")
        assertEquals(false, store.state.value.drive.locked)
        store.onTelemetry(body(at = 101_000L) { put("doorLockState", "locked") })
        assertEquals(false, store.state.value.drive.locked)
        store.onDemoCarControl("lock")
        assertEquals(true, store.state.value.drive.locked)
        store.onDemoCarControl("unlock")
        store.onStatus(JSONObject().put("state", "disconnected"))
        store.onTelemetry(body(at = 102_000L) { put("doorLockState", "locked") })
        assertEquals(true, store.state.value.drive.locked)
    }

    @Test
    fun stoppingTheDemoForgetsItsMadeUpBodyButARealDisconnectKeepsTheLastKnown() {
        store.onStatus(JSONObject().put("state", "demo").put("adapter", "Demo stream"))
        store.onTelemetry(body { put("source", "demo") })
        store.onDemoCarControl("flash")
        store.onStatus(JSONObject().put("state", "disconnected"))
        assertNull(car.openings)
        assertNull(car.outsideTempC)
        assertNull(car.controls.lastCommand)

        store.onStatus(JSONObject().put("state", "connected").put("adapter", "OBDLink MX+"))
        store.onTelemetry(body(at = 200_000L))
        store.onStatus(JSONObject().put("state", "disconnected"))
        assertEquals(18.0, car.outsideTempC!!, 0.0)
    }

    @Test
    fun settingsCarryTheUnitsAndPlacardToCarAndDrive() {
        store.onSettings { it.copy(metricUnits = true, tirePlacardPsi = 41.0) }
        assertTrue(car.metricUnits)
        assertEquals(41.0, car.placardPsi, 0.0)
        assertEquals(41.0, store.state.value.drive.tirePlacardPsi, 0.0)
    }
}
