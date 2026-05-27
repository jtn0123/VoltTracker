package com.volttracker.obdpoc;

import java.util.Locale;

/** Explicit, testable session-state contract for {@link ObdService}. */
final class SessionStateMachine {

    enum Phase {
        IDLE,
        CONNECTING,
        CONNECTED,
        SCANNING,
        CLEAR_DTC,
        DEMO,
        STOPPING,
        ERROR
    }

    private Phase phase = Phase.IDLE;
    private String detail = "";
    private boolean blocked;

    synchronized void start(Phase next, String detail) {
        if (next == null || next == Phase.IDLE || next == Phase.STOPPING) {
            throw new IllegalArgumentException("Invalid starting phase: " + next);
        }
        phase = next;
        this.detail = detail == null ? "" : detail;
        blocked = false;
    }

    synchronized void stop(String detail) {
        phase = Phase.IDLE;
        this.detail = detail == null ? "" : detail;
        blocked = false;
    }

    synchronized void observeStatus(String state, String detail, boolean blocked) {
        phase = phaseForDashboardState(state, blocked);
        this.detail = detail == null ? "" : detail;
        this.blocked = blocked;
    }

    synchronized Phase phase() {
        return phase;
    }

    synchronized String detail() {
        return detail;
    }

    synchronized boolean blocked() {
        return blocked;
    }

    synchronized boolean active() {
        return phase != Phase.IDLE && phase != Phase.ERROR;
    }

    static Phase phaseForDashboardState(String state, boolean blocked) {
        if (blocked) {
            return Phase.ERROR;
        }
        String clean = state == null ? "" : state.trim().toLowerCase(Locale.US);
        switch (clean) {
            case "connecting":
            case "initializing":
            case "reconnecting":
                return Phase.CONNECTING;
            case "connected":
                return Phase.CONNECTED;
            case "scanning":
            case "scan-complete":
                return Phase.SCANNING;
            case "clearing-codes":
            case "codes-cleared":
                return Phase.CLEAR_DTC;
            case "demo":
                return Phase.DEMO;
            case "stopping":
                return Phase.STOPPING;
            case "error":
                return Phase.ERROR;
            case "idle":
            case "ready":
            default:
                return Phase.IDLE;
        }
    }
}
