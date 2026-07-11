package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Registry-level tests for [ObdKnownValueParserRegistry].
 *
 * The leaf decoders are exhaustively pinned in [ObdProtocolTest]; this file instead exercises the
 * *dispatch table*: that a command routes to the right decoder, that every registered standard
 * command resolves to a working parser, that unmapped commands fall through to the legacy table,
 * and that genuinely unknown commands return null instead of throwing.
 *
 * The registry exposes a single public entry point — `parse(cleanCommand, response)` — and expects
 * an already-cleaned, uppercase command (the trim/uppercase step lives in
 * [ObdProtocol.parseKnownValue]). The `standardParsers` map itself is private, so this test cannot
 * iterate the live key set; the completeness sweep below therefore drives the full set of
 * documented standard keys (mirrored from the registry source) through `parse` instead.
 *
 * Pure parsing logic with no android.* dependencies, so this runs as a plain JVM test.
 */
class ObdKnownValueParserRegistryTest {
    // ---- 1. Representative commands route to the expected decoder --------------------

    @Test
    fun standardCommandRoutesToExpectedDecoder() {
        // 0142 -> control module voltage decoder. Same vector ObdProtocolTest pins at 14.000 V.
        val cmv = ObdKnownValueParserRegistry.parse("0142", "414236B0")
        assertNotNull(cmv)
        assertEquals("control module voltage", cmv!!.name)
        assertEquals(14.000, cmv.valueNumeric!!, 0.001)
        assertEquals("V", cmv.unit)

        // 010D -> vehicle speed decoder. 410D41 -> 0x41 = 65 km/h.
        val speed = ObdKnownValueParserRegistry.parse("010D", "410D41")
        assertNotNull(speed)
        assertEquals("vehicle speed", speed!!.name)
        assertEquals(65.0, speed.valueNumeric!!, 0.01)
        assertEquals("km/h", speed.unit)

        // 015B -> state of charge decoder. 415B7F -> 0x7F * 100 / 255 = 50 %.
        val soc = ObdKnownValueParserRegistry.parse("015B", "415B7F")
        assertNotNull(soc)
        assertEquals("state of charge", soc!!.name)
        assertEquals(50.0, soc.valueNumeric!!, 0.01)
        assertEquals("%", soc.unit)
    }

    @Test
    fun unmappedCommandFallsThroughToLegacyDecoder() {
        // 222429 has no standard-parser entry, so parse() must hand off to
        // ObdProtocol.parseKnownValueLegacy. Same vector ObdProtocolTest pins at 360.0 V.
        val hvVoltage = ObdKnownValueParserRegistry.parse("222429", "6224295A00")
        assertNotNull(hvVoltage)
        assertEquals("hv pack voltage", hvVoltage!!.name)
        assertEquals(360.0, hvVoltage.valueNumeric!!, 0.01)
        assertEquals("V", hvVoltage.unit)
    }

    // ---- 2. Structural integrity: every registered standard key resolves -------------

    @Test
    fun everyStandardCommandResolvesToAWorkingParser() {
        // For each registered standard key, a well-formed response must decode to a non-null
        // value with the expected name/unit. If a future edit drops or mis-wires a registration
        // (wrong decoder, swapped name/unit), the corresponding row fails here. The
        // expected values reuse the vectors ObdProtocolTest already pins.
        val cases =
            listOf(
                StandardCase("ATRV", "14.3V", "adapter voltage", 14.3, "V"),
                StandardCase("010D", "410D41", "vehicle speed", 65.0, "km/h"),
                StandardCase("010C", "410C1880", "engine rpm", 1568.0, "rpm"),
                StandardCase("0142", "414236B0", "control module voltage", 14.000, "V"),
                StandardCase("011F", "411F003C", "engine run time", 60.0, "s"),
                StandardCase("01A6", "41A60012D687", "odometer", 123456.7, "km"),
                StandardCase("0105", "410563", "coolant temperature", 59.0, "deg C"),
                StandardCase("010F", "410F64", "intake air temperature", 60.0, "deg C"),
                StandardCase("012F", "412F80", "fuel level", 50.0, "%"),
                StandardCase("0104", "4104B2", "engine load", 70.0, "%"),
                StandardCase("0111", "41115D", "throttle position", 36.0, "%"),
                StandardCase("0149", "414980", "accelerator pedal position", 50.0, "%"),
                StandardCase("015B", "415B7F", "state of charge", 50.0, "%"),
                StandardCase("015C", "415C64", "engine oil temperature", 60.0, "deg C"),
            )

        // Sanity-check that the harness lists each standard key exactly once. The live map is
        // private, but a duplicate row in this fixture would silently weaken the sweep.
        val keys = cases.map { it.command }
        assertEquals("duplicate command in the standard-case fixture", keys.size, keys.toSet().size)

        for (case in cases) {
            val parsed = ObdKnownValueParserRegistry.parse(case.command, case.response)
            assertNotNull("${case.command} must resolve to a parser", parsed)
            assertEquals("${case.command} name", case.name, parsed!!.name)
            assertEquals("${case.command} unit", case.unit, parsed.unit)
            assertEquals("${case.command} value", case.value, parsed.valueNumeric!!, 0.5)
        }
    }

    // ---- 3. Header-collision guardrail ------------------------------------------------

    @Test
    fun collidingCellDidIsHeaderRoutedAndNeverHitsTheFlatCommandMap() {
        // "2241A3" collides across ECUs: on the 7E4 energy ECU it is HV pack capacity
        // (word / 10 Ah), but on the 7E7 BECM the same command string is the cell-35 voltage
        // probe. The flat command registry must ALWAYS apply the 7E4 meaning; the 7E7 meaning
        // is only reachable through the header-aware entry point
        // (ObdProtocol.parseCellVoltageProbe), which the cell-probe runner calls directly.
        val capacityFrame = "6241A30205" // word 517 -> 51.7 Ah (7E4 meaning)
        val cellFrame = "6241A31720" // word 5920 -> 3.700 V at the BECM 1/1600 scale

        val viaRegistry = ObdKnownValueParserRegistry.parse("2241A3", capacityFrame)
        assertNotNull(viaRegistry)
        assertEquals("HV battery capacity", viaRegistry!!.name)
        assertEquals("Ah", viaRegistry.unit)
        assertEquals(51.7, viaRegistry.valueNumeric!!, 0.01)

        val viaProbe = ObdProtocol.parseCellVoltageProbe("2241A3", cellFrame)
        assertNotNull(viaProbe)
        assertEquals("cell 35 voltage", viaProbe!!.name)
        assertEquals("V", viaProbe.unit)
        assertEquals(3.7, viaProbe.valueNumeric!!, 0.001)

        // The BECM cell frame reads as 592 Ah under the 7E4 formula — outside the capacity
        // sanity band — so the registry must return null for it and must NOT fall through to
        // the header-aware cell decoder: a 7E4 frame must never decode with the 7E7 meaning.
        assertNull(ObdKnownValueParserRegistry.parse("2241A3", cellFrame))
        // Symmetrically, the 7E4 capacity frame is implausible as a cell voltage on either
        // known scale, so the probe path rejects it rather than inventing a reading.
        assertNull(ObdProtocol.parseCellVoltageProbe("2241A3", capacityFrame))
    }

    // ---- 4. Unknown commands return null, never throw -------------------------------

    @Test
    fun unknownCommandReturnsNull() {
        // Not in standardParsers and not in the legacy table -> null, not an exception.
        assertNull(ObdKnownValueParserRegistry.parse("ATZ", "ELM327 v1.4b"))
        assertNull(ObdKnownValueParserRegistry.parse("0100", "4100BE7FB813"))
        assertNull(ObdKnownValueParserRegistry.parse("", "414236B0"))
    }

    @Test
    fun registeredCommandWithBadResponseReturnsNullNotThrow() {
        // A registered key still returns null (rather than throwing) when the response is
        // missing or malformed, so the dispatch path is null-safe end to end.
        assertNull(ObdKnownValueParserRegistry.parse("010C", "CAN ERROR"))
        assertNull(ObdKnownValueParserRegistry.parse("0142", null))
        assertNull(ObdKnownValueParserRegistry.parse("015B", ""))
        assertNull(ObdKnownValueParserRegistry.parse("010D", "410D"))
    }

    private data class StandardCase(
        val command: String,
        val response: String,
        val name: String,
        val value: Double,
        val unit: String,
    )
}
