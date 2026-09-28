package com.volttracker.obdpoc

import com.volttracker.obdpoc.engine.EngineHost
import com.volttracker.obdpoc.engine.ObdPollingEngine
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
        const val CHARGER_KW = 3.6

        // 3.6 kW is the 2017 Volt's onboard-charger ceiling on Level 2.
        // Exaggerated vs the real ~0.007 %/s a 3.6 kW charger manages, so the
        // SOC visibly climbs within the 30 s demo charge window. The drive-phase
        // drain is matched so each cycle is SOC-neutral (0.06 * 60 == 0.12 * 30):
        // the sawtooth repeats forever instead of drifting into a cap.
        const val CHARGE_SOC_PER_SECOND = 0.12
        private const val DRIVE_SOC_PER_SECOND = 0.06
        private const val SOC_START = 77.8
        private const val DEMO_DISPLAYED_SOC_OFFSET = 3.0
        private const val SOC_FLOOR = 13.4

        // Safety bounds only — the periodic form ranges 74.2..77.8 and can
        // never reach either; kept below the 100% default charge target so the
        // hero could not hide mid-window even if the constants drift apart.
        private const val SOC_CHARGE_CAP = 95.0

        /** Raw PRNDL code for D (see [VoltGear]); the demo only ever shows P or D. */
        private const val DEMO_DRIVE_GEAR_RAW = 3
        private const val DEMO_CLOSED = "closed"

        // The drive phase is one short trip (mirrors actions-demo.ts's DEMO_*_AT_S):
        // EV with regen dips until 36 s, the engine runs 36-48 s, regen braking to a
        // stop 48-54 s, then parked in P until the charger is plugged in at 60 s.
        const val GAS_START_SECONDS = 36.0
        const val BRAKE_START_SECONDS = 48.0
        const val PARK_START_SECONDS = 54.0
        private const val BRAKE_SECONDS = PARK_START_SECONDS - BRAKE_START_SECONDS
        private const val MPH_TO_KPH = 1.609

        fun isChargingPhase(t: Double): Boolean = t.mod(CYCLE_SECONDS) >= DRIVE_PHASE_SECONDS

        /** Which leg of the demo cycle t falls in. */
        fun legAt(t: Double): DemoLeg {
            val phase = t.mod(CYCLE_SECONDS)
            return when {
                phase >= DRIVE_PHASE_SECONDS -> DemoLeg.CHARGING
                phase >= PARK_START_SECONDS -> DemoLeg.PARKED
                phase >= BRAKE_START_SECONDS -> DemoLeg.BRAKING
                phase >= GAS_START_SECONDS -> DemoLeg.GAS
                else -> DemoLeg.EV
            }
        }

        /** The design prototype's gentle 25-47 mph urban speed band, in kph, on the drive clock. */
        private fun cruiseKph(driveT: Double): Double =
            (34 + 9 * Math.sin(driveT / 4.2) + 4 * Math.sin(driveT / 1.7)) * MPH_TO_KPH

        /** Road speed at t: cruising, easing linearly to a stop while braking, 0 parked or charging. */
        fun demoSpeedKph(t: Double): Long {
            val driveT = driveSeconds(t)
            val phase = t.mod(CYCLE_SECONDS)
            return when (legAt(t)) {
                DemoLeg.EV, DemoLeg.GAS -> Math.round(cruiseKph(driveT))
                DemoLeg.BRAKING -> {
                    val fromKph = cruiseKph(driveT - (phase - BRAKE_START_SECONDS))
                    Math.round(fromKph * (PARK_START_SECONDS - phase) / BRAKE_SECONDS)
                }
                DemoLeg.PARKED, DemoLeg.CHARGING -> 0L
            }
        }

        /** Signed pack power at t: + drive, - regen. The EV leg dips into regen; braking regenerates. */
        fun demoPowerKw(t: Double): Double {
            val driveT = driveSeconds(t)
            val phase = t.mod(CYCLE_SECONDS)
            return when (legAt(t)) {
                DemoLeg.EV -> 6.0 + 14.0 * Math.sin(driveT / 3.1) + 5.0 * Math.sin(driveT / 1.3)
                DemoLeg.GAS -> 30.0 + 9.0 * Math.sin(driveT / 3.0)
                DemoLeg.BRAKING -> -(8.0 + 10.0 * (PARK_START_SECONDS - phase) / BRAKE_SECONDS)
                DemoLeg.PARKED, DemoLeg.CHARGING -> 0.0
            }
        }

        /** Engine speed: only the gas leg runs the engine. */
        fun demoRpm(t: Double): Long =
            if (legAt(t) == DemoLeg.GAS) Math.round(1260 + 420 * Math.sin(driveSeconds(t) / 2.1)) else 0L

        /** Seconds spent moving in [0, t) — the map clock, frozen while parked and charging. */
        fun routeSeconds(t: Double): Double {
            val cycles = Math.floor(t / CYCLE_SECONDS)
            return cycles * PARK_START_SECONDS + minOf(t.mod(CYCLE_SECONDS), PARK_START_SECONDS)
        }

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
            val leg = legAt(t)
            val driveT = driveSeconds(t)
            val routeT = routeSeconds(t)
            val speedKph = demoSpeedKph(t)
            val sample = JSONObject()
            try {
                val sampleNumber = engine.incrementSampleCount()
                sample.put("source", "demo")
                sample.put("connected", true)
                sample.put("adapter", service.activeName)
                sample.put("sampleCount", sampleNumber)
                sample.put("sessionMs", maxOf(0L, System.currentTimeMillis() - service.sessionStartedAtMs))
                sample.put("supportedPids", engine.supportedPidsSummary())
                sample.put("vehicleState", leg.vehicleState)
                sample.put("speedKph", speedKph)
                sample.put("rpm", demoRpm(t))
                sample.put("coolantC", Math.round(82 + 4 * Math.sin(t / 8.0)))
                sample.put("loadPct", if (leg.moving) Math.round(20 + 7 * Math.sin(driveT / 3.3)) else 4L)
                sample.put(
                    "throttlePct",
                    if (leg == DemoLeg.EV || leg == DemoLeg.GAS) Math.round(14 + 9 * Math.sin(driveT / 2.2)) else 0L,
                )
                // Hoist the shared demo formulas once so the mirrored PIDs below
                // (and the raw-vs-rounded pack voltage) stay in step with the JS
                // runBrowserDemoStream mirror instead of drifting per call site.
                val busV = ObdElmDecode.round1(if (charging) 14.2 else 13.8 + 0.2 * Math.sin(t / 5.0))
                val soc = demoSoc(t)
                val chargerKw = demoChargerPowerKw(t)
                val drivePowerKw = demoPowerKw(t)
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
                sample.put("capacityAh", 47.3)
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
                sample.put("motorAPowerKw", if (leg.moving) ObdElmDecode.round1(drivePowerKw * 0.6) else 0.0)
                sample.put("transmissionTempC", ObdElmDecode.round1(68.0 + 3.0 * Math.sin(t / 7.0)))
                sample.put("prndlState", if (leg.moving) "D" else VoltGear.PARK)
                sample.put("prndlRaw", if (leg.moving) DEMO_DRIVE_GEAR_RAW else VoltGear.PARK_RAW)
                sample.put("gearConfidence", GearConfidence.CONFIRMED.wireName)
                sample.put("motorTempC", ObdElmDecode.round1(55.0 + 5.0 * Math.sin(t / 9.0)))
                sample.put("inverterTempC", ObdElmDecode.round1(42.0 + 3.0 * Math.sin(t / 8.0)))
                // The cluster's SOC reads a few points above the raw pack SOC on a real Volt; keeping
                // them apart in the demo shows any screen that mixes the two scales.
                sample.put(
                    "displayedSocPct",
                    ObdElmDecode.round1((soc + DEMO_DISPLAYED_SOC_OFFSET).coerceAtMost(100.0)),
                )
                sample.put("packResistanceMohm", 148.5)
                sample.put("hvIsolationKohm", 2000)
                sample.put("motorBTempC", ObdElmDecode.round1(48.0 + 4.0 * Math.sin(t / 10.0)))
                // Same SOC-proportional ~66 km full-charge range as actions-demo.ts, so both demo
                // streams show "≈ 26 mi EV range" and agree with the cluster range below.
                sample.put("evRangeKm", Math.round(soc / 100.0 * 66.0))
                sample.put("batteryHeaterPct", 0)
                sample.put("pemCoolantTempC", ObdElmDecode.round1(38.0 + 2.0 * Math.sin(t / 11.0)))
                sample.put("lifetimeChargeEnergyKwh", 2198.1)
                for (section in 1..6) {
                    sample.put("packSection${section}TempC", 22 + section % 3)
                }
                // SW-CAN (GMLAN) broadcasts the listen window hears on an OBDLink (mirrors
                // actions-demo.ts; SwcanReadings).
                sample.put("aux12vVoltage", if (charging) 13.9 else 14.1)
                sample.put("aux12vSocPct", 86.0)
                sample.put("aux12vCurrentA", if (charging) 3.5 else 6.0)
                sample.put("tirePressureFlKpa", 260.0)
                sample.put("tirePressureFrKpa", 264.0)
                sample.put("tirePressureRlKpa", 256.0)
                sample.put("tirePressureRrKpa", 260.0)
                sample.put("doorLockState", if (charging) "unlocked" else "locked")
                sample.put("doorLockSource", "fob")
                sample.put("doorFlState", DEMO_CLOSED)
                sample.put("doorFrState", DEMO_CLOSED)
                sample.put("doorRlState", DEMO_CLOSED)
                sample.put("doorRrState", DEMO_CLOSED)
                sample.put("hoodState", DEMO_CLOSED)
                sample.put("trunkState", DEMO_CLOSED)
                sample.put("alarmState", "disarmed")
                sample.put("windowFlPct", 0)
                sample.put("windowFrPct", 0)
                sample.put("windowRlPct", 0)
                sample.put("windowRrPct", 0)
                sample.put("blowerPct", 35.0)
                sample.put("remoteStartState", "off")
                sample.put("cabinTempEstC", ObdElmDecode.round1(21.0 + Math.sin(t / 15.0)))
                sample.put("acState", "on")
                sample.put("peCoolantTempC", ObdElmDecode.round1(32.0 + 2.0 * Math.sin(t / 10.0)))
                sample.put("clusterEvRangeKm", ObdElmDecode.round1(soc / 100.0 * 66.0))
                sample.put("fuelRangeKm", 471.0)
                if (charging) {
                    sample.put("chargeCurrentLimitA", 12.0)
                    sample.put("chargerAcVoltage", 240)
                    sample.put("chargerAcCurrentA", 14.0)
                    sample.put("chargerAcPowerKw", 3.4)
                }
                // The position clock only runs while moving, so the marker stays put
                // once the car parks instead of orbiting an unplugged charger.
                sample.put("latitude", 34.0522 + 0.009 * Math.sin(routeT / 28.0))
                sample.put("longitude", -118.2437 + 0.009 * Math.cos(routeT / 28.0))
                sample.put("accuracyM", 6.0)
                sample.put("gpsSpeedMps", ObdElmDecode.round1(speedKph / 3.6))
                sample.put("bearingDeg", ObdElmDecode.round1((Math.toDegrees(routeT / 28.0) % 360 + 360) % 360))
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

/** One leg of the demo cycle and the classifier state (VehicleState.asPayloadKey) it reports. */
enum class DemoLeg(
    val vehicleState: String,
    val moving: Boolean,
) {
    EV("driving_ev", true),
    GAS("driving_gas", true),
    BRAKING("driving_ev", true),
    PARKED("parked", false),
    CHARGING("charging", false),
}
