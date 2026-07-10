package com.volttracker.obdpoc

import com.volttracker.obdpoc.materialize.PackEnergyMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackEnergyMathTest {
    @Test
    fun packSignConventionIsShared() {
        assertEquals(3.6, PackEnergyMath.dischargePowerKw(360.0, 10.0)!!, 0.0001)
        assertEquals(3.6, PackEnergyMath.chargePowerKw(360.0, -10.0)!!, 0.0001)
    }

    @Test
    fun trapezoidRejectsNonForwardTimeAndLongTelemetryOutages() {
        assertEquals(0.05, PackEnergyMath.trapezoidKwh(2.0, 4.0, 0L, 60_000L)!!, 0.0001)
        assertNull(PackEnergyMath.trapezoidKwh(2.0, 4.0, 0L, 3_600_000L))
        assertNull(PackEnergyMath.trapezoidKwh(2.0, 4.0, 10L, 10L))
    }

    @Test
    fun nonFiniteInputsAreRejected() {
        assertNull(PackEnergyMath.dischargePowerKw(Double.NaN, 10.0))
        assertNull(PackEnergyMath.trapezoidKwh(Double.POSITIVE_INFINITY, 4.0, 0L, 1L))
    }
}
