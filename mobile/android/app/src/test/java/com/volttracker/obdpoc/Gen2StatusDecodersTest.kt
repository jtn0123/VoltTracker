package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Gen2StatusDecodersTest {
    private fun text(
        command: String,
        response: String?,
    ): String? = ObdProtocol.parseKnownValue(command, response)?.valueText

    private fun number(
        command: String,
        response: String?,
    ): Double? = ObdProtocol.parseKnownValue(command, response)?.valueNumeric

    @Test
    fun engineStartReasonListsEverySetBitInByteThenBitOrder() {
        assertEquals("None", text("222439", "62 24 39 00 00 00 00"))
        assertEquals("Battery charge low", text("222439", "62 24 39 00 00 00 08"))
        assertEquals(
            "System fault; Remote start; Acceleration demand; Engine computer request",
            text("222439", "62 24 39 80 01 01 40"),
        )
        assertEquals("the label count", 4.0, number("222439", "62 24 39 80 01 01 40")!!, 0.0)
        assertEquals("reserved bits add nothing", "None", text("222439", "62 24 39 43 40 00 80"))
        assertNull("too short for four bytes", text("222439", "62 24 39 00 00 00"))
        assertNull(text("222439", "NO DATA"))
    }

    @Test
    fun chargeRecordsDecodeFromASegmentedMultiFrameReply() {
        // ATH0 + CAF1 prints a multi-frame reply as a length line and numbered segments.
        assertEquals("Full charge", text("2243CB", "009\r0: 62 43 CB 01 00 00\r1: 00 00 00 AA AA AA AA\r>"))
        assertEquals(
            "Unplugged at the wall; Charge cord error; Charger too hot; Charge time limit",
            text("2243CC", "009\r0:6243CC080220\r1:010000AAAAAAAA"),
        )
        assertEquals("None", text("2243CF", "009\r0:6243CF000000\r1:000000AAAAAAAA"))
        assertEquals(
            Gen2StatusDecoders.CHARGE_RECORD_COMMANDS.mapIndexed { i, _ -> "charge record ${i + 1}" },
            Gen2StatusDecoders.CHARGE_RECORD_COMMANDS.map {
                ObdProtocol.parseKnownValue(it, "009\r0:62${it.substring(2)}000000\r1:000000AAAAAAAA")?.name
            },
        )
    }

    @Test
    fun segmentIndicesNoLongerLeakIntoMultiFramePayloads() {
        // Joining the raw lines kept "0:" / "1:" digits, shifting byte D onto the segment index.
        assertEquals("Charge time limit", text("2243CB", "009\r0:6243CB000000\r1:010000AAAAAAAA"))
    }

    @Test
    fun enumsReadByteAAndRefuseCodesOutsideTheTable() {
        assertEquals("Charging", text("2243AE", "62 43 AE 02"))
        assertEquals("Error", text("2243AE", "62 43 AE 04"))
        assertNull(text("2243AE", "62 43 AE 05"))
        assertEquals("Off", text("2243DE", "62 43 DE 00"))
        assertEquals("Passive heating", text("2243DE", "62 43 DE 04"))
        assertEquals("Ready", text("224532", "62 45 32 01"))
        assertEquals("Shutting down", text("224532", "62 45 32 02"))
        assertEquals("Passed", text("224530", "62 45 30 00"))
        assertEquals("Failed", text("22452F", "62 45 2F 01"))
        assertNull(text("22452F", "62 45 2F 02"))
    }

    @Test
    fun maintenanceChargeIsBitThreeOfByteA() {
        assertEquals("Active", text("22441E", "62 44 1E 08 00 00 00"))
        assertEquals("Off", text("22441E", "62 44 1E F7 FF 00 02"))
        assertEquals(1.0, number("22441E", "62 44 1E 0F 00 00 00")!!, 0.0)
    }

    @Test
    fun chargeTimesReadAsWeekdayAndClock() {
        assertEquals("Tue 23:05", text("2283E1", "62 83 E1 03 17 05"))
        assertEquals(
            "minutes into the week",
            (2 * 24 * 60 + 23 * 60 + 5).toDouble(),
            number("2283E1", "6283E1031705")!!,
            0.0,
        )
        assertEquals("Sun 00:00", text("2283E2", "62 83 E2 01 00 00"))
        assertEquals("Sat 06:30", text("228375", "62 83 75 07 06 1E"))
        assertEquals("Wed 04:30", text("228377", "62 83 77 04 04 1E"))
        // No time scheduled: a parsed value with no text, so it shows as no reading.
        assertEquals("", text("2283E1", "62 83 E1 00 00 00"))
        assertNull(number("2283E1", "62 83 E1 08 00 00"))
        assertEquals("", text("2283E1", "62 83 E1 03 18 00"))
        assertNull("too short", text("2283E1", "62 83 E1 03 17"))
    }

    @Test
    fun pumpsAndRemoteStartCounters() {
        assertEquals(20.0, number("222C05", "62 2C 05 33")!!, 0.0)
        assertEquals(40.0, number("224357", "62 43 57 66")!!, 0.0)
        assertEquals(2.0, number("229025", "62 90 25 02")!!, 0.0)
        // Byte B in 10 s steps; byte A is ignored.
        assertEquals(590.0, number("228537", "62 85 37 FF 3B")!!, 0.0)
        assertEquals(0.0, number("228537", "62 85 37 00 00")!!, 0.0)
        assertNull("needs byte B", number("228537", "62 85 37 00"))
    }
}
