package com.volttracker.obdpoc.ui.drive

import com.volttracker.obdpoc.ui.units.VoltUnits

/** Propulsion source for the moment: EV (battery) or extended-range (gas). */
enum class DriveMode { EV, GAS }

/** What the car is doing — the Drive ring morphs between these (mockups Direction A). */
enum class DrivePhase { DRIVE, PARKED, CHARGING }

/** Tyre pressures in psi, from the SW-CAN TPMS broadcast (OBDLink adapters only). */
data class TirePressures(
    val fl: Double,
    val fr: Double,
    val rl: Double,
    val rr: Double,
) {
    val all: List<Double> get() = listOf(fl, fr, rl, rr)
}

/** The most recent finished drive this session, for the parked "Last drive" tile. */
data class LastDrive(
    val miles: Double,
    val miPerKwh: Double?,
    val endedAtMs: Long,
)

/**
 * Everything the Drive screen renders, as one immutable value.
 * Pure data — previewable and screenshot-testable with no service running.
 * Nullable fields are "not reported / stale": the screen shows "—", never a guess. Readings are
 * stored in miles, mph, °F and psi; [units] formats them in the chosen system.
 */
data class DriveUiState(
    val connected: Boolean = false,
    /** A connect/scan handshake is underway — session-start actions must wait. */
    val connecting: Boolean = false,
    val statusLabel: String = "No adapter",
    val adapterLabel: String = "--",
    val phase: DrivePhase = DrivePhase.PARKED,
    /** Signed pack power in kW: positive = drive (discharge), negative = regen. */
    val powerKw: Double = 0.0,
    val maxDriveKw: Double = 40.0,
    val maxRegenKw: Double = 40.0,
    val speedMph: Int = 0,
    /** Recent speed samples (mph), oldest first — the hero sparkline. */
    val speedTrace: List<Float> = emptyList(),
    /** Signed pack power samples (kW) over the last minute, oldest first. */
    val powerTrace: List<Float> = emptyList(),
    /** Engine-running flag per [powerTrace] sample (colors the cockpit power strip amber). */
    val gasTrace: List<Boolean> = emptyList(),
    /** SOC samples (%) across the session, oldest first. */
    val socTrace: List<Float> = emptyList(),
    /** Raw pack state of charge (%), as the BECM reports it. */
    val socPercent: Double = 0.0,
    /** The SOC the car's cluster shows (%), or null when the car hasn't reported it. */
    val displayedSocPercent: Double? = null,
    /**
     * The car's own EV range estimate (2241A6 `evRangeKm`) in miles, or null when it
     * hasn't reported or has gone stale — never the distance driven this cycle.
     */
    val evRangeMiles: Double? = null,
    /** Fuel tank level (%), or null when not reported. */
    val fuelPercent: Double? = null,
    /** The cluster's gas range (SW-CAN `fuelRangeKm`) in miles; null when not reported or stale. */
    val gasRangeMiles: Double? = null,
    /** Pack temperature (°F); null when not reported or not connected. */
    val packTempF: Int? = null,
    val packVolts: Double? = null,
    /** Signed pack current in A: positive = discharge, negative = charge/regen. */
    val packAmps: Double? = null,
    val mode: DriveMode = DriveMode.EV,
    val rpm: Int = 0,
    val auxVolts: Double? = null,
    /** SW-CAN 12 V battery monitor (OBDLink only); null when not reported or stale. */
    val aux12Volts: Double? = null,
    val aux12SocPercent: Int? = null,
    val aux12Amps: Double? = null,
    val coolantF: Int? = null,
    val gpsAccuracyFt: Int? = null,
    val ambientF: Int? = null,
    val gear: String = "--",
    /** Motor A/B electrical power in kW — what the enhanced PIDs actually report. */
    val motorAKw: Double? = null,
    val motorBKw: Double? = null,
    val motorTempF: Int? = null,
    val inverterTempF: Int? = null,
    val cabinTempF: Int? = null,
    val transTempF: Int? = null,
    val torqueNm: Int? = null,
    val oilLifePct: Int? = null,
    /** SW-CAN tyre pressures; null when not reported or stale. */
    val tires: TirePressures? = null,
    /** Settings → Units & vehicle: the placard the tyres are judged against (psi). */
    val tirePlacardPsi: Double = TIRE_PLACARD_PSI,
    /** SW-CAN door-lock state; null when unknown. */
    val locked: Boolean? = null,
    val cellSpreadMv: Double? = null,
    val minCellVolts: Double? = null,
    val maxCellVolts: Double? = null,
    val minCellNumber: Int? = null,
    /** Per-cell voltages from the last full cell read (96 groups); empty until one runs. */
    val cellVoltages: List<Double?> = emptyList(),
    /** Charger power into the pack (kW) while plugged in. */
    val chargeKw: Double = 0.0,
    val chargeAcVolts: Double? = null,
    val chargeAcAmps: Double? = null,
    /** "L1" / "L2", or null when the car hasn't said. */
    val chargeLevel: String? = null,
    /** SOC when this charge began; null until a charge is seen starting. */
    val chargeFromSoc: Double? = null,
    val chargeAddedKwh: Double = 0.0,
    /** Home electricity rate ($/kWh) from Settings → Costs; 0 = not set, so no cost is shown. */
    val electricityRate: Double = 0.0,
    val chargeStartedAtMs: Long? = null,
    val chargeEta: ChargeEta? = null,
    /** Wall-clock time of the newest sample (ms) — anchors "Full by" and relative labels. */
    val sampleAtMs: Long = 0L,
    /** Live readings present in the newest sample (cockpit subtitle). */
    val signalCount: Int = 0,
    /** Density: false = Focus (the Arc ring), true = Detailed (the cockpit). */
    val detailed: Boolean = false,
    /** Settings → Units: show km, km/h, °C, kPa and kWh/100 km instead of the imperial units. */
    val metricUnits: Boolean = false,
    /** Settings → Costs → Charge target (%): where a charge is "full" for the ring and the ETA. */
    val chargeTargetPct: Int = 100,
    val tripMiles: Double = 0.0,
    val tripDuration: String = "--",
    val tripMaxMph: Int = 0,
    val tripMiPerKwh: Double? = null,
    val tripKwh: Double? = null,
    /** The car's since-charge cycle split: share of miles on electricity, and gas mpg. */
    val cycleEvPercent: Int? = null,
    val cycleMpg: Double? = null,
    val lastDrive: LastDrive? = null,
) {
    /** Settings → Units, for formatting the readings. */
    val units: VoltUnits get() = VoltUnits.of(metricUnits)

    companion object {
        private val powerSamples =
            listOf(
                12f,
                15f,
                19f,
                23f,
                26f,
                22f,
                16f,
                7f,
                -6f,
                -14f,
                -18f,
                -9f,
                3f,
                11f,
                17f,
                22f,
                26f,
                28f,
                23f,
                17f,
                9f,
                -4f,
                -12f,
                -16f,
                -7f,
                5f,
                13f,
                18f,
                21f,
                20f,
                16f,
                12f,
                10f,
                14f,
                19f,
                24f,
                22f,
                17f,
                11f,
                6f,
                -8f,
                -19f,
                -22f,
                -15f,
                -4f,
                8f,
                14f,
                19f,
                23f,
                21f,
                18f,
                16f,
                15f,
                17f,
                19f,
                20f,
                19f,
                18f,
                18f,
                18.4f,
            )

        /** 96 cell groups spread 3.893–3.912 V with #47 the low one, like the mockups' histogram. */
        private val demoCells: List<Double?> =
            List(DEMO_CELLS) { i -> if (i == DEMO_LOW_CELL) 3.893 else 3.895 + (i * 7 % 18) / 1000.0 }

        /**
         * Sample state mirroring the mockups' electric-drive frame — used by previews and the
         * Roborazzi screenshot suite. [demoRegen], [demoGas], [demoParked] and [demoCharging]
         * are the other ring states.
         */
        val demo =
            DriveUiState(
                connected = true,
                electricityRate = 0.12,
                statusLabel = "Live · 1 Hz",
                adapterLabel = "OBDLink MX+",
                phase = DrivePhase.DRIVE,
                powerKw = 18.4,
                speedMph = 47,
                powerTrace = powerSamples,
                gasTrace = powerSamples.map { false },
                socPercent = 63.0,
                displayedSocPercent = 63.0,
                evRangeMiles = 26.0,
                fuelPercent = 62.0,
                gasRangeMiles = 293.0,
                packTempF = 74,
                packVolts = 356.4,
                packAmps = 52.0,
                auxVolts = 14.1,
                aux12Volts = 14.1,
                aux12SocPercent = 86,
                aux12Amps = 6.0,
                coolantF = 178,
                ambientF = 64,
                gear = "D",
                motorAKw = 11.0,
                motorBKw = 7.4,
                motorTempF = 142,
                inverterTempF = 118,
                cabinTempF = 70,
                transTempF = 131,
                tires = TirePressures(37.7, 38.3, 37.1, 37.7),
                locked = true,
                cellSpreadMv = 19.0,
                minCellVolts = 3.893,
                maxCellVolts = 3.912,
                minCellNumber = 47,
                cellVoltages = demoCells,
                signalCount = 42,
                tripMiles = 12.4,
                tripDuration = "22 min",
                tripMaxMph = 52,
                tripMiPerKwh = 4.1,
                tripKwh = 3.0,
            )

        val demoRegen = demo.copy(speedMph = 38, powerKw = -21.6, packAmps = -61.0)

        val demoGas =
            demo.copy(
                mode = DriveMode.GAS,
                speedMph = 64,
                powerKw = 24.8,
                socPercent = 16.0,
                displayedSocPercent = 0.0,
                evRangeMiles = 0.0,
                rpm = 1860,
                packTempF = 79,
                coolantF = 191,
                fuelPercent = 58.0,
                gasRangeMiles = 274.0,
                packAmps = 8.0,
                gasTrace = powerSamples.mapIndexed { i, _ -> i >= 40 },
                tripMiles = 58.2,
                tripDuration = "61 min",
                tripMiPerKwh = 3.9,
                tripKwh = 10.6,
                cycleEvPercent = 64,
                cycleMpg = 41.2,
            )

        val demoParked =
            demo.copy(
                phase = DrivePhase.PARKED,
                gear = "P",
                speedMph = 0,
                powerKw = 0.4,
                packAmps = 1.0,
                auxVolts = 12.6,
                aux12Volts = 12.6,
                aux12Amps = -0.4,
                powerTrace = emptyList(),
                gasTrace = emptyList(),
                signalCount = 31,
                tripMiles = 0.0,
                tripDuration = "--",
                tripMiPerKwh = null,
                tripKwh = null,
                lastDrive = LastDrive(miles = 18.4, miPerKwh = 4.1, endedAtMs = DEMO_NOW_MS - 60 * 60_000L),
                sampleAtMs = DEMO_NOW_MS,
            )

        val demoCharging =
            demoParked.copy(
                phase = DrivePhase.CHARGING,
                socPercent = 71.0,
                displayedSocPercent = 71.0,
                evRangeMiles = 29.0,
                powerKw = -3.6,
                packAmps = -10.2,
                auxVolts = 13.9,
                aux12Volts = 13.9,
                aux12Amps = 3.5,
                locked = false,
                chargeKw = 3.6,
                chargeAcVolts = 240.0,
                chargeAcAmps = 15.0,
                chargeLevel = "L2",
                chargeFromSoc = 41.0,
                chargeAddedKwh = 4.3,
                chargeStartedAtMs = DEMO_NOW_MS - 30 * 60_000L,
                chargeEta = ChargeEta.Finish(86 * 60_000L),
                signalCount = 38,
            )

        /** A fixed "now" for the demo states (2026-09-27 21:42 US Pacific, the mockups' clock). */
        const val DEMO_NOW_MS = 1_790_570_520_000L
        private const val DEMO_CELLS = 96
        private const val DEMO_LOW_CELL = 46
    }
}
