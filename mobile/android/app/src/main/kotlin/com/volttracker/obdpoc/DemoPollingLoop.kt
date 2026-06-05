package com.volttracker.obdpoc

import org.json.JSONException
import org.json.JSONObject

/**
 * Emits the synthetic demo telemetry stream that runs in place of a real OBD adapter loop.
 */
class DemoPollingLoop(
    private val service: ObdService,
    private val engine: ObdPollingEngine,
) {
    fun run() {
        service.broadcastStatus("connected", "Demo telemetry is running without an OBD adapter.", false)
        val start = System.currentTimeMillis()
        while (service.running.get()) {
            val t = (System.currentTimeMillis() - start) / 1000.0
            val sample = JSONObject()
            try {
                val sampleNumber = engine.incrementSampleCount()
                sample.put("source", "demo")
                sample.put("connected", true)
                sample.put("adapter", service.activeName)
                sample.put("sampleCount", sampleNumber)
                sample.put("sessionMs", maxOf(0L, System.currentTimeMillis() - service.sessionStartedAtMs))
                sample.put("supportedPids", engine.supportedPidsSummary())
                sample.put("vehicleState", "demo-preview")
                sample.put("speedKph", maxOf(0L, Math.round(54 + 23 * Math.sin(t / 3.4))))
                sample.put("rpm", Math.round(1260 + 420 * Math.sin(t / 2.1)))
                sample.put("coolantC", Math.round(82 + 4 * Math.sin(t / 8.0)))
                sample.put("loadPct", Math.round(34 + 18 * Math.sin(t / 4.4)))
                sample.put("throttlePct", Math.round(18 + 14 * Math.sin(t / 2.7)))
                sample.put("voltage", ObdElmDecode.round1(13.8 + 0.2 * Math.sin(t / 5.0)))
                sample.put("soc", maxOf(13.4, ObdElmDecode.round1(77.8 - t * 0.01)))
                sample.put("batteryTemp", ObdElmDecode.round1(24.0 + Math.sin(t / 8.0)))
                sample.put("powerKw", ObdElmDecode.round1(16.0 + Math.sin(t / 2.2) * 12.0))
                sample.put("latitude", 32.7157 + 0.009 * Math.sin(t / 28.0))
                sample.put("longitude", -117.1611 + 0.009 * Math.cos(t / 28.0))
                sample.put("accuracyM", 6.0)
                sample.put("gpsSpeedMps", ObdElmDecode.round1(kotlin.math.abs(15.0 + 9.0 * Math.cos(t / 28.0))))
                sample.put("bearingDeg", ObdElmDecode.round1((Math.toDegrees(t / 28.0) % 360 + 360) % 360))
                sample.put("updatedAt", System.currentTimeMillis())
                engine.appendSessionHealth(sample)
                sample.put("raw", "demo")
            } catch (ignored: JSONException) {
                // Local numeric values are safe.
            }
            service.broadcastTelemetry(sample)
            sleep(1000)
        }
    }

    companion object {
        private fun sleep(millis: Long) {
            try {
                Thread.sleep(millis)
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
