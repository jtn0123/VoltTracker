package com.volttracker.obdpoc.materialize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackEnergyMathTest {
    @Test
    fun integratesNormalSampleIntervals() {
        assertEquals(
            20.0 / 120.0,
            PackEnergyMath.trapezoidKwh(20.0, 20.0, 0L, 30_000L)!!,
            0.000001,
        )
    }

    @Test
    fun rejectsIntervalsThatWouldExtrapolateAcrossAnOutage() {
        assertNull(
            PackEnergyMath.trapezoidKwh(
                20.0,
                20.0,
                0L,
                PackEnergyMath.MAX_INTEGRATION_GAP_MS + 1L,
            ),
        )
    }
}
