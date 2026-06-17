package com.volttracker.obdpoc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Micro-benchmark + safety check for the per-sample [TelemetryPayload] round-trip that
 * [LiveSampleReader.read] used to perform on every poll cycle (`fromJson(sample).toJson()`). The
 * production path now returns the freshly-built sample directly, so this measures the CPU/allocation
 * cost that was removed. Pure JVM (no Robolectric) so it runs fast and deterministically.
 */
class TelemetryRoundTripBenchmarkTest {
    private fun representativeSample(): JSONObject {
        val s = JSONObject()
        // Typed fields (the 18 TelemetryPayload pins).
        s.put("source", "obd")
        s.put("connected", true)
        s.put("adapter", "OBDLink MX+ 54242")
        s.put("sampleCount", 1234)
        s.put("sessionMs", 1_234_567L)
        s.put("updatedAt", 1_781_654_704_162L)
        s.put("speedKph", 64)
        s.put("rpm", 1875)
        s.put("coolantC", 41)
        s.put("loadPct", 37)
        s.put("throttlePct", 18)
        s.put("soc", 62)
        s.put("voltage", 14.27)
        s.put("powerKw", 35.3)
        s.put("vehicleState", "driving_ev")
        s.put("vehicleStateConfidence", "high")
        s.put("supportedPids", "BE3FA813")
        s.put("raw", "010D=410D28;010C=410C0FA0;0149=414930")
        // ~38 EV "extras" carried through verbatim — a real Volt sample is ~75 fields.
        val extras =
            mapOf(
                "intakeAirTempC" to 41,
                "controlModuleVoltage" to 14.27,
                "engineRunTimeSec" to 0,
                "fuelLevelPct" to 62,
                "engineOilTempC" to 37,
                "batteryTemp" to 30,
                "packVoltage" to 352.1,
                "capacityAh" to 45.4,
                "sohPct" to 87.3,
                "packEnergyKwh" to 16.1,
                "hvBatteryRawSoc" to 36.82,
                "hvBatteryChargeCount" to 2007,
                "lastChargeEnergyWh" to 14940,
                "minCellVoltage" to 3.792,
                "maxCellVoltage" to 3.792,
                "cellBalanceMv" to 0,
                "minCellNumber" to 31,
                "maxCellNumber" to 62,
                "chargerHvVoltage" to 350.5,
                "chargerHvCurrent" to 0,
                "chargerPowerKw" to 0,
                "chargingMode" to "NOT_CHARGING",
                "chargingLevel" to "NOT_CHARGING",
                "motorACurrentA" to -0.4,
                "motorBCurrentA" to -0.6,
                "motorAVoltage" to 350,
                "motorBVoltage" to 350,
                "motorAPowerKw" to -0.1,
                "motorBPowerKw" to -0.2,
                "evDistanceThisCycleKm" to 0,
                "prndlState" to "8",
                "batteryCoolantPumpRpm" to 1610,
                "outsideTempC" to 23.5,
                "voltageStaleMs" to 4219,
                "speedKphStaleMs" to 4579,
                "rpmStaleMs" to 4502,
                "socStaleMs" to 4245,
                "throttlePctStaleMs" to 4425,
            )
        for ((k, v) in extras) {
            s.put(k, v)
        }
        return s
    }

    @Test
    fun roundTripPreservesEveryKeySoRemovalIsSafe() {
        val sample = representativeSample()
        val roundTripped = TelemetryPayload.fromJson(sample).toJson()
        // The old read() path produced this round-tripped object; the new path returns `sample`. They
        // must carry the same keys, otherwise dropping the round-trip would change the dashboard feed.
        assertEquals("round-trip must not change the key count", sample.length(), roundTripped.length())
        val keys = sample.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            assertTrue("round-trip dropped key '$key'", roundTripped.has(key))
            assertEquals(
                "round-trip changed value of '$key'",
                sample.get(key).toString(),
                roundTripped.get(key).toString(),
            )
        }
    }

    @Test
    fun telemetryRoundTripCostPerSampleBenchmark() {
        val sample = representativeSample()
        val keyCount = sample.length()
        val warmup = 20_000
        val iters = 200_000
        var sink = 0
        repeat(warmup) { sink += TelemetryPayload.fromJson(sample).toJson().length() }
        val start = System.nanoTime()
        repeat(iters) { sink += TelemetryPayload.fromJson(sample).toJson().length() }
        val elapsedNs = System.nanoTime() - start
        val nsPerOp = elapsedNs / iters
        println(
            "[L8 benchmark] TelemetryPayload round-trip removed from LiveSampleReader.read(): " +
                "keys=$keyCount, ${nsPerOp}ns/sample (~${"%.1f".format(nsPerOp / 1000.0)}µs) over $iters " +
                "iters (sink=$sink) — this CPU + allocation is now saved on every poll sample.",
        )
        assertTrue("benchmark must execute the work", sink != 0)
    }
}
