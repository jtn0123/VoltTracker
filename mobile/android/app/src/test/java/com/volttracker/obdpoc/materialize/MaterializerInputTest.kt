package com.volttracker.obdpoc.materialize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins [MaterializerInput]'s fail-fast window validation. SessionRecorder catches the throw and
 * skips materialization, so the point here is that an impossible window is rejected rather than
 * silently producing empty/garbage trips.
 */
class MaterializerInputTest {
    @Test
    fun validWindowConstructs() {
        val input = MaterializerInput(7L, T_BASE, T_BASE + 60_000L)
        assertEquals(7L, input.sessionId)
        assertEquals(T_BASE, input.startedAtMs)
        assertEquals(T_BASE + 60_000L, input.closedAtMs)
    }

    @Test
    fun zeroDurationWindowIsAllowed() {
        // A session that closes at the same instant it started yields no trips, which the
        // materializers handle — so it's a legal (if empty) input, not an error.
        val input = MaterializerInput(1L, T_BASE, T_BASE)
        assertEquals(T_BASE, input.closedAtMs)
    }

    @Test
    fun closedBeforeStartedIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            MaterializerInput(1L, T_BASE, T_BASE - 1L)
        }
    }

    @Test
    fun nonPositiveSessionIdIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            MaterializerInput(0L, T_BASE, T_BASE)
        }
    }

    @Test
    fun negativeStartIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            MaterializerInput(1L, -1L, T_BASE)
        }
    }

    companion object {
        private const val T_BASE = 1_700_000_000_000L
    }
}
