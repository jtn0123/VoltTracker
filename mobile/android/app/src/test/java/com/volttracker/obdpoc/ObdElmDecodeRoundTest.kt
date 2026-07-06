package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Boundary coverage for [ObdElmDecode.round1] / [ObdElmDecode.round3], the fixed-decimal helpers the
 * demo telemetry rounds with — round3 backs the Battery-tab cell-voltage fields, so a regression here
 * would skew the cell-balance card.
 */
class ObdElmDecodeRoundTest {
    @Test
    fun round3KeepsThreeDecimals() {
        assertEquals(0.0, ObdElmDecode.round3(0.0), 1e-9)
        assertEquals(3.836, ObdElmDecode.round3(3.8362), 1e-9)
        assertEquals(3.837, ObdElmDecode.round3(3.8367), 1e-9)
        assertEquals(3.85, ObdElmDecode.round3(3.85), 1e-9)
        // Mirrors a demo cell-voltage computation: 3.9 - 14/2000 = 3.893.
        assertEquals(3.893, ObdElmDecode.round3(3.9 - 14.0 / 2000.0), 1e-9)
    }

    @Test
    fun round1KeepsOneDecimal() {
        assertEquals(0.0, ObdElmDecode.round1(0.0), 1e-9)
        assertEquals(13.8, ObdElmDecode.round1(13.84), 1e-9)
        assertEquals(13.9, ObdElmDecode.round1(13.86), 1e-9)
    }
}
