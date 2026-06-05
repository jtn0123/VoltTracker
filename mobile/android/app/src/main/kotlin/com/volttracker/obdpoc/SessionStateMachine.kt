package com.volttracker.obdpoc

import java.util.Locale

/** Explicit, testable session-state contract for [ObdService]. */
class SessionStateMachine {
    enum class Phase {
        IDLE,
        CONNECTING,
        CONNECTED,
        SCANNING,
        CLEAR_DTC,
        DEMO,
        STOPPING,
        ERROR,
    }

    private var currentPhase = Phase.IDLE
    private var currentDetail = ""
    private var blockedFlag = false

    @Synchronized
    fun start(
        next: Phase?,
        detail: String?,
    ) {
        require(next != null && next != Phase.IDLE && next != Phase.STOPPING) {
            "Invalid starting phase: $next"
        }
        currentPhase = next
        currentDetail = detail ?: ""
        blockedFlag = false
    }

    @Synchronized
    fun stop(detail: String?) {
        currentPhase = Phase.IDLE
        currentDetail = detail ?: ""
        blockedFlag = false
    }

    @Synchronized
    fun observeStatus(
        state: String?,
        detail: String?,
        blocked: Boolean,
    ) {
        currentPhase = phaseForDashboardState(state, blocked)
        currentDetail = detail ?: ""
        blockedFlag = blocked
    }

    @Synchronized
    fun phase(): Phase = currentPhase

    @Synchronized
    fun detail(): String = currentDetail

    @Synchronized
    fun blocked(): Boolean = blockedFlag

    @Synchronized
    fun active(): Boolean = currentPhase != Phase.IDLE && currentPhase != Phase.ERROR

    companion object {
        @JvmStatic
        fun phaseForDashboardState(
            state: String?,
            blocked: Boolean,
        ): Phase {
            if (blocked) {
                return Phase.ERROR
            }
            return when ((state ?: "").trim().lowercase(Locale.US)) {
                "connecting", "initializing", "reconnecting" -> Phase.CONNECTING
                "connected" -> Phase.CONNECTED
                "scanning", "scan-complete" -> Phase.SCANNING
                "clearing-codes", "codes-cleared" -> Phase.CLEAR_DTC
                "demo" -> Phase.DEMO
                "stopping" -> Phase.STOPPING
                "error" -> Phase.ERROR
                // "idle", "ready", and anything unrecognised fall back to IDLE.
                else -> Phase.IDLE
            }
        }
    }
}
