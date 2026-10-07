package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.GuidedTestStatus
import com.volttracker.obdpoc.ui.car.BodyGroup
import com.volttracker.obdpoc.ui.car.CarControl
import com.volttracker.obdpoc.ui.car.CarControlsUi
import com.volttracker.obdpoc.ui.car.CarMemory
import com.volttracker.obdpoc.ui.car.CarUiState
import com.volttracker.obdpoc.ui.car.Opening
import com.volttracker.obdpoc.ui.car.Openings
import com.volttracker.obdpoc.ui.car.aux12Tile
import com.volttracker.obdpoc.ui.car.batterySummary
import com.volttracker.obdpoc.ui.car.carHeadline
import com.volttracker.obdpoc.ui.car.climateTile
import com.volttracker.obdpoc.ui.car.command
import com.volttracker.obdpoc.ui.car.dashWarningLabel
import com.volttracker.obdpoc.ui.car.dashWarningsLine
import com.volttracker.obdpoc.ui.car.doorsTile
import com.volttracker.obdpoc.ui.car.guidedStepLabel
import com.volttracker.obdpoc.ui.car.guidedTestLine
import com.volttracker.obdpoc.ui.car.isOn
import com.volttracker.obdpoc.ui.car.label
import com.volttracker.obdpoc.ui.car.lastResultLine
import com.volttracker.obdpoc.ui.car.missingLine
import com.volttracker.obdpoc.ui.car.odometerLine
import com.volttracker.obdpoc.ui.car.oilTile
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
import com.volttracker.obdpoc.ui.drive.ToneText
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
            openings = Openings.allClosed,
            windowsPct = listOf(0, 0, 0, 0),
            acOn = false,
            dashWarnings = emptyList(),
            dashWarningsComplete = true,
            remoteStartOn = false,
            outsideTempC = 17.8,
            seenAtMs = BodyGroup.entries.associateWith { now - 60_000L },
            nowMs = now,
        )

    @Test
    fun theHeadlineSaysLockedAndClosedOrWhatNeedsLookingAt() {
        assertEquals("Lock sent · All closed", carHeadline(parked, car).text)
        assertEquals(PillTone.EV, carHeadline(parked, car).tone)
        assertEquals("Unlock sent · All closed", carHeadline(parked.copy(locked = false), car).text)
        val low = parked.copy(tires = TirePressures(38.0, 38.0, 37.0, 31.0))
        assertEquals("Check tire pressure", carHeadline(low, car).text)
        assertEquals(PillTone.WARN, carHeadline(low, car).tone)
        val hood = car.copy(openings = opened(Opening.DRIVER_DOOR, Opening.HOOD))
        assertEquals("Driver door, hood open", carHeadline(parked, hood).text)
        assertEquals("A window is open", carHeadline(parked, car.copy(windowsPct = listOf(40, 0, 0, 0))).text)
        // Doors reported but windows not: only the doors are known to be closed.
        assertEquals("Lock sent · Doors closed", carHeadline(parked, car.copy(windowsPct = null)).text)
        assertEquals(
            "Lock sent · Doors closed",
            carHeadline(parked, car.copy(windowsPct = listOf(0, null, null, null))).text,
        )
        // Not every door has reported yet: closed isn't claimed.
        val partial = car.copy(openings = Openings(mapOf(Opening.DRIVER_DOOR to false)))
        assertEquals("Lock sent", carHeadline(parked, partial).text)
        val washer = car.copy(dashWarnings = listOf("washer_fluid_low"))
        assertEquals(ToneText("Washer fluid low", PillTone.WARN), carHeadline(parked, washer))
        val two = car.copy(dashWarnings = listOf("washer_fluid_low", "bulb_reverse"))
        assertEquals("2 dash warnings", carHeadline(parked, two).text)
        val nothing = carHeadline(DriveUiState(), CarUiState())
        assertEquals("Lock and doors not reported", nothing.text)
        assertEquals(PillTone.NEUTRAL, nothing.tone)
    }

    @Test
    fun aHigherPlacardMakesTheSameTiresLow() {
        assertEquals("Lock sent · All closed", carHeadline(parked, car).text)
        assertEquals("Check tire pressure", carHeadline(parked, car.copy(placardPsi = 42.0)).text)
    }

    @Test
    fun updatedAndMissingLinesSayHowLongAgo() {
        assertEquals("Updated 1 min ago", car.updatedLabel())
        assertEquals("Updated just now", car.copy(seenAtMs = mapOf(BodyGroup.LOCK to now - 5_000L)).updatedLabel())
        assertEquals("Updated 2 hr ago", car.copy(seenAtMs = mapOf(BodyGroup.LOCK to now - 7_300_000L)).updatedLabel())
        assertEquals(
            "Updated 1 day ago",
            car.copy(seenAtMs = mapOf(BodyGroup.LOCK to now - 30 * HOUR_MS)).updatedLabel(),
        )
        assertEquals(
            "Updated 3 days ago",
            car.copy(seenAtMs = mapOf(BodyGroup.LOCK to now - 72 * HOUR_MS)).updatedLabel(),
        )
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
        assertEquals("Updates when a window moves", heard.missingLine(BodyGroup.WINDOWS))
        assertEquals("Not sent by the car yet", heard.missingLine(BodyGroup.OIL))
        assertEquals("Not sent by the car yet", heard.missingLine(BodyGroup.WARNINGS))
    }

    @Test
    fun climateLeadsWithTheAirConditioningThenFanAndTemperatures() {
        // No A/C request and no compressor power read: not asked for, but the compressor may run.
        val unasked = climateTile(parked, car)
        assertEquals("A/C", unasked.value)
        assertEquals(" not requested", unasked.unit)
        assertEquals(listOf("Cabin 70°F · Outside 64°F"), unasked.lines)
        assertEquals(PillTone.NEUTRAL, unasked.tone)
        // "A/C off" only once the compressor reads no power.
        val off = climateTile(parked, car.copy(acKw = 0.0))
        assertEquals("A/C off", off.value)
        assertEquals("", off.unit)
        val on =
            climateTile(
                parked,
                car.copy(acOn = true, fanPct = 40, acKw = 1.24, metricUnits = true, remoteStartOn = true),
            )
        assertEquals("A/C on", on.value)
        assertEquals(" 1.2 kW", on.unit)
        assertEquals(listOf("Fan 40%", "Cabin 21°C · Outside 18°C", "Remote start running"), on.lines)
        assertEquals(PillTone.EV, on.tone)
        assertEquals("Fan off", climateTile(parked, car.copy(fanPct = 0)).lines.first())
        // Asked for, with the compressor idle or not reported: "requested", not "on".
        val idle = climateTile(parked, car.copy(acOn = true, acKw = 0.0))
        assertEquals("A/C", idle.value)
        assertEquals(" requested", idle.unit)
        assertEquals("Compressor idle", idle.lines.first())
        val unreported = climateTile(parked, car.copy(acOn = true, acKw = null))
        assertEquals(" requested", unreported.unit)
        assertFalse(unreported.lines.contains("Compressor idle"))
        // The compressor drawing power with no A/C request (it cools the battery too) isn't "A/C off".
        val battery = climateTile(parked, car.copy(acOn = false, acKw = 0.8))
        assertEquals("Compressor on", battery.value)
        assertEquals(" 0.8 kW", battery.unit)
        assertEquals("A/C not requested", battery.lines.first())
        assertFalse(climateTile(parked, car.copy(acOn = false, acKw = 0.0)).lines.contains("A/C not requested"))
        // Before the A/C reports, the cabin temperature leads as it always did.
        val cabin = climateTile(parked, car.copy(acOn = null, fanPct = 30))
        assertEquals("70", cabin.value)
        assertEquals("°F cabin", cabin.unit)
        assertEquals(listOf("Outside 64°F"), cabin.lines)
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
    fun tiresFromAnEarlierDriveShowUntilTheCarSendsNewOnes() {
        val remembered =
            car.copy(memory = CarMemory(tires = TirePressures(36.0, 36.0, 36.0, 36.0), tiresAtMs = now - 50 * HOUR_MS))
        val tile = tiresTile(parked.copy(tires = null), remembered)
        assertEquals("36", tile.value)
        assertEquals(
            "an old reading is not a verdict on today's tyres",
            listOf("Placard 38 psi", "Read 2 days ago"),
            tile.lines,
        )
        assertEquals(PillTone.NEUTRAL, tile.tone)
        // This drive's pressures win over the remembered ones.
        assertEquals("38", tiresTile(parked, remembered).value)
    }

    @Test
    fun aTireSensorTheCarFlagsInvalidIsNamedInsteadOfAPressure() {
        val one = tiresTile(parked.copy(tires = null), car.copy(tireSensorsInvalid = listOf("fl")))
        assertEquals(DASH, one.value)
        assertEquals(listOf("Front left: no valid reading"), one.lines)
        val remembered =
            car.copy(
                memory =
                    CarMemory(
                        tires = TirePressures(36.0, 36.0, 36.0, 36.0),
                        tiresAtMs =
                            now - HOUR_MS,
                    ),
            )
        assertEquals(
            "no remembered pressure stands in for a sensor that is down now",
            listOf("Front left, rear right: no valid reading"),
            tiresTile(parked.copy(tires = null), remembered.copy(tireSensorsInvalid = listOf("fl", "rr"))).lines,
        )
    }

    @Test
    fun oilLifeShowsWhatIsLeftAndTurnsAmberNearAChange() {
        val tile = oilTile(parked.copy(oilLifePct = 72), car)
        assertEquals("72", tile.value)
        assertEquals("%", tile.unit)
        assertEquals(listOf("Engine oil left"), tile.lines)
        assertEquals(0.72f, tile.meter ?: 0f, 1e-6f)
        assertEquals(PillTone.NEUTRAL, tile.tone)
        val low = oilTile(parked.copy(oilLifePct = 12), car)
        assertEquals(listOf("Plan an oil change"), low.lines)
        assertEquals(PillTone.WARN, low.tone)
        assertTrue(low.warnValue)
        val remembered = car.copy(memory = CarMemory(oilLifePct = 70, oilAtMs = now - 3 * HOUR_MS))
        assertEquals(listOf("Engine oil left", "Read 3 hr ago"), oilTile(parked, remembered).lines)
        assertEquals(DASH, oilTile(parked, car).value)
        assertEquals(listOf("Shows when connected"), oilTile(DriveUiState(), CarUiState()).lines)
    }

    @Test
    fun dashWarningsNameTheLightsThatAreOn() {
        assertEquals(ToneText("None on", PillTone.EV), dashWarningsLine(car))
        val lit = car.copy(dashWarnings = listOf("washer_fluid_low", "bulb_left_brake"))
        assertEquals(
            ToneText("Washer fluid low · Left brake light out", PillTone.WARN),
            dashWarningsLine(lit),
        )
        val old = car.copy(seenAtMs = mapOf(BodyGroup.WARNINGS to now - 10 * 60_000L))
        assertEquals("None on · As of 10 min ago", dashWarningsLine(old).text)
        assertEquals(
            "a clear report from some of the broadcasts is not an all-clear",
            ToneText("None seen so far", PillTone.NEUTRAL),
            dashWarningsLine(car.copy(dashWarningsComplete = false)),
        )
        assertEquals("Needs OBDLink adapter", dashWarningsLine(CarUiState()).text)
        assertEquals("Shows when connected", dashWarningsLine(CarUiState(), connected = false).text)
        assertEquals("Oil pressure low", dashWarningLabel("oil_pressure_low"))
        assertEquals("Some new light", dashWarningLabel("some_new_light"))
    }

    @Test
    fun windowsSayWhichAreDownAndHowFar() {
        assertEquals("Closed", windowsTile(car).value)
        assertEquals(listOf("All four up"), windowsTile(car).lines)
        val one = windowsTile(car.copy(windowsPct = listOf(0, 0, 50, 1)))
        assertEquals("1 open", one.value)
        assertEquals(listOf("Rear left part way down", "Others up"), one.lines)
        assertEquals(PillTone.WARN, one.tone)
        assertTrue("an open window shows amber, like an open door", one.warnValue)
        assertFalse(windowsTile(car).warnValue)
        val all = windowsTile(car.copy(windowsPct = listOf(100, 100, 100, 100)))
        assertEquals("All open", all.value)
        assertEquals("Driver fully down", all.lines.first())
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
    fun aWindowThatReportedAloneShowsWithTheRestUnknown() {
        // On-car 10-06: the driver window reports by itself; the others send filler.
        val driverDown = windowsTile(car.copy(windowsPct = listOf(33, null, null, null)))
        assertEquals("1 open", driverDown.value)
        assertEquals(listOf("Driver part way down", "Others not reported"), driverDown.lines)
        val driverUp = windowsTile(car.copy(windowsPct = listOf(0, null, null, null)))
        assertEquals("Closed", driverUp.value)
        assertEquals(listOf("Driver up", "Others not reported"), driverUp.lines)
        val old = car.copy(seenAtMs = mapOf(BodyGroup.WINDOWS to now - 10 * 60_000L))
        assertEquals(listOf("All four up", "As of 10 min ago"), windowsTile(old).lines)
    }

    @Test
    fun doorsCountWhatIsOpenAndSayHowMuchIsKnown() {
        val closed = doorsTile(car)
        assertEquals("Closed", closed.value)
        assertEquals(listOf("Doors, hood and hatch"), closed.lines)
        val hatch = doorsTile(car.copy(openings = opened(Opening.HATCH)))
        assertEquals("1 open", hatch.value)
        assertEquals(listOf("Hatch open"), hatch.lines)
        assertEquals(PillTone.WARN, hatch.tone)
        assertTrue(hatch.warnValue)
        val partial = doorsTile(car.copy(openings = Openings(mapOf(Opening.DRIVER_DOOR to false))))
        assertEquals("Closed", partial.value)
        assertEquals(listOf("1 of 6 reported"), partial.lines)
        assertEquals(listOf("Needs OBDLink adapter"), doorsTile(CarUiState()).lines)
        val old = car.copy(seenAtMs = mapOf(BodyGroup.DOORS to now - 3 * HOUR_MS))
        assertEquals(listOf("Doors, hood and hatch", "As of 3 hr ago"), doorsTile(old).lines)
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

    @Test
    fun guidedTestRowSaysWhatItIsAndHowTheLastOneEnded() {
        assertEquals("Connect to the car to run it", guidedTestLine(false, GuidedTestStatus()))
        assertEquals(
            "Spoken steps round the car, off and on, then a drive (about 35 min)",
            guidedTestLine(true, GuidedTestStatus()),
        )
        assertEquals(
            "Finished · the results are in the session log",
            guidedTestLine(true, GuidedTestStatus(ended = "Finished")),
        )
        assertEquals(
            "This needs an OBDLink adapter",
            guidedTestLine(true, GuidedTestStatus(ended = "This needs an OBDLink adapter")),
        )
        assertEquals("Starting", guidedStepLabel(GuidedTestStatus(running = true, steps = 38)))
        assertEquals("Step 6 of 38", guidedStepLabel(GuidedTestStatus(running = true, step = 6, steps = 38)))
    }

    private fun opened(vararg open: Opening) = Openings(Opening.entries.associateWith { it in open })

    private companion object {
        const val HOUR_MS = 3_600_000L
    }
}
