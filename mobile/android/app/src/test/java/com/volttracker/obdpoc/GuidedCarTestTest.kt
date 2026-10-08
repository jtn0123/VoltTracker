package com.volttracker.obdpoc

import com.volttracker.obdpoc.engine.GuidedCarScript
import com.volttracker.obdpoc.engine.GuidedCarTest
import com.volttracker.obdpoc.engine.GuidedExpect
import com.volttracker.obdpoc.engine.GuidedPhase
import com.volttracker.obdpoc.engine.GuidedStep
import com.volttracker.obdpoc.engine.GuidedStepKind
import com.volttracker.obdpoc.engine.ParkedDetector
import com.volttracker.obdpoc.engine.SwcanListenRunner
import com.volttracker.obdpoc.engine.SwitchBenchmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections

/** The spoken guided car test against a fake car, adapter and voice, in fake time. */
class GuidedCarTestTest {
    private val clock = FakeClock()
    private val io = FakeCarIo(clock)
    private val bus = FakeBus(io)
    private val voice = FakeVoice(clock)
    private val statuses: MutableList<GuidedTestStatus> = Collections.synchronizedList(mutableListOf())

    private val lockStep =
        GuidedStep(
            "lock",
            GuidedStepKind.EVENT,
            "Lock the doors.",
            30_000L,
            GuidedExpect.isOneOf(SwcanField.LOCK_STATE, "locked"),
        )
    private val seatStep = GuidedStep("seat", GuidedStepKind.TIMED, "Press the seat heater.", 8_000L)
    private val confirmStep = GuidedStep("intro", GuidedStepKind.CONFIRM, "Can you hear me?", 90_000L)
    private val powerOff =
        GuidedStep(
            "power_off",
            GuidedStepKind.POWER_OFF,
            "Turn the car off.",
            60_000L,
            GuidedExpect.isOneOf(SwcanField.POWER_MODE, "off"),
        )
    private val chargePort =
        GuidedStep(
            "charge_port",
            GuidedStepKind.TIMED,
            "Open the charge port.",
            5_000L,
            phase = GuidedPhase.PARKED_OR_OFF,
        )
    private val drive =
        GuidedStep(
            "drive",
            GuidedStepKind.DRIVE,
            "Drive.",
            35 * 60_000L,
            GuidedExpect.isOneOf(SwcanField.POWER_MODE, "off"),
            GuidedPhase.DRIVING,
        )
    private val shortDrive =
        GuidedStep(
            "drive",
            GuidedStepKind.DRIVE,
            "Drive.",
            2 * 60_000L,
            GuidedExpect.isOneOf(SwcanField.POWER_MODE, "off"),
            GuidedPhase.DRIVING,
        )

    private val seatPress =
        GuidedStep(
            "seat_press",
            GuidedStepKind.EVENT,
            "Press the seat heater button once.",
            12_000L,
            GuidedExpect.changes(SwcanField.SEAT_HEAT_FL, unseen = 0.0),
        )
    private val portOpen =
        GuidedStep(
            "charge_port_open",
            GuidedStepKind.EVENT,
            "Open the charge port door.",
            45_000L,
            GuidedExpect.isOneOf(SwcanField.CHARGE_PORT_DOOR, "open"),
        )
    private val portClose =
        GuidedStep(
            "charge_port_close",
            GuidedStepKind.EVENT,
            "Close the charge port door.",
            30_000L,
            GuidedExpect.becomes(SwcanField.CHARGE_PORT_DOOR, "closed"),
        )
    private val fuelOpen =
        GuidedStep(
            "fuel_door_open",
            GuidedStepKind.EVENT,
            "Press the fuel door button.",
            45_000L,
            GuidedExpect.isOneOf(SwcanField.REFUEL_STATE, "ready"),
        )
    private val fuelClose =
        GuidedStep(
            "fuel_door_close",
            GuidedStepKind.EVENT,
            "Open the fuel door and close it.",
            60_000L,
            GuidedExpect.becomes(SwcanField.REFUEL_STATE, "idle"),
        )

    private fun test(vararg steps: GuidedStep) =
        GuidedCarTest(
            object : SwcanListenRunner.Io by io {
                override fun openVoice() = voice
            },
            bus,
            fullSteps = steps.toList(),
            clock = clock::now,
        ) { statuses += it }

    private fun GuidedCarTest.started(): GuidedCarTest {
        request(GuidedCarTest.Op.START)
        return this
    }

    private fun steps() = io.all("guided_step")

    /** How long after the instruction was said the step heard what it waited for. */
    private fun heardAfterSaid(step: Map<String, String>) =
        step.getValue("heardMs").toLong() - step.getValue("saidMs").toLong()

    private fun captures() = io.all("guided_capture")

    @Test
    fun aStepThatHearsWhatItWaitsForSaysSoAndMovesOn() {
        voice.onSay = { if (it == "Lock the doors.") io.broadcast(clock.now + 3_000L, LOCKED) }
        val test = test(lockStep, seatStep).started()

        assertTrue(test.runNext())

        val step = steps().single()
        assertEquals("heard", step["result"])
        assertEquals("1", step["attempt"])
        assertEquals("complete", step["capture"])
        // Said at the first line the monitor printed, heard 3 s later, then a 5 s tail.
        assertEquals("250", step["saidMs"])
        assertEquals("3250", step["heardMs"])
        assertEquals("8250", step["listenMs"])
        assertEquals(listOf("Lock the doors.", "Got it."), voice.said)
        assertEquals("Heard it", statuses.last().lastResult)
        assertEquals("locked", bus.recorded.first { it.field == SwcanField.LOCK_STATE }.value)
        assertEquals(listOf("<enter>", "STM", "<leave>"), io.commands)
        assertTrue(test.isActive())
        // What was heard is in the step's capture chunk, the lock frame's payload among the changes.
        val chunk = captures().single()
        assertEquals("lock", chunk["step"])
        assertEquals("1", chunk["chunk"])
        assertTrue(chunk.getValue("changes").contains("0C414040 00 01 00 01"))
    }

    @Test
    fun theInstructionIsSaidOnceTheBusIsHeardAndTheStepsTimeStartsWhenItIsSpoken() {
        val test = test(lockStep).started()

        test.runNext()

        val step = steps().single()
        assertEquals("missed", step["result"])
        // "Lock the doors." takes 0.75 s to say, from 0.25 s in.
        assertEquals("250", step["saidMs"])
        assertEquals("750", step["spokenMs"])
        assertEquals("30750", step["listenMs"])
        assertEquals("Didn't hear that one. Moving on.", voice.said[1])
    }

    @Test
    fun theLastStepFinishesTheTest() {
        val test = test(seatStep).started()

        test.runNext()

        assertEquals("recorded", steps().single()["result"])
        assertFalse(test.isActive())
        assertEquals("Finished", statuses.last().ended)
        assertFalse(statuses.last().running)
        assertEquals("That's the whole test. Thank you.", voice.said.last())
        assertTrue(voice.closed)
        assertEquals("Finished", io.event("guided_test_end")!!["ended"])
        assertFalse("only the drive ends the session", test.takeSessionEnd())
    }

    @Test
    fun skipAndStopCutWhatIsBeingSaid() {
        val test = test(lockStep, seatStep, seatStep).started()
        io.onTick = { at -> if (at == 2_000L) test.request(GuidedCarTest.Op.SKIP) }

        test.runNext()
        assertEquals("skipped", steps().last()["result"])
        assertEquals("Skipped", statuses.last().lastResult)
        assertEquals(1, voice.interrupts)

        io.onTick = { test.request(GuidedCarTest.Op.STOP) }
        assertTrue(test.runNext())

        assertEquals("stopped", steps().last()["result"])
        assertEquals("Stopped", statuses.last().ended)
        // Cut on the stop, and again before the last word.
        assertEquals(3, voice.interrupts)
        assertEquals("Test stopped.", voice.said.last())
        assertFalse(test.isActive())
        assertEquals(2, io.commands.count { it == "<leave>" })
        assertFalse("a stopped test does not run again", test.runNext())
    }

    @Test
    fun aStartWhileATestRunsIsIgnored() {
        val test = test(seatStep, seatStep).started()
        test.runNext()

        test.request(GuidedCarTest.Op.START)
        test.runNext()

        assertEquals("Finished", statuses.last().ended)
        assertFalse(test.runNext())
        assertEquals(1, io.all("guided_test_start").size)
    }

    @Test
    fun aStartAfterAStopStillStands() {
        val test = test(seatStep, seatStep).started()
        test.runNext()
        test.request(GuidedCarTest.Op.STOP)
        test.request(GuidedCarTest.Op.START)

        assertTrue(test.runNext())
        assertEquals("Stopped", io.event("guided_test_end")!!["ended"])
        assertTrue(test.runNext())
        assertEquals("a new test begins", 2, io.all("guided_test_start").size)
    }

    @Test
    fun theHearingCheckWaitsForATapAndABusThatIsHeard() {
        io.heartbeat = DOOR_CLOSED
        val test = test(confirmStep, seatStep).started()
        voice.onSay = { if (it == "Can you hear me?") test.request(GuidedCarTest.Op.CONFIRM) }

        test.runNext()

        assertTrue("the card shows the button", statuses.any { it.confirm })
        assertEquals("done", steps().single()["result"])
        // Over half a second after the question was spoken.
        assertEquals("1500", steps().single()["listenMs"])
        assertEquals("Thanks.", voice.said.last())
        assertFalse(statuses.last().confirm)
        assertTrue(test.isActive())
    }

    @Test
    fun skipCantPassOverTheHearingCheck() {
        io.heartbeat = DOOR_CLOSED
        val test = test(confirmStep, seatStep, seatStep).started()
        io.onTick = { at ->
            if (at == 1_000L) test.request(GuidedCarTest.Op.SKIP)
            if (at == 2_000L) test.request(GuidedCarTest.Op.CONFIRM)
        }

        test.runNext()

        assertEquals("done", steps().single()["result"])
        assertEquals("intro", io.event("guided_skip_refused")!!["step"])
        assertEquals("nothing cut", 0, voice.interrupts)

        io.onTick = {}
        test.runNext()
        assertEquals("the refused skip doesn't carry to the next step", "recorded", steps().last()["result"])
    }

    @Test
    fun noTapEndsTheTest() {
        io.heartbeat = DOOR_CLOSED
        val test = test(confirmStep, seatStep).started()

        test.runNext()

        assertEquals("no_confirm", steps().single()["result"])
        assertEquals("No tap on “I can hear it”", statuses.last().ended)
        assertFalse(test.isActive())
    }

    @Test
    fun aSilentBusEndsTheTestAtTheHearingCheck() {
        val test = test(confirmStep, seatStep).started()
        voice.onSay = { test.request(GuidedCarTest.Op.CONFIRM) }

        test.runNext()

        assertEquals("no_bus", steps().single()["result"])
        assertEquals("Not hearing the body bus", statuses.last().ended)
    }

    @Test
    fun aPhoneThatCantSpeakEndsTheTest() {
        voice.failed = true
        val test = test(lockStep, seatStep).started()

        test.runNext()

        assertEquals("voice_failed", steps().single()["result"])
        assertEquals("The phone couldn't speak", statuses.last().ended)
        assertFalse(test.isActive())
    }

    @Test
    fun aParkedStepWaitsForPark() {
        io.inPark = false
        val test = test(lockStep).started()

        assertTrue("waiting holds the turn", test.runNext())
        assertEquals("the gear is asked for", 1, io.gearReads)
        assertTrue("no word before a fresh read has had time to come", voice.said.isEmpty())

        clock.advance(15_000L)
        assertTrue(test.runNext())
        test.runNext()

        assertFalse(io.commands.contains("<enter>"))
        assertEquals("said once", listOf(PARK_PROMPT), voice.said)
        assertEquals(PARK_PROMPT, statuses.last().instruction)
        assertEquals("lock", io.event("guided_waiting_for_park")!!["step"])
        assertEquals(3, io.gearReads)

        io.inPark = true
        clock.advance(5_000L)
        test.runNext()

        val read = io.event("guided_park_read")!!
        assertEquals("20000", read["waitedMs"])
        assertEquals("park", read["evidence"])
        assertEquals("missed", steps().single()["result"])
    }

    @Test
    fun aParkReadAtOnceStartsTheStepWithoutAWord() {
        val test = test(lockStep).started()

        test.runNext()

        assertEquals("0", io.event("guided_park_read")!!["waitedMs"])
        assertEquals(0, io.gearReads)
        assertEquals(null, io.event("guided_waiting_for_park"))
    }

    @Test
    fun noParkForFiveMinutesEndsTheTest() {
        io.inPark = false
        val test = test(lockStep).started()
        test.runNext()

        clock.advance(5 * 60_000L)
        test.runNext()

        assertEquals("The car wasn't read in Park", statuses.last().ended)
        assertFalse(test.isActive())
    }

    @Test
    fun anOffPhaseStepRunsWithTheCarOffWhateverTheGear() {
        voice.onSay = { if (it == "Turn the car off.") io.broadcast(clock.now + 1_000L, POWER_OFF) }
        val test = test(powerOff, chargePort).started()
        test.runNext()

        io.inPark = false
        test.runNext()

        assertEquals(listOf("heard", "recorded"), steps().map { it["result"] })
        assertEquals("off", io.event("guided_park_read")!!["evidence"])
    }

    @Test
    fun theCarHeardOffNoLongerCountsOnceAnythingShowsItMoving() {
        voice.onSay = { if (it == "Turn the car off.") io.broadcast(clock.now + 1_000L, POWER_OFF) }
        val test = test(powerOff, chargePort).started()
        test.runNext()

        io.inPark = false
        io.motions += 1
        test.runNext()

        assertEquals("no listen without Park", 1, io.commands.count { it == "<enter>" })
        assertEquals(listOf("power_off"), io.all("guided_park_read").map { it["step"] })
    }

    @Test
    fun theCarHeardOffLongAgoNoLongerCounts() {
        voice.onSay = { if (it == "Turn the car off.") io.broadcast(clock.now + 1_000L, POWER_OFF) }
        val test = test(powerOff, chargePort).started()
        test.runNext()

        io.inPark = false
        clock.advance(10 * 60_000L)
        test.runNext()

        assertEquals(1, io.commands.count { it == "<enter>" })
        assertEquals(1, io.gearReads)
    }

    @Test
    fun theCarHeardOnAgainEndsTheOffEvidence() {
        voice.onSay = {
            if (it == "Turn the car off.") {
                io.broadcast(clock.now + 1_000L, POWER_OFF)
                io.broadcast(clock.now + 5_000L, POWER_RUN)
            }
        }
        val test = test(powerOff, chargePort).started()
        test.runNext()
        assertEquals("heard", steps().single()["result"])

        io.inPark = false
        test.runNext()

        assertEquals(1, io.commands.count { it == "<enter>" })
    }

    @Test
    fun theWheelsTurningPausesTheStepUntilParkIsReadAgain() {
        voice.onSay = { if (it == "Lock the doors.") io.broadcast(clock.now + 2_000L, WHEELS_ROLLING) }
        val test = test(lockStep, seatStep).started()

        test.runNext()

        val step = steps().single()
        assertEquals("moved", step["result"])
        assertEquals("true", step["moved"])
        assertEquals(1, io.motionNoted)
        assertTrue(voice.said.last().startsWith("The car is moving"))

        test.runNext()
        assertEquals("no listen until Park", 1, io.commands.count { it == "<enter>" })

        voice.onSay = {}
        io.inPark = true
        test.runNext()
        assertEquals("the same step again", listOf("lock", "lock"), steps().map { it["step"] })
        assertEquals("2", steps().last()["attempt"])
    }

    @Test
    fun aParkAnsweredBeforeTheWheelsTurnedCantRestartTheStep() {
        val detector = ParkedDetector()
        io.detector = detector
        detector.observe(0.0, "P", 0L, clock.now)
        voice.onSay = { if (it == "Lock the doors.") io.broadcast(clock.now + 2_000L, WHEELS_ROLLING) }
        val test = test(lockStep, seatStep).started()
        test.runNext()
        assertEquals("moved", steps().single()["result"])

        // The gear PID went quiet: its Park, answered at the start, is carried into later samples.
        voice.onSay = {}
        detector.observe(0.0, "P", clock.now, clock.now)
        test.runNext()
        assertEquals("no listen on a Park from before the wheels turned", 1, io.commands.count { it == "<enter>" })
        assertTrue("a fresh read was asked for", io.gearReads > 0)

        clock.advance(1_000L)
        detector.observe(0.0, "P", 0L, clock.now)
        test.runNext()
        assertEquals("a Park answered since does", listOf("lock", "lock"), steps().map { it["step"] })
    }

    @Test
    fun wheelsTurningAfterTheStepEndedStillEndTheCarOffEvidence() {
        voice.onSay = { if (it == "Turn the car off.") io.broadcast(clock.now + 1_000L, POWER_OFF) }
        // The wheels are only in the adapter's queue, read after the stop.
        io.stopTail = listOf(WHEELS_ROLLING)
        val test = test(powerOff, chargePort).started()
        test.runNext()

        val step = steps().single()
        assertEquals("the result stands", "heard", step["result"])
        assertEquals("false", step["moved"])
        assertEquals("true", step["lateMotion"])
        assertEquals(1, io.motionNoted)

        io.stopTail = emptyList()
        io.inPark = false
        test.runNext()
        assertEquals("no listen: the car moved after it was heard off", 1, io.commands.count { it == "<enter>" })
        assertEquals(listOf("power_off"), io.all("guided_park_read").map { it["step"] })
    }

    @Test
    fun aPowerModeReadAfterTheStepEndedCanEndTheCarOffEvidence() {
        voice.onSay = { if (it == "Turn the car off.") io.broadcast(clock.now + 1_000L, POWER_OFF) }
        io.stopTail = listOf(POWER_RUN)
        val test = test(powerOff, chargePort).started()
        test.runNext()
        assertEquals("heard", steps().single()["result"])

        io.stopTail = emptyList()
        io.inPark = false
        test.runNext()
        assertEquals(1, io.commands.count { it == "<enter>" })
    }

    @Test
    fun anOffReadOnlyAfterTheStepEndedIsNoCarOffEvidence() {
        io.stopTail = listOf(POWER_OFF)
        val test = test(powerOff, chargePort).started()
        test.runNext()
        assertEquals("missed", steps().single()["result"])

        io.stopTail = emptyList()
        io.inPark = false
        test.runNext()
        assertEquals(1, io.commands.count { it == "<enter>" })
        assertEquals(listOf("power_off"), io.all("guided_park_read").map { it["step"] })
    }

    @Test
    fun wheelsAtAStandstillAreNotMotion() {
        io.heartbeat = WHEELS_STILL
        val test = test(seatStep).started()

        test.runNext()

        assertEquals("recorded", steps().single()["result"])
        assertEquals(0, io.motionNoted)
    }

    @Test
    fun aLongListenIsWrittenOutInChunks() {
        io.heartbeat = DOOR_CLOSED
        val test = test(GuidedStep("long", GuidedStepKind.TIMED, "Wait.", 70_000L)).started()

        test.runNext()

        val chunks = captures()
        assertEquals(listOf("1", "2", "3"), chunks.map { it["chunk"] })
        assertEquals("0", chunks[0]["fromMs"])
        assertEquals(chunks[0]["toMs"], chunks[1]["fromMs"])
        assertEquals(chunks[1]["toMs"], chunks[2]["fromMs"])
        assertEquals("3", steps().single()["chunks"])
        val frames = chunks.sumOf { it.getValue("frames").toInt() }
        assertEquals(steps().single().getValue("frames").toInt(), frames)
        assertTrue(chunks.all { it.getValue("originEpochMs").toLong() > 0L })
    }

    @Test
    fun framesAfterTheStopAreMarkedLateAndNeverCount() {
        // The lock frame comes only in the adapter's queue, after the step stopped listening.
        io.stopTail = listOf(LOCKED, POWER_RUN)
        val test = test(lockStep).started()

        test.runNext()

        val step = steps().single()
        assertEquals("missed", step["result"])
        assertEquals("2", step["drainedFrames"])
        // A missed lock step listens past one 30 s chunk; the late frames are in the last.
        val chunk = captures().last()
        assertEquals("2", chunk["drainedFrames"])
        assertTrue(chunk.getValue("arrivals").contains("*"))
        assertTrue(chunk.getValue("changes").contains("*"))
    }

    @Test
    fun aMonitorTheAdapterEndsIsPickedUpAgain() {
        io.endEarlyAfterMs = 3_000L
        val test = test(seatStep).started()

        test.runNext()

        val step = steps().single()
        assertEquals("2", step["monitorRuns"])
        assertEquals("1", step["restarts"])
        // Each run covers the bus from its first line (a quarter second in) to its end.
        assertEquals("250-3000,3250-9250", step["coverage"])
        assertEquals("2", step["coverageSpans"])
        assertEquals("false", step["coverageCut"])
        assertEquals("250", step["gapMs"])
        assertEquals("500", step["startupMs"])
        // The adapter's buffer filling lost frames, and the gap lost more: the capture says so.
        assertEquals("BUFFER FULL:1", step["monitorErrors"])
        assertEquals("partial:monitor_error+monitor_restarted", step["capture"])
        assertEquals(2, io.commands.count { it == "STM" })
    }

    @Test
    fun aMonitorThatEndsWithoutAWordIsPickedUpButItsGapIsNoWholeCapture() {
        io.endEarlyAfterMs = 3_000L
        io.earlyEndLine = null
        val test = test(seatStep).started()

        test.runNext()

        val step = steps().single()
        assertEquals("2", step["monitorRuns"])
        assertEquals("", step["monitorErrors"])
        // Whatever the car sent between the runs was missed.
        assertEquals("partial:monitor_restarted", step["capture"])
        assertEquals("partial", step["result"])
    }

    @Test
    fun anErrorLineIsNotAMonitorThatWorks() {
        // The adapter answers the monitor with an error first: the instruction waits for a working bus.
        io.preamble = { listOf("CAN ERROR", "GARBLED LINE") }
        val test = test(lockStep, seatStep).started()

        test.runNext()

        val step = steps().single()
        assertEquals("250", step["saidMs"])
        assertEquals("CAN ERROR:1,unparsed:1", step["monitorErrors"])
        assertEquals("partial:monitor_error", step["capture"])
        assertEquals("partial", step["result"])
    }

    @Test
    fun anErrorAsTheBusWakesStaysOnTheCaptureWhileTheStepHearsWhatFollows() {
        // As at power-on on 10-07: one CAN ERROR as the body bus woke, then the car's frames.
        val powerOn =
            GuidedStep(
                "power_on",
                GuidedStepKind.EVENT,
                "Turn the car back on.",
                120_000L,
                GuidedExpect.isOneOf(SwcanField.POWER_MODE, "run"),
            )
        voice.onSay = {
            if (it == powerOn.say) {
                io.broadcast(clock.now + 3_000L, "CAN ERROR")
                io.broadcast(clock.now + 4_000L, POWER_RUN)
            }
        }
        val test = test(powerOn).started()

        test.runNext()

        val step = steps().single()
        assertEquals("heard", step["result"])
        assertEquals(4_000L, heardAfterSaid(step))
        assertEquals("CAN ERROR:1", step["monitorErrors"])
        assertEquals("the capture still says what it may have lost", "partial:monitor_error", step["capture"])
    }

    @Test
    fun aMonitorThatOnlyErrorsNeverSaysTheStep() {
        io.alwaysEndsAtOnce = true
        val test = test(lockStep, seatStep).started()

        test.runNext()

        assertEquals("bus_failed", steps().single()["result"])
        assertTrue(voice.said.none { it == "Lock the doors." })
    }

    @Test
    fun aMonitorThatWontStayUpIsAPartialCapture() {
        io.alwaysEndsAtOnce = true
        io.earlyEndLine = null
        io.preamble = { listOf(DOOR_CLOSED) }
        val test = test(seatStep, seatStep).started()

        test.runNext()

        val step = steps().single()
        assertEquals("partial", step["result"])
        assertEquals("partial:monitor_restarted+monitor_stuck", step["capture"])
        assertEquals("5", step["monitorRuns"])
        assertEquals("Recorded, with gaps", statuses.last().lastResult)
    }

    @Test
    fun aStopPromptThatNeverComesIsAPartialCapture() {
        io.stopPrompt = false
        val test = test(lockStep, seatStep).started()

        test.runNext()

        val step = steps().single()
        assertEquals("partial", step["result"])
        assertEquals("partial:no_prompt", step["capture"])
        assertEquals("false", step["gotPrompt"])
    }

    @Test
    fun aDroppedLinkKeepsWhatWasHeardAndTheStepRunsAgain() {
        io.heartbeat = DOOR_CLOSED
        io.dropLinkAt = 3_000L
        val test = test(seatStep, lockStep).started()

        assertThrows(IOException::class.java) { test.runNext() }

        val cut = steps().single()
        assertEquals("interrupted", cut["result"])
        assertEquals("IOException", cut["error"])
        assertEquals("partial:interrupted", cut["capture"])
        assertTrue(cut.getValue("frames").toInt() >= 2)
        assertEquals(cut["frames"], captures().single()["frames"])
        assertEquals("seat", io.event("guided_bus_state_unknown")!!["step"])
        assertEquals("unknown", cut["gotPrompt"])
        assertEquals("unknown", cut["restored"])
        assertFalse("no restore on a dead link", io.commands.contains("<leave>"))
        assertTrue("the test outlives the reconnect", test.isActive())

        io.dropLinkAt = null
        test.runNext()

        assertEquals(listOf("interrupted", "recorded"), steps().map { it["result"] })
        assertEquals("2", steps().last()["attempt"])
    }

    @Test
    fun aBugMidListenStillSwitchesBack() {
        io.crashAt = 2_000L
        val test = test(seatStep).started()

        assertThrows(IllegalStateException::class.java) { test.runNext() }

        assertEquals("interrupted", steps().single()["result"])
        assertEquals("<leave>", io.commands.last())
    }

    @Test
    fun theDriveIsOneListenUntilTheCarIsOffAndItsShutdownRecorded() {
        voice.onSay = { if (it == "Drive.") io.broadcast(clock.now + 20 * 60_000L, POWER_OFF) }
        val test = test(drive).started()

        test.runNext()

        val step = steps().single()
        assertEquals("heard", step["result"])
        assertEquals("1", step["monitorRuns"])
        // Off 20 min after the instruction, then 30 s of shutdown.
        assertEquals((20 * 60_000L + 250L).toString(), step["heardMs"])
        assertEquals((20 * 60_000L + 250L + 30_000L).toString(), step["listenMs"])
        assertEquals("guided_drive", io.event("session_diagnostic")!!["reason"])
        assertTrue(statuses.any { it.note.startsWith("Live data is paused") })
        assertEquals("Finished", statuses.last().ended)
        assertTrue("the session ends now", test.takeSessionEnd())
        assertFalse("once", test.takeSessionEnd())
        assertEquals("true", io.event("guided_test_end")!!["endsSession"])
    }

    @Test
    fun anOffHeardWhileTheDriveIsBeingSaidDoesNotEndIt() {
        // An old "off" the car repeats, heard before the drive has even been asked for.
        voice.onSay = { if (it == "Drive.") io.broadcast(clock.now + 100L, POWER_OFF) }
        val test = test(shortDrive).started()

        test.runNext()

        val step = steps().single()
        assertEquals("missed", step["result"])
        assertEquals("", step["heardMs"])
    }

    @Test
    fun aVoiceThatFailsBeforeTheDriveIsSaidEndsTheTest() {
        voice.failed = true
        val test = test(shortDrive).started()

        test.runNext()

        assertEquals("voice_failed", steps().single()["result"])
        assertEquals("The phone couldn't speak", statuses.last().ended)
        assertNull("the drive never started", io.event("session_diagnostic"))
        assertFalse(test.takeSessionEnd())
    }

    @Test
    fun aVoiceThatFailsOnceTheDriveIsUnderwayDoesNotEndIt() {
        voice.onSay = { if (it == "Drive.") io.broadcast(clock.now + 60_000L, POWER_OFF) }
        io.onTick = { at -> if (at == 5_000L) voice.failed = true }
        val test = test(drive).started()

        test.runNext()

        assertEquals("heard", steps().single()["result"])
        assertTrue(test.takeSessionEnd())
    }

    @Test
    fun aShutdownCutShortIsAPartialCaptureOfThePowerOff() {
        voice.onSay = { if (it == "Turn the car off.") io.broadcast(clock.now + 1_000L, POWER_OFF) }
        io.earlyEndLine = null
        io.onTick = { at ->
            // The adapter stops monitoring a moment after the car switched off, and won't again.
            if (at == 2_000L) {
                io.endEarlyAfterMs = 0L
                io.alwaysEndsAtOnce = true
            }
        }
        val test = test(powerOff, seatStep).started()

        test.runNext()

        val step = steps().single()
        assertEquals("partial", step["result"])
        assertEquals("1250", step["heardMs"])
        assertEquals("partial:monitor_restarted+monitor_stuck+shutdown_short", step["capture"])
    }

    @Test
    fun aGapBetweenMonitorRunsInTheShutdownIsAShortShutdown() {
        voice.onSay = { if (it == "Drive.") io.broadcast(clock.now + 60_000L, POWER_OFF) }
        io.earlyEndLine = null
        // Ten seconds into the shutdown the adapter stops monitoring by itself, and is picked up again.
        io.onTick = { at -> if (at == 70_000L) io.endEarlyAfterMs = 0L }
        val test = test(drive).started()

        test.runNext()

        val step = steps().single()
        assertEquals("2", step["monitorRuns"])
        assertTrue(
            "the listen itself ran the whole 30 s",
            step.getValue("listenMs").toLong() - step.getValue("heardMs").toLong() >= 30_000L,
        )
        assertEquals("shutdown_short", step["result"])
        assertEquals("partial:monitor_restarted+shutdown_short", step["capture"])
        assertTrue(test.takeSessionEnd())
    }

    @Test
    fun aGapBeforeTheCarWasHeardOffLeavesTheShutdownWhole() {
        voice.onSay = { if (it == "Drive.") io.broadcast(clock.now + 60_000L, POWER_OFF) }
        io.earlyEndLine = null
        io.onTick = { at -> if (at == 30_000L) io.endEarlyAfterMs = 0L }
        val test = test(drive).started()

        test.runNext()

        val step = steps().single()
        assertEquals("heard", step["result"])
        assertEquals("partial:monitor_restarted", step["capture"])
    }

    @Test
    fun aDriveWhoseShutdownIsCutShortSaysSo() {
        voice.onSay = { if (it == "Drive.") io.broadcast(clock.now + 60_000L, POWER_OFF) }
        io.earlyEndLine = null
        io.onTick = { at ->
            if (at == 70_000L) {
                io.endEarlyAfterMs = 0L
                io.alwaysEndsAtOnce = true
            }
        }
        val test = test(drive).started()

        test.runNext()

        val step = steps().single()
        assertEquals("shutdown_short", step["result"])
        assertTrue(step.getValue("capture").contains("shutdown_short"))
        assertEquals("Finished, but the car switching off was only partly recorded", statuses.last().ended)
        assertTrue(voice.said.last().startsWith("That's the whole test, though"))
        assertTrue("the drive is over: the session still ends", test.takeSessionEnd())
    }

    @Test
    fun aDriveCutAfterTheCarWasHeardOffEndsAsTheLinkDrops() {
        voice.onSay = { if (it == "Drive.") io.broadcast(clock.now + 60_000L, POWER_OFF) }
        io.dropLinkAt = 70_000L
        val test = test(drive).started()

        assertThrows(IOException::class.java) { test.runNext() }

        // Over before any reconnect: the engine asks as the link drops, and the switched-off car
        // may never answer a reconnect.
        assertEquals(listOf("interrupted"), steps().map { it["result"] })
        assertEquals("Finished, but the car switching off was only partly recorded", statuses.last().ended)
        assertEquals("true", io.event("guided_test_end")!!["endsSession"])
        assertFalse(test.isActive())
        assertTrue(test.takeSessionEnd())

        io.dropLinkAt = null
        assertFalse("nothing left to run", test.runNext())
        assertEquals(1, io.commands.count { it == "<enter>" })
    }

    @Test
    fun aLineReadWithTheOneTheStepEndedOnIsLate() {
        // Both come in the tick the lock step runs out: the lock is read after the step is over.
        io.broadcast(30_750L, DOOR_CLOSED)
        io.broadcast(30_750L, LOCKED)
        val test = test(lockStep).started()

        test.runNext()

        val step = steps().single()
        assertEquals("missed", step["result"])
        assertEquals("30750", step["listenMs"])
        assertEquals("1", step["drainedFrames"])
    }

    @Test
    fun theDriveEndsOnItsOwnAfterItsLongestRun() {
        val test = test(shortDrive).started()

        test.runNext()

        assertEquals("missed", steps().single()["result"])
        // Two minutes from when "Drive." had been said (0.25 s in, 0.3 s to say, the next tick).
        assertEquals((2 * 60_000L + 750L).toString(), steps().single()["listenMs"])
        assertFalse(test.isActive())
        assertFalse("the session goes on", test.takeSessionEnd())
    }

    @Test
    fun aDriveCutByADroppedLinkCarriesOnWithoutSayingItAgain() {
        io.dropLinkAt = 60_000L
        val test = test(drive).started()
        assertThrows(IOException::class.java) { test.runNext() }

        io.dropLinkAt = null
        io.broadcast(clock.now + 30_000L, POWER_OFF)
        test.runNext()

        assertEquals(listOf("interrupted", "heard"), steps().map { it["result"] })
        assertEquals(listOf("Drive."), voice.said.filter { it == "Drive." })
        assertTrue(test.takeSessionEnd())
    }

    @Test
    fun aDriveWhoseFirstSwitchFailsIsStillAnnounced() {
        bus.enterFails = "STP 61"
        val test = test(shortDrive).started()
        test.runNext()
        assertEquals("bus_failed", steps().single()["result"])
        assertTrue(voice.said.none { it == "Drive." })

        bus.enterFails = null
        test.runNext()

        assertEquals(listOf("Drive."), voice.said.filter { it == "Drive." })
        assertEquals("missed", steps().last()["result"])
    }

    @Test
    fun itWaitsForTheAdapterAndTurnsAwayAPlainElm() {
        bus.ready = null
        val test = test(lockStep).started()

        assertFalse(test.runNext())
        assertTrue("waiting holds the session", test.isActive())
        assertEquals("Waiting for the adapter", statuses.last().instruction)

        bus.ready = false
        assertFalse(test.runNext())
        assertFalse(test.isActive())
        assertEquals("This needs an OBDLink adapter", statuses.last().ended)
        assertTrue(io.commands.isEmpty())
    }

    @Test
    fun afterAReconnectTheStepWaitsForTheAdapterToBeIdentified() {
        val test = test(seatStep, seatStep).started()
        test.runNext()

        bus.ready = null
        assertTrue(test.runNext())
        assertEquals(1, io.commands.count { it == "<enter>" })

        bus.ready = true
        test.runNext()
        assertEquals(2, io.commands.count { it == "<enter>" })
    }

    @Test
    fun aSecondFailedSwitchBackEndsTheTest() {
        bus.leaveResults += listOf(false, false)
        val test = test(seatStep, seatStep, seatStep).started()

        test.runNext()
        assertEquals(1, io.reinitCount)
        assertTrue(test.isActive())
        test.runNext()

        assertEquals(2, io.reinitCount)
        assertFalse(test.isActive())
        assertEquals("The adapter didn't switch buses cleanly", statuses.last().ended)
        assertEquals("false", steps().last()["restored"])
    }

    @Test
    fun aBusThatWontSetUpIsLoggedAndTriedAgain() {
        bus.enterFails = "STP 61"
        val test = test(lockStep, seatStep).started()

        repeat(3) { test.runNext() }

        assertEquals(listOf("bus_failed", "bus_failed", "bus_failed"), steps().map { it["result"] })
        assertEquals(listOf("1", "2", "3"), steps().map { it["attempt"] })
        assertEquals("STP 61", io.event("guided_bus_failed")!!["failedCommand"])
        assertFalse("three in a row end it", test.isActive())
        assertTrue("the instruction was never said", voice.said.none { it == "Lock the doors." })
    }

    @Test
    fun abandoningBetweenStepsLetsTheVoiceGoAtOnce() {
        val test = test(seatStep, seatStep).started()
        test.runNext()

        test.abandon()

        assertTrue(voice.closed)
        assertEquals(1, voice.interrupts)
        assertEquals("Stopped", statuses.last().ended)
        assertNull("not on the bus, so nothing unknown", io.event("guided_bus_state_unknown"))
        assertTrue("the poll thread tidies up on its next turn", test.runNext())
        assertFalse(test.isActive())
        assertEquals("Stopped", io.event("guided_test_end")!!["ended"])
    }

    @Test
    fun abandoningMidListenWaitsForTheSwitchBack() {
        val test = test(seatStep, seatStep).started()
        var abandoner: Thread? = null
        var commandsOnReturn = emptyList<String>()
        io.onTick = { at ->
            if (at == 2_000L) {
                val thread =
                    Thread {
                        test.abandon()
                        commandsOnReturn = io.commands.toList()
                    }
                abandoner = thread
                thread.start()
                // Until it has asked for the stop and is waiting for the bus.
                val deadline = System.nanoTime() + 5_000_000_000L
                while (thread.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.yield()
            }
        }

        test.runNext()
        abandoner!!.join(5_000L)

        assertTrue("it returned once HS was restored", commandsOnReturn.contains("<leave>"))
        assertNull(io.event("guided_bus_state_unknown"))
        assertEquals("stopped", steps().single()["result"])
        assertTrue(voice.closed)
        assertFalse(test.isActive())
    }

    @Test
    fun abandoningGivesUpOnAListenThatWontEnd() {
        val test = test(seatStep).started()
        var abandoned = false
        io.onTick = { at ->
            if (at == 2_000L) {
                // The poll thread is stuck (a read that never returns) until the abandon gives up.
                val thread = Thread { test.abandon() }
                thread.start()
                thread.join(10_000L)
                abandoned = !thread.isAlive
            }
        }

        test.runNext()

        assertTrue(abandoned)
        assertEquals("session_ended_on_the_body_bus", io.event("guided_bus_state_unknown")!!["reason"])
        assertTrue(voice.closed)
    }

    @Test
    fun sensitiveFramesNeverReachTheLog() {
        voice.onSay = {
            io.broadcast(clock.now + 500L, "10 2A A0 97 01 02 03 04")
            io.broadcast(clock.now + 600L, DOOR_CLOSED)
        }
        io.stopTail = listOf("10 28 C0 40 05 06 07 08")
        val test = test(seatStep).started()

        test.runNext()

        val logged = io.events.joinToString(" ") { it.second.values.joinToString(" ") }
        assertFalse(logged.contains("102AA097"))
        assertFalse(logged.contains("01 02 03 04"))
        assertFalse(logged.contains("1028C040"))
        assertTrue(captures().single().getValue("ids").contains("0C630040"))
    }

    @Test
    fun aChangeWaitsForANewValue() {
        val ac =
            GuidedStep("ac", GuidedStepKind.EVENT, "Press A C.", 30_000L, GuidedExpect.changes(SwcanField.AC_STATE))
        // The climate frame repeats "off" (seen during the first step), then turns "on" 10 s into the second.
        val test = test(seatStep, ac).started()
        io.broadcast(1_000L, AC_OFF)
        test.runNext()
        val start = clock.now
        for (i in 1..8) io.broadcast(start + i * 1_000L, AC_OFF)
        io.broadcast(start + 10_000L, AC_ON)

        test.runNext()

        val step = steps().last()
        assertEquals("heard", step["result"])
        assertEquals("10000", step["heardMs"])
    }

    @Test
    fun aSeatsFirstBroadcastCountsAsAPressFromOff() {
        voice.onSay = { if (it == seatPress.say) io.broadcast(clock.now + 3_000L, SEAT_FL_HIGH) }
        val test = test(seatPress).started()

        test.runNext()

        val step = steps().single()
        assertEquals("heard", step["result"])
        assertEquals(3_000L, heardAfterSaid(step))
        // Counted from where the seat was taken to start, not from a reading: the log says so.
        assertEquals("unheard", step["heardFrom"])
    }

    @Test
    fun aSeatsFirstBroadcastReadingOffNeedsASecond() {
        // It may have been on at its lowest and gone off: not a press from off.
        voice.onSay = {
            if (it == seatPress.say) {
                io.broadcast(clock.now + 2_000L, SEAT_FL_OFF)
                io.broadcast(clock.now + 4_000L, SEAT_FL_HIGH)
            }
        }
        val test = test(seatPress).started()

        test.runNext()

        val step = steps().single()
        assertEquals(4_000L, heardAfterSaid(step))
        assertEquals("0.0", step["heardFrom"])
    }

    @Test
    fun theLineThatStartsTheInstructionIsNotTheDriverDoingIt() {
        // The monitor's first line is a seat already lit: the instruction is said on it, so it only
        // sets where the seat starts, and repeating it is no press.
        io.broadcast(FakeCarIo.TICK_MS, SEAT_FL_HIGH)
        voice.onSay = {
            if (it == seatPress.say) {
                io.broadcast(clock.now + 2_000L, SEAT_FL_HIGH)
                io.broadcast(clock.now + 4_000L, SEAT_FL_MEDIUM)
            }
        }
        val test = test(seatPress).started()

        test.runNext()

        val step = steps().single()
        assertEquals(FakeCarIo.TICK_MS.toString(), step["saidMs"])
        assertEquals(4_000L, heardAfterSaid(step))
        assertEquals("3.0", step["heardFrom"])
    }

    @Test
    fun aCueLineIsNotTheDriverDoingItEvenForAFieldHeardBefore() {
        // The seat read off during an earlier step, then reads lit on the line that starts this one.
        val test = test(seatStep, seatPress).started()
        io.broadcast(1_000L, SEAT_FL_OFF)
        test.runNext()
        io.broadcast(clock.now, SEAT_FL_HIGH)
        voice.onSay = {
            if (it == seatPress.say) {
                io.broadcast(clock.now + 2_000L, SEAT_FL_HIGH)
                io.broadcast(clock.now + 4_000L, SEAT_FL_MEDIUM)
            }
        }

        test.runNext()

        assertEquals(4_000L, heardAfterSaid(steps().last()))
    }

    @Test
    fun theChargePortAndFuelDoorMoveOnAsSoonAsTheCarReportsThem() {
        voice.onSay = {
            when (it) {
                portOpen.say -> io.broadcast(clock.now + 10_000L, PORT_OPEN)
                portClose.say -> io.broadcast(clock.now + 3_000L, PORT_CLOSED)
                fuelOpen.say -> {
                    io.broadcast(clock.now + 1_000L, REFUEL_IDLE)
                    // The button pressed, then the door released a couple of seconds later.
                    io.broadcast(clock.now + 8_000L, REFUEL_REQUESTED)
                    io.broadcast(clock.now + 10_000L, REFUEL_READY)
                }
                fuelClose.say -> io.broadcast(clock.now + 4_000L, REFUEL_IDLE)
            }
        }
        val test = test(portOpen, portClose, fuelOpen, fuelClose).started()

        repeat(4) { test.runNext() }

        assertEquals(listOf("heard", "heard", "heard", "heard"), steps().map { it["result"] })
        assertEquals(listOf(10_000L, 3_000L, 10_000L, 4_000L), steps().map { heardAfterSaid(it) })
    }

    @Test
    fun aClosingCountsOnlyFromSomethingElse() {
        // A door already reading closed is no closing; open then closed is.
        voice.onSay = {
            if (it == portClose.say) {
                io.broadcast(clock.now + 1_000L, PORT_CLOSED)
                io.broadcast(clock.now + 2_000L, PORT_OPEN)
                io.broadcast(clock.now + 3_000L, PORT_CLOSED)
            }
        }
        val test = test(portClose).started()

        test.runNext()

        assertEquals(3_000L, heardAfterSaid(steps().single()))
    }

    @Test
    fun aRefuelRequestThatNeverReadsReadyIsMissed() {
        voice.onSay = { if (it == fuelOpen.say) io.broadcast(clock.now + 2_000L, REFUEL_REQUESTED) }
        val test = test(fuelOpen).started()

        test.runNext()

        assertEquals("missed", steps().single()["result"])
        assertTrue("the test goes on", voice.said.contains("Didn't hear that one. Moving on."))
    }

    @Test
    fun aFuelDoorNeverReleasedIsSaidAndTheNextStepOnlyAsksIfItWas() {
        val script = GuidedCarScript.steps.associateBy { it.id }
        val open = script.getValue("fuel_door_open")
        val close = script.getValue("fuel_door_close")
        voice.onSay = { if (it == open.say) io.broadcast(clock.now + 2_000L, REFUEL_REQUESTED) }
        val test = test(open, close).started()

        repeat(2) { test.runNext() }

        assertEquals(listOf("missed", "missed"), steps().map { it["result"] })
        assertEquals(open.say, voice.said[0])
        assertEquals("Didn't hear that one. Moving on.", voice.said[1])
        assertTrue(voice.said[2].startsWith("If the dash showed Ready to Refuel,"))
        assertTrue(voice.said[2].endsWith("If it didn't, just wait."))
    }

    @Test
    fun aCarSwitchedBackOnBeforeItsShutdownWasRecordedKeepsTheDriveGoing() {
        voice.onSay = {
            if (it == "Drive.") {
                io.broadcast(clock.now + 60_000L, POWER_OFF)
                io.broadcast(clock.now + 70_000L, POWER_RUN)
                io.broadcast(clock.now + 5 * 60_000L, POWER_OFF)
                // The car repeats off while it shuts down: that's no resuming.
                io.broadcast(clock.now + 5 * 60_000L + 5_000L, POWER_OFF)
            }
        }
        val test = test(drive).started()

        test.runNext()

        val step = steps().single()
        assertEquals("heard", step["result"])
        assertEquals("1", step["resumed"])
        assertEquals((5 * 60_000L + 250L).toString(), step["heardMs"])
        assertEquals((5 * 60_000L + 250L + 30_000L).toString(), step["listenMs"])
        assertTrue(test.takeSessionEnd())
    }

    @Test
    fun anOffTheCarThenFlagsNotValidIsNotTheEndOfADrive() {
        voice.onSay = {
            if (it == "Drive.") {
                io.broadcast(clock.now + 20_000L, POWER_OFF)
                io.broadcast(clock.now + 25_000L, POWER_NOT_VALID)
            }
        }
        val test = test(shortDrive).started()

        test.runNext()

        val step = steps().single()
        assertEquals("missed", step["result"])
        assertEquals("1", step["resumed"])
        assertFalse(test.takeSessionEnd())
    }

    @Test
    fun accessoryIsNotTheEndOfADrive() {
        voice.onSay = { if (it == "Drive.") io.broadcast(clock.now + 30_000L, POWER_ACCESSORY) }
        val test = test(shortDrive).started()

        test.runNext()

        val step = steps().single()
        assertEquals("missed", step["result"])
        assertEquals("0", step["resumed"])
        assertFalse(test.takeSessionEnd())
    }

    @Test
    fun anOffUndoneByAccessoryOrMotionIsNotTheEndOfADrive() {
        voice.onSay = {
            if (it == "Drive.") {
                io.broadcast(clock.now + 20_000L, POWER_OFF)
                io.broadcast(clock.now + 25_000L, POWER_ACCESSORY)
                io.broadcast(clock.now + 60_000L, POWER_OFF)
                io.broadcast(clock.now + 65_000L, WHEELS_ROLLING)
            }
        }
        val test = test(shortDrive).started()

        test.runNext()

        val step = steps().single()
        assertEquals("missed", step["result"])
        assertEquals("", step["heardMs"])
        assertEquals("2", step["resumed"])
        assertFalse(test.takeSessionEnd())
    }

    @Test
    fun theDriveOnItsOwnRunsItsOwnScript() {
        val test =
            GuidedCarTest(
                object : SwcanListenRunner.Io by io {
                    override fun openVoice() = voice
                },
                bus,
                fullSteps = listOf(lockStep, seatStep),
                driveSteps = listOf(seatStep),
                clock = clock::now,
            ) { statuses += it }
        bus.ready = null
        test.request(GuidedCarTest.Op.START_DRIVE)
        test.runNext()
        assertEquals("the drive script's length while waiting", 1, statuses.last().steps)
        bus.ready = true

        test.runNext()

        assertEquals(listOf("seat"), steps().map { it["step"] })
        assertEquals("drive", io.event("guided_test_start")!!["script"])
        assertEquals("1", io.event("guided_test_start")!!["steps"])
        assertFalse(test.isActive())

        test.request(GuidedCarTest.Op.START)
        test.runNext()

        assertEquals("full", io.event("guided_test_start")!!["script"])
        assertEquals(listOf("seat", "lock"), steps().map { it["step"] })
    }

    @Test
    fun theGuidedDriveIsTheHearingCheckThenTheDrive() {
        io.heartbeat = DOOR_CLOSED
        val test =
            GuidedCarTest(
                object : SwcanListenRunner.Io by io {
                    override fun openVoice() = voice
                },
                bus,
                clock = clock::now,
            ) { statuses += it }
        voice.onSay = {
            if (it.startsWith("Guided drive")) test.request(GuidedCarTest.Op.CONFIRM)
            if (it.startsWith("Now drive")) io.broadcast(clock.now + 20 * 60_000L, POWER_OFF)
        }
        test.request(GuidedCarTest.Op.START_DRIVE)

        var turns = 0
        while (test.runNext()) turns += 1

        assertEquals(2, turns)
        assertEquals(listOf("intro", "drive"), steps().map { it["step"] })
        assertEquals("heard", steps().last()["result"])
        assertEquals("Finished", statuses.last().ended)
        assertTrue(test.takeSessionEnd())
    }

    @Test
    fun theScriptsSeatPressesCountAFirstLampFromOff() {
        val presses = GuidedCarScript.steps.filter { it.id.startsWith("seat_") && it.kind == GuidedStepKind.EVENT }
        assertEquals(10, presses.size)
        for (press in presses) {
            val expect = press.expect!!
            assertTrue(press.id, expect.matches(null, 1.0))
            assertFalse(press.id, expect.matches(null, 0.0))
        }
        // Turning the seats off is its own step first, so it can't be taken for a press.
        val ids = GuidedCarScript.steps.map { it.id }
        val prep = GuidedCarScript.steps[ids.indexOf(presses.first().id) - 1]
        assertEquals(GuidedCarScript.SEATS_OFF, prep.id)
        assertEquals(GuidedStepKind.TIMED, prep.kind)
        assertTrue(prep.say.startsWith("Make sure every seat heater is off"))
    }

    @Test
    fun theScriptsCarOffDoorsWaitForWhatTheCarReports() {
        val byId = GuidedCarScript.steps.associateBy { it.id }
        for (id in listOf("charge_port_open", "charge_port_close", "fuel_door_open", "fuel_door_close")) {
            assertEquals(id, GuidedStepKind.EVENT, byId.getValue(id).kind)
            assertEquals(id, GuidedPhase.PARKED_OR_OFF, byId.getValue(id).phase)
        }
        assertTrue(byId.getValue("charge_port_open").say.startsWith("The car stays off for the next few steps."))
        val fuelReady = byId.getValue("fuel_door_open").expect!!
        assertTrue(fuelReady.matches("requested", "ready"))
        assertFalse("pressed is not yet released", fuelReady.matches("idle", "requested"))
        val portClosed = byId.getValue("charge_port_close").expect!!
        assertFalse("closed, with nothing heard before", portClosed.matches(null, "closed"))
        assertTrue(portClosed.matches("open", "closed"))
    }

    @Test
    fun onlyOffEndsEitherDrive() {
        for (step in listOf(GuidedCarScript.steps.last(), GuidedCarScript.driveSteps.last())) {
            assertEquals(GuidedStepKind.DRIVE, step.kind)
            val expect = step.expect!!
            assertTrue(expect.matches("run", "off"))
            assertFalse(expect.matches("run", "accessory"))
        }
        assertEquals(
            listOf(GuidedStepKind.CONFIRM, GuidedStepKind.DRIVE),
            GuidedCarScript.driveSteps.map { it.kind },
        )
    }

    @Test
    fun opsComeByWireName() {
        assertEquals(GuidedCarTest.Op.START_DRIVE, GuidedCarTest.Op.fromWire("start_drive"))
        assertEquals(GuidedCarTest.Op.SKIP, GuidedCarTest.Op.fromWire("skip"))
        assertEquals(GuidedCarTest.Op.CONFIRM, GuidedCarTest.Op.fromWire("confirm"))
        assertNull(GuidedCarTest.Op.fromWire("explode"))
        assertNull(GuidedCarTest.Op.fromWire(null))
    }

    @Test
    fun theWholeScriptRunsToTheEnd() {
        io.heartbeat = DOOR_CLOSED
        io.preamble = { if (it.contains('|')) listOf("A6|OK|OK|OK|OK|OK|OK|OK") else emptyList() }
        io.replies["STBC 1"] = "OK\r\r>"
        io.replies[SwitchBenchmark.BATCH_RESTORE] = "OK|OK|OK|OK|OK|OK|OK|OK|A6\r\r>"
        val test =
            GuidedCarTest(
                object : SwcanListenRunner.Io by io {
                    override fun openVoice() = voice
                },
                bus,
                clock = clock::now,
            ) { statuses += it }
        voice.onSay = { if (it.startsWith("Guided car test")) test.request(GuidedCarTest.Op.CONFIRM) }
        test.request(GuidedCarTest.Op.START)

        var turns = 0
        while (test.runNext()) turns += 1

        assertEquals("Finished", statuses.last().ended)
        val ran = steps().map { it["step"] }.toSet() + io.all("guided_bench_summary").map { it["block"] }
        assertEquals(GuidedCarScript.steps.map { it.id }.toSet(), ran)
        assertEquals("one turn a step, the drive included", GuidedCarScript.steps.size, turns)
        assertEquals("separate and batched blocks of ten", 40, io.all("guided_bench").size)
        assertTrue(io.all("guided_bench").all { it["failure"] == "" })
        // The power-off step comes before the charge port and fuel door.
        val order = steps().map { it["step"] }
        assertTrue(order.indexOf("power_off") < order.indexOf("charge_port_open"))
        assertTrue(order.indexOf("fuel_door_close") < order.indexOf("power_on"))
    }

    private companion object {
        const val LOCKED = "0C 41 40 40 00 01 00 01"
        const val DOOR_CLOSED = "0C 63 00 40 00"
        const val POWER_OFF = "10 24 20 40 00"
        const val POWER_RUN = "10 24 20 40 02"
        const val POWER_ACCESSORY = "10 24 20 40 01"
        const val POWER_NOT_VALID = "10 24 20 40 04"
        const val SEAT_FL_HIGH = "10 72 20 40 0C 00 3C 00"
        const val SEAT_FL_MEDIUM = "10 72 20 40 0C 00 2C 00"
        const val SEAT_FL_OFF = "10 72 20 40 00 00 00 00"
        const val PORT_OPEN = "10 22 40 CB 02"
        const val PORT_CLOSED = "10 22 40 CB 00"
        const val REFUEL_IDLE = "10 76 40 CB 00"
        const val REFUEL_REQUESTED = "10 76 40 CB 01"
        const val REFUEL_READY = "10 76 40 CB 02"
        const val AC_OFF = "10 73 40 99 10"
        const val AC_ON = "10 73 40 99 20"
        const val WHEELS_ROLLING = "10 6B 80 40 02 53 02 4C 02 4E 02 4C"
        const val WHEELS_STILL = "10 6B 80 40 00 00 00 00 00 00 00 00"
        const val PARK_PROMPT = "Put the car in Park. I'll carry on once it reads Park."
    }
}
