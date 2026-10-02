package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BcmTirePressureTest {
    @Test
    fun aFourKpaReplyReadsInWheelOrder() {
        val tires = BcmTirePressure.parse("7E8 07 62 C9 01 3C 3D 3E 3F\r>")!!
        assertEquals(4.0, tires.kpaPerCount, 0.0)
        assertEquals(240.0, tires.flKpa!!, 1e-9)
        assertEquals(244.0, tires.rlKpa!!, 1e-9)
        assertEquals(248.0, tires.frKpa!!, 1e-9)
        assertEquals(252.0, tires.rrKpa!!, 1e-9)
    }

    @Test
    fun aCommunityScaleReplyPicksOnePointThreeSevenThree() {
        // 0xAF = 175 counts * 1.373 = 240.3 kPa (35 psi).
        val tires = BcmTirePressure.parse("62C901AFAFB0AE")!!
        assertEquals(1.373, tires.kpaPerCount, 0.0)
        assertEquals(175 * 1.373, tires.flKpa!!, 1e-9)
        assertEquals(174 * 1.373, tires.rrKpa!!, 1e-9)
    }

    @Test
    fun aMissingSensorIsNullButTheRestStillRead() {
        val tires = BcmTirePressure.parse("62 C9 01 00 3C FF 3C")!!
        assertNull(tires.flKpa)
        assertNull(tires.frKpa)
        assertEquals(240.0, tires.rlKpa!!, 1e-9)
    }

    @Test
    fun refusalsShortRepliesAndNonsenseAreRejected() {
        assertNull(BcmTirePressure.parse(null))
        assertNull(BcmTirePressure.parse("NO DATA"))
        assertNull("negative reply", BcmTirePressure.parse("7F 22 31"))
        assertNull("too few bytes", BcmTirePressure.parse("62 C9 01 3C 3C"))
        assertNull("no valid wheel", BcmTirePressure.parse("62 C9 01 FE FE FF 00"))
        assertNull("not hex", BcmTirePressure.parse("62 C9 01 3C 3C 3C ZZ"))
        // A median near 240 kPa at 4/count, but one wheel at 0xFD*4 = 1012 kPa is impossible.
        assertNull("implausible wheel", BcmTirePressure.parse("62 C9 01 3C 3C 3C FD"))
    }
}
