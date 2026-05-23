package com.volttracker.obdpoc.classify;

import java.util.ArrayList;
import java.util.List;

/**
 * Single source of truth for "what state is the car in?". Inputs are the raw signals collected by
 * the polling engine (speed, RPM, adapter voltage, pack current, plus a couple of explicit Volt
 * hints); the output is a {@link ClassifierResult} that the engine publishes verbatim on the
 * app-state payload.
 *
 * <p>The rules favor {@link VehicleState#UNKNOWN} over a confident wrong answer — a misleading
 * "parked" reading is worse than admitting we don't know.
 */
public final class VehicleStateClassifier {

    private static final double DRIVING_SPEED_KPH = 5.0;
    private static final double IGNITION_RPM = 300.0;
    private static final double CHARGING_CURRENT_A = 1.0;
    private static final double DCDC_ACTIVE_VOLTS = 13.0;
    private static final double LOW_BATTERY_VOLTS = 12.7;

    private VehicleStateClassifier() {}

    public static ClassifierResult classify(ClassifierInput in) {
        if (in == null || allSignalsNull(in)) {
            return new ClassifierResult(VehicleState.UNKNOWN, Confidence.UNKNOWN, List.of());
        }

        List<String> reasons = new ArrayList<>(3);

        // Charging beats everything else: if the car is plugged in we are not "driving" no
        // matter what stray RPM/speed noise shows up.
        if (Boolean.TRUE.equals(in.pluggedHint)) {
            if (in.packCurrentA != null && in.packCurrentA > CHARGING_CURRENT_A) {
                reasons.add("plugged");
                reasons.add("current_into_pack");
                return new ClassifierResult(VehicleState.CHARGING, Confidence.CERTAIN, reasons);
            }
            reasons.add("plugged_hint");
            return new ClassifierResult(VehicleState.PLUGGED, Confidence.OBSERVED, reasons);
        }

        boolean engineRunning =
                Boolean.TRUE.equals(in.engineRunningHint)
                        || (in.rpm != null && in.rpm > IGNITION_RPM);
        boolean moving = in.speedKph != null && in.speedKph > DRIVING_SPEED_KPH;

        if (engineRunning) {
            if (moving) {
                reasons.add("engine_running");
                reasons.add("moving");
                return new ClassifierResult(VehicleState.DRIVING_GAS, Confidence.OBSERVED, reasons);
            }
            reasons.add("engine_running");
            reasons.add("stationary");
            return new ClassifierResult(VehicleState.READY, Confidence.OBSERVED, reasons);
        }

        if (moving) {
            reasons.add("moving");
            reasons.add("engine_quiet");
            return new ClassifierResult(VehicleState.DRIVING_EV, Confidence.OBSERVED, reasons);
        }

        if (in.adapterVoltage != null) {
            if (in.adapterVoltage > DCDC_ACTIVE_VOLTS) {
                reasons.add("charging_voltage");
                return new ClassifierResult(VehicleState.READY, Confidence.WEAK, reasons);
            }
            if (in.adapterVoltage <= LOW_BATTERY_VOLTS) {
                reasons.add("low_voltage");
                return new ClassifierResult(VehicleState.PARKED, Confidence.WEAK, reasons);
            }
            reasons.add("voltage_ambiguous");
            return new ClassifierResult(VehicleState.UNKNOWN, Confidence.WEAK, reasons);
        }

        reasons.add("no_signal");
        return new ClassifierResult(VehicleState.UNKNOWN, Confidence.UNKNOWN, reasons);
    }

    private static boolean allSignalsNull(ClassifierInput in) {
        return in.speedKph == null
                && in.rpm == null
                && in.adapterVoltage == null
                && in.packCurrentA == null
                && in.pluggedHint == null
                && in.engineRunningHint == null;
    }
}
