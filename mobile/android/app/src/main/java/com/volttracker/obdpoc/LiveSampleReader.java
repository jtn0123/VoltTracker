package com.volttracker.obdpoc;

import static com.volttracker.obdpoc.ObdElmDecode.round1;

import com.volttracker.obdpoc.PidSchedule.PidSpec;
import com.volttracker.obdpoc.classify.ClassifierInput;
import com.volttracker.obdpoc.classify.ClassifierResult;
import com.volttracker.obdpoc.classify.VehicleStateClassifier;
import java.io.IOException;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Builds one live OBD telemetry sample from the current PID polling state. */
final class LiveSampleReader {

    /**
     * The narrow slice of {@link ObdPollingEngine} that building one sample actually needs: the
     * per-session sample counter, the supported-PID summary, and the two enrichers that decorate a
     * sample with session-health and location fields. Passing this into {@link
     * #read(SampleContext)} instead of the whole engine keeps the reader's coupling to four
     * well-defined operations rather than the engine's entire surface, while {@link
     * ObdPollingEngine} (which owns the runtime state these read) supplies the implementation at
     * the call site.
     */
    interface SampleContext {
        int incrementSampleCount();

        String supportedPidsSummary();

        void appendSessionHealth(JSONObject sample) throws JSONException;

        void appendLocation(JSONObject sample) throws JSONException;

        String redactedVin();
    }

    private final ObdService service;
    private final SpeedPlausibilityFilter speedFilter;
    private final PidPollingState pidPolling;

    LiveSampleReader(
            ObdService service, SpeedPlausibilityFilter speedFilter, PidPollingState pidPolling) {
        this.service = service;
        this.speedFilter = speedFilter;
        this.pidPolling = pidPolling;
    }

    JSONObject read(SampleContext context) throws IOException {
        JSONObject sample = new JSONObject();
        StringBuilder rawThisCycle = new StringBuilder();
        try {
            boolean isInitialCycle = pidPolling.isInitialCycle();
            List<PidSpec> due = pidPolling.dueForCurrentCycle();
            pidPolling.runScheduledPolls(due, rawThisCycle);
            pidPolling.advanceCycle();

            String voltageRaw = pidPolling.lastRaw("ATRV");
            Float voltage = ObdProtocol.parseVoltage(voltageRaw);
            if (voltage != null) {
                sample.put("voltage", voltage);
            }

            String speedRaw = pidPolling.lastRaw("010D");
            Integer speed = ObdProtocol.parseSpeedKph(speedRaw);
            boolean polledSpeedThisCycle =
                    !isInitialCycle ? PidPollingState.wasPolledThisCycle(due, "010D") : true;
            boolean chargeTransitionHint =
                    polledSpeedThisCycle && ObdProtocol.hasMaxSpeedSentinel(speedRaw);
            Integer acceptedSpeed = appendSpeed(sample, speed, polledSpeedThisCycle);
            if (acceptedSpeed == null && polledSpeedThisCycle && chargeTransitionHint) {
                sample.put("speedRejectedKph", 255);
                sample.put("chargeTransitionHint", true);
                service.recorder.logEvent(
                        "speed_rejected", "speedKph", "255", "reason", "charge_transition_hint");
            }

            Float rpm = ObdProtocol.parseRpm(pidPolling.lastRaw("010C"));
            if (rpm != null) {
                sample.put("rpm", Math.round(rpm));
            }
            Integer coolant = ObdProtocol.parseCoolantC(pidPolling.lastRaw("0105"));
            if (coolant != null) {
                sample.put("coolantC", coolant);
            }
            putNumeric(sample, "intakeAirTempC", "010F", 0);
            Integer load = ObdProtocol.parseEngineLoadPct(pidPolling.lastRaw("0104"));
            if (load != null) {
                sample.put("loadPct", load);
            }
            appendThrottle(sample);
            Integer soc = ObdProtocol.parseStateOfChargePct(pidPolling.lastRaw("015B"));
            if (soc != null) {
                sample.put("soc", soc);
            }
            appendStandardContextFields(sample);

            String packVoltageRaw = pidPolling.lastRaw("222429");
            String packCurrentRaw = pidPolling.lastRaw("222414");
            appendBatteryFields(sample, packVoltageRaw, packCurrentRaw);
            appendChargingFields(sample);
            appendEnhancedContextFields(sample);

            long now = System.currentTimeMillis();
            pidPolling.putStaleMsIfTracked(sample, "voltageStaleMs", "ATRV", now);
            pidPolling.putStaleMsIfTracked(sample, "speedKphStaleMs", "010D", now);
            pidPolling.putStaleMsIfTracked(sample, "rpmStaleMs", "010C", now);
            pidPolling.putStaleMsIfTracked(sample, "loadPctStaleMs", "0104", now);
            putThrottleStaleMsIfKnown(sample, now);
            pidPolling.putStaleMsIfTracked(sample, "socStaleMs", "015B", now);
            putStaleMsForPresentValue(
                    sample, "controlModuleVoltage", "controlModuleVoltageStaleMs", "0142", now);
            putStaleMsForPresentValue(
                    sample, "engineRunTimeSec", "engineRunTimeStaleMs", "011F", now);
            putStaleMsForPresentValue(sample, "fuelLevelPct", "fuelLevelStaleMs", "012F", now);
            putStaleMsForPresentValue(
                    sample, "intakeAirTempC", "intakeAirTempStaleMs", "010F", now);
            putStaleMsForFirstPresentValue(
                    sample, "engineOilTempC", "engineOilTempStaleMs", now, "221154", "015C");
            putStaleMsForPresentValue(sample, "odometerKm", "odometerStaleMs", "01A6", now);
            pidPolling.putStaleMsIfTracked(sample, "coolantCStaleMs", "0105", now);
            pidPolling.putStaleMsIfTracked(sample, "batteryTempStaleMs", "22434F", now);
            pidPolling.putStaleMsIfTracked(sample, "packVoltageStaleMs", "222429", now);
            pidPolling.putStaleMsIfTracked(sample, "packCurrentAStaleMs", "222414", now);
            putPowerStaleMsIfKnown(sample, now);
            putChargingStaleMs(sample, now);
            putEnhancedContextStaleMs(sample, now);

            int sampleCount = context.incrementSampleCount();
            sample.put("source", "obd");
            sample.put("connected", true);
            sample.put("adapter", service.activeName);
            String redactedVin = context.redactedVin();
            if (redactedVin != null && !redactedVin.isEmpty()) {
                sample.put("vin", redactedVin);
            }
            sample.put("sampleCount", sampleCount);
            sample.put("sessionMs", Math.max(0, now - service.sessionStartedAtMs));
            sample.put("supportedPids", context.supportedPidsSummary());
            Double packCurrentA = appendPackCurrent(sample, packCurrentRaw);
            appendVehicleState(
                    sample, acceptedSpeed, rpm, voltage, packCurrentA, chargeTransitionHint, now);
            sample.put("updatedAt", now);
            context.appendSessionHealth(sample);
            context.appendLocation(sample);
            sample.put("raw", ObdPollingEngine.boundedRawTranscript(rawThisCycle));
        } catch (JSONException ex) {
            service.recorder.logError("sample_encoding_error", ex);
            return new JSONObject();
        }
        return sample;
    }

    private Integer appendSpeed(JSONObject sample, Integer speed, boolean polledSpeedThisCycle)
            throws JSONException {
        if (polledSpeedThisCycle) {
            if (speed != null && speedFilter.accept(speed, System.currentTimeMillis())) {
                sample.put("speedKph", speed);
                return speed;
            }
            if (speed != null) {
                sample.put("speedRejectedKph", speed);
                service.recorder.logEvent("speed_rejected", "speedKph", String.valueOf(speed));
            }
            return null;
        }
        if (speed != null) {
            sample.put("speedKph", speed);
            return speed;
        }
        return null;
    }

    private void appendStandardContextFields(JSONObject sample) throws JSONException {
        putNumeric(sample, "controlModuleVoltage", "0142", 2);
        putNumeric(sample, "engineRunTimeSec", "011F", 0);
        putNumeric(sample, "fuelLevelPct", "012F", 0);
        putNumericFirst(sample, "engineOilTempC", 0, "221154", "015C");
        putNumeric(sample, "odometerKm", "01A6", 1);
        if (sample.has("odometerKm")) {
            sample.put("odometerMiles", round1(sample.optDouble("odometerKm") * 0.621371));
        }
    }

    private void appendThrottle(JSONObject sample) throws JSONException {
        Integer pedal = ObdProtocol.parseAccelPedalPct(pidPolling.lastRaw("0149"));
        if (pedal != null) {
            sample.put("throttlePct", pedal);
            sample.put("throttleSource", "accelPedal");
            return;
        }
        Integer throttle = ObdProtocol.parseThrottlePct(pidPolling.lastRaw("0111"));
        if (throttle != null) {
            sample.put("throttlePct", throttle);
            sample.put("throttleSource", "iceThrottleBody");
        }
    }

    private void putThrottleStaleMsIfKnown(JSONObject sample, long now) throws JSONException {
        if (!sample.has("throttlePct")) {
            return;
        }
        String source = sample.optString("throttleSource", "");
        String command = "iceThrottleBody".equals(source) ? "0111" : "0149";
        pidPolling.putStaleMsIfTracked(sample, "throttlePctStaleMs", command, now);
    }

    private void appendBatteryFields(
            JSONObject sample, String packVoltageRaw, String packCurrentRaw) throws JSONException {
        ObdProtocol.ParsedPidValue batteryTemp =
                ObdProtocol.parseKnownValue("22434F", pidPolling.lastRaw("22434F"));
        if (batteryTemp != null && batteryTemp.valueNumeric != null) {
            sample.put("batteryTemp", round1(batteryTemp.valueNumeric));
        }
        ObdProtocol.ParsedPidValue packVoltage =
                ObdProtocol.parseKnownValue("222429", packVoltageRaw);
        if (packVoltage != null && packVoltage.valueNumeric != null) {
            sample.put("packVoltage", round1(packVoltage.valueNumeric));
        }
        Double powerKw = ObdProtocol.parsePackPowerKw(packVoltageRaw, packCurrentRaw);
        if (powerKw != null) {
            sample.put("powerKw", round1(powerKw));
        }
        putNumeric(sample, "hvBatteryRawSoc", "2243AF", 2);
        putNumeric(sample, "hvBatteryChargeCount", "2243A5", 0);
        putNumeric(sample, "lastChargeEnergyWh", "22437D", 0);
    }

    private void appendChargingFields(JSONObject sample) throws JSONException {
        putNumeric(sample, "chargerHvVoltage", "22436B", 1);
        putNumeric(sample, "chargerHvCurrent", "22436C", 2);
        putDerivedChargerPower(sample);
        putText(sample, "chargingMode", "224373");
        putText(sample, "chargingLevel", "224531");
    }

    private void appendEnhancedContextFields(JSONObject sample) throws JSONException {
        putNumericFirst(sample, "engineOilLifePct", 0, "22119F01", "22119F");
        putNumeric(sample, "engineTorqueNm", "22203F", 1);
        putNumeric(sample, "motorACurrentA", "222883", 1);
        putNumeric(sample, "motorBCurrentA", "222884", 1);
        putNumeric(sample, "motorAVoltage", "222885", 1);
        putNumeric(sample, "motorBVoltage", "222886", 1);
        putDerivedMotorPower(sample, "motorAPowerKw", "222885", "222883");
        putDerivedMotorPower(sample, "motorBPowerKw", "222886", "222884");
        putNumeric(sample, "evDistanceThisCycleKm", "222487", 2);
        putText(sample, "prndlState", "222889");
        putNumericFirst(sample, "transmissionTempC", 0, "22194001", "221940");
        putNumeric(sample, "batteryCoolantPumpRpm", "2241B2", 0);
        putNumeric(sample, "batteryCoolantValveRaw", "2241B4", 0);
        putNumeric(sample, "batteryHeaterPowerW", "2241B6", 0);
        putNumeric(sample, "outsideTempRawC", "22801E", 1);
        putNumeric(sample, "outsideTempC", "22801F", 1);
    }

    private void putPowerStaleMsIfKnown(JSONObject sample, long now) throws JSONException {
        if (!sample.has("powerKw")) {
            return;
        }
        Long voltageStaleMs = pidPolling.staleMsFor("222429", now);
        Long currentStaleMs = pidPolling.staleMsFor("222414", now);
        if (voltageStaleMs != null && currentStaleMs != null) {
            sample.put("powerKwStaleMs", Math.max(voltageStaleMs, currentStaleMs));
        }
    }

    private void putChargingStaleMs(JSONObject sample, long now) throws JSONException {
        putStaleMsForPresentValue(
                sample, "hvBatteryRawSoc", "hvBatteryRawSocStaleMs", "2243AF", now);
        putStaleMsForPresentValue(
                sample, "hvBatteryChargeCount", "hvBatteryChargeCountStaleMs", "2243A5", now);
        putStaleMsForPresentValue(
                sample, "lastChargeEnergyWh", "lastChargeEnergyStaleMs", "22437D", now);
        putStaleMsForPresentValue(
                sample, "chargerHvVoltage", "chargerHvVoltageStaleMs", "22436B", now);
        putStaleMsForPresentValue(
                sample, "chargerHvCurrent", "chargerHvCurrentStaleMs", "22436C", now);
        putStaleMsForPresentValue(sample, "chargerPowerKw", "chargerPowerStaleMs", "22436C", now);
        putStaleMsForPresentValue(sample, "chargingMode", "chargingModeStaleMs", "224373", now);
        putStaleMsForPresentValue(sample, "chargingLevel", "chargingLevelStaleMs", "224531", now);
    }

    private void putEnhancedContextStaleMs(JSONObject sample, long now) throws JSONException {
        putStaleMsForFirstPresentValue(
                sample, "engineOilLifePct", "engineOilLifeStaleMs", now, "22119F01", "22119F");
        putStaleMsForPresentValue(sample, "engineTorqueNm", "engineTorqueStaleMs", "22203F", now);
        putStaleMsForPresentValue(sample, "motorACurrentA", "motorAStaleMs", "222883", now);
        putStaleMsForPresentValue(sample, "motorBCurrentA", "motorBStaleMs", "222884", now);
        putStaleMsForPresentValue(
                sample, "evDistanceThisCycleKm", "evDistanceThisCycleStaleMs", "222487", now);
        putStaleMsForPresentValue(sample, "prndlState", "prndlStateStaleMs", "222889", now);
        putStaleMsForFirstPresentValue(
                sample, "transmissionTempC", "transmissionTempStaleMs", now, "22194001", "221940");
        putStaleMsForPresentValue(
                sample, "batteryCoolantPumpRpm", "batteryCoolantPumpStaleMs", "2241B2", now);
        putStaleMsForPresentValue(
                sample, "batteryCoolantValveRaw", "batteryCoolantValveStaleMs", "2241B4", now);
        putStaleMsForPresentValue(
                sample, "batteryHeaterPowerW", "batteryHeaterPowerStaleMs", "2241B6", now);
        putStaleMsForPresentValue(sample, "outsideTempC", "outsideTempStaleMs", "22801F", now);
    }

    private void putStaleMsForPresentValue(
            JSONObject sample, String valueKey, String staleKey, String command, long now)
            throws JSONException {
        if (sample.has(valueKey)) {
            pidPolling.putStaleMsIfTracked(sample, staleKey, command, now);
        }
    }

    private void putStaleMsForFirstPresentValue(
            JSONObject sample, String valueKey, String staleKey, long now, String... commands)
            throws JSONException {
        if (!sample.has(valueKey)) {
            return;
        }
        Long bestStaleMs = null;
        for (String command : commands) {
            Long staleMs = pidPolling.staleMsFor(command, now);
            if (staleMs != null && (bestStaleMs == null || staleMs < bestStaleMs)) {
                bestStaleMs = staleMs;
            }
        }
        if (bestStaleMs != null) {
            sample.put(staleKey, bestStaleMs);
        }
    }

    private Double appendPackCurrent(JSONObject sample, String packCurrentRaw)
            throws JSONException {
        ObdProtocol.ParsedPidValue packCurrent =
                ObdProtocol.parseKnownValue("222414", packCurrentRaw);
        Double packCurrentA = packCurrent == null ? null : packCurrent.valueNumeric;
        if (packCurrentA != null) {
            sample.put("packCurrentA", round1(packCurrentA));
        }
        return packCurrentA;
    }

    private void putNumeric(JSONObject sample, String key, String command, int decimals)
            throws JSONException {
        ObdProtocol.ParsedPidValue parsed =
                ObdProtocol.parseKnownValue(command, pidPolling.lastRaw(command));
        if (parsed == null || parsed.valueNumeric == null) {
            return;
        }
        putRoundedNumeric(sample, key, parsed.valueNumeric.doubleValue(), decimals);
    }

    private void putNumericFirst(JSONObject sample, String key, int decimals, String... commands)
            throws JSONException {
        for (String command : commands) {
            ObdProtocol.ParsedPidValue parsed =
                    ObdProtocol.parseKnownValue(command, pidPolling.lastRaw(command));
            if (parsed != null && parsed.valueNumeric != null) {
                putRoundedNumeric(sample, key, parsed.valueNumeric.doubleValue(), decimals);
                return;
            }
        }
    }

    private void putRoundedNumeric(JSONObject sample, String key, double value, int decimals)
            throws JSONException {
        if (decimals <= 0) {
            sample.put(key, Math.round(value));
        } else if (decimals == 1) {
            sample.put(key, round1(value));
        } else {
            double scale = Math.pow(10.0, decimals);
            sample.put(key, Math.round(value * scale) / scale);
        }
    }

    private void putText(JSONObject sample, String key, String command) throws JSONException {
        ObdProtocol.ParsedPidValue parsed =
                ObdProtocol.parseKnownValue(command, pidPolling.lastRaw(command));
        if (parsed != null && parsed.valueText != null && !parsed.valueText.isEmpty()) {
            sample.put(key, parsed.valueText);
        }
    }

    private void putDerivedChargerPower(JSONObject sample) throws JSONException {
        ObdProtocol.ParsedPidValue voltage =
                ObdProtocol.parseKnownValue("22436B", pidPolling.lastRaw("22436B"));
        ObdProtocol.ParsedPidValue current =
                ObdProtocol.parseKnownValue("22436C", pidPolling.lastRaw("22436C"));
        if (voltage == null
                || voltage.valueNumeric == null
                || current == null
                || current.valueNumeric == null) {
            return;
        }
        double powerKw =
                voltage.valueNumeric.doubleValue() * current.valueNumeric.doubleValue() / 1000.0;
        sample.put("chargerPowerKw", round1(powerKw));
    }

    private void putDerivedMotorPower(
            JSONObject sample, String key, String voltageCommand, String currentCommand)
            throws JSONException {
        ObdProtocol.ParsedPidValue voltage =
                ObdProtocol.parseKnownValue(voltageCommand, pidPolling.lastRaw(voltageCommand));
        ObdProtocol.ParsedPidValue current =
                ObdProtocol.parseKnownValue(currentCommand, pidPolling.lastRaw(currentCommand));
        if (voltage == null
                || voltage.valueNumeric == null
                || current == null
                || current.valueNumeric == null) {
            return;
        }
        sample.put(
                key,
                round1(
                        voltage.valueNumeric.doubleValue()
                                * current.valueNumeric.doubleValue()
                                / 1000.0));
    }

    private void appendVehicleState(
            JSONObject sample,
            Integer acceptedSpeed,
            Float rpm,
            Float voltage,
            Double packCurrentA,
            boolean chargeTransitionHint,
            long now)
            throws JSONException {
        Boolean engineRunningHint = rpm == null ? null : (rpm > 200f);
        ClassifierInput input;
        try {
            input =
                    new ClassifierInput(
                            acceptedSpeed == null ? null : acceptedSpeed.doubleValue(),
                            rpm == null ? null : Math.round(rpm),
                            voltage == null ? null : voltage.doubleValue(),
                            packCurrentA,
                            chargeTransitionHint ? Boolean.TRUE : null,
                            engineRunningHint,
                            now);
        } catch (IllegalArgumentException ex) {
            // Impossible sensor values (parser bug or wild adapter). Don't crash the
            // sample — log the anomaly and fall back to an all-unknown classification.
            service.recorder.logError("classifier_input_rejected", ex);
            input = new ClassifierInput(null, null, null, null, null, null, now);
        }
        ClassifierResult classified = VehicleStateClassifier.classify(input);
        sample.put("vehicleState", classified.state.asPayloadKey());
        sample.put("vehicleStateConfidence", classified.confidence.asPayloadKey());
        sample.put("vehicleStateReasons", new JSONArray(classified.reasons));
    }
}
