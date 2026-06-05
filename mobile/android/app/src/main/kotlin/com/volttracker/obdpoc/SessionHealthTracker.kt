package com.volttracker.obdpoc

import org.json.JSONException
import org.json.JSONObject

/** Tracks per-session sample health counters shared by live and demo telemetry. */
class SessionHealthTracker(
    private val service: ObdService,
) {
    private var backgroundSampleCount = 0
    private var sampleGapCount = 0
    private var lastSampleAtMs = 0L
    private var lastSampleGapMs = 0L
    private var maxSampleGapMs = 0L

    fun reset() {
        synchronized(service.ioLock) {
            backgroundSampleCount = 0
            sampleGapCount = 0
            lastSampleAtMs = 0L
            lastSampleGapMs = 0L
            maxSampleGapMs = 0L
        }
    }

    fun backgroundSampleCount(): Int = backgroundSampleCount

    fun sampleGapCount(): Int = sampleGapCount

    @Throws(JSONException::class)
    fun append(sample: JSONObject) {
        synchronized(service.ioLock) {
            val now = sample.optLong("updatedAt", System.currentTimeMillis())
            val gapMs = if (lastSampleAtMs > 0L) maxOf(0L, now - lastSampleAtMs) else 0L
            if (gapMs > 0L) {
                lastSampleGapMs = gapMs
                maxSampleGapMs = maxOf(maxSampleGapMs, gapMs)
                val expectedGapMs = if (service.recorder.activeMode() == "demo") 3500L else 6000L
                if (gapMs > expectedGapMs) {
                    sampleGapCount += 1
                    service.recorder.logEvent(
                        "sample_gap",
                        "gapMs",
                        gapMs.toString(),
                        "mode",
                        service.recorder.activeMode(),
                    )
                }
            }
            lastSampleAtMs = now
            if (!service.appInForeground) {
                backgroundSampleCount += 1
            }
            sample.put("appForeground", service.appInForeground)
            sample.put("foregroundServiceActive", service.foregroundServiceActive)
            sample.put("backgroundSampleCount", backgroundSampleCount)
            sample.put("sampleGapCount", sampleGapCount)
            sample.put("lastSampleGapMs", lastSampleGapMs)
            sample.put("maxSampleGapMs", maxSampleGapMs)
        }
    }
}
