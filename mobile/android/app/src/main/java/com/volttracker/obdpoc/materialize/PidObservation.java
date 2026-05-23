package com.volttracker.obdpoc.materialize;

/**
 * Read-side view of one row from {@code pid_observations} as the materializers consume it.
 *
 * <p><b>Null-handling contract.</b> The three captured-from-wire strings — {@code command}, {@code
 * header}, {@code response} — are normalised from {@code null} to {@code ""} so downstream string
 * code can treat them uniformly. The two interpretive fields — {@code parserKey} and {@code
 * parsedNumeric} — are intentionally nullable because {@code null} carries meaning here:
 *
 * <ul>
 *   <li>{@code parserKey == null} means "no parser claimed this observation" (today this is the
 *       common case; see below).
 *   <li>{@code parsedNumeric == null} means "no numeric value was extracted" (distinct from {@code
 *       0.0}, which would mean "parsed and the value is zero").
 * </ul>
 *
 * <p>{@code parserKey} is a stable identifier of <em>which</em> parser produced this observation
 * (e.g. {@code pluggedIn}, {@code packCurrent}). Today no PID parser emits a key — the field is
 * here so future Volt-specific parsers can drive plugged/charging detection directly instead of
 * relying on adapter-voltage heuristics.
 */
public final class PidObservation {
    public final long capturedAtMs;
    public final String command;
    public final String header;
    public final String response;
    public final String parserKey;
    public final Double parsedNumeric;

    public PidObservation(
            long capturedAtMs,
            String command,
            String header,
            String response,
            String parserKey,
            Double parsedNumeric) {
        this.capturedAtMs = capturedAtMs;
        this.command = command == null ? "" : command;
        this.header = header == null ? "" : header;
        this.response = response == null ? "" : response;
        this.parserKey = parserKey;
        this.parsedNumeric = parsedNumeric;
    }
}
