package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.components.PillTone
import com.volttracker.obdpoc.ui.drive.ArcGeometry
import com.volttracker.obdpoc.ui.drive.ChargeEta
import com.volttracker.obdpoc.ui.drive.DriveMode
import com.volttracker.obdpoc.ui.drive.DrivePhase
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.drive.PowerRole
import com.volttracker.obdpoc.ui.drive.TirePressures
import com.volttracker.obdpoc.ui.drive.atReserve
import com.volttracker.obdpoc.ui.drive.aux12Status
import com.volttracker.obdpoc.ui.drive.chargeEta
import com.volttracker.obdpoc.ui.drive.chargeLevelLabel
import com.volttracker.obdpoc.ui.drive.clockLabel
import com.volttracker.obdpoc.ui.drive.driveSubtitle
import com.volttracker.obdpoc.ui.drive.durationLabel
import com.volttracker.obdpoc.ui.drive.energyFlow
import com.volttracker.obdpoc.ui.drive.gasDriving
import com.volttracker.obdpoc.ui.drive.oneDecimal
import com.volttracker.obdpoc.ui.drive.powerRole
import com.volttracker.obdpoc.ui.drive.regenerating
import com.volttracker.obdpoc.ui.drive.shortDurationLabel
import com.volttracker.obdpoc.ui.drive.shownSocPercent
import com.volttracker.obdpoc.ui.drive.tireLow
import com.volttracker.obdpoc.ui.drive.tireStatus
import com.volttracker.obdpoc.ui.drive.totalRangeMiles
import com.volttracker.obdpoc.ui.drive.wholeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** The pure Drive-tab rules behind the Arc ring, the tiles and the energy-flow card. */
class DriveLogicTest {
    @Test
    fun ringMapsRegenIntoTheFirstFiftyDegreesAndDriveIntoTheRest() {
        assertEquals(ArcGeometry.ZERO_DEG, ArcGeometry.kwToDeg(0.0), 0.001f)
        assertEquals(ArcGeometry.END_DEG, ArcGeometry.kwToDeg(120.0), 0.001f)
        assertEquals(ArcGeometry.START_DEG, ArcGeometry.kwToDeg(-60.0), 0.001f)
        // Out-of-range power pins to the ends instead of wrapping.
        assertEquals(ArcGeometry.END_DEG, ArcGeometry.kwToDeg(400.0), 0.001f)
        assertEquals(ArcGeometry.START_DEG, ArcGeometry.kwToDeg(-400.0), 0.001f)
        assertEquals(-105f, ArcGeometry.kwToDeg(-30.0), 0.001f)
        assertEquals(ArcGeometry.START_DEG, ArcGeometry.socToDeg(0.0), 0.001f)
        assertEquals(0f, ArcGeometry.socToDeg(50.0), 0.001f)
        assertEquals(ArcGeometry.END_DEG, ArcGeometry.socToDeg(130.0), 0.001f)
        assertEquals(0f, ArcGeometry.rpmSweep(-5), 0.001f)
        assertEquals(130f, ArcGeometry.rpmSweep(2400), 0.001f)
        assertEquals(260f, ArcGeometry.rpmSweep(9000), 0.001f)
    }

    @Test
    fun powerRoleFollowsWhatTheCarIsDoing() {
        assertEquals(PowerRole.DRIVE, DriveUiState.demo.powerRole)
        assertEquals(PowerRole.REGEN, DriveUiState.demoRegen.powerRole)
        assertEquals(PowerRole.GAS, DriveUiState.demoGas.powerRole)
        assertEquals(PowerRole.IDLE, DriveUiState.demoParked.powerRole)
        assertEquals(PowerRole.IDLE, DriveUiState.demoCharging.powerRole)
        // Regen while the engine runs is still regen (green), not gas.
        assertEquals(PowerRole.REGEN, DriveUiState.demoGas.copy(powerKw = -8.0).powerRole)
        assertTrue(DriveUiState.demoRegen.regenerating)
        assertFalse(DriveUiState.demoParked.copy(powerKw = -5.0).regenerating)
        assertTrue(DriveUiState.demoGas.gasDriving)
        assertFalse(DriveUiState.demoGas.copy(phase = DrivePhase.PARKED).gasDriving)
    }

    @Test
    fun shownSocPrefersTheClusterFigure() {
        assertEquals(0.0, DriveUiState.demoGas.shownSocPercent, 0.0)
        assertEquals(16.0, DriveUiState.demoGas.copy(displayedSocPercent = null).shownSocPercent, 0.0)
    }

    @Test
    fun reserveOnlyWhenTheBatteryIsReallySpent() {
        assertTrue(DriveUiState.demoGas.atReserve)
        assertFalse(DriveUiState.demoGas.copy(evRangeMiles = 12.0, displayedSocPercent = 40.0).atReserve)
        assertFalse(DriveUiState.demo.copy(evRangeMiles = 0.0).atReserve)
    }

    @Test
    fun totalRangeNeedsBothHalves() {
        assertEquals(319.0, DriveUiState.demo.totalRangeMiles ?: -1.0, 0.0)
        assertNull(DriveUiState.demo.copy(gasRangeMiles = null).totalRangeMiles)
        assertNull(DriveUiState.demo.copy(evRangeMiles = null).totalRangeMiles)
    }

    @Test
    fun tyresReadAgainstThePlacardAssumption() {
        assertTrue(tireLow(33.9))
        assertFalse(tireLow(34.0))
        assertEquals("Not reported", tireStatus(null).text)
        assertEquals(PillTone.EV, tireStatus(TirePressures(38.0, 38.0, 37.0, 37.0)).tone)
        assertEquals("RL low", tireStatus(TirePressures(38.0, 38.0, 30.0, 37.0)).text)
        val two = tireStatus(TirePressures(30.0, 38.0, 30.0, 37.0))
        assertEquals("2 tyres low", two.text)
        assertEquals(PillTone.WARN, two.tone)
    }

    @Test
    fun twelveVoltHealthOnlyJudgedAtRest() {
        assertEquals("Not reported", aux12Status(null, DrivePhase.PARKED).text)
        assertEquals("Charging", aux12Status(12.1, DrivePhase.DRIVE).text)
        assertEquals("Charging", aux12Status(13.8, DrivePhase.PARKED).text)
        assertEquals("Healthy", aux12Status(12.6, DrivePhase.PARKED).text)
        assertEquals(PillTone.WARN, aux12Status(12.2, DrivePhase.PARKED).tone)
        assertEquals(PillTone.BAD, aux12Status(11.6, DrivePhase.PARKED).tone)
    }

    @Test
    fun chargeEtaMirrorsTheWebViewGates() {
        assertNull(chargeEta(null, 3.6, null))
        assertNull(chargeEta(50.0, null, null))
        assertNull(chargeEta(50.0, 0.3, null))
        assertNull(chargeEta(100.0, 3.6, null))
        assertNull(chargeEta(-1.0, 3.6, null))
        assertEquals(ChargeEta.NearlyFull, chargeEta(99.5, 3.6, null))
        // 14 kWh × 50 % / 3.5 kW = 2 h.
        assertEquals(ChargeEta.Finish(7_200_000L), chargeEta(50.0, 3.5, null))
        // State of health scales the usable pack: 7 kWh × 50 % / 3.5 kW = 1 h.
        assertEquals(ChargeEta.Finish(3_600_000L), chargeEta(50.0, 3.5, 50.0))
        assertEquals(ChargeEta.Finish(7_200_000L), chargeEta(50.0, 3.5, 150.0))
        // 14 kWh at a 0.5 kW trickle is 28 h: too far out to promise a time.
        assertEquals(ChargeEta.Estimating, chargeEta(0.0, 0.5, null))
    }

    @Test
    fun durationsAndClock() {
        assertEquals("--", durationLabel(null))
        assertEquals("--", durationLabel(0))
        assertEquals("22 min", durationLabel(22 * 60_000L))
        assertEquals("1h 45m", durationLabel(105 * 60_000L))
        assertEquals("99 min", durationLabel(99 * 60_000L))
        assertEquals("2h 05m", durationLabel(125 * 60_000L))
        assertEquals("48m", shortDurationLabel(48 * 60_000L))
        assertEquals("1h 26m", shortDurationLabel(86 * 60_000L))
        assertEquals("11:08 PM", clockLabel(0L, (23 * 60 + 8) * 60_000L, TimeZone.getTimeZone("UTC")))
        assertEquals("9:12", clockLabel((21 * 60 + 12) * 60_000L, zone = TimeZone.getTimeZone("UTC"), short = true))
    }

    @Test
    fun chargeLevelNeverGuessesFromPowerAlone() {
        assertEquals("L2", chargeLevelLabel("AC_2", null))
        assertEquals("L1", chargeLevelLabel("AC_1", 240.0))
        assertEquals("L2", chargeLevelLabel(null, 238.0))
        assertEquals("L1", chargeLevelLabel(null, 118.0))
        assertNull(chargeLevelLabel(null, null))
        assertNull(chargeLevelLabel("DC", 0.0))
    }

    @Test
    fun numberLabels() {
        assertEquals("293", wholeLabel(292.6))
        assertEquals("--", wholeLabel(null))
        assertEquals("18.4", oneDecimal(18.44))
    }

    @Test
    fun energyFlowTellsTheStoryOfEachState() {
        val ev = energyFlow(DriveUiState.demo)
        assertEquals("Battery → drive unit", ev.caption)
        assertTrue(ev.batteryActive && ev.driveActive)
        assertFalse(ev.gridActive || ev.engineActive || ev.regen)
        assertEquals("18 kW", ev.driveValue)
        assertEquals("Unplugged", ev.gridValue)

        val regen = energyFlow(DriveUiState.demoRegen)
        assertEquals("Regenerating", regen.caption)
        assertTrue(regen.regen && regen.batteryActive)

        val gas = energyFlow(DriveUiState.demoGas)
        assertEquals("Engine → drive unit", gas.caption)
        assertTrue(gas.engineActive)
        assertFalse(gas.batteryActive)
        assertEquals("1,860 rpm", gas.engineValue)

        val parked = energyFlow(DriveUiState.demoParked)
        assertEquals("Idle", parked.caption)
        assertEquals("Idle", parked.driveValue)
        assertEquals("Off", parked.engineValue)

        val charging = energyFlow(DriveUiState.demoCharging)
        assertEquals("Grid → battery", charging.caption)
        assertTrue(charging.gridActive && charging.batteryActive)
        assertEquals("3.6 kW", charging.gridValue)
        assertEquals("71%", charging.batteryValue)
    }

    @Test
    fun subtitleSaysWhatTheLinkAndCarAreDoing() {
        assertEquals("No adapter", driveSubtitle(DriveUiState(), detailed = false))
        assertEquals("Live · OBDLink MX+", driveSubtitle(DriveUiState.demo, detailed = false))
        assertEquals("Live · 1 Hz · 42 signals", driveSubtitle(DriveUiState.demo, detailed = true))
        assertEquals("Connected · parked", driveSubtitle(DriveUiState.demoParked, detailed = false))
        assertEquals("Connected · charging · 38 signals", driveSubtitle(DriveUiState.demoCharging, detailed = true))
        assertEquals("Live · 1 Hz", driveSubtitle(DriveUiState.demo.copy(signalCount = 0), detailed = true))
        assertEquals(DriveMode.GAS, DriveUiState.demoGas.mode)
    }
}
