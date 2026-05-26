package com.volttracker.obdpoc;

/**
 * Coarse classification of why an OBD adapter connect / first-read attempt failed. Produced by
 * {@link ConnectionFailureClassifier} from the {@link java.io.IOException} + timing of the failed
 * attempt, and consumed by:
 *
 * <ul>
 *   <li>{@code ObdPollingEngine} — surfaces it on {@code reconnect}, {@code reconnect_exhausted}
 *       and {@code wedged_suspected} JSONL events as {@code failureClass}.
 *   <li>{@code ObdService.broadcastStatus} — auto-merges the last value onto every status payload
 *       as {@code failureClass} (via {@link #wireName()}) so the dashboard can show actionable copy
 *       instead of a raw stack trace.
 *   <li>Dashboard (Bucket 4a) — picks user-facing error copy and troubleshooter steps off the
 *       {@link #wireName()} string.
 * </ul>
 *
 * <p>The {@link #wireName()} short snake-case strings are the stable JSON wire format. Renaming one
 * is a wire break for the dashboard — add new constants at the bottom and migrate consumers first.
 */
public enum FailureClass {
    /**
     * Socket opened (Android reported RFCOMM connect success), but the very first read returned
     * {@code -1} within the wedged window (&lt;500 ms). Strong signal that the adapter accepted the
     * SPP slot but is wedged — another app may already own it, or the adapter firmware needs a
     * power-cycle.
     */
    INSTANT_DROP("instant_drop"),

    /**
     * {@link android.bluetooth.BluetoothSocket#connect()} itself threw, or the watchdog inside
     * {@link ElmConnection#open} closed a socket that never finished connecting. Usually means the
     * car is asleep (ELM327 has no power) or the adapter is out of range.
     */
    CONNECT_TIMEOUT("connect_timeout"),

    /**
     * The OS could not resolve the ELM327 SPP service record. Either the adapter is unbonded, or
     * the stale SDP cache needs flushing — Android sometimes serves a cached miss for a freshly
     * paired adapter until the next bond refresh.
     */
    SDP_FAILURE("sdp_failure"),

    /** The Bluetooth adapter is null or disabled. The user must turn Bluetooth on. */
    BT_OFF("bt_off"),

    /**
     * The adapter is no longer bonded, or the bond is in a state Android will not accept. Usually
     * surfaces as "permission denied" or an explicit bond-state-changed signal.
     */
    BOND_LOST("bond_lost"),

    /**
     * Remote side actively refused the RFCOMM connection. Distinct from {@link #CONNECT_TIMEOUT}
     * (which is silence): the adapter heard us and said no — typically because another OBD client
     * is already attached.
     */
    REMOTE_REFUSED("remote_refused"),

    /** Default bucket for an exception that did not match any of the above heuristics. */
    UNKNOWN("unknown");

    private final String wire;

    FailureClass(String wire) {
        this.wire = wire;
    }

    /** Stable snake-case string used in JSONL payloads and status broadcasts. */
    public String wireName() {
        return wire;
    }
}
