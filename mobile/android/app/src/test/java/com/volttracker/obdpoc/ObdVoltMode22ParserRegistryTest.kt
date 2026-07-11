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

    @Test
    fun unknownCommandsReturnNull() {
        assertNull(ObdVoltMode22ParserRegistry.parse("22FFFF", "62FFFF01"))
        assertNull(ObdVoltMode22ParserRegistry.parse("010C", "410C1880"))
        assertNull(ObdVoltMode22ParserRegistry.parse("", null))
    }
}
