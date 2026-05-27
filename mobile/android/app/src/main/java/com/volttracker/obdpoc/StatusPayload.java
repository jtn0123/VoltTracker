package com.volttracker.obdpoc;

import java.util.Iterator;
import org.json.JSONException;
import org.json.JSONObject;

/** Typed builder for live service-status payloads sent to the dashboard and session log. */
final class StatusPayload {

    final String state;
    final String detail;
    final boolean blocked;
    final String adapter;
    final long updatedAt;
    final String logFile;
    final FailureClass failureClass;
    final Double lastVoltage;
    final String competingApps;
    final JSONObject extras;

    StatusPayload(
            String state,
            String detail,
            boolean blocked,
            String adapter,
            long updatedAt,
            String logFile,
            FailureClass failureClass,
            Double lastVoltage,
            String competingApps,
            JSONObject extras) {
        this.state = state == null ? "" : state;
        this.detail = detail == null ? "" : detail;
        this.blocked = blocked;
        this.adapter = adapter == null ? "" : adapter;
        this.updatedAt = updatedAt;
        this.logFile = logFile;
        this.failureClass = failureClass;
        this.lastVoltage = lastVoltage;
        this.competingApps = competingApps == null ? "" : competingApps;
        this.extras = extras;
    }

    JSONObject toJson() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("state", state);
            payload.put("detail", detail);
            payload.put("blocked", blocked);
            payload.put("adapter", adapter);
            payload.put("updatedAt", updatedAt);
            if (logFile != null) {
                payload.put("logFile", logFile);
            }
            if (failureClass != null) {
                payload.put("failureClass", failureClass.wireName());
            }
            if (lastVoltage != null) {
                payload.put("lastVoltage", lastVoltage.doubleValue());
            }
            if (!competingApps.isEmpty()) {
                payload.put("competingApps", competingApps);
            }
            if (extras != null) {
                Iterator<String> keys = extras.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    payload.put(key, extras.get(key));
                }
            }
        } catch (JSONException ignored) {
            // Static field names and local primitive values are safe.
        }
        return payload;
    }
}
