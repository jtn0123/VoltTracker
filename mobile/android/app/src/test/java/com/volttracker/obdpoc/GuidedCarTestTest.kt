package com.volttracker.obdpoc

import com.volttracker.obdpoc.engine.GuidedCarScript
import com.volttracker.obdpoc.engine.GuidedCarTest
import com.volttracker.obdpoc.engine.GuidedExpect
import com.volttracker.obdpoc.engine.GuidedStep
import com.volttracker.obdpoc.engine.GuidedStepKind
import com.volttracker.obdpoc.engine.SwcanListenRunner
import com.volttracker.obdpoc.engine.SwitchBenchmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The spoken guided car test against a fake car, adapter and voice, in fake time. */
class GuidedCarTestTest {
    private val clock = FakeClock()
    private val io = FakeCarIo(clock)
    private val bus = FakeBus(io)
    private val voice = FakeVoice(clock)
    private val statuses = mutableListOf<GuidedTestStatus>()

    private val lockStep =
        GuidedStep(
            "lock",
            GuidedStepKind.EVENT,
            "Lock the doors.",
            30_000L,
            GuidedExpect.isOneOf(SwcanField.LOCK_STATE, "locked"),
        )
    private val seatStep = GuidedStep("seat", GuidedStepKind.TIMED, "Press the seat heater.", 8_000L)
    private val powerOff =
        GuidedStep(
            "power_off",
            GuidedStepKind.POWER_OFF,
            "Turn the car off.",
            60_000L,
            GuidedExpect.isOneOf(SwcanField.POWER_MODE, "off"),
        )
    private val drive =
        GuidedStep(
            "drive",
            GuidedStepKind.DRIVE,
            "Drive.",
            35 * 60_000L,
            GuidedExpect.isOneOf(SwcanField.POWER_MODE, "off"),
        )

    private fun test(vararg steps: GuidedStep) =
        GuidedCarTest(
            object : SwcanListenRunner.Io by io {
                override fun openVoice() = voice
            },
            bus,
            steps.toList(),
            clock::now,
        ) { statuses += it }

    private fun GuidedCarTest.started(): GuidedCarTest {
        request(GuidedCarTest.Op.START)
        return this
    }

    private fun steps() = io.all("guided_step")

    @Test
    fun aStepThatHearsWhatItWaitsForSaysSoAndMovesOn() {
        voice.onSay = { if (it == "Lock the doors.") io.broadcast(clock.now + 3_000L, LOCKED) }
        val test = test(lockStep, seatStep).started()

        assertTrue(test.runNext())

        val step = steps().single()
        assertEquals("heard", step["result"])
        // Heard 3 s after the instruction, then a 3 s tail.
        val heardMs = step["heardMs"]!!.toLong()
        assertTrue("heardMs=$heardMs", heardMs in 3_000L..3_250L)
        assertTrue(step["listenMs"]!!.toLong() in heardMs + 3_000L..heardMs + 3_250L)
        assertEquals(listOf("Lock the doors.", "Got it."), voice.said)
        assertEquals("Heard it", statuses.last().lastResult)
        assertEquals("locked", bus.recorded.single { it.field == SwcanField.LOCK_STATE }.value)
        assertEquals(listOf("<enter>", "STM", "<leave>"), io.commands)
        assertTrue(test.isActive())
    }

    @Test
    fun aStepsTimeStartsOnceTheInstructionHasBeenSpoken() {
        val test = test(lockStep).started()

        test.runNext()

        val step = steps().single()
        assertEquals("missed", step["result"])
        val spokenMs = step["spokenMs"]!!.toLong()
        assertTrue("the instruction takes about 0.75 s to say: $spokenMs", spokenMs in 500L..1_000L)
        assertTrue(step["listenMs"]!!.toLong() in spokenMs + 30_000L..spokenMs + 30_250L)
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
    }

    @Test
    fun skipEndsAStepAndStopEndsTheTest() {
        val test = test(lockStep, seatStep, seatStep).started()
        io.onTick = { at -> if (at == 2_000L) test.request(GuidedCarTest.Op.SKIP) }

        test.runNext()
        assertEquals("skipped", steps().last()["result"])
        assertEquals("Skipped", statuses.last().lastResult)

        io.onTick = { test.request(GuidedCarTest.Op.STOP) }
        assertTrue(test.runNext())

        assertEquals("stopped", steps().last()["result"])
        assertEquals("Stopped", statuses.last().ended)
        assertEquals("Test stopped.", voice.said.last())
        assertFalse(test.isActive())
        assertEquals(2, io.commands.count { it == "<leave>" })
        assertFalse("a stopped test does not run again", test.runNext())
    }

    @Test
    fun turningOffListensOnUntilTheShutdownBurstEnds() {
        voice.onSay = {
            if (it == "Turn the car off.") {
                io.broadcast(clock.now + 2_000L, POWER_OFF)
                // The body modules' shutdown burst: a door frame every half second for 10 s.
                for (i in 1..20) io.broadcast(clock.now + 2_000L + i * 500L, DOOR_CLOSED)
            }
        }
        val test = test(powerOff).started()

        test.runNext()

        val step = steps().single()
        assertEquals("heard", step["result"])
        // The last frame came 12 s in; the step ends after 5 s of silence.
        val listenMs = step["listenMs"]!!.toLong()
        assertTrue("listenMs=$listenMs", listenMs in 17_000L..17_500L)
    }

    @Test
    fun theDriveListensAMinuteAtATimeUntilTheCarSwitchesOff() {
        val test = test(drive).started()

        test.runNext()
        test.runNext()
        io.broadcast(clock.now + 20_000L, POWER_OFF)
        test.runNext()

        val segments = steps()
        assertEquals(listOf("segment", "segment", "heard"), segments.map { it["result"] })
        assertEquals(listOf("1", "2", "3"), segments.map { it["segment"] })
        assertTrue(segments[0]["listenMs"]!!.toLong() in 60_000L..60_250L)
        assertEquals("the drive instruction is said once", listOf("Drive."), voice.said.filter { it == "Drive." })
        assertEquals("Driving: 2 min recorded", statuses.first { it.lastResult.startsWith("Driving: 2") }.lastResult)
        assertEquals("Finished", statuses.last().ended)
    }

    @Test
    fun theDriveEndsOnItsOwnAfterItsLongestRun() {
        val test = test(GuidedStep("drive", GuidedStepKind.DRIVE, "Drive.", 2 * 60_000L)).started()

        repeat(2) { test.runNext() }

        assertEquals(listOf("segment", "missed"), steps().map { it["result"] })
        assertFalse(test.isActive())
    }

    @Test
    fun aMonitorTheAdapterEndsIsPickedUpAgain() {
        io.endEarlyAfterMs = 3_000L
        val test = test(seatStep).started()

        test.runNext()

        val step = steps().single()
        assertEquals("2", step["monitorRuns"])
        assertEquals(2, io.commands.count { it == "STM" })
        assertTrue("the step still runs its course: ${step["listenMs"]}", step["listenMs"]!!.toLong() >= 8_000L)
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
    fun aBusThatWontSetUpIsLoggedAndPassedOver() {
        bus.enterFails = "STP 61"
        val test = test(lockStep, lockStep, lockStep, lockStep).started()

        repeat(3) { test.runNext() }

        assertEquals(listOf("bus_failed", "bus_failed", "bus_failed"), steps().map { it["result"] })
        assertEquals("STP 61", io.event("guided_bus_failed")!!["failedCommand"])
        assertFalse("three in a row end it", test.isActive())
    }

    @Test
    fun abandoningLetsTheVoiceGoAtOnce() {
        val test = test(seatStep, seatStep).started()
        test.runNext()

        test.abandon()

        assertTrue(voice.closed)
        assertEquals("Stopped", statuses.last().ended)
        assertTrue("the poll thread tidies up on its next turn", test.runNext())
        assertFalse(test.isActive())
        assertEquals("Stopped", io.event("guided_test_end")!!["ended"])
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
    fun sensitiveFramesNeverReachTheLog() {
        voice.onSay = {
            io.broadcast(clock.now + 500L, "10 2A A0 97 01 02 03 04")
            io.broadcast(clock.now + 600L, DOOR_CLOSED)
        }
        val test = test(seatStep).started()

        test.runNext()

        val logged = io.events.joinToString(" ") { it.second.values.joinToString(" ") }
        assertFalse(logged.contains("102AA097"))
        assertFalse(logged.contains("01 02 03 04"))
        assertTrue(steps().single()["ids"]!!.contains("0C630040"))
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
        assertTrue(step["heardMs"]!!.toLong() >= 9_500L)
    }

    @Test
    fun opsComeByWireName() {
        assertEquals(GuidedCarTest.Op.SKIP, GuidedCarTest.Op.fromWire("skip"))
        assertNull(GuidedCarTest.Op.fromWire("explode"))
        assertNull(GuidedCarTest.Op.fromWire(null))
    }

    @Test
    fun theWholeScriptRunsToTheEndWhenTheCarIsSilent() {
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
        test.request(GuidedCarTest.Op.START)

        var turns = 0
        while (test.runNext()) turns += 1

        assertEquals("Finished", statuses.last().ended)
        val ran = steps().map { it["step"] }.toSet() + io.all("guided_bench_summary").map { it["block"] }
        assertEquals(GuidedCarScript.steps.map { it.id }.toSet(), ran)
        // 35 one-minute drive segments, then the drive gives up.
        assertEquals(35, steps().count { it["step"] == "drive" })
        assertEquals(GuidedCarScript.steps.size + 34, turns)
        assertEquals("separate and batched blocks of ten", 40, io.all("guided_bench").size)
    }

    private companion object {
        const val LOCKED = "0C 41 40 40 00 01 00 01"
        const val DOOR_CLOSED = "0C 63 00 40 00"
        const val POWER_OFF = "10 24 20 40 00"
        const val AC_OFF = "10 73 40 99 10"
        const val AC_ON = "10 73 40 99 20"
    }
}
