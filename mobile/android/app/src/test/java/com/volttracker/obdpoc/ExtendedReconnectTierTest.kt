package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure-JVM tests for [ExtendedReconnectTier] (audit item B3): the entry decision, the fake-clock
 * window arithmetic, and the signal-vs-timeout wait semantics that let an ACL-connected broadcast
 * or a user cancel end the inter-attempt wait immediately.
 */
class ExtendedReconnectTierTest {
    // ---- shouldEnter: only a connected, actively-driving session earns the extended tier ----

    @Test
    fun shouldEnterOnlyForConnectedActivelyDrivingSessions() {
        for (state in listOf("driving_ev", "driving_gas", "ready")) {
            assertTrue(
                "a mid-drive drop ($state) after connecting must enter the extended tier",
                ExtendedReconnectTier.shouldEnter(true, state),
            )
            assertFalse(
                "a session that never connected must NOT enter the extended tier even if $state",
                ExtendedReconnectTier.shouldEnter(false, state),
            )
        }
    }

    @Test
    fun shouldEnterRejectsParkedPluggedChargingAndUnknownStates() {
        // The asleep-car / parked-idle handling must not regress: a drop in a parking lot or on
        // a charger keeps the prompt pre-B3 stop instead of burning battery for 15 minutes.
        for (state in listOf("parked", "plugged", "charging", "unknown", "")) {
            assertFalse(
                "exhaustion while $state must stop promptly, not retry for the extended window",
                ExtendedReconnectTier.shouldEnter(true, state),
            )
        }
    }

    // ---- window arithmetic (fake clock) ----------------------------------------------------

    @Test
    fun windowExpiresOnlyAfterTheConfiguredWindowElapses() {
        val clock = AtomicLong(1_000L)
        val tier = ExtendedReconnectTier(intervalMs = 1L, windowMs = 900_000L, nowMs = clock::get)

        tier.begin()
        assertTrue(tier.active)
        assertFalse("window must not be expired right after begin()", tier.windowExpired())

        clock.set(1_000L + 900_000L - 1L)
        assertFalse("one ms before the deadline the tier keeps retrying", tier.windowExpired())

        clock.set(1_000L + 900_000L)
        assertTrue("at the deadline the tier must give up cleanly", tier.windowExpired())
    }

    @Test
    fun resetDisarmsTheTierAndClearsTheAttemptCount() {
        val tier = ExtendedReconnectTier(intervalMs = 1L, windowMs = 100L)
        tier.begin()
        assertTrue(tier.awaitNextAttempt())
        assertEquals(1, tier.attemptCount)

        tier.reset()

        assertFalse(tier.active)
        assertEquals(0, tier.attemptCount)
    }

    // ---- wait semantics --------------------------------------------------------------------

    @Test
    fun awaitNextAttemptElapsesTheIntervalWhenNoSignalArrives() {
        val tier = ExtendedReconnectTier(intervalMs = 120L, windowMs = 60_000L)
        tier.begin()

        val start = System.nanoTime()
        assertTrue(tier.awaitNextAttempt())
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L

        assertTrue(
            "an unsignalled wait must sleep out the interval (waited ${elapsedMs}ms)",
            elapsedMs >= 100L,
        )
        assertEquals(1, tier.attemptCount)
    }

    @Test
    fun signalWakesAPendingWaitImmediately() {
        // A generous interval that would blow the test timeout if the signal were ignored.
        val tier = ExtendedReconnectTier(intervalMs = 60_000L, windowMs = 900_000L)
        tier.begin()

        val signaller =
            Thread({
                try {
                    Thread.sleep(50L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                tier.signal()
            }, "acl-signal")
        signaller.isDaemon = true
        signaller.start()

        val start = System.nanoTime()
        assertTrue(tier.awaitNextAttempt())
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L
        signaller.join(1_000L)

        assertTrue(
            "a signalled wait must return well before the 60s interval (waited ${elapsedMs}ms)",
            elapsedMs < 10_000L,
        )
    }

    @Test
    fun beginDropsStaleSignalsFromBeforeTheTierWasArmed() {
        val tier = ExtendedReconnectTier(intervalMs = 150L, windowMs = 60_000L)
        tier.signal() // stale: arrives before the tier is armed
        tier.begin()

        val start = System.nanoTime()
        assertTrue(tier.awaitNextAttempt())
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L

        assertTrue(
            "a stale pre-begin signal must not short-circuit the first wait (waited ${elapsedMs}ms)",
            elapsedMs >= 100L,
        )
    }

    @Test
    fun interruptionEndsTheWaitAndPreservesTheInterruptFlag() {
        val tier = ExtendedReconnectTier(intervalMs = 60_000L, windowMs = 900_000L)
        tier.begin()

        var result = true
        var interruptedFlag = false
        val waiter =
            Thread({
                result = tier.awaitNextAttempt()
                interruptedFlag = Thread.currentThread().isInterrupted
            }, "tier-waiter")
        waiter.start()
        Thread.sleep(50L)
        waiter.interrupt()
        waiter.join(5_000L)

        assertFalse("waiter thread must have exited", waiter.isAlive)
        assertFalse("an interrupted wait must report the session is stopping", result)
        assertTrue("the interrupt flag must be preserved for the outer loop", interruptedFlag)
    }
}
