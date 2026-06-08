package com.volttracker.obdpoc

/**
 * Build-time and runtime feature flags.
 *
 * First Kotlin source in the module: it exercises the Kotlin toolchain (compile, ktlint, JaCoCo,
 * Java interop) on a trivial, dependency-free class. `const val` members compile to `public static
 * final` fields, so Java call sites keep reading them as `BuildFlags.MATERIALIZE_SESSIONS` and the
 * compiler still constant-folds the branches that gate on them. New Android code goes here in
 * Kotlin — see CONTRIBUTING.md.
 */
object BuildFlags {
    /**
     * Trip and charge-session materialization runs on session close. Conservative heuristics; can
     * be disabled if they misfire in the field.
     */
    const val MATERIALIZE_SESSIONS = true

    /**
     * When true, every OBD command transacted with the adapter writes a `kind=command` row to
     * `status_events`. That's ≈8 rows/s during normal polling — useful when reproducing a decoding
     * bug but pure noise the rest of the time. The `pid_observations` table stays populated either
     * way, and the `.jsonl` session log file always captures the full command transcript, so
     * flipping this off does not lose data — only stops bloating the status-events table that
     * Insights / Trips queries join through.
     */
    const val TRACE_COMMANDS_TO_STATUS_EVENTS = false
}
