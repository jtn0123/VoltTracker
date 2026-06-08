package com.volttracker.obdpoc.materialize

/**
 * How sure a materializer is in the row it emitted. Mapped to the `confidence REAL` column on
 * `trip_segments` / `charge_sessions` by [asScore].
 *
 * This is intentionally separate from [com.volttracker.obdpoc.classify.Confidence], which is the
 * per-frame classifier confidence — the materializer rolls many frames into one row and the scoring
 * rubric is different.
 */
enum class Confidence(
    private val score: Double,
) {
    /** Not enough signal to draw any conclusion — materializers don't emit rows at this level. */
    UNKNOWN(0.0),

    /** Heuristic only (e.g. voltage-based charging guess with no PID confirmation). */
    WEAK(0.4),

    /** Multiple agreeing samples or a high sample count. */
    OBSERVED(0.8),
    ;

    fun asScore(): Double = score
}
