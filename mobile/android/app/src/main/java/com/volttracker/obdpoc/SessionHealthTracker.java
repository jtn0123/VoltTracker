package com.volttracker.obdpoc;

import org.json.JSONException;
import org.json.JSONObject;

/** Tracks per-session sample health counters shared by live and demo telemetry. */
final class SessionHealthTracker {

    private final ObdService service;
    private int backgroundSampleCount;
    private int sampleGapCount;
    private long lastSampleAtMs;
    private long lastSampleGapMs;
    private long maxSampleGapMs;

    SessionHealthTracker(ObdService service) {
        this.service = service;
    }

    void reset() {
        synchronized (service.ioLock) {
            backgroundSampleCount = 0;
            sampleGapCount = 0;
            lastSampleAtMs = 0L;
            lastSampleGapMs = 0L;
            maxSampleGapMs = 0L;
        }
    }

    int backgroundSampleCount() {
        return backgroundSampleCount;
    }

    int sampleGapCount() {
        return sampleGapCount;
    }

    void append(JSONObject sample) throws JSONException {
        synchronized (service.ioLock) {
            long now = sample.optLong("updatedAt", System.currentTimeMillis());
            long gapMs = lastSampleAtMs > 0L ? Math.max(0L, now - lastSampleAtMs) : 0L;
            if (gapMs > 0L) {
                lastSampleGapMs = gapMs;
                maxSampleGapMs = Math.max(maxSampleGapMs, gapMs);
                long expectedGapMs = "demo".equals(service.recorder.activeMode()) ? 3500L : 6000L;
                if (gapMs > expectedGapMs) {
                    sampleGapCount += 1;
                    service.recorder.logEvent(
                            "sample_gap",
                            "gapMs",
                            String.valueOf(gapMs),
                            "mode",
                            service.recorder.activeMode());
                }
            }
            lastSampleAtMs = now;
            if (!service.appInForeground) {
                backgroundSampleCount += 1;
            }
            sample.put("appForeground", service.appInForeground);
            sample.put("foregroundServiceActive", service.foregroundServiceActive);
            sample.put("backgroundSampleCount", backgroundSampleCount);
            sample.put("sampleGapCount", sampleGapCount);
            sample.put("lastSampleGapMs", lastSampleGapMs);
            sample.put("maxSampleGapMs", maxSampleGapMs);
        }
    }
}
