package com.volttracker.obdpoc.classify

/**
 * Single source of truth for "what state is the car in?". Inputs are raw signals collected by the
 * polling engine; the output is published verbatim on the app-state payload.
 */
object VehicleStateClassifier {
    private const val DRIVING_SPEED_KPH = 5.0
    private const val IGNITION_RPM = 300.0
    private const val CHARGING_CURRENT_A = 1.0
    private const val DCDC_ACTIVE_VOLTS = 13.0
    private const val LOW_BATTERY_VOLTS = 12.7

    @JvmStatic
    fun classify(input: ClassifierInput?): ClassifierResult {
        if (input == null || allSignalsNull(input)) {
            return ClassifierResult(VehicleState.UNKNOWN, Confidence.UNKNOWN, emptyList())
        }

        val reasons = ArrayList<String>(3)

        if (input.pluggedHint == true) {
            if (input.packCurrentA != null && input.packCurrentA <= -CHARGING_CURRENT_A) {
                reasons.add("plugged")
                reasons.add("current_into_pack")
                return ClassifierResult(VehicleState.CHARGING, Confidence.CERTAIN, reasons)
            }
            reasons.add("plugged_hint")
            return ClassifierResult(VehicleState.PLUGGED, Confidence.OBSERVED, reasons)
        }

        val engineRunning = input.engineRunningHint == true || (input.rpm != null && input.rpm > IGNITION_RPM)
        val moving = input.speedKph != null && input.speedKph > DRIVING_SPEED_KPH

        if (engineRunning) {
            if (moving) {
                reasons.add("engine_running")
                reasons.add("moving")
                return ClassifierResult(VehicleState.DRIVING_GAS, Confidence.OBSERVED, reasons)
            }
            reasons.add("engine_running")
            reasons.add("stationary")
            return ClassifierResult(VehicleState.READY, Confidence.OBSERVED, reasons)
        }

        if (moving) {
            reasons.add("moving")
            reasons.add("engine_quiet")
            return ClassifierResult(VehicleState.DRIVING_EV, Confidence.OBSERVED, reasons)
        }

        val adapterVoltage = input.adapterVoltage
        if (adapterVoltage != null) {
            if (adapterVoltage > DCDC_ACTIVE_VOLTS) {
                reasons.add("charging_voltage")
                return ClassifierResult(VehicleState.READY, Confidence.WEAK, reasons)
            }
            if (adapterVoltage <= LOW_BATTERY_VOLTS) {
                reasons.add("low_voltage")
                return ClassifierResult(VehicleState.PARKED, Confidence.WEAK, reasons)
            }
            reasons.add("voltage_ambiguous")
            return ClassifierResult(VehicleState.UNKNOWN, Confidence.WEAK, reasons)
        }

        reasons.add("no_signal")
        return ClassifierResult(VehicleState.UNKNOWN, Confidence.UNKNOWN, reasons)
    }

    private fun allSignalsNull(input: ClassifierInput): Boolean =
        input.speedKph == null &&
            input.rpm == null &&
            input.adapterVoltage == null &&
            input.packCurrentA == null &&
            input.pluggedHint == null &&
            input.engineRunningHint == null
}
