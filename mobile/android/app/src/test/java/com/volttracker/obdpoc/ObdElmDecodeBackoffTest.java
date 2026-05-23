package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the two pure backoff helpers on {@link ObdElmDecode}: {@code
 * initialConnectBackoffMs} (used before any link has ever been established) and {@code
 * reconnectBackoffMs} (used mid-session after a link drop).
 *
 * <p>These are the only behavioral guarantees the polling loop depends on, so the tests pin them
 * down without touching the polling state machine itself.
 */
public class ObdElmDecodeBackoffTest {

    /** Documented sane upper bound for any single backoff sleep. */
    private static final long SANE_UPPER_BOUND_MS = 60_000L;

    // ---- monotonic non-decreasing ----------------------------------------------------

    @Test
    public void reconnectBackoffIsMonotonicNonDecreasing() {
        for (int n = 1; n < ObdProbes.MAX_RECONNECT_ATTEMPTS; n++) {
            long current = ObdElmDecode.reconnectBackoffMs(n);
            long next = ObdElmDecode.reconnectBackoffMs(n + 1);
            assertTrue(
                    "reconnectBackoffMs should be non-decreasing: f("
                            + n
                            + ")="
                            + current
                            + " > f("
                            + (n + 1)
                            + ")="
                            + next,
                    next >= current);
        }
    }

    @Test
    public void initialConnectBackoffIsMonotonicNonDecreasing() {
        for (int n = 1; n < ObdProbes.MAX_RECONNECT_ATTEMPTS; n++) {
            long current = ObdElmDecode.initialConnectBackoffMs(n);
            long next = ObdElmDecode.initialConnectBackoffMs(n + 1);
            assertTrue(
                    "initialConnectBackoffMs should be non-decreasing: f("
                            + n
                            + ")="
                            + current
                            + " > f("
                            + (n + 1)
                            + ")="
                            + next,
                    next >= current);
        }
    }

    // ---- first-attempt floor ---------------------------------------------------------

    @Test
    public void firstAttemptIsNonNegativeAndFinite() {
        long reconnect = ObdElmDecode.reconnectBackoffMs(1);
        long initial = ObdElmDecode.initialConnectBackoffMs(1);
        assertTrue("reconnectBackoffMs(1) must be >= 0, got " + reconnect, reconnect >= 0L);
        assertTrue("initialConnectBackoffMs(1) must be >= 0, got " + initial, initial >= 0L);
        // "Finite" in long terms: well below any conceivable wall-clock sleep ceiling.
        assertTrue("reconnectBackoffMs(1) must be finite-ish", reconnect < SANE_UPPER_BOUND_MS);
        assertTrue("initialConnectBackoffMs(1) must be finite-ish", initial < SANE_UPPER_BOUND_MS);
    }

    // ---- bounded ceiling -------------------------------------------------------------

    @Test
    public void backoffAtMaxAttemptsStaysWithinSaneBound() {
        // Per the implementation: reconnect caps at 30_000 ms, initial caps at 3_000 ms.
        // Both must stay safely below SANE_UPPER_BOUND_MS even at the configured ceiling.
        long reconnectAtMax = ObdElmDecode.reconnectBackoffMs(ObdProbes.MAX_RECONNECT_ATTEMPTS);
        long initialAtMax = ObdElmDecode.initialConnectBackoffMs(ObdProbes.MAX_RECONNECT_ATTEMPTS);
        assertTrue(
                "reconnectBackoffMs at MAX should not exceed "
                        + SANE_UPPER_BOUND_MS
                        + " ms, got "
                        + reconnectAtMax,
                reconnectAtMax <= SANE_UPPER_BOUND_MS);
        assertTrue(
                "initialConnectBackoffMs at MAX should not exceed "
                        + SANE_UPPER_BOUND_MS
                        + " ms, got "
                        + initialAtMax,
                initialAtMax <= SANE_UPPER_BOUND_MS);
    }

    @Test
    public void reconnectBackoffCapsAtThirtySeconds() {
        // The JavaDoc on reconnectBackoffMs documents a 30 s cap. Push well past
        // the configured MAX_RECONNECT_ATTEMPTS to confirm the cap is real and stable.
        for (int n = ObdProbes.MAX_RECONNECT_ATTEMPTS;
                n <= ObdProbes.MAX_RECONNECT_ATTEMPTS + 10;
                n++) {
            long value = ObdElmDecode.reconnectBackoffMs(n);
            assertTrue(
                    "reconnectBackoffMs(" + n + ") must be <= 30000 ms, got " + value,
                    value <= 30_000L);
        }
    }

    @Test
    public void initialConnectBackoffCapsAtThreeSeconds() {
        // Implementation caps initial-connect backoff at 3 s so an asleep car exhausts
        // its retries quickly rather than stalling the user on exponential growth.
        for (int n = ObdProbes.MAX_RECONNECT_ATTEMPTS;
                n <= ObdProbes.MAX_RECONNECT_ATTEMPTS + 10;
                n++) {
            long value = ObdElmDecode.initialConnectBackoffMs(n);
            assertTrue(
                    "initialConnectBackoffMs(" + n + ") must be <= 3000 ms, got " + value,
                    value <= 3_000L);
        }
    }

    // ---- initial vs reconnect differ in shape ----------------------------------------

    @Test
    public void initialConnectBackoffIsGentlerThanReconnect() {
        // Per the JavaDoc on initialConnectBackoffMs and runBluetoothLoop: initial-connect
        // backoff is "quicker and gentler" than mid-session reconnect. Contract: for every
        // attempt n in 1..MAX_RECONNECT_ATTEMPTS, initial <= reconnect (and strictly less
        // somewhere in the range so the two curves are not identical).
        boolean strictlyLessSomewhere = false;
        for (int n = 1; n <= ObdProbes.MAX_RECONNECT_ATTEMPTS; n++) {
            long initial = ObdElmDecode.initialConnectBackoffMs(n);
            long reconnect = ObdElmDecode.reconnectBackoffMs(n);
            assertTrue(
                    "initialConnectBackoffMs("
                            + n
                            + ")="
                            + initial
                            + " must be <= reconnectBackoffMs("
                            + n
                            + ")="
                            + reconnect,
                    initial <= reconnect);
            if (initial < reconnect) {
                strictlyLessSomewhere = true;
            }
        }
        assertTrue(
                "initial-connect and reconnect curves must differ for at least one attempt",
                strictlyLessSomewhere);
    }

    @Test
    public void initialAndReconnectDifferAtFirstAttempt() {
        // At attempt=1 the two curves are visibly different: initial ~500 ms vs reconnect ~2000 ms.
        // Pin the difference so a future refactor that accidentally collapses them fails loudly.
        long initial = ObdElmDecode.initialConnectBackoffMs(1);
        long reconnect = ObdElmDecode.reconnectBackoffMs(1);
        assertNotEquals(
                "initial and reconnect backoff should differ at attempt=1 (initial is gentler)",
                initial,
                reconnect);
    }

    // ---- boundary behavior -----------------------------------------------------------

    @Test
    public void zeroAttemptReturnsZeroWithoutThrowing() {
        // Current contract (read from ObdElmDecode): attempt < 1 returns 0L rather than
        // throwing. Document that choice here so any change to throw IllegalArgumentException
        // is a deliberate, breaking edit caught by this test.
        assertEquals(0L, ObdElmDecode.reconnectBackoffMs(0));
        assertEquals(0L, ObdElmDecode.initialConnectBackoffMs(0));
    }

    @Test
    public void negativeAttemptReturnsZeroWithoutThrowing() {
        // Same contract extended to negative input: both helpers clamp to 0 silently.
        assertEquals(0L, ObdElmDecode.reconnectBackoffMs(-1));
        assertEquals(0L, ObdElmDecode.initialConnectBackoffMs(-1));
        assertEquals(0L, ObdElmDecode.reconnectBackoffMs(Integer.MIN_VALUE));
        assertEquals(0L, ObdElmDecode.initialConnectBackoffMs(Integer.MIN_VALUE));
    }
}
