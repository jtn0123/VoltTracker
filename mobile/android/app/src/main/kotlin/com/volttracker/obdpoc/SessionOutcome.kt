package com.volttracker.obdpoc

/**
 * One immutable snapshot of the cross-thread session-outcome state [ObdService] accumulates while
 * a session runs and reads back when it finalizes the session row (audit item B5).
 *
 * Written from the poll/IO thread (status broadcasts, failure classification, the voltage probe),
 * the competing-app executor, and the main thread (session start / log open); read together by
 * `ObdService.closeSessionLog` and on every status broadcast. Publishing the whole record through
 * a single `AtomicReference` (copy-on-write CAS) means every reader sees one
 * consistent snapshot — previously these lived in five separately-@Volatile fields, so a close
 * racing a status write could persist `state` from one write and `detail`/`failureClass` from
 * another.
 */
data class SessionOutcome(
    /** Last dashboard-facing session state (e.g. "connected", "error"); "" before any status. */
    val state: String = "",
    /** Human-readable detail accompanying [state]. */
    val detail: String = "",
    /** Most recent classified connection failure, or null when cleared / none recorded. */
    val failureClass: FailureClass? = null,
    /** Last adapter-reported aux-battery voltage, or null when not probed this session. */
    val voltage: Double? = null,
    /** Comma-separated competing OBD app names, or null when not scanned / none found. */
    val competingAppsCsv: String? = null,
)
