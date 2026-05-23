package com.volttracker.obdpoc;

/** Build-time and runtime feature flags. */
public final class BuildFlags {
    private BuildFlags() {}

    /**
     * Trip and charge-session materialization runs on session close. Conservative heuristics; can
     * be disabled if they misfire in the field.
     */
    public static final boolean MATERIALIZE_SESSIONS = true;
}
