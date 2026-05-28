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
    private final ObdService service;
    private final ObdPollingEngine engine;
    private final SpeedPlausibilityFilter speedFilter;
    private final PidPollingState pidPolling;

    LiveSampleReader(
            ObdService service,
            ObdPollingEngine engine,
            SpeedPlausibilityFilter speedFilter,
            PidPollingState pidPolling) {
        this.service = service;
        this.engine = engine;
        this.speedFilter = speedFilter;
        this.pidPolling = pidPolling;
    }

    JSONObject read() throws IOException {
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
            Integer load = ObdProtocol.parseEngineLoadPct(pidPolling.lastRaw("0104"));
            if (load != null) {
                sample.put("loadPct", load);
            }
            appendThrottle(sample);
            Integer soc = ObdProtocol.parseStateOfChargePct(pidPolling.lastRaw("015B"));
            if (soc != null) {
                sample.put("soc", soc);
            }

            String packVoltageRaw = pidPolling.lastRaw("222429");
            String packCurrentRaw = pidPolling.lastRaw("222414");
            appendBatteryFields(sample, packVoltageRaw, packCurrentRaw);

            long now = System.currentTimeMillis();
            pidPolling.putStaleMsIfTracked(sample, "voltageStaleMs", "ATRV", now);
            pidPolling.putStaleMsIfTracked(sample, "socStaleMs", "015B", now);
            pidPolling.putStaleMsIfTracked(sample, "coolantCStaleMs", "0105", now);
            pidPolling.putStaleMsIfTracked(sample, "batteryTempStaleMs", "22434F", now);

            int sampleCount = engine.incrementSampleCount();
            sample.put("source", "obd");
            sample.put("connected", true);
            sample.put("adapter", service.activeName);
            sample.put("sampleCount", sampleCount);
            sample.put("sessionMs", Math.max(0, now - service.sessionStartedAtMs));
            sample.put("supportedPids", engine.supportedPidsSummary());
            Double packCurrentA = appendPackCurrent(sample, packCurrentRaw);
            appendVehicleState(
                    sample, acceptedSpeed, rpm, voltage, packCurrentA, chargeTransitionHint, now);
            sample.put("updatedAt", now);
            engine.appendSessionHealth(sample);
            engine.appendLocation(sample);
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
        ClassifierResult classified =
                VehicleStateClassifier.classify(
                        new ClassifierInput(
                                acceptedSpeed == null ? null : acceptedSpeed.doubleValue(),
                                rpm == null ? null : Math.round(rpm),
                                voltage == null ? null : voltage.doubleValue(),
                                packCurrentA,
                                chargeTransitionHint ? Boolean.TRUE : null,
                                engineRunningHint,
                                now));
        sample.put("vehicleState", classified.state.asPayloadKey());
        sample.put("vehicleStateConfidence", classified.confidence.asPayloadKey());
        sample.put("vehicleStateReasons", new JSONArray(classified.reasons));
    }
}
