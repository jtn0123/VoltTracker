package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.car.BodyGroup
import com.volttracker.obdpoc.ui.car.CarControl
import com.volttracker.obdpoc.ui.car.CarControlsUi
import com.volttracker.obdpoc.ui.car.CarUiState
import com.volttracker.obdpoc.ui.car.Openings
import com.volttracker.obdpoc.ui.car.aux12Tile
import com.volttracker.obdpoc.ui.car.batterySummary
import com.volttracker.obdpoc.ui.car.carHeadline
import com.volttracker.obdpoc.ui.car.climateTile
import com.volttracker.obdpoc.ui.car.command
import com.volttracker.obdpoc.ui.car.isOn
import com.volttracker.obdpoc.ui.car.label
import com.volttracker.obdpoc.ui.car.lastResultLine
import com.volttracker.obdpoc.ui.car.missingLine
import com.volttracker.obdpoc.ui.car.odometerLine
import com.volttracker.obdpoc.ui.car.open
import com.volttracker.obdpoc.ui.car.statusLine
import com.volttracker.obdpoc.ui.car.tiresTile
import com.volttracker.obdpoc.ui.car.updatedLabel
import com.volttracker.obdpoc.ui.car.windowsTile
import com.volttracker.obdpoc.ui.components.DASH
import com.volttracker.obdpoc.ui.components.PillTone
import com.volttracker.obdpoc.ui.drive.DrivePhase
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.drive.TirePressures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Car tab's status pill, tiles and control states, worked out from the body readings. */
class CarTabLogicTest {
    private val now = 1_000_000_000L
    private val parked =
        DriveUiState(
            connected = true,
            phase = DrivePhase.PARKED,
            aux12Volts = 12.6,
            aux12SocPercent = 86,
            cabinTempF = 70,
            tires = TirePressures(38.0, 38.0, 37.0, 38.0),
            locked = true,
        )
    private val car =
        CarUiState(
            openings = Openings(emptyList()),
            windowsPct = listOf(0, 0, 0, 0),
            acOn = false,
            remoteStartOn = false,
            outsideTempC = 17.8,
            seenAtMs = BodyGroup.entries.associateWith { now - 60_000L },
            nowMs = now,
        )

    @Test
    fun theHeadlineSaysLockedAndClosedOrWhatNeedsLookingAt() {
        assertEquals("Locked · All closed", carHeadline(parked, car).text)
        assertEquals(PillTone.EV, carHeadline(parked, car).tone)
        assertEquals("Unlocked · All closed", carHeadline(parked.copy(locked = false), car).text)
        val low = parked.copy(tires = TirePressures(38.0, 38.0, 37.0, 31.0))
        assertEquals("Check tire pressure", carHeadline(low, car).text)
        assertEquals(PillTone.WARN, carHeadline(low, car).tone)
        val hood = car.copy(openings = Openings(listOf("Driver door", "Hood")))
        assertEquals("Driver door, hood open", carHeadline(parked, hood).text)
        assertEquals("A window is open", carHeadline(parked, car.copy(windowsPct = listOf(40, 0, 0, 0))).text)
        // Doors reported but windows not: only the doors are known to be closed.
        assertEquals("Locked · Doors closed", carHeadline(parked, car.copy(windowsPct = null)).text)
        val nothing = carHeadline(DriveUiState(), CarUiState())
        assertEquals("Lock and doors not reported", nothing.text)
        assertEquals(PillTone.NEUTRAL, nothing.tone)
    }

    @Test
    fun aHigherPlacardMakesTheSameTiresLow() {
        assertEquals("Locked · All closed", carHeadline(parked, car).text)
        assertEquals("Check tire pressure", carHeadline(parked, car.copy(placardPsi = 42.0)).text)
    }

    @Test
    fun updatedAndMissingLinesSayHowLongAgo() {
        assertEquals("Updated 1 min ago", car.updatedLabel())
        assertEquals("Updated just now", car.copy(seenAtMs = mapOf(BodyGroup.LOCK to now - 5_000L)).updatedLabel())
        assertEquals("Updated 2 hr ago", car.copy(seenAtMs = mapOf(BodyGroup.LOCK to now - 7_300_000L)).updatedLabel())
        assertNull(CarUiState().updatedLabel())
        assertEquals("Needs OBDLink adapter", CarUiState().missingLine(BodyGroup.TIRES))
        assertEquals("Not reported", CarUiState().missingLine(BodyGroup.AUX12))
        assertEquals(
            "No reading for 4 min",
            car.copy(seenAtMs = mapOf(BodyGroup.TIRES to now - 240_000L)).missingLine(BodyGroup.TIRES),
        )
    }

    @Test
    fun theOdometerShowsOnceTheCarReportsIt() {
        assertNull(odometerLine(parked))
        val read = parked.copy(odometerMiles = 59_448.8)
        assertEquals("Odometer 59,448 mi", odometerLine(read))
        assertEquals("Odometer 95,673 km", odometerLine(read.copy(metricUnits = true)))
    }

    @Test
    fun theTwelveVoltTileReadsTheMonitorElseThePort() {
        val tile = aux12Tile(parked)
        assertEquals("12.6", tile.value)
        assertEquals(listOf("86% · resting · healthy"), tile.lines)
        assertEquals(0.86f, tile.meter ?: 0f, 1e-6f)
        val port = aux12Tile(parked.copy(aux12Volts = null, aux12SocPercent = null, auxVolts = 12.2))
        assertEquals("12.2", port.value)
        assertEquals(listOf("resting · low", "At the OBD port"), port.lines)
        assertNull(port.meter)
        assertEquals(PillTone.WARN, port.tone)
        assertEquals(DASH, aux12Tile(DriveUiState()).value)
        assertEquals(listOf("Shows when connected"), aux12Tile(DriveUiState()).lines)
        assertEquals(listOf("Not reported"), aux12Tile(DriveUiState(connected = true)).lines)
        assertEquals("86% · plugged in · charging", aux12Tile(parked.copy(phase = DrivePhase.CHARGING)).lines.first())
        // On-car: in Park with the car on, GM's regulated voltage sits at 12.7 V. A fresh gear
        // reading says the car is on, so the tile must not call it "resting".
        assertEquals(
            "100% · car on · healthy",
            aux12Tile(parked.copy(gear = "P", aux12Volts = 12.7, aux12SocPercent = 100)).lines.first(),
        )
    }

    @Test
    fun aMissingBodyReadingSaysWhyOnceTheBusIsHeard() {
        val heard = CarUiState(nowMs = now, seenAtMs = mapOf(BodyGroup.AUX12 to now - 5_000L))
        assertEquals("Shows after a short drive", heard.missingLine(BodyGroup.TIRES))
        assertEquals("Not sent by the car yet", heard.missingLine(BodyGroup.CLIMATE))
        assertEquals("Updates when one opens or locks", heard.missingLine(BodyGroup.DOORS))
        assertEquals("Updates when one opens or locks", heard.missingLine(BodyGroup.WINDOWS))
    }

    @Test
    fun climateShowsCabinOutsideAndTheAirConditioning() {
        val tile = climateTile(parked, car)
        assertEquals("70", tile.value)
        assertEquals("°F cabin", tile.unit)
        assertEquals(listOf("Outside 64°F · A/C off"), tile.lines)
        val metric = climateTile(parked, car.copy(metricUnits = true, acOn = true, remoteStartOn = true))
        assertEquals("21", metric.value)
        assertEquals("°C cabin", metric.unit)
        assertEquals(listOf("Outside 18°C · A/C on", "Remote start running"), metric.lines)
        val none = climateTile(DriveUiState(), CarUiState())
        assertEquals(DASH, none.value)
        assertEquals(listOf("Shows when connected"), none.lines)
        assertEquals(listOf("Needs OBDLink adapter"), climateTile(DriveUiState(connected = true), CarUiState()).lines)
    }

    @Test
    fun tiresReadTheAverageOrTheLowestAgainstThePlacard() {
        val ok = tiresTile(parked, car)
        assertEquals("38", ok.value)
        assertEquals(" psi avg", ok.unit)
        assertEquals(listOf("Placard 38 psi · all normal"), ok.lines)
        val low = tiresTile(parked.copy(tires = TirePressures(38.0, 38.0, 37.0, 31.0)), car)
        assertEquals("31", low.value)
        assertEquals(" psi rear right", low.unit)
        assertEquals(listOf("7 psi below placard (38)"), low.lines)
        assertTrue(low.warnValue)
        val two = tiresTile(parked.copy(tires = TirePressures(30.0, 38.0, 37.0, 31.0)), car)
        assertEquals(" psi front left", two.unit)
        assertEquals(listOf("8 psi below placard (38) · 2 low"), two.lines)
        val metric = tiresTile(parked, car.copy(metricUnits = true))
        assertEquals(" kPa avg", metric.unit)
        assertEquals(listOf("Placard 262 kPa · all normal"), metric.lines)
        assertEquals(listOf("Shows when connected"), tiresTile(DriveUiState(), CarUiState()).lines)
        assertEquals(listOf("Needs OBDLink adapter"), tiresTile(DriveUiState(connected = true), CarUiState()).lines)
    }

    @Test
    fun oldTirePressuresStayAndSayHowOldTheyAre() {
        // The car sends them about once a drive, so an 18-minute-old reading still shows.
        val earlier = car.copy(seenAtMs = mapOf(BodyGroup.TIRES to now - 18 * 60_000L))
        assertEquals(listOf("Placard 38 psi · all normal", "Read 18 min ago"), tiresTile(parked, earlier).lines)
        val low = tiresTile(parked.copy(tires = TirePressures(38.0, 38.0, 37.0, 31.0)), earlier)
        assertEquals(listOf("7 psi below placard (38)", "Read 18 min ago"), low.lines)
    }

    @Test
    fun windowsCountTheOpenOnesWithTheDoorsUnder() {
        assertEquals("Closed", windowsTile(car).value)
        assertEquals(listOf("Doors, hood, hatch closed"), windowsTile(car).lines)
        assertEquals("1 open", windowsTile(car.copy(windowsPct = listOf(0, 0, 50, 1))).value)
        assertEquals("All open", windowsTile(car.copy(windowsPct = listOf(90, 90, 90, 90))).value)
        val hatch = windowsTile(car.copy(openings = Openings(listOf("Hatch"))))
        assertEquals(listOf("Hatch open"), hatch.lines)
        assertEquals(PillTone.WARN, hatch.tone)
        assertEquals(listOf("Needs OBDLink adapter"), windowsTile(CarUiState()).lines)
        assertEquals(listOf("Shows when connected"), windowsTile(CarUiState(), connected = false).lines)
        assertEquals(
            "a reading heard before the link dropped still says how old it is",
            listOf("No reading for 2 min"),
            windowsTile(
                CarUiState(seenAtMs = mapOf(BodyGroup.WINDOWS to 0L), nowMs = 120_000L),
                connected = false,
            ).lines,
        )
    }

    @Test
    fun controlsOfferButtonsOnlyWhenTheGateIsReady() {
        val ready = CarControlsUi(enabled = true, gate = "ready")
        assertTrue(ready.open(connected = true, demo = false))
        assertFalse(ready.open(connected = false, demo = false))
        assertFalse(ready.copy(pinLockedOut = true).open(connected = true, demo = false))
        assertFalse(ready.copy(gate = "not_in_park").open(connected = true, demo = false))
        assertFalse(CarControlsUi().open(connected = true, demo = false))
        assertTrue(CarControlsUi().open(connected = false, demo = true))
    }

    @Test
    fun theControlLineSaysWhyNot() {
        fun line(
            c: CarControlsUi,
            connected: Boolean = true,
        ) = c.statusLine(connected, demo = false).text
        assertTrue(line(CarControlsUi()).startsWith("Car controls are off"))
        assertTrue(line(CarControlsUi(enabled = true, pinLockedOut = true)).startsWith("PIN locked"))
        assertEquals("Connect to the car to use controls.", line(CarControlsUi(enabled = true, gate = "ready"), false))
        assertEquals("Connect to the car to use controls.", line(CarControlsUi(enabled = true)))
        assertEquals("Ready. Each command asks you to confirm.", line(CarControlsUi(enabled = true, gate = "ready")))
        assertEquals("Sending a command…", line(CarControlsUi(enabled = true, gate = "busy")))
        assertEquals(
            "Put the car in Park.",
            line(CarControlsUi(enabled = true, gate = "not_in_park", gateDetail = "Put the car in Park.")),
        )
        assertEquals(
            "Controls are not available right now.",
            line(CarControlsUi(enabled = true, gate = "adapter_not_stn")),
        )
        assertTrue(CarControlsUi().statusLine(connected = false, demo = true).text.startsWith("Demo"))
    }

    @Test
    fun theLastResultNamesTheCommandAndOutcome() {
        assertNull(CarControlsUi().lastResultLine())
        val done = CarControlsUi(lastCommand = "lock", lastOutcome = "confirmed")
        assertEquals("Lock the doors: confirmed by the car.", done.lastResultLine()?.text)
        val refused = CarControlsUi(lastCommand = "locate", lastOutcome = "refused", lastDetail = "Moving.")
        assertEquals("Sound the horn and flash the lights: not sent. Moving.", refused.lastResultLine()?.text)
        assertEquals(PillTone.WARN, refused.lastResultLine()?.tone)
        val demo = CarControlsUi(lastCommand = "flash", lastOutcome = "simulated")
        assertEquals("Flash the lights: simulated in the demo, nothing was sent.", demo.lastResultLine()?.text)
    }

    @Test
    fun buttonsMarkTheCarsStateAndStartBecomesStop() {
        assertTrue(CarControl.LOCK.isOn(parked, car))
        assertFalse(CarControl.UNLOCK.isOn(parked, car))
        assertEquals("remote_start", CarControl.START.command(car))
        val running = car.copy(remoteStartOn = true)
        assertEquals("remote_stop", CarControl.START.command(running))
        assertEquals("Stop", CarControl.START.label(running))
        assertTrue(CarControl.START.isOn(parked, running))
        assertEquals("locate", CarControl.HORN.command(car))
    }

    @Test
    fun batterySummaryUsesWhatWasReported() {
        assertEquals("91% health · cells balanced (19 mV)", batterySummary(91.2, 19.4))
        assertEquals("cells balanced (8 mV)", batterySummary(null, 8.0))
        assertEquals("Health appears after a battery read", batterySummary(null, null))
    }
}
