package com.volttracker.obdpoc.materialize

/**
 * Read-side view of one row from `pid_observations` as the materializers consume it.
 *
 * **Null-handling contract.** The three captured-from-wire strings — `command`, `header`,
 * `response` — are normalised from `null` to `""` so downstream string code can treat them
 * uniformly. The two interpretive fields — `parserKey` and `parsedNumeric` — are intentionally
 * nullable because `null` carries meaning here:
 *
 *  - `parserKey == null` means "no parser claimed this observation" (today this is the common case;
 *    see below).
 *  - `parsedNumeric == null` means "no numeric value was extracted" (distinct from `0.0`, which
 *    would mean "parsed and the value is zero").
 *
 * `parserKey` is a stable identifier of *which* parser produced this observation (e.g. `pluggedIn`,
 * `packCurrent`). Today no PID parser emits a key — the field is here so future Volt-specific
 * parsers can drive plugged/charging detection directly instead of relying on adapter-voltage
 * heuristics.
 */
class PidObservation(
    @JvmField val capturedAtMs: Long,
    command: String?,
    header: String?,
    response: String?,
    @JvmField val parserKey: String?,
    @JvmField val parsedNumeric: Double?,
) {
    @JvmField val command: String = command ?: ""

    @JvmField val header: String = header ?: ""

    @JvmField val response: String = response ?: ""
}
