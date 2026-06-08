package com.volttracker.obdpoc

/**
 * Coarse classification of why an OBD adapter connect / first-read attempt failed. Produced by
 * [ConnectionFailureClassifier] from the [java.io.IOException] + timing of the failed attempt, and
 * consumed by:
 *
 *  - `ObdPollingEngine` — surfaces it on `reconnect`, `reconnect_exhausted` and `wedged_suspected`
 *    JSONL events as `failureClass`.
 *  - `ObdService.broadcastStatus` — auto-merges the last value onto every status payload as
 *    `failureClass` (via [wireName]) so the dashboard can show actionable copy instead of a raw
 *    stack trace.
 *  - Dashboard — picks user-facing error copy and troubleshooter steps off the [wireName] string.
 *
 * The [wireName] short snake-case strings are the stable JSON wire format. Renaming one is a wire
 * break for the dashboard — add new constants at the bottom and migrate consumers first.
 */
enum class FailureClass(
    private val wire: String,
) {
    /**
     * Socket opened (Android reported RFCOMM connect success), but the very first read returned
     * `-1` within the wedged window (<500 ms). Strong signal that the adapter accepted the SPP slot
     * but is wedged — another app may already own it, or the adapter firmware needs a power-cycle.
     */
    INSTANT_DROP("instant_drop"),

    /**
     * [android.bluetooth.BluetoothSocket.connect] itself threw, or the watchdog inside
     * [ElmConnection.open] closed a socket that never finished connecting. Usually means the car is
     * asleep (ELM327 has no power) or the adapter is out of range.
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
     * Remote side actively refused the RFCOMM connection. Distinct from [CONNECT_TIMEOUT] (which is
     * silence): the adapter heard us and said no — typically because another OBD client is already
     * attached.
     */
    REMOTE_REFUSED("remote_refused"),

    /** Default bucket for an exception that did not match any of the above heuristics. */
    UNKNOWN("unknown"),
    ;

    /** Stable snake-case string used in JSONL payloads and status broadcasts. */
    fun wireName(): String = wire
}
