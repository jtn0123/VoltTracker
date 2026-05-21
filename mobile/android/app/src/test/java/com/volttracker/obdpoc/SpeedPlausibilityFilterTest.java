package com.volttracker.obdpoc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Exercises every accept/reject branch of {@link SpeedPlausibilityFilter}. */
public class SpeedPlausibilityFilterTest {

    private static final long T0 = 1_700_000_000_000L;

    @Test
    public void firstReadingIsAccepted() {
        assertTrue(new SpeedPlausibilityFilter().accept(50, T0));
    }

    @Test
    public void outOfRangeReadingsAreRejected() {
        SpeedPlausibilityFilter f = new SpeedPlausibilityFilter();
        assertFalse(f.accept(-1, T0));
        // 255 is the charge-transition sentinel, not a real speed
        assertFalse(f.accept(255, T0));
    }

    @Test
    public void plausibleChangeIsAccepted() {
        SpeedPlausibilityFilter f = new SpeedPlausibilityFilter();
        f.accept(50, T0);
        // +10 km/h in one second is normal acceleration
        assertTrue(f.accept(60, T0 + 1_000L));
    }

    @Test
    public void impossibleJumpIsRejected() {
        SpeedPlausibilityFilter f = new SpeedPlausibilityFilter();
        f.accept(50, T0);
        // +150 km/h in one second is a glitch
        assertFalse(f.accept(200, T0 + 1_000L));
    }

    @Test
    public void rejectedReadingDoesNotMoveTheBaseline() {
        SpeedPlausibilityFilter f = new SpeedPlausibilityFilter();
        f.accept(50, T0);
        assertFalse(f.accept(200, T0 + 1_000L));
        // the next real reading is compared to 50, not the rejected 200
        assertTrue(f.accept(55, T0 + 2_000L));
    }

    @Test
    public void resetClearsTheBaseline() {
        SpeedPlausibilityFilter f = new SpeedPlausibilityFilter();
        f.accept(50, T0);
        f.reset();
        // after reset the next reading is a fresh first reading, not a jump
        assertTrue(f.accept(200, T0 + 1_000L));
    }
}
