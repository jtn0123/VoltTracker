package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRetryCoordinatorTest {
    @Test
    fun secondInstantDropStartsWedgedModeAndLongBackoff() {
        val coordinator = ConnectionRetryCoordinator()

        val first = coordinator.recordFailure(FailureClass.INSTANT_DROP, 250L)
        assertEquals(1, first.attempt)
        assertFalse(first.wedgedMode)
        assertFalse(first.wedgedModeStarted)

        val second = coordinator.recordFailure(FailureClass.INSTANT_DROP, 300L)
        assertEquals(2, second.attempt)
        assertTrue(second.wedgedMode)
        assertTrue(second.wedgedModeStarted)
        assertEquals(2, second.consecutiveInstantDrops)

        val third = coordinator.recordFailure(FailureClass.INSTANT_DROP, 250L)
        assertEquals(3, third.attempt)
        assertTrue(third.wedgedMode)
        assertFalse(third.wedgedModeStarted)
        assertEquals(8_000L, third.backoffMs)
    }

    @Test
    fun nonInstantDropClearsWedgedMode() {
        val coordinator = ConnectionRetryCoordinator()
        coordinator.recordFailure(FailureClass.INSTANT_DROP, 250L)
        coordinator.recordFailure(FailureClass.INSTANT_DROP, 250L)

        val next = coordinator.recordFailure(FailureClass.CONNECT_TIMEOUT, 1_500L)

        assertFalse(next.wedgedMode)
        assertFalse(next.wedgedModeStarted)
        assertEquals(0, next.consecutiveInstantDrops)
    }

    @Test
    fun successfulConnectKeepsEverConnectedAndCanResetAttemptBudget() {
        val coordinator = ConnectionRetryCoordinator()
        coordinator.recordFailure(FailureClass.CONNECT_TIMEOUT, 1_500L)

        coordinator.noteConnected()
        coordinator.resetAttemptBudget()

        assertTrue(coordinator.everConnected)
        assertEquals(0, coordinator.attempt)
        val reconnect = coordinator.recordFailure(FailureClass.CONNECT_TIMEOUT, 1_500L)
        assertTrue(reconnect.everConnected)
        assertEquals(ObdElmDecode.reconnectBackoffMs(1), reconnect.backoffMs)
    }

    @Test
    fun attemptsExhaustAfterConfiguredBudget() {
        val coordinator = ConnectionRetryCoordinator(maxAttempts = 2)

        assertFalse(coordinator.recordFailure(FailureClass.CONNECT_TIMEOUT, 1_000L).exhausted)
        assertFalse(coordinator.recordFailure(FailureClass.CONNECT_TIMEOUT, 1_000L).exhausted)

        val exhausted = coordinator.recordFailure(FailureClass.CONNECT_TIMEOUT, 1_000L)
        assertTrue(exhausted.exhausted)
        assertEquals(3, exhausted.attempt)
        assertEquals(0L, exhausted.backoffMs)
    }
}
