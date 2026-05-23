package com.volttracker.obdpoc.data;

import org.json.JSONException;
import org.json.JSONObject;

public final class TelemetrySampleRecord {
    public final long id;
    public final long sessionId;
    public final long capturedAtMs;
    public final String source;
    public final String vehicleState;
    public final int speedKph;
    public final int rpm;
    public final int coolantC;
    public final int loadPct;
    public final int throttlePct;
    public final double voltage;
    public final double soc;
    public final double batteryTemp;
    public final double powerKw;
    public final int sampleNumber;
    public final long sessionMs;
    public final String raw;
    public final String json;

    TelemetrySampleRecord(
            long id,
            long sessionId,
            long capturedAtMs,
            String source,
            String vehicleState,
            int speedKph,
            int rpm,
            int coolantC,
            int loadPct,
            int throttlePct,
            double voltage,
            double soc,
            double batteryTemp,
            double powerKw,
            int sampleNumber,
            long sessionMs,
            String raw,
            String json) {
        this.id = id;
        this.sessionId = sessionId;
        this.capturedAtMs = capturedAtMs;
        this.source = nonNull(source);
        this.vehicleState = nonNull(vehicleState);
        this.speedKph = speedKph;
        this.rpm = rpm;
        this.coolantC = coolantC;
        this.loadPct = loadPct;
        this.throttlePct = throttlePct;
        this.voltage = voltage;
        this.soc = soc;
        this.batteryTemp = batteryTemp;
        this.powerKw = powerKw;
        this.sampleNumber = sampleNumber;
        this.sessionMs = sessionMs;
        this.raw = nonNull(raw);
        this.json = nonNull(json);
    }

    public JSONObject toJson() {
        try {
            return new JSONObject(json);
        } catch (JSONException ex) {
            return new JSONObject();
        }
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
