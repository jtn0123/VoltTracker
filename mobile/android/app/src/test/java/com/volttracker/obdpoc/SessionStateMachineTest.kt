package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateMachineTest {
    @Test
    fun startAndStopTrackActivePhases() {
        val state = SessionStateMachine()

        state.start(SessionStateMachine.Phase.CONNECTING, "Connecting")
        assertEquals(SessionStateMachine.Phase.CONNECTING, state.phase())
        assertEquals("Connecting", state.detail())
        assertTrue(state.active())

        state.stop("Stopped")
        assertEquals(SessionStateMachine.Phase.IDLE, state.phase())
        assertEquals("Stopped", state.detail())
        assertFalse(state.active())
    }

    @Test
    fun startRejectsIdleOrStoppingAsSessionStarts() {
        val state = SessionStateMachine()

        assertThrows(IllegalArgumentException::class.java) {
            state.start(SessionStateMachine.Phase.IDLE, "bad")
        }
        assertThrows(IllegalArgumentException::class.java) {
            state.start(SessionStateMachine.Phase.STOPPING, "bad")
        }
    }

    @Test
    fun dashboardStatusesMapToExplicitPhases() {
        assertEquals(
            SessionStateMachine.Phase.CONNECTING,
            SessionStateMachine.phaseForDashboardState("initializing", false),
        )
        assertEquals(
            SessionStateMachine.Phase.CONNECTED,
            SessionStateMachine.phaseForDashboardState("connected", false),
        )
        assertEquals(
            SessionStateMachine.Phase.SCANNING,
            SessionStateMachine.phaseForDashboardState("scan-complete", false),
        )
        assertEquals(
            SessionStateMachine.Phase.CLEAR_DTC,
            SessionStateMachine.phaseForDashboardState("codes-cleared", false),
        )
        assertEquals(
            SessionStateMachine.Phase.DEMO,
            SessionStateMachine.phaseForDashboardState("demo", false),
        )
        assertEquals(
            SessionStateMachine.Phase.ERROR,
            SessionStateMachine.phaseForDashboardState("connected", true),
        )
    }

    @Test
    fun observeStatusStoresDetailAndBlockedFlag() {
        val state = SessionStateMachine()

        state.observeStatus("error", "Adapter timed out", true)

        assertEquals(SessionStateMachine.Phase.ERROR, state.phase())
        assertEquals("Adapter timed out", state.detail())
        assertTrue(state.blocked())
        assertFalse(state.active())
    }
}
