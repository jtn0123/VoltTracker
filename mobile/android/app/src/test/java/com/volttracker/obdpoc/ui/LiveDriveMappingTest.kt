package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.charge.eta
import com.volttracker.obdpoc.ui.drive.ChargeEta
import com.volttracker.obdpoc.ui.drive.DriveMode
import com.volttracker.obdpoc.ui.drive.DrivePhase
import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live telemetry → the redesigned Drive state: ring phase, SW-CAN readings, staleness, session figures. */
class LiveDriveMappingTest {
    private fun sample(
        at: Long,
        block: JSONObject.() -> Unit = {},
    ): JSONObject =
        JSONObject()
            .put("updatedAt", at)
            .put("soc", 63)
            .apply(block)

    private fun LiveUiStateStore.drive() = state.value.drive

    @Test
    fun phaseFollowsTheClassifierThenChargerThenGearAndSpeed() {
        val store = LiveUiStateStore()
        store.onTelemetry(sample(1_000L) { put("vehicleState", "charging") })
        assertEquals(DrivePhase.CHARGING, store.drive().phase)
        store.onTelemetry(sample(2_000L) { put("chargerPowerKw", 3.6) })
        assertEquals(DrivePhase.CHARGING, store.drive().phase)
        store.onTelemetry(sample(3_000L) { put("vehicleState", "driving_ev") })
        assertEquals(DrivePhase.DRIVE, store.drive().phase)
        store.onTelemetry(sample(4_000L) { put("vehicleState", "parked") })
        assertEquals(DrivePhase.PARKED, store.drive().phase)
        store.onTelemetry(sample(5_000L) { put("prndlState", "D") })
        assertEquals(DrivePhase.DRIVE, store.drive().phase)
        store.onTelemetry(sample(6_000L) { put("prndlState", "P") })
        assertEquals(DrivePhase.PARKED, store.drive().phase)
        store.onTelemetry(sample(7_000L) { put("speedKph", 30) })
        assertEquals(DrivePhase.DRIVE, store.drive().phase)
        // Nothing decisive keeps the phase.
        store.onTelemetry(sample(8_000L))
        assertEquals(DrivePhase.DRIVE, store.drive().phase)
    }

    @Test
    fun swcanReadingsMapAndConvertUnits() {
        val store = LiveUiStateStore()
        store.onTelemetry(
            sample(1_000L) {
                put("displayedSocPct", 61.5)
                put("fuelLevelPct", 62)
                put("fuelRangeKm", 471.0)
                put("aux12vVoltage", 12.6)
                put("aux12vSocPct", 86)
                put("aux12vCurrentA", -0.4)
                put("tirePressureFlKpa", 260.0)
                put("tirePressureFrKpa", 262.0)
                put("tirePressureRlKpa", 255.0)
                put("tirePressureRrKpa", 258.0)
                put("doorLockState", "LOCKED")
                put("cabinTempEstC", 21.0)
                put("motorTempC", 60)
                put("inverterTempC", 48)
                put("cellBalanceMv", 19)
                put("minCellVoltage", 3.893)
                put("maxCellVoltage", 3.912)
                put("minCellNumber", 47)
                put("cellVoltages", JSONArray().put(3.9).put(JSONObject.NULL).put(3.91))
                put("cycleEvDistanceKm", 64.0)
                put("cycleFuelDistanceKm", 36.0)
                put("cycleFuelUsedL", 3.5)
            },
        )
        val d = store.drive()
        assertEquals(61.5, d.displayedSocPercent ?: 0.0, 0.0)
        assertEquals(62.0, d.fuelPercent ?: 0.0, 0.0)
        assertEquals(292.7, d.gasRangeMiles ?: 0.0, 0.1)
        assertEquals(12.6, d.aux12Volts ?: 0.0, 0.0)
        assertEquals(86, d.aux12SocPercent)
        assertEquals(-0.4, d.aux12Amps ?: 0.0, 0.0)
        assertEquals(37.7, d.tires?.fl ?: 0.0, 0.1)
        assertEquals(37.0, d.tires?.rl ?: 0.0, 0.1)
        assertEquals(true, d.locked)
        assertEquals(69, d.cabinTempF)
        assertEquals(140, d.motorTempF)
        assertEquals(118, d.inverterTempF)
        assertEquals(19.0, d.cellSpreadMv ?: 0.0, 0.0)
        assertEquals(47, d.minCellNumber)
        assertEquals(listOf(3.9, null, 3.91), d.cellVoltages)
        assertEquals(64, d.cycleEvPercent)
        // 100 km = 62.1 mi on 3.5 L = 0.925 gal.
        assertEquals(67.2, d.cycleMpg ?: 0.0, 0.1)
    }

    @Test
    fun staleBroadcastsReadAsNotReportedWhileAbsentOnesKeepTheirValue() {
        val store = LiveUiStateStore()
        store.onTelemetry(
            sample(1_000L) {
                put("aux12vVoltage", 12.6)
                put("fuelRangeKm", 471.0)
                put("doorLockState", "unlocked")
                listOf("Fl", "Fr", "Rl", "Rr").forEach { put("tirePressure${it}Kpa", 260.0) }
            },
        )
        store.onTelemetry(sample(2_000L))
        assertEquals(12.6, store.drive().aux12Volts ?: 0.0, 0.0)
        assertEquals(false, store.drive().locked)
        assertTrue(store.drive().tires != null)

        store.onTelemetry(
            sample(3_000L) {
                put("aux12vStaleMs", 200_000)
                put("rangeStaleMs", 200_000)
                put("doorLockStaleMs", 200_000)
                put("tirePressureStaleMs", 200_000)
            },
        )
        val d = store.drive()
        assertNull(d.aux12Volts)
        assertNull(d.gasRangeMiles)
        assertNull(d.locked)
        assertNull(d.tires)
    }

    @Test
    fun partialTyresAndUnknownLockValuesNeverGuess() {
        val store = LiveUiStateStore()
        store.onTelemetry(sample(1_000L) { put("tirePressureFlKpa", 260.0).put("doorLockState", "ajar") })
        assertNull(store.drive().tires)
        assertNull(store.drive().locked)
    }

    @Test
    fun cycleSplitNeedsDistanceAndMeasurableFuel() {
        val store = LiveUiStateStore()
        store.onTelemetry(
            sample(1_000L) {
                put("cycleEvDistanceKm", 0.0)
                put("cycleFuelDistanceKm", 0.0)
                put("cycleFuelUsedL", 0.05)
            },
        )
        assertNull(store.drive().cycleEvPercent)
        assertNull(store.drive().cycleMpg)
    }

    @Test
    fun chargingFillsTheChargeFieldsAndDrivingClearsThem() {
        val store = LiveUiStateStore()
        store.onTelemetry(
            sample(1_000L) {
                put("vehicleState", "charging")
                put("chargerPowerKw", 3.5)
                put("chargerAcVoltage", 240.0)
                put("chargerAcCurrentA", 15.0)
                put("soc", 50)
            },
        )
        var d = store.drive()
        assertEquals(DrivePhase.CHARGING, d.phase)
        assertEquals(3.5, d.chargeKw, 0.0)
        assertEquals("L2", d.chargeLevel)
        assertEquals(240.0, d.chargeAcVolts ?: 0.0, 0.0)
        assertEquals(15.0, d.chargeAcAmps ?: 0.0, 0.0)
        assertEquals(50.0, d.chargeFromSoc ?: 0.0, 0.0)
        assertEquals(1_000L, d.chargeStartedAtMs)
        assertEquals(ChargeEta.Finish(7_200_000L), d.chargeEta)
        assertEquals(1_000L, d.sampleAtMs)

        store.onTelemetry(sample(2_000L) { put("vehicleState", "driving_ev").put("chargerPowerKw", 0.0) })
        d = store.drive()
        assertEquals(0.0, d.chargeKw, 0.0)
        assertNull(d.chargeLevel)
        assertNull(d.chargeAcVolts)
        assertNull(d.chargeEta)
    }

    @Test
    fun driveTimesTheChargeWithTheKnownPackHealthLikeTheChargeTab() {
        val store = LiveUiStateStore()
        store.onTelemetry(sample(1_000L) { put("sohPct", 80.0) })
        store.onTelemetry(
            sample(2_000L) {
                put("vehicleState", "charging")
                put("chargerPowerKw", 3.5)
                put("soc", 50)
            },
        )
        // 14 kWh usable × 80% health × half the pack, at 3.5 kW: 96 min, not the 120 of a new pack.
        val eta = store.drive().chargeEta as ChargeEta.Finish
        assertEquals(5_760_000.0, eta.remainingMs.toDouble(), 1_000.0)
        assertEquals(store.state.value.charge.eta, store.drive().chargeEta)
    }

    @Test
    fun engineFlagRidesAlongEachPowerSample() {
        val store = LiveUiStateStore()
        store.onTelemetry(sample(1_000L) { put("powerKw", 10.0).put("vehicleState", "driving_ev") })
        store.onTelemetry(sample(2_000L) { put("powerKw", 20.0).put("vehicleState", "driving_gas") })
        store.onTelemetry(sample(3_000L) { put("powerKw", 20.0).put("rpm", 1500) })
        val d = store.drive()
        assertEquals(listOf(false, true, true), d.gasTrace)
        assertEquals(DriveMode.GAS, d.mode)
    }

    @Test
    fun aParkedCarChargingOffTheEngineReadsAsEngineOn() {
        // On-car: at 13% the engine ran at 1370 rpm in Park and the classifier said "ready".
        val store = LiveUiStateStore()
        store.onTelemetry(sample(1_000L) { put("vehicleState", "ready").put("rpm", 1370).put("speedKph", 0) })
        assertEquals(DriveMode.GAS, store.drive().mode)
        store.onTelemetry(sample(2_000L) { put("vehicleState", "ready").put("rpm", 0).put("speedKph", 0) })
        assertEquals(DriveMode.EV, store.drive().mode)
    }

    @Test
    fun signalCountSkipsBookkeepingStalenessAndNulls() {
        val store = LiveUiStateStore()
        store.onTelemetry(
            JSONObject()
                .put("updatedAt", 1_000L)
                .put("source", "obd")
                .put("vehicleState", "parked")
                .put("soc", 63)
                .put("packVoltage", 356.4)
                .put("socStaleMs", 10)
                .put("rpm", JSONObject.NULL),
        )
        assertEquals(2, store.drive().signalCount)
    }

    @Test
    fun aFreshLinkRestartsTheSessionFigures() {
        val store = LiveUiStateStore()
        store.onStatus(JSONObject().put("state", "connected").put("adapter", "OBDLink MX+"))
        for (i in 0..5) {
            store.onTelemetry(
                sample(1_000L + i * 1_000L) {
                    put("vehicleState", "driving_ev")
                    put("powerKw", 36.0)
                    put("latitude", 42.0 + i * 0.001)
                    put("longitude", -83.0)
                    put("speedKph", 60)
                },
            )
        }
        assertEquals(0.34, store.drive().tripMiles, 0.01)
        assertEquals("OBDLink MX+", store.drive().adapterLabel)
        assertEquals("0 min", store.drive().tripDuration)

        store.onStatus(JSONObject().put("state", "disconnected"))
        store.onStatus(JSONObject().put("state", "connected").put("adapter", "OBDLink MX+"))
        store.onTelemetry(sample(100L) { put("vehicleState", "driving_ev") })
        assertEquals(0.0, store.drive().tripMiles, 0.0)
        assertFalse(store.drive().lastDrive != null)
    }

    @Test
    fun drivePrefsLandInDriveAndSettingsState() {
        val store = LiveUiStateStore()
        store.onSettings { it.copy(driveDetailed = true, driveEnergyFlow = false, homeRate = 0.14) }
        assertTrue(store.state.value.drive.detailed)
        assertEquals(0.14, store.state.value.drive.electricityRate, 0.0)
        assertFalse(store.state.value.settings.driveEnergyFlow)
    }
}
