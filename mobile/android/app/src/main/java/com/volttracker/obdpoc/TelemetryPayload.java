package com.volttracker.obdpoc;

import org.json.JSONObject;

/** Typed handoff wrapper for telemetry payloads before dashboard/log serialization. */
final class TelemetryPayload {

    private final JSONObject payload;

    private TelemetryPayload(JSONObject payload) {
        this.payload = payload == null ? new JSONObject() : payload;
    }

    static TelemetryPayload fromJson(JSONObject payload) {
        return new TelemetryPayload(payload);
    }

    boolean isEmpty() {
        return payload.length() == 0;
    }

    String source() {
        return payload.optString("source", "");
    }

    boolean connected() {
        return payload.optBoolean("connected", false);
    }

    long updatedAt() {
        return payload.optLong("updatedAt", 0L);
    }

    JSONObject toJson() {
        return payload;
    }
}
