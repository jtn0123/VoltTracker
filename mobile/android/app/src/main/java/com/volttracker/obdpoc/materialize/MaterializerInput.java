package com.volttracker.obdpoc.materialize;

/** Immutable bundle of the session identity and time bounds the materializers operate on. */
public final class MaterializerInput {
    public final long sessionId;
    public final long startedAtMs;
    public final long closedAtMs;

    public MaterializerInput(long sessionId, long startedAtMs, long closedAtMs) {
        this.sessionId = sessionId;
        this.startedAtMs = startedAtMs;
        this.closedAtMs = closedAtMs;
    }
}
