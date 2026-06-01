package com.volttracker.obdpoc.materialize;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/**
 * Pins {@link MaterializerInput}'s fail-fast window validation. SessionRecorder catches the throw
 * and skips materialization, so the point here is that an impossible window is rejected rather than
 * silently producing empty/garbage trips.
 */
public class MaterializerInputTest {

    private static final long T_BASE = 1_700_000_000_000L;

    @Test
    public void validWindowConstructs() {
        MaterializerInput input = new MaterializerInput(7L, T_BASE, T_BASE + 60_000L);
        assertEquals(7L, input.sessionId);
        assertEquals(T_BASE, input.startedAtMs);
        assertEquals(T_BASE + 60_000L, input.closedAtMs);
    }

    @Test
    public void zeroDurationWindowIsAllowed() {
        // A session that closes at the same instant it started yields no trips, which the
        // materializers handle — so it's a legal (if empty) input, not an error.
        MaterializerInput input = new MaterializerInput(1L, T_BASE, T_BASE);
        assertEquals(T_BASE, input.closedAtMs);
    }

    @Test
    public void closedBeforeStartedIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MaterializerInput(1L, T_BASE, T_BASE - 1L));
    }

    @Test
    public void nonPositiveSessionIdIsRejected() {
        assertThrows(
                IllegalArgumentException.class, () -> new MaterializerInput(0L, T_BASE, T_BASE));
    }

    @Test
    public void negativeStartIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MaterializerInput(1L, -1L, T_BASE));
    }
}
