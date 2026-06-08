package com.volttracker.obdpoc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises every accept/reject branch of [SpeedPlausibilityFilter]. */
class SpeedPlausibilityFilterTest {
    @Test
    fun firstReadingIsAccepted() {
        assertTrue(SpeedPlausibilityFilter().accept(50, T0))
    }

    @Test
    fun outOfRangeReadingsAreRejected() {
        val f = SpeedPlausibilityFilter()
        assertFalse(f.accept(-1, T0))
        // 255 is the charge-transition sentinel, not a real speed
        assertFalse(f.accept(255, T0))
    }

    @Test
    fun plausibleChangeIsAccepted() {
        val f = SpeedPlausibilityFilter()
        f.accept(50, T0)
        // +10 km/h in one second is normal acceleration
        assertTrue(f.accept(60, T0 + 1_000L))
    }

    @Test
    fun impossibleJumpIsRejected() {
        val f = SpeedPlausibilityFilter()
        f.accept(50, T0)
        // +150 km/h in one second is a glitch
        assertFalse(f.accept(200, T0 + 1_000L))
    }

    @Test
    fun rejectedReadingDoesNotMoveTheBaseline() {
        val f = SpeedPlausibilityFilter()
        f.accept(50, T0)
        assertFalse(f.accept(200, T0 + 1_000L))
        // the next real reading is compared to 50, not the rejected 200
        assertTrue(f.accept(55, T0 + 2_000L))
    }

    @Test
    fun resetClearsTheBaseline() {
        val f = SpeedPlausibilityFilter()
        f.accept(50, T0)
        f.reset()
        // after reset the next reading is a fresh first reading, not a jump
        assertTrue(f.accept(200, T0 + 1_000L))
    }

    companion object {
        private const val T0 = 1_700_000_000_000L
    }
}
