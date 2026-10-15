package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Dispatch tests for [ObdVoltMode22ParserRegistry]. The per-PID decode formulas are exhaustively
 * pinned at exact values in [ObdProtocolTest]; this file exercises the Mode-22 dispatch itself:
 * mapped commands resolve, non-mapped probe DIDs fall through to the header-agnostic cell decode,
 * and unknown commands return null.
 */
class ObdVoltMode22ParserRegistryTest {
    @Test
    fun directlyDecodesRepresentativeMode22Signals() {
        val packVoltage = ObdVoltMode22ParserRegistry.parse("222429", "6224291C00")
        assertNotNull(packVoltage)
        assertEquals("hv pack voltage", packVoltage!!.name)
        assertEquals(112.0, packVoltage.valueNumeric!!, 0.01)

        val minCell = ObdVoltMode22ParserRegistry.parse("224329", "6243291720")
        assertNotNull(minCell)
        assertEquals(3.7, minCell!!.valueNumeric!!, 0.001)
    }

    @Test
    fun decodesTheGmFourByteOdometer() {
        val odometer = ObdVoltMode22ParserRegistry.parse("2234B2", "6234B2003C4B00")
        assertNotNull(odometer)
        assertEquals("odometer", odometer!!.name)
        assertEquals(61740.0, odometer.valueNumeric!!, 0.001)
        assertNull("a truncated frame must not decode", ObdVoltMode22ParserRegistry.parse("2234B2", "6234B2003C4B"))
    }

    @Test
    fun decodesTheGen2LiveReads() {
        assertEquals(72.2, value("22452D", "62452DB8"), 0.1)
        assertEquals(67.8, value("22452E", "62452EAD"), 0.1)
        assertEquals(12.2, value("22002C", "62002C1F"), 0.1)
        assertEquals(90.0, value("223040", "6230400514"), 0.01)
        assertEquals(84.0, value("223041", "62304104D8"), 0.01)
        // EGR gas runs hotter than the usual temperature bound; 250 C must still read.
        assertEquals(250.0, value("223040", "6230400B54"), 0.01)
        // The 12 V capacity is the first word of a two-frame BCM reply.
        assertEquals(41.5, value("22CB31", "008\r0:62CB31298000\r1:0000AAAAAAAAAA"), 0.01)
        assertEquals(41.5, value("22CB31", "62CB312980"), 0.01)
        assertNull("a negative capacity is garbage", ObdVoltMode22ParserRegistry.parse("22CB31", "62CB31FF00"))
        assertNull("so is zero", ObdVoltMode22ParserRegistry.parse("22CB31", "62CB310000"))
    }

    @Test
    fun decodesTheGen2ScanCandidates() {
        assertEquals(12.3, value("224470", "624470007B"), 0.01)
        assertEquals(1000.0, value("2241C5", "6241C50000FA00"), 0.01)
        assertEquals(500.0, value("2241C6", "6241C600007D00"), 0.01)
        assertEquals(2.5, value("2241AD", "6241AD0014"), 0.01)
        assertEquals(-1.0, value("2290FB", "6290FBFF80"), 0.01)
        assertEquals(12.6, value("2290B1", "6290B13138"), 0.01)
        assertEquals(50.0, value("2290F8", "6290F83FFF"), 0.01)
        assertEquals(9.0, value("224533", "6245331E"), 0.01)
        assertEquals(9.0, value("224545", "624545B4"), 0.01)
        assertEquals(53.3, value("224546", "62454688"), 0.1)
        assertEquals(20.0, value("22451E", "62451E33"), 0.1)
        assertEquals(13.8, value("220042", "62004235E8"), 0.01)
        assertNull("a truncated four-byte counter", ObdVoltMode22ParserRegistry.parse("2241C5", "6241C50000FA"))
    }

    @Test
    fun unmappedProbeDidFallsThroughToCellDecode() {
        // "224181" (cell 1) has no flat-map entry, so the registry falls through to the
        // 96-cell probe decoder for it.
        val cell = ObdVoltMode22ParserRegistry.parse("224181", "624181BD6F")
        assertNotNull(cell)
        assertEquals("cell 1 voltage", cell!!.name)
        assertEquals(3.7, cell.valueNumeric!!, 0.001)
    }

    @Test
    fun mappedCollidingDidNeverFallsThroughToCellDecode() {
        // "2241A3" is mapped (7E4 pack capacity); a BECM cell frame for the same command string
        // reads as an implausible 592 Ah and must yield null — never the 7E7 cell meaning.
        assertNull(ObdVoltMode22ParserRegistry.parse("2241A3", "6241A31720"))
    }

    @Test
    fun cellProbeDispatchIsHeaderAwareAndRejectsUnrelatedCommands() {
        val cell = ObdProtocol.parseCellVoltageProbe("224181", "624181BD6F")
        assertNotNull(cell)
        assertEquals("cell 1 voltage", cell!!.name)
        assertEquals(3.7, cell.valueNumeric!!, 0.001)
        assertNull(ObdProtocol.parseCellVoltageProbe("010C", "410C1880"))
    }

    private fun value(
        command: String,
        response: String,
    ): Double = ObdVoltMode22ParserRegistry.parse(command, response)!!.valueNumeric!!

    @Test
    fun unknownCommandsReturnNull() {
        assertNull(ObdVoltMode22ParserRegistry.parse("22FFFF", "62FFFF01"))
        assertNull(ObdVoltMode22ParserRegistry.parse("010C", "410C1880"))
        assertNull(ObdVoltMode22ParserRegistry.parse("", null))
    }
}
