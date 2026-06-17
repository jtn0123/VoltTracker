package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for [ClientErrorRateLimiter], extracted off [VoltBridge.logClientError]. Pins
 * the token-bucket burst, the drop-count fold-in, and time-based refill with an injected clock.
 */
class ClientErrorRateLimiterTest {
    @Test
    fun admitsUpToTheBurstThenDrops() {
        val limiter = ClientErrorRateLimiter(burst = 3, refillPerSec = 1, clock = { 0L })

        // First three calls admit (no prior drops -> 0); the fourth is over-rate -> -1.
        assertEquals(0L, limiter.acquireOrCountDrop())
        assertEquals(0L, limiter.acquireOrCountDrop())
        assertEquals(0L, limiter.acquireOrCountDrop())
        assertEquals(-1L, limiter.acquireOrCountDrop())
    }

    @Test
    fun foldsTheDroppedCountIntoTheNextAdmittedCall() {
        var nowMs = 0L
        val limiter = ClientErrorRateLimiter(burst = 1, refillPerSec = 1, clock = { nowMs })

        assertEquals("first call admits", 0L, limiter.acquireOrCountDrop())
        assertEquals("bucket empty -> drop", -1L, limiter.acquireOrCountDrop())
        assertEquals("another drop", -1L, limiter.acquireOrCountDrop())

        // After a full second the bucket refills one token; the next admit reports the 2 drops.
        nowMs = 1_000L
        assertEquals("admitted call folds in the dropped count", 2L, limiter.acquireOrCountDrop())
        // And the drop counter resets after being reported.
        nowMs = 2_000L
        assertEquals(0L, limiter.acquireOrCountDrop())
    }

    @Test
    fun refillIsCappedAtTheBurst() {
        var nowMs = 0L
        val limiter = ClientErrorRateLimiter(burst = 2, refillPerSec = 5, clock = { nowMs })

        assertEquals(0L, limiter.acquireOrCountDrop())
        assertEquals(0L, limiter.acquireOrCountDrop())
        // A long idle would refill 50 tokens unbounded; the cap keeps it at burst=2, so only two
        // back-to-back calls admit before the bucket is empty again.
        nowMs = 10_000L
        assertEquals(0L, limiter.acquireOrCountDrop())
        assertEquals(0L, limiter.acquireOrCountDrop())
        assertTrue("refill must be capped at the burst", limiter.acquireOrCountDrop() < 0L)
    }
}
