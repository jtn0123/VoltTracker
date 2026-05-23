package com.volttracker.obdpoc.materialize;

import java.util.ArrayList;
import java.util.List;

/**
 * Conservative charge-session materializer.
 *
 * <p><b>Heuristic.</b> No PID parser today emits a dedicated "plugged in" or "charging current" key
 * (the {@link PidObservation#parserKey} field is reserved for when one does). Until then the
 * materializer falls back to a telemetry-only inference: {@link TelemetrySample#adapterVoltage}
 * above {@link Tunables#PLUGGED_VOLTAGE_THRESHOLD} combined with near-zero speed is treated as a
 * "plugged" sample. A continuous run of those samples — with no gap larger than {@link
 * Tunables#MAX_GAP_MS} — becomes one charge session.
 *
 * <p><b>Gap behaviour.</b> A single gap inside an otherwise-plugged window <em>merges</em> into one
 * session with {@link ChargeSession#interruptionCount} incremented; the intent is that a brief
 * cable jiggle or polling stall during a real charge does not split the row. Only gaps that exceed
 * {@link Tunables#SPLIT_GAP_MS} (i.e. plugged signal absent for that long) actually split the
 * window into two sessions.
 *
 * <p><b>Confidence.</b> Voltage-only inference is always {@link Confidence#WEAK}. If a future PID
 * parser starts emitting a plugged or pack-current key, the matcher in {@link #isPluggedPid}
 * upgrades the confidence to {@link Confidence#OBSERVED}.
 */
public final class ChargeSessionMaterializer {

    private ChargeSessionMaterializer() {}

    static final class Tunables {
        /** Brief gap inside a session — counted as an interruption but not a split. */
        static final long MAX_GAP_MS = 5L * 60_000L;

        /** Gap above this splits the window into two separate charge sessions. */
        static final long SPLIT_GAP_MS = 30L * 60_000L;

        /** Below this duration the window is rejected (likely noise). */
        static final long MIN_DURATION_MS = 60_000L;

        /** Adapter voltage above this with low speed indicates the car is on a charger. */
        static final double PLUGGED_VOLTAGE_THRESHOLD = 13.5;

        /** Speed above this means the car is moving — not plugged in. */
        static final double STATIONARY_SPEED_KPH = 1.0;
    }

    /** Reserved parser keys a future PID parser would emit. */
    private static final String PARSER_PLUGGED_IN = "pluggedIn";

    private static final String PARSER_PACK_CURRENT = "packCurrent";

    public static List<ChargeSession> materialize(MaterializerInput in, MaterializerData data) {
        List<ChargeSession> result = new ArrayList<>();
        if (in == null || data == null) {
            return result;
        }
        List<TelemetrySample> telemetry = data.readTelemetrySamples(in.sessionId);
        List<PidObservation> pids = data.readPidObservations(in.sessionId);
        boolean pidConfirmed = hasAnyPluggedPid(pids);

        if (telemetry == null || telemetry.isEmpty()) {
            // Without telemetry voltage data and without a dedicated plugged PID we have nothing
            // to merge: a PID-only signal alone is too sparse to build start/end bounds. Returning
            // empty here is the documented conservative behaviour.
            return result;
        }

        List<TelemetrySample> currentRun = new ArrayList<>();
        int interruptions = 0;

        for (TelemetrySample sample : telemetry) {
            boolean plugged = isPluggedSample(sample);
            if (plugged) {
                if (!currentRun.isEmpty()) {
                    long gap =
                            sample.capturedAtMs
                                    - currentRun.get(currentRun.size() - 1).capturedAtMs;
                    if (gap > Tunables.SPLIT_GAP_MS) {
                        ChargeSession finalized = finalize(currentRun, interruptions, pidConfirmed);
                        if (finalized != null) {
                            result.add(finalized);
                        }
                        currentRun = new ArrayList<>();
                        interruptions = 0;
                    } else if (gap > Tunables.MAX_GAP_MS) {
                        interruptions += 1;
                    }
                }
                currentRun.add(sample);
            }
            // Non-plugged samples between plugged runs are ignored — only the gap timestamp
            // matters and that is computed off the last plugged sample.
        }
        if (!currentRun.isEmpty()) {
            ChargeSession finalized = finalize(currentRun, interruptions, pidConfirmed);
            if (finalized != null) {
                result.add(finalized);
            }
        }
        return result;
    }

    /**
     * Voltage-only "is this sample part of a charging window?" check.
     *
     * <p>Treats unknown speed ({@code speedKph == null}) as <em>not stationary</em> and therefore
     * not plugged. The car could be moving with a high adapter voltage (e.g. alternator under load)
     * and without a speed signal we can't tell — so we err on the side of dropping the sample
     * rather than risk false-positive charge sessions. The voltage-only heuristic is already weak;
     * this keeps it from getting weaker.
     */
    private static boolean isPluggedSample(TelemetrySample sample) {
        if (sample == null || sample.adapterVoltage == null) {
            return false;
        }
        if (sample.adapterVoltage <= Tunables.PLUGGED_VOLTAGE_THRESHOLD) {
            return false;
        }
        if (sample.speedKph == null) {
            // Unknown speed → conservative: do not infer plugged.
            return false;
        }
        if (sample.speedKph > Tunables.STATIONARY_SPEED_KPH) {
            return false;
        }
        return true;
    }

    private static boolean hasAnyPluggedPid(List<PidObservation> pids) {
        if (pids == null) {
            return false;
        }
        for (PidObservation observation : pids) {
            if (isPluggedPid(observation)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPluggedPid(PidObservation observation) {
        if (observation == null || observation.parserKey == null) {
            return false;
        }
        return PARSER_PLUGGED_IN.equals(observation.parserKey)
                || PARSER_PACK_CURRENT.equals(observation.parserKey);
    }

    private static ChargeSession finalize(
            List<TelemetrySample> run, int interruptions, boolean pidConfirmed) {
        if (run.isEmpty()) {
            return null;
        }
        long startedAtMs = run.get(0).capturedAtMs;
        long endedAtMs = run.get(run.size() - 1).capturedAtMs;
        long durationMs = Math.max(0L, endedAtMs - startedAtMs);
        if (durationMs < Tunables.MIN_DURATION_MS) {
            return null;
        }
        Double voltageStart = run.get(0).adapterVoltage;
        Double voltageEnd = run.get(run.size() - 1).adapterVoltage;
        Confidence confidence = pidConfirmed ? Confidence.OBSERVED : Confidence.WEAK;
        return new ChargeSession(
                startedAtMs,
                endedAtMs,
                durationMs,
                voltageStart,
                voltageEnd,
                interruptions,
                "unknown",
                confidence);
    }
}
