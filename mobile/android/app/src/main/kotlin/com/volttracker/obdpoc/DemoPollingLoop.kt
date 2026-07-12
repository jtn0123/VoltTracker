package com.volttracker.obdpoc

import org.json.JSONException
import org.json.JSONObject

/**
 * Emits the synthetic demo telemetry stream that runs in place of a real OBD adapter loop.
 */
private fun defaultDemoSleep(millis: Long): Boolean =
    try {
        Thread.sleep(millis)
        true
    } catch (ex: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

class DemoPollingLoop(
    private val service: EngineHost,
    private val engine: ObdPollingEngine,
    private val sleeper: ObdPollingEngine.LoopSleeper =
        ObdPollingEngine.LoopSleeper { millis ->
            defaultDemoSleep(millis)
        },
) {
    companion object {
        // A compressed "day with the car" cycle: 60 s of driving, then 30 s
        // parked on a Level-2 charger. The charge window feeds the Charge
        // tab's live time-to-full hero, which no demo could preview before.
        // Mirrors actions-demo.ts's browser cycle; keep the two in step.
        const val DRIVE_PHASE_SECONDS = 60.0
        const val CYCLE_SECONDS = 90.0
        const val CHARGER_KW = 7.2

        // Exaggerated vs the real ~0.014 %/s a 7.2 kW charger manages, so the
        // SOC visibly climbs within the 30 s demo charge window. The drive-phase
        // drain is matched so each cycle is SOC-neutral (0.06 * 60 == 0.12 * 30):
        // the sawtooth repeats forever instead of drifting into a cap.
        const val CHARGE_SOC_PER_SECOND = 0.12
        private const val DRIVE_SOC_PER_SECOND = 0.06
        private const val SOC_START = 77.8
        private const val SOC_FLOOR = 13.4

        // Safety bounds only — the periodic form ranges 74.2..77.8 and can
        // never reach either; kept below the 100% default charge target so the
        // hero could not hide mid-window even if the constants drift apart.
        private const val SOC_CHARGE_CAP = 95.0

        fun isChargingPhase(t: Double): Boolean = t.mod(CYCLE_SECONDS) >= DRIVE_PHASE_SECONDS

        /** Seconds spent driving in [0, t) — the route/sine clock, frozen while charging. */
        fun driveSeconds(t: Double): Double {
            val cycles = Math.floor(t / CYCLE_SECONDS)
            return cycles * DRIVE_PHASE_SECONDS + minOf(t.mod(CYCLE_SECONDS), DRIVE_PHASE_SECONDS)
        }

        /** Seconds spent charging in [0, t). */
        fun chargeSeconds(t: Double): Double = t - driveSeconds(t)

        /**
         * Deterministic SOC at t: a continuous, periodic sawtooth within the
         * current cycle — drains across the drive phase, visibly recovers
         * across the charge window, and repeats without drifting (cumulative
         * cross-cycle sums pinned the old form at the cap after ~9 minutes).
         */
        fun demoSoc(t: Double): Double {
            val phase = t.mod(CYCLE_SECONDS)
            val soc =
                SOC_START - DRIVE_SOC_PER_SECOND * minOf(phase, DRIVE_PHASE_SECONDS) +
                    CHARGE_SOC_PER_SECOND * maxOf(0.0, phase - DRIVE_PHASE_SECONDS)
            return soc.coerceIn(SOC_FLOOR, SOC_CHARGE_CAP)
        }

        /**
         * Live charger draw at t. 0.0 while driving — samples merge into the
         * dashboard's telemetry state, so a stale charger reading would
         * otherwise pin the live charge card open after the phase flips.
         */
        fun demoChargerPowerKw(t: Double): Double =
            if (isChargingPhase(t)) {
                ObdElmDecode.round1(CHARGER_KW + 0.3 * Math.sin(t / 5.0))
            } else {
                0.0
            }
    }

    fun run() {
        service.broadcastStatus("connected", "Demo telemetry is running without an OBD adapter.", false)
        var firstSampleMarked = false
        val start = System.currentTimeMillis()
        // Token-aware check (B3): a stale demo runner superseded by a newer session must stop
        // instead of reading the new session's running flag as its own.
        while (service.isSessionRunnerActive()) {
            val t = (System.currentTimeMillis() - start) / 1000.0
            val charging = isChargingPhase(t)
            val driveT = driveSeconds(t)
            val sample = JSONObject()
            try {
                val sampleNumber = engine.incrementSampleCount()
                sample.put("source", "demo")
                sample.put("connected", true)
                sample.put("adapter", service.activeName)
                sample.put("sampleCount", sampleNumber)
                sample.put("sessionMs", maxOf(0L, System.currentTimeMillis() - service.sessionStartedAtMs))
                sample.put("supportedPids", engine.supportedPidsSummary())
                sample.put("vehicleState", if (charging) "charging" else "demo-preview")
                sample.put("speedKph", if (charging) 0L else maxOf(0L, Math.round(54 + 23 * Math.sin(driveT / 3.4))))
                sample.put("rpm", if (charging) 0L else Math.round(1260 + 420 * Math.sin(driveT / 2.1)))
                sample.put("coolantC", Math.round(82 + 4 * Math.sin(t / 8.0)))
                sample.put("loadPct", if (charging) 4L else Math.round(34 + 18 * Math.sin(driveT / 4.4)))
                sample.put("throttlePct", if (charging) 0L else Math.round(18 + 14 * Math.sin(driveT / 2.7)))
                // Hoist the shared demo formulas once so the mirrored PIDs below
                // (and the raw-vs-rounded pack voltage) stay in step with the JS
                // runBrowserDemoStream mirror instead of drifting per call site.
                val busV = ObdElmDecode.round1(if (charging) 14.2 else 13.8 + 0.2 * Math.sin(t / 5.0))
                val soc = demoSoc(t)
                val chargerKw = demoChargerPowerKw(t)
                val drivePowerKw = if (charging) 0.0 else 16.0 + Math.sin(driveT / 2.2) * 12.0
                val rawPackV = 353.0 + (soc - 50.0) * 0.2
                val packWatts = (if (charging) -chargerKw else drivePowerKw) * 1000.0
                sample.put("voltage", busV)
                sample.put("soc", ObdElmDecode.round1(soc))
                sample.put("batteryTemp", ObdElmDecode.round1(24.0 + Math.sin(t / 8.0)))
                sample.put("powerKw", ObdElmDecode.round1(drivePowerKw))
                sample.put("chargerPowerKw", chargerKw)
                // Extra PIDs a real Volt answers, so the Live-signals console shows a
                // populated "reporting" list in demo (mirrors runBrowserDemoStream).
                sample.put("packVoltage", ObdElmDecode.round1(rawPackV))
                sample.put("packCurrentA", ObdElmDecode.round1(packWatts / rawPackV))
                sample.put("controlModuleVoltage", busV)
                sample.put("odometerKm", 77593.0)
                sample.put("intakeAirTempC", ObdElmDecode.round1(22.0 + 3.0 * Math.sin(t / 11.0)))
                sample.put("outsideTempC", ObdElmDecode.round1(18.0 + 2.0 * Math.sin(t / 13.0)))
                sample.put("sohPct", 91.0)
                sample.put("packEnergyKwh", ObdElmDecode.round1(soc / 100.0 * 14.0))
                sample.put("hvBatteryRawSoc", ObdElmDecode.round1(soc + 2.0))
                // HV cell-group balance for the Battery-tab cell card (mirrors
                // actions-demo.ts): a healthy pack wobbling ~10-20 mV around ~3.9 V,
                // cell 47 on the low side to match the "Cell 47 trending low" insight.
                val cellAvgV = 3.85 + (soc - 50.0) * 0.003
                val cellSpreadMv = Math.round(14.0 + 6.0 * Math.sin(t / 9.0)).toInt()
                sample.put("minCellVoltage", ObdElmDecode.round3(cellAvgV - cellSpreadMv / 2000.0))
                sample.put("maxCellVoltage", ObdElmDecode.round3(cellAvgV + cellSpreadMv / 2000.0))
                sample.put("cellBalanceMv", cellSpreadMv)
                sample.put("minCellNumber", 47)
                sample.put("maxCellNumber", 12)
                sample.put("socVariationPct", 0.4)
                sample.put("motorAPowerKw", if (charging) 0.0 else ObdElmDecode.round1(drivePowerKw * 0.6))
                sample.put("transmissionTempC", ObdElmDecode.round1(68.0 + 3.0 * Math.sin(t / 7.0)))
                sample.put("prndlState", if (charging) "P" else "D")
                // The position clock is driveT, so the marker parks during the
                // charge window instead of orbiting an unplugged charger.
                sample.put("latitude", 34.0522 + 0.009 * Math.sin(driveT / 28.0))
                sample.put("longitude", -118.2437 + 0.009 * Math.cos(driveT / 28.0))
                sample.put("accuracyM", 6.0)
                sample.put(
                    "gpsSpeedMps",
                    if (charging) 0.0 else ObdElmDecode.round1(kotlin.math.abs(15.0 + 9.0 * Math.cos(driveT / 28.0))),
                )
                sample.put("bearingDeg", ObdElmDecode.round1((Math.toDegrees(driveT / 28.0) % 360 + 360) % 360))
                sample.put("updatedAt", System.currentTimeMillis())
                engine.appendSessionHealth(sample)
                sample.put("raw", "demo")
            } catch (ignored: JSONException) {
                // Local numeric values are safe.
            }
            service.broadcastTelemetry(sample)
            if (!firstSampleMarked) {
                firstSampleMarked = true
                // Demo counterpart of the live path's obd_first_sample:live mark (ObdPollingEngine.
                // logFirstSampleTiming): lets the emulator smoke capture connect→first-sample
                // latency without a car. The ":demo" suffix keeps it distinct from real-adapter runs.
                StartupTrace.mark("${StartupTrace.OBD_FIRST_SAMPLE}:demo")
            }
            if (!sleeper.sleep(1000)) {
                return
            }
        }
    }
}
