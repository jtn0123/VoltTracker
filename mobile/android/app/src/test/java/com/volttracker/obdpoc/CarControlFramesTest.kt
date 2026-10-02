package com.volttracker.obdpoc

import com.volttracker.obdpoc.engine.CarControlRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins THE car-controls allowlist. Any change to a transmittable frame must change this file too, so
 * it cannot happen by accident. Every frame is from OVMS vehicle_voltampera @ b092263 (see
 * [CarControlFrames]).
 */
class CarControlFramesTest {
    @Test
    fun allowlistIsExactlyTheseStpxCommands() {
        assertEquals(
            setOf(
                "STPX h:100, d:, r:0",
                "STPX h:621, d:00FFFFFFFFFF0000, r:0",
                "STPX h:1024E097, d:0001FF, r:0",
                "STPX h:1024E097, d:0003FF, r:0",
                "STPX h:1024E097, d:0C00FF, r:0",
                "STPX h:1024E097, d:3C00FF, r:0",
                "STPX h:1024E097, d:8001FF, r:0",
                "STPX h:1024E097, d:4001FF, r:0",
                "STPX h:1024E097, d:0000FF, r:0",
                "STPX h:241, d:013E000000000000, r:0",
                "STPX h:241, d:07AE08017FFF0000, r:0",
                "STPX h:241, d:07AE080100000000, r:0",
                "STPX h:241, d:07AE3BFF02020202, r:0",
                "STPX h:241, d:07AE3BFF01010101, r:0",
            ),
            CarControlFrames.ALLOWED_STPX,
        )
        assertEquals(CarControlFrames.ALLOWED.size, CarControlFrames.ALLOWED_STPX.size)
    }

    @Test
    fun framesGoOutOnTheirOwnBus() {
        val byId = CarControlFrames.ALLOWED.groupBy({ it.id }, { it.bus }).mapValues { it.value.toSet() }
        assertEquals(setOf(ControlBus.SWCAN_11BIT), byId[0x100])
        assertEquals(setOf(ControlBus.SWCAN_11BIT), byId[0x621])
        assertEquals(setOf(ControlBus.SWCAN_29BIT), byId[CarControlFrames.TELEMATICS_ID])
        assertEquals(setOf(ControlBus.HSCAN_11BIT), byId[CarControlFrames.BCM_ID])
        assertEquals(setOf(0x100, 0x621, CarControlFrames.TELEMATICS_ID, CarControlFrames.BCM_ID), byId.keys)
    }

    @Test
    fun stpxForRefusesAnythingOutsideTheAllowlist() {
        val outsiders =
            listOf(
                // Engine / trunk / charge writes OVMS has but VoltTracker must never send.
                ControlFrame(ControlBus.SWCAN_29BIT, CarControlFrames.TELEMATICS_ID, listOf(0x00, 0x00, 0x10)),
                ControlFrame(ControlBus.SWCAN_29BIT, 0x10AAA097, listOf(0x00)),
                // Right bytes, wrong bus.
                ControlFrame(ControlBus.SWCAN_11BIT, CarControlFrames.TELEMATICS_ID, listOf(0x00, 0x01, 0xFF)),
                // Right bytes, one byte short.
                ControlFrame(ControlBus.SWCAN_29BIT, CarControlFrames.TELEMATICS_ID, listOf(0x00, 0x01)),
                ControlFrame(ControlBus.HSCAN_11BIT, 0x7E0, listOf(0x02, 0x10, 0x03)),
            )
        for (frame in outsiders) {
            assertFalse(frame in CarControlFrames.ALLOWED)
            try {
                CarControlFrames.stpxFor(frame)
                fail("stpxFor accepted $frame")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("allowlist"))
            }
        }
        for (frame in CarControlFrames.ALLOWED) {
            assertTrue(CarControlFrames.isAllowedTransmit(CarControlFrames.stpxFor(frame)))
        }
        // The string check is a second line of defence for the rendered command.
        assertFalse(CarControlFrames.isAllowedTransmit("STPX h:1024E097, d:000010, r:0"))
        assertFalse(CarControlFrames.isAllowedTransmit("STPX h:7E0, d:021003, r:0"))
        assertFalse(CarControlFrames.isAllowedTransmit("STPX h:1024E097, d:0001FF, r:1"))
    }

    @Test
    fun everyCommandSequenceUsesOnlyAllowlistedFrames() {
        for (command in CarCommand.entries) {
            val sends = command.steps.filterIsInstance<ControlStep.Send>()
            assertTrue("$command sends something", sends.isNotEmpty())
            sends.forEach { assertTrue("$command sends ${it.frame}", it.frame in CarControlFrames.ALLOWED) }
        }
    }

    @Test
    fun everyTelematicsRequestIsFollowedByARelease() {
        for (command in CarCommand.entries) {
            val telematics =
                command.steps
                    .filterIsInstance<ControlStep.Send>()
                    .map { it.frame }
                    .filter { it.id == CarControlFrames.TELEMATICS_ID }
            if (telematics.isEmpty()) continue
            assertEquals("$command ends released", CarControlFrames.TELEMATICS_RELEASE, telematics.last())
        }
    }

    @Test
    fun highVoltageWakeupOnlyEverCarriesTheWakeFrame() {
        for (command in CarCommand.entries) {
            val steps = command.steps
            steps.forEachIndexed { index, step ->
                if (step is ControlStep.HighVoltageWakeup) {
                    val next = steps.drop(index + 1).first { it !is ControlStep.Pause }
                    assertEquals(CarControlFrames.WAKEUP, (next as ControlStep.Send).frame)
                    assertTrue(steps[index + 2] is ControlStep.NormalTransceiver)
                }
            }
        }
    }

    @Test
    fun sequencesMatchOvms() {
        fun frames(command: CarCommand) = command.steps.filterIsInstance<ControlStep.Send>().map { it.frame }
        val wake = listOf(CarControlFrames.WAKEUP, CarControlFrames.WAKEUP_BCM)
        assertEquals(
            wake +
                listOf(
                    CarControlFrames.TELEMATICS_LOCK,
                    CarControlFrames.TELEMATICS_FLASH,
                    CarControlFrames.TELEMATICS_RELEASE,
                ),
            frames(CarCommand.LOCK),
        )
        assertEquals(
            wake +
                listOf(
                    CarControlFrames.TELEMATICS_UNLOCK,
                    CarControlFrames.TELEMATICS_FLASH,
                    CarControlFrames.TELEMATICS_RELEASE,
                ),
            frames(CarCommand.UNLOCK),
        )
        assertEquals(
            wake + listOf(CarControlFrames.TELEMATICS_FLASH, CarControlFrames.TELEMATICS_RELEASE),
            frames(CarCommand.FLASH_LIGHTS),
        )
        assertEquals(
            wake + listOf(CarControlFrames.TELEMATICS_LOCATE, CarControlFrames.TELEMATICS_RELEASE),
            frames(CarCommand.LOCATE),
        )
        assertEquals(
            wake + listOf(CarControlFrames.TELEMATICS_REMOTE_START, CarControlFrames.TELEMATICS_RELEASE),
            frames(CarCommand.REMOTE_START),
        )
        assertEquals(
            listOf(CarControlFrames.TELEMATICS_REMOTE_STOP, CarControlFrames.TELEMATICS_RELEASE),
            frames(CarCommand.REMOTE_STOP),
        )
        val windowFrames = frames(CarCommand.WINDOWS_DOWN)
        assertEquals(wake, windowFrames.take(2))
        assertEquals(
            1 + CarControlFrames.WINDOW_REPEATS,
            windowFrames.count { it == CarControlFrames.BCM_WINDOWS_DOWN },
        )
        assertEquals(1, windowFrames.count { it == CarControlFrames.BCM_INTERIOR_LAMP_ON })
        assertEquals(1, windowFrames.count { it == CarControlFrames.BCM_INTERIOR_LAMP_OFF })
        assertEquals(
            1 + CarControlFrames.WINDOW_REPEATS,
            frames(CarCommand.WINDOWS_UP).count {
                it ==
                    CarControlFrames.BCM_WINDOWS_UP
            },
        )
    }

    @Test
    fun wireNamesRoundTrip() {
        for (command in CarCommand.entries) assertEquals(command, CarCommand.fromWireName(command.wireName))
        assertNull(CarCommand.fromWireName("engine_start"))
        assertNull(CarCommand.fromWireName(null))
        assertNull(CarCommand.fromWireName("LOCK"))
    }

    @Test
    fun runnerConfigCommandsNeverTransmit() {
        // Everything the runner may send besides STPX is adapter-local: no periodic messages,
        // wake messages, RTRs, or OBD requests. STCSWM is only the transceiver mode switch.
        val forbidden = listOf("STPX", "STPPM", "ATWM", "ATRTR", "STSL")
        for (command in CarControlRunner.CONFIG_COMMANDS) {
            val compact = command.replace(" ", "")
            assertTrue(command, compact.startsWith("AT") || compact.startsWith("ST"))
            assertTrue("$command transmits", forbidden.none { compact.startsWith(it) })
        }
    }
}
