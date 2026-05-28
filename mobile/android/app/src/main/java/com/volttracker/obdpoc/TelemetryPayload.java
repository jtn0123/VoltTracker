package com.volttracker.obdpoc;

import org.json.JSONObject;

/** Typed handoff for telemetry payloads before dashboard/log serialization. */
final class TelemetryPayload {

    private final String source;
    private final Boolean connected;
    private final Long updatedAt;
    private final String adapter;
    private final Integer sampleCount;
    private final Long sessionMs;
    private final Integer speedKph;
    private final Integer rpm;
    private final Integer coolantC;
    private final Integer loadPct;
    private final Integer throttlePct;
    private final Integer soc;
    private final Double voltage;
    private final Double powerKw;
    private final String vehicleState;
    private final String vehicleStateConfidence;
    private final String supportedPids;
    private final String raw;
    private final JSONObject extras;

    private TelemetryPayload(Builder builder) {
        this.source = builder.source;
        this.connected = builder.connected;
        this.updatedAt = builder.updatedAt;
        this.adapter = builder.adapter;
        this.sampleCount = builder.sampleCount;
        this.sessionMs = builder.sessionMs;
        this.speedKph = builder.speedKph;
        this.rpm = builder.rpm;
        this.coolantC = builder.coolantC;
        this.loadPct = builder.loadPct;
        this.throttlePct = builder.throttlePct;
        this.soc = builder.soc;
        this.voltage = builder.voltage;
        this.powerKw = builder.powerKw;
        this.vehicleState = builder.vehicleState;
        this.vehicleStateConfidence = builder.vehicleStateConfidence;
        this.supportedPids = builder.supportedPids;
        this.raw = builder.raw;
        this.extras = copyObject(builder.extras);
    }

    static TelemetryPayload fromJson(JSONObject payload) {
        if (payload == null) {
            return builder().build();
        }
        Builder builder =
                builder()
                        .source(optString(payload, "source"))
                        .connected(optBoolean(payload, "connected"))
                        .updatedAt(optLong(payload, "updatedAt"))
                        .adapter(optString(payload, "adapter"))
                        .sampleCount(optInteger(payload, "sampleCount"))
                        .sessionMs(optLong(payload, "sessionMs"))
                        .speedKph(optInteger(payload, "speedKph"))
                        .rpm(optInteger(payload, "rpm"))
                        .coolantC(optInteger(payload, "coolantC"))
                        .loadPct(optInteger(payload, "loadPct"))
                        .throttlePct(optInteger(payload, "throttlePct"))
                        .soc(optInteger(payload, "soc"))
                        .voltage(optDouble(payload, "voltage"))
                        .powerKw(optDouble(payload, "powerKw"))
                        .vehicleState(optString(payload, "vehicleState"))
                        .vehicleStateConfidence(optString(payload, "vehicleStateConfidence"))
                        .supportedPids(optString(payload, "supportedPids"))
                        .raw(optString(payload, "raw"));
        java.util.Iterator<String> keys = payload.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!isTypedKey(key)) {
                builder.extra(key, payload.opt(key));
            }
        }
        return builder.build();
    }

    static Builder builder() {
        return new Builder();
    }

    boolean isEmpty() {
        return toJson().length() == 0;
    }

    String source() {
        return source == null ? "" : source;
    }

    boolean connected() {
        return connected != null && connected;
    }

    long updatedAt() {
        return updatedAt == null ? 0L : updatedAt;
    }

    JSONObject toJson() {
        JSONObject out = copyObject(extras);
        putIfNotNull(out, "source", source);
        putIfNotNull(out, "connected", connected);
        putIfNotNull(out, "updatedAt", updatedAt);
        putIfNotNull(out, "adapter", adapter);
        putIfNotNull(out, "sampleCount", sampleCount);
        putIfNotNull(out, "sessionMs", sessionMs);
        putIfNotNull(out, "speedKph", speedKph);
        putIfNotNull(out, "rpm", rpm);
        putIfNotNull(out, "coolantC", coolantC);
        putIfNotNull(out, "loadPct", loadPct);
        putIfNotNull(out, "throttlePct", throttlePct);
        putIfNotNull(out, "soc", soc);
        putIfNotNull(out, "voltage", voltage);
        putIfNotNull(out, "powerKw", powerKw);
        putIfNotNull(out, "vehicleState", vehicleState);
        putIfNotNull(out, "vehicleStateConfidence", vehicleStateConfidence);
        putIfNotNull(out, "supportedPids", supportedPids);
        putIfNotNull(out, "raw", raw);
        return out;
    }

    private static JSONObject copyObject(JSONObject source) {
        JSONObject copy = new JSONObject();
        if (source == null) {
            return copy;
        }
        java.util.Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            putIfNotNull(copy, key, deepCopyValue(source.opt(key)));
        }
        return copy;
    }

    private static Object deepCopyValue(Object value) {
        if (value instanceof JSONObject) {
            return copyObject((JSONObject) value);
        }
        if (value instanceof org.json.JSONArray) {
            org.json.JSONArray source = (org.json.JSONArray) value;
            org.json.JSONArray copy = new org.json.JSONArray();
            for (int i = 0; i < source.length(); i++) {
                copy.put(deepCopyValue(source.opt(i)));
            }
            return copy;
        }
        return value;
    }

    private static boolean isTypedKey(String key) {
        switch (key) {
            case "source":
            case "connected":
            case "updatedAt":
            case "adapter":
            case "sampleCount":
            case "sessionMs":
            case "speedKph":
            case "rpm":
            case "coolantC":
            case "loadPct":
            case "throttlePct":
            case "soc":
            case "voltage":
            case "powerKw":
            case "vehicleState":
            case "vehicleStateConfidence":
            case "supportedPids":
            case "raw":
                return true;
            default:
                return false;
        }
    }

    private static String optString(JSONObject payload, String key) {
        return payload.has(key) && !payload.isNull(key) ? payload.optString(key) : null;
    }

    private static Boolean optBoolean(JSONObject payload, String key) {
        return payload.has(key) && !payload.isNull(key) ? payload.optBoolean(key) : null;
    }

    private static Integer optInteger(JSONObject payload, String key) {
        return payload.has(key) && !payload.isNull(key) ? payload.optInt(key) : null;
    }

    private static Long optLong(JSONObject payload, String key) {
        return payload.has(key) && !payload.isNull(key) ? payload.optLong(key) : null;
    }

    private static Double optDouble(JSONObject payload, String key) {
        return payload.has(key) && !payload.isNull(key) ? payload.optDouble(key) : null;
    }

    private static void putIfNotNull(JSONObject out, String key, Object value) {
        if (value == null) {
            return;
        }
        try {
            out.put(key, value);
        } catch (org.json.JSONException ex) {
            throw new IllegalArgumentException("Could not encode telemetry field " + key, ex);
        }
    }

    static final class Builder {
        private String source;
        private Boolean connected;
        private Long updatedAt;
        private String adapter;
        private Integer sampleCount;
        private Long sessionMs;
        private Integer speedKph;
        private Integer rpm;
        private Integer coolantC;
        private Integer loadPct;
        private Integer throttlePct;
        private Integer soc;
        private Double voltage;
        private Double powerKw;
        private String vehicleState;
        private String vehicleStateConfidence;
        private String supportedPids;
        private String raw;
        private final JSONObject extras = new JSONObject();

        Builder source(String value) {
            this.source = value;
            return this;
        }

        Builder connected(Boolean value) {
            this.connected = value;
            return this;
        }

        Builder updatedAt(Long value) {
            this.updatedAt = value;
            return this;
        }

        Builder adapter(String value) {
            this.adapter = value;
            return this;
        }

        Builder sampleCount(Integer value) {
            this.sampleCount = value;
            return this;
        }

        Builder sessionMs(Long value) {
            this.sessionMs = value;
            return this;
        }

        Builder speedKph(Integer value) {
            this.speedKph = value;
            return this;
        }

        Builder rpm(Integer value) {
            this.rpm = value;
            return this;
        }

        Builder coolantC(Integer value) {
            this.coolantC = value;
            return this;
        }

        Builder loadPct(Integer value) {
            this.loadPct = value;
            return this;
        }

        Builder throttlePct(Integer value) {
            this.throttlePct = value;
            return this;
        }

        Builder soc(Integer value) {
            this.soc = value;
            return this;
        }

        Builder voltage(Double value) {
            this.voltage = value;
            return this;
        }

        Builder powerKw(Double value) {
            this.powerKw = value;
            return this;
        }

        Builder vehicleState(String value) {
            this.vehicleState = value;
            return this;
        }

        Builder vehicleStateConfidence(String value) {
            this.vehicleStateConfidence = value;
            return this;
        }

        Builder supportedPids(String value) {
            this.supportedPids = value;
            return this;
        }

        Builder raw(String value) {
            this.raw = value;
            return this;
        }

        Builder extra(String key, Object value) {
            if (key == null || isTypedKey(key)) {
                return this;
            }
            putIfNotNull(extras, key, value);
            return this;
        }

        TelemetryPayload build() {
            return new TelemetryPayload(this);
        }
    }
}
