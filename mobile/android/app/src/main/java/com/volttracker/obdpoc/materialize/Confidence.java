package com.volttracker.obdpoc.materialize;

/**
 * How sure a materializer is in the row it emitted. Mapped to the {@code confidence REAL} column on
 * {@code trip_segments} / {@code charge_sessions} by {@link #asScore()}.
 *
 * <p>This is intentionally separate from {@link com.volttracker.obdpoc.classify.Confidence}, which
 * is the per-frame classifier confidence — the materializer rolls many frames into one row and the
 * scoring rubric is different.
 */
public enum Confidence {
    /** Not enough signal to draw any conclusion — materializers don't emit rows at this level. */
    UNKNOWN(0.0),
    /** Heuristic only (e.g. voltage-based charging guess with no PID confirmation). */
    WEAK(0.4),
    /** Multiple agreeing samples or a high sample count. */
    OBSERVED(0.8);

    private final double score;

    Confidence(double score) {
        this.score = score;
    }

    public double asScore() {
        return score;
    }
}
