package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ObdVoltMode22DecoderTest {
    @Test
    fun directlyDecodesRepresentativeMode22Signals() {
        val packVoltage = ObdVoltMode22Decoder.parse("222429", "6224291C00")
        assertNotNull(packVoltage)
        assertEquals("hv pack voltage", packVoltage!!.name)
        assertEquals(112.0, packVoltage.valueNumeric!!, 0.01)

        val minCell = ObdVoltMode22Decoder.parse("224329", "6243291720")
        assertNotNull(minCell)
        assertEquals(3.7, minCell!!.valueNumeric!!, 0.001)
    }

    @Test
    fun cellProbeDispatchIsHeaderAwareAndRejectsUnrelatedCommands() {
        val cell = ObdVoltMode22Decoder.parseCellVoltage("224181", "624181BD6F")
        assertNotNull(cell)
        assertEquals("cell 1 voltage", cell!!.name)
        assertEquals(3.7, cell.valueNumeric!!, 0.001)
        assertNull(ObdVoltMode22Decoder.parseCellVoltage("010C", "410C1880"))
    }
}
