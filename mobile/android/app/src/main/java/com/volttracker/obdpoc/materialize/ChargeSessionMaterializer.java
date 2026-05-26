package com.volttracker.obdpoc.materialize;

import java.util.ArrayList;
import java.util.List;

/**
 * Conservative charge-session materializer.
 *
 * <p><b>Primary signal — HV pack current.</b> When {@link TelemetrySample#packCurrentA} is
 * available (signed; discharge positive per the Volt convention) we treat a sample as plugged when
 * current is flowing <em>into</em> the pack — {@code packCurrentA <= -}{@link
 * Tunables#CHARGING_PACK_CURRENT_A_THRESHOLD}. Combined with near-zero speed, that's a
 * high-confidence "the car is on a charger" signal that {@link Confidence#OBSERVED} earns.
 *
 * <p><b>Fallback — aux 12V.</b> When pack current isn't in the sample (older sessions from
 * pre-schema-v8 installs, or vehicles where 222414 doesn't respond), we fall back to the legacy
 * heuristic: {@link TelemetrySample#adapterVoltage} above {@link
 * Tunables#PLUGGED_VOLTAGE_THRESHOLD} with near-zero speed. This is documented {@link
 * Confidence#WEAK} because the alternator under load can raise 12V transiently without an EVSE
 * involved.
 *
 * <p><b>Gap behaviour.</b> A single gap inside an otherwise-plugged window <em>merges</em> into one
 * session with {@link ChargeSession#interruptionCount} incremented; the intent is that a brief
 * cable jiggle or polling stall during a real charge does not split the row. Only gaps that exceed
 * {@link Tunables#SPLIT_GAP_MS} (i.e. plugged signal absent for that long) actually split the
 * window into two sessions.
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

        /**
         * Pack current more negative than this (i.e. ≥ this many amps flowing INTO the pack) is
         * treated as charging. The 1.0 A floor filters out parked-with-12V-conversion noise (the
         * DC-DC trickle from the pack into the 12V battery can show up as a tiny negative current
         * at idle); real EVSE charging is tens to hundreds of amps.
         */
        static final double CHARGING_PACK_CURRENT_A_THRESHOLD = 1.0;

        /** Adapter voltage above this with low speed indicates the car is on a charger. */
        static final double PLUGGED_VOLTAGE_THRESHOLD = 13.5;

        /** Speed above this means the car is moving — not plugged in. */
        static final double STATIONARY_SPEED_KPH = 1.0;
    }

    public static List<ChargeSession> materialize(MaterializerInput in, MaterializerData data) {
        List<ChargeSession> result = new ArrayList<>();
        if (in == null || data == null) {
            return result;
        }
        List<TelemetrySample> telemetry = data.readTelemetrySamples(in.sessionId);

        if (telemetry == null || telemetry.isEmpty()) {
            // Without telemetry we have no continuous window to anchor a charge session on.
            return result;
        }

        // Walk the samples once to find out whether the high-confidence pack-current signal
        // ever fires in this session; if it does we materialize OBSERVED, otherwise the
        // fallback voltage heuristic produces WEAK rows.
        boolean usedPackCurrent = false;
        List<TelemetrySample> currentRun = new ArrayList<>();
        int interruptions = 0;

        for (TelemetrySample sample : telemetry) {
            PluggedReason plugged = isPluggedSample(sample);
            if (plugged != PluggedReason.NOT_PLUGGED) {
                if (plugged == PluggedReason.PACK_CURRENT) {
                    usedPackCurrent = true;
                }
                if (!currentRun.isEmpty()) {
                    long gap =
                            sample.capturedAtMs
                                    - currentRun.get(currentRun.size() - 1).capturedAtMs;
                    if (gap > Tunables.SPLIT_GAP_MS) {
                        ChargeSession finalized =
                                finalize(currentRun, interruptions, usedPackCurrent);
                        if (finalized != null) {
                            result.add(finalized);
                        }
                        currentRun = new ArrayList<>();
                        interruptions = 0;
                        usedPackCurrent = plugged == PluggedReason.PACK_CURRENT;
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
            ChargeSession finalized = finalize(currentRun, interruptions, usedPackCurrent);
            if (finalized != null) {
                result.add(finalized);
            }
        }
        return result;
    }

    /** Why a sample qualified as plugged, or {@link #NOT_PLUGGED} if it didn't. */
    private enum PluggedReason {
        NOT_PLUGGED,
        PACK_CURRENT,
        AUX_VOLTAGE
    }

    /**
     * Combines the primary pack-current signal with the legacy aux-voltage fallback.
     *
     * <p>Discharge is positive in this codebase, so real charging current is <em>negative</em>. A
     * strong negative current (at or beyond {@link Tunables#CHARGING_PACK_CURRENT_A_THRESHOLD} in
     * magnitude) is a high-confidence "the car is on a charger" signal — many amps cannot flow INTO
     * the pack while the car is moving — so it overrides a missing or near-zero speed reading. This
     * matters in practice because the Volt's 0xFF speed sentinel during a charge makes the {@code
     * speed_kph} column NULL for most of the session; without the short-circuit we'd reject every
     * charging sample on the sentinel-mode path.
     *
     * <p>For weaker signals (no pack current at all, or aux voltage only), we still require a valid
     * stationary speed reading because the aux-voltage heuristic is too noisy to trust on its own.
     */
    private static PluggedReason isPluggedSample(TelemetrySample sample) {
        if (sample == null) {
            return PluggedReason.NOT_PLUGGED;
        }
        boolean strongCharging =
                sample.packCurrentA != null
                        && sample.packCurrentA <= -Tunables.CHARGING_PACK_CURRENT_A_THRESHOLD;
        boolean clearlyMoving =
                sample.speedKph != null && sample.speedKph > Tunables.STATIONARY_SPEED_KPH;
        if (clearlyMoving) {
            return PluggedReason.NOT_PLUGGED;
        }
        if (strongCharging) {
            return PluggedReason.PACK_CURRENT;
        }
        if (sample.speedKph == null) {
            // Without a strong pack-current signal AND without a speed reading, we cannot
            // distinguish stationary-plugged from moving-with-regen — be conservative.
            return PluggedReason.NOT_PLUGGED;
        }
        if (sample.packCurrentA != null) {
            // Definitive pack-current reading that does NOT indicate charging — overrides the
            // noisier aux-voltage heuristic.
            return PluggedReason.NOT_PLUGGED;
        }
        if (sample.adapterVoltage != null
                && sample.adapterVoltage > Tunables.PLUGGED_VOLTAGE_THRESHOLD) {
            return PluggedReason.AUX_VOLTAGE;
        }
        return PluggedReason.NOT_PLUGGED;
    }

    private static ChargeSession finalize(
            List<TelemetrySample> run, int interruptions, boolean usedPackCurrent) {
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
        Confidence confidence = usedPackCurrent ? Confidence.OBSERVED : Confidence.WEAK;
        Double startSoc = firstFiniteSoc(run);
        Double endSoc = lastFiniteSoc(run);
        Double peakPowerKw = peakChargePowerKw(run);
        Double energyKwh = integrateChargeEnergyKwh(run);
        return new ChargeSession(
                startedAtMs,
                endedAtMs,
                durationMs,
                voltageStart,
                voltageEnd,
                interruptions,
                "unknown",
                confidence,
                startSoc,
                endSoc,
                peakPowerKw,
                energyKwh);
    }

    private static Double firstFiniteSoc(List<TelemetrySample> run) {
        for (TelemetrySample s : run) {
            if (s.socPct != null && !s.socPct.isNaN() && !s.socPct.isInfinite()) {
                return s.socPct;
            }
        }
        return null;
    }

    private static Double lastFiniteSoc(List<TelemetrySample> run) {
        for (int i = run.size() - 1; i >= 0; i--) {
            TelemetrySample s = run.get(i);
            if (s.socPct != null && !s.socPct.isNaN() && !s.socPct.isInfinite()) {
                return s.socPct;
            }
        }
        return null;
    }

    /**
     * Peak power INTO the pack during the window, in kW. Pack convention is discharge-positive, so
     * charging power = {@code -(packVoltage * packCurrentA / 1000)}. Returns {@code null} when no
     * sample in the run carried both values.
     */
    private static Double peakChargePowerKw(List<TelemetrySample> run) {
        Double peak = null;
        for (TelemetrySample s : run) {
            if (s.packVoltage == null || s.packCurrentA == null) {
                continue;
            }
            double kw = -(s.packVoltage * s.packCurrentA) / 1000.0;
            if (Double.isNaN(kw) || Double.isInfinite(kw)) {
                continue;
            }
            if (kw <= 0.0) {
                continue;
            }
            if (peak == null || kw > peak) {
                peak = kw;
            }
        }
        return peak;
    }

    /**
     * Trapezoidal integration of charging power across the run. Same sign convention as {@link
     * #peakChargePowerKw}. Returns {@code null} when fewer than two samples in the window carry
     * both pack values (a single point can't form an area).
     */
    private static Double integrateChargeEnergyKwh(List<TelemetrySample> run) {
        Double prevKw = null;
        Long prevMs = null;
        double energyKwh = 0.0;
        int integrated = 0;
        for (TelemetrySample s : run) {
            if (s.packVoltage == null || s.packCurrentA == null) {
                continue;
            }
            double kw = -(s.packVoltage * s.packCurrentA) / 1000.0;
            if (Double.isNaN(kw) || Double.isInfinite(kw)) {
                continue;
            }
            // Clip negative (discharge) samples to 0 so a brief discharge dip inside an otherwise-
            // plugged window doesn't subtract from the integrated charge total.
            if (kw < 0.0) {
                kw = 0.0;
            }
            if (prevKw != null && prevMs != null) {
                double hours = (s.capturedAtMs - prevMs) / 3_600_000.0;
                if (hours > 0.0) {
                    energyKwh += ((prevKw + kw) / 2.0) * hours;
                    integrated += 1;
                }
            }
            prevKw = kw;
            prevMs = s.capturedAtMs;
        }
        return integrated == 0 ? null : energyKwh;
    }
}
