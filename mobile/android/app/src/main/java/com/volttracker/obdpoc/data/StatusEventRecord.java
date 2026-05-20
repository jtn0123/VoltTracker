package com.volttracker.obdpoc.data;

import org.json.JSONException;
import org.json.JSONObject;

public final class StatusEventRecord {
    public final long id;
    public final long sessionId;
    public final long occurredAtMs;
    public final String kind;
    public final String state;
    public final String detail;
    public final boolean blocked;
    public final String payload;

    StatusEventRecord(
            long id,
            long sessionId,
            long occurredAtMs,
            String kind,
            String state,
            String detail,
            boolean blocked,
            String payload
    ) {
        this.id = id;
        this.sessionId = sessionId;
        this.occurredAtMs = occurredAtMs;
        this.kind = nonNull(kind);
        this.state = nonNull(state);
        this.detail = nonNull(detail);
        this.blocked = blocked;
        this.payload = nonNull(payload);
    }

    public JSONObject toJson() {
        try {
            return new JSONObject(payload);
        } catch (JSONException ex) {
            return new JSONObject();
        }
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
