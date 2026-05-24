package com.volttracker.obdpoc;

/** Build-time and runtime feature flags. */
public final class BuildFlags {
    private BuildFlags() {}

    /**
     * Trip and charge-session materialization runs on session close. Conservative heuristics; can
     * be disabled if they misfire in the field.
     */
    public static final boolean MATERIALIZE_SESSIONS = true;

    /**
     * When true, every OBD command transacted with the adapter writes a {@code kind=command} row to
     * {@code status_events}. That's ≈8 rows/s during normal polling — useful when reproducing a
     * decoding bug but pure noise the rest of the time. The {@code pid_observations} table stays
     * populated either way, and the {@code .jsonl} session log file always captures the full
     * command transcript, so flipping this off does not lose data — only stops bloating the
     * status-events table that Insights / Trips queries join through.
     */
    public static final boolean TRACE_COMMANDS_TO_STATUS_EVENTS = false;
}
