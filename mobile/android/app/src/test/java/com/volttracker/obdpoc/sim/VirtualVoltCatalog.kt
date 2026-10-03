package com.volttracker.obdpoc.sim

/**
 * What the virtual Volt answers for each (header, command), and how much we trust that answer.
 *
 * Every reply is tagged with where it came from, so the scorecard can tell "the app can't decode
 * what the car sends" apart from "we have never seen the car answer this at all":
 *
 * - [Evidence.REAL] — these exact bytes (or bytes of this exact shape) came off the target car.
 *   Sources: ObdProtocolTest "real captures", docs/pid-validation-2026-06-03.md,
 *   docs/field-test-2026-05-19.md, docs/obd-log-findings-2026-06-16.md, and the 2026-09-29 on-car
 *   session (parked, car on, A/C running, pack at 14 % raw; not committed, it carries the VIN).
 *   Where the car's state then doesn't fit a mode (driving, charging), the reply keeps the car's
 *   byte layout at a plausible value and the comment gives what the car actually sent.
 * - [Evidence.SEEN] — the car is recorded answering this PID, but the bytes were not kept; the
 *   reply is synthesized from the decoder's formula at a plausible value.
 * - [Evidence.DEAD] — the car is recorded refusing this PID (NO DATA or a 7F negative reply);
 *   docs/enhanced-discovery-tracker.md and the 2026-06-16 log findings.
 * - [Evidence.PLACEHOLDER] — the car answers, but only ever with an all-zero placeholder payload
 *   (docs/obd-log-findings-2026-06-16.md), so there is no real value to show.
 * - [Evidence.GUESS] — never observed on this car; synthesized from the community formula only.
 */
object VirtualVoltCatalog {
    enum class Evidence { REAL, SEEN, DEAD, PLACEHOLDER, GUESS }

    /** Vehicle situation the car is in; charger PIDs only answer while [CHARGING]. */
    enum class Mode { DRIVING, CHARGING }

    class Entry(
        val header: String,
        val command: String,
        val evidence: Evidence,
        /** Reply per mode, without the trailing prompt; null means NO DATA in that mode. */
        val replies: Map<Mode, String?>,
    )

    private const val NEGATIVE_OUT_OF_RANGE = "7F2231"

    private fun both(reply: String?): Map<Mode, String?> = mapOf(Mode.DRIVING to reply, Mode.CHARGING to reply)

    private fun chargingOnly(reply: String): Map<Mode, String?> = mapOf(Mode.DRIVING to null, Mode.CHARGING to reply)

    private fun split(
        driving: String?,
        charging: String?,
    ): Map<Mode, String?> = mapOf(Mode.DRIVING to driving, Mode.CHARGING to charging)

    private fun entry(
        header: String,
        command: String,
        evidence: Evidence,
        replies: Map<Mode, String?>,
    ) = Entry(header, command, evidence, replies)

    val ENTRIES: List<Entry> =
        listOf(
            // --- Mode 01 on the 7DF broadcast (answered by the ECM) ---------------------------
            entry("7DF", "010D", Evidence.REAL, split("410D32", "410D00")), // 50 km/h / parked
            entry("7DF", "010C", Evidence.REAL, both("410C0000")), // EV mode: engine off
            entry("7DF", "0104", Evidence.REAL, both("410400")), // EV mode: no engine load
            entry("7DF", "0111", Evidence.REAL, both("41115D")),
            entry("7DF", "0105", Evidence.REAL, both("410563")),
            entry("7DF", "0149", Evidence.GUESS, split("414933", "414900")),
            // The car answered 415B22..415B24 (13-14 %) on 2026-09-29; same shape, a mid value.
            entry("7DF", "015B", Evidence.REAL, both("415B99")),
            entry("7DF", "010F", Evidence.GUESS, both("410F46")),
            entry("7DF", "0142", Evidence.SEEN, both("414236B0")),
            entry("7DF", "011F", Evidence.SEEN, both("411F012C")),
            entry("7DF", "012F", Evidence.SEEN, both("412F80")),
            entry("7DF", "015C", Evidence.DEAD, both(null)),
            // ATRV is answered by the adapter itself (battery voltage at the OBD port), not the car.
            entry("7DF", "ATRV", Evidence.REAL, both("14.9V")),
            entry("7DF", "01A6", Evidence.DEAD, both(null)),
            // GM odometer as read by the open-source Voltage app; 0x003C4B00 / 64 = 61,740 km.
            entry("7DF", "2234B2", Evidence.GUESS, both("6234B2003C4B00")),
            // --- Mode 22 on 7E0 (engine) ---------------------------------------------------
            entry("7E0", "22119F", Evidence.DEAD, both(NEGATIVE_OUT_OF_RANGE)),
            entry("7E0", "22119F01", Evidence.DEAD, both(NEGATIVE_OUT_OF_RANGE)),
            entry("7E0", "221154", Evidence.SEEN, both("62115450")),
            // --- Mode 22 on 7E1 (hybrid powertrain) -----------------------------------------
            entry("7E1", "222414", Evidence.REAL, split("622414FE87", "6224140000")),
            entry("7E1", "222429", Evidence.REAL, both("6224295806")),
            // Motor currents, signed 0.05 A: parked on 2026-09-29 the car sent FFF4 (-0.6 A) and
            // FFF6 (-0.5 A), and as low as FD9C (-30.6 A). Driving is a mid value.
            entry("7E1", "222883", Evidence.REAL, split("62288300C8", "622883FFF4")),
            entry("7E1", "222884", Evidence.REAL, split("6228840064", "622884FFF6")),
            // Motor voltages: 8340 (336.0 V) from the car with the pack at 352 V.
            entry("7E1", "222885", Evidence.REAL, both("6228858340")),
            entry("7E1", "222886", Evidence.REAL, both("6228868340")),
            entry("7E1", "222889", Evidence.SEEN, split("62288903", "62288908")), // D / P (VoltGear)
            // Every read on 2026-09-29 came back 7F 22 31: this car's 7E1 has no inverter temperature.
            entry("7E1", "221C26", Evidence.DEAD, both(NEGATIVE_OUT_OF_RANGE)),
            // The car sent 6224870000 (0 km, parked); driving is 12.34 km.
            entry("7E1", "222487", Evidence.REAL, split("62248704D2", "6224870000")),
            // --- Mode 22 on 7E2 (transmission) -----------------------------------------------
            entry("7E2", "221940", Evidence.DEAD, both(null)),
            entry("7E2", "22194001", Evidence.DEAD, both(null)),
            // --- Mode 22 on 7E4 (battery / charger) --------------------------------------------
            entry("7E4", "22434F", Evidence.REAL, both("62434F43")),
            entry("7E4", "22436B", Evidence.REAL, chargingOnly("62436B02A0")),
            entry("7E4", "22436C", Evidence.REAL, chargingOnly("62436C00C8")),
            entry("7E4", "224373", Evidence.SEEN, split("6243730000", "624373FFFF")),
            entry("7E4", "224531", Evidence.SEEN, split("62453100", "62453102")),
            // Charger AC input: the car answers 62436800 / 62436900 when not plugged in (2026-09-29).
            // The app only asks while charging; there the reply is 240 V / 14 A in the same layout.
            entry("7E4", "224368", Evidence.REAL, chargingOnly("62436878")), // 240 V
            entry("7E4", "224369", Evidence.REAL, chargingOnly("62436946")), // 14 A
            entry("7E4", "2243AF", Evidence.SEEN, both("6243AF89F9")),
            // The car answered 62833400 (0 %, pack at 14 % raw) on 2026-09-29; same shape, a mid value.
            entry("7E4", "228334", Evidence.REAL, both("6283348C")), // 54.9 %
            entry("7E4", "224329", Evidence.REAL, both("6243291687")),
            entry("7E4", "22432B", Evidence.REAL, both("62432B16A8")),
            entry("7E4", "22432A", Evidence.PLACEHOLDER, both("62432A00")),
            entry("7E4", "22432C", Evidence.PLACEHOLDER, both("62432C00")),
            // Real logs (2026-07): every read of 22435F came back 7F 22 31.
            entry("7E4", "22435F", Evidence.DEAD, both(NEGATIVE_OUT_OF_RANGE)),
            entry("7E4", "2241B2", Evidence.REAL, both("6241B208E5")),
            // Coolant valve, heater power and outside air (raw 26.5 C, filtered 27 C), from the car on
            // 2026-09-29.
            entry("7E4", "2241B4", Evidence.REAL, both("6241B400")),
            entry("7E4", "2241B6", Evidence.REAL, both("6241B60000")),
            entry("7E4", "22801E", Evidence.REAL, both("62801E85")),
            entry("7E4", "22801F", Evidence.REAL, both("62801F86")),
            entry("7E4", "2243A5", Evidence.SEEN, both("6243A50456")),
            entry("7E4", "22437D", Evidence.SEEN, both("62437D0339")),
            entry("7E4", "2241A3", Evidence.GUESS, both("6241A30205")),
            entry("7E4", "2240E9", Evidence.GUESS, both("6240E9012C")), // 150 mOhm
            entry("7E4", "2243A6", Evidence.GUESS, both("6243A650")), // 2000 kOhm
            // OVMS Volt/Ampera poll list (MY2017). 41A6 and 4389 are REAL: their replies turned up
            // in the 2026 phone logs when another app on the bus asked for them (41A6 = 0 km at a
            // 14 % SOC; 0x00218A49 Wh = 2198.1 kWh lifetime). The heater, PEM coolant and
            // pack-section replies below are the car's own bytes from 2026-09-29.
            // 41A6 keeps the car's layout at 55 km (1/64 km), matching the cluster range frame and the
            // 54.9 % dash SOC; the car's own 0 km came with an empty pack.
            entry("7E4", "2241A6", Evidence.REAL, both("6241A60DC0")),
            entry("7E4", "224389", Evidence.REAL, both("62438900218A49")),
            entry("7E4", "22439E", Evidence.REAL, both("62439E00")), // heater off
            entry("7E4", "221C43", Evidence.REAL, both("621C4350")), // 40 C
            entry("7E7", "2240D7", Evidence.REAL, both("6240D745")), // 29 C
            entry("7E7", "2240D9", Evidence.REAL, both("6240D944")),
            entry("7E7", "2240DB", Evidence.REAL, both("6240DB45")),
            entry("7E7", "2240DD", Evidence.REAL, both("6240DD45")),
            entry("7E7", "2240DF", Evidence.REAL, both("6240DF45")),
            entry("7E7", "2240E1", Evidence.REAL, both("6240E146")),
            // --- Mode 22 on the drive-unit motor-generator nodes (replies on 0x657 / 0x658) ------
            // 57 C / 56 C from the car on 2026-09-29 (car on). Still assumed asleep while charging.
            entry("257", "2228CB", Evidence.REAL, split("6228CB61", null)),
            entry("258", "22368F", Evidence.REAL, split("62368F60", null)),
            // Body module tires, FL RL FR RR at 4 kPa/count (240-252 kPa); unconfirmed on the car.
            entry("241", "22C901", Evidence.GUESS, both("62C9013C3D3E3F")),
        )

    /**
     * SW-CAN (GMLAN) broadcast frames the virtual car puts on OBD pin 1, printed the way an OBDLink
     * shows them in `STM` with `ATH1 ATS1` (29-bit id as four bytes, then data). [Evidence.GUESS]
     * frames are built from the OVMS vehicle_voltampera decoders at plausible values; [Evidence.REAL]
     * ones are copied from the 2026-09-29 capture. Each line's decoded value is noted beside it;
     * SwcanFrameDecoderTest pins the same math.
     */
    class SwcanFrame(
        val evidence: Evidence,
        val line: String,
        /** Live-sample fields this frame should produce. */
        val fields: List<String>,
    )

    private val SWCAN_COMMON: List<SwcanFrame> =
        listOf(
            // 12.7 V, 0.0 A; captured on the car 2026-09-29. BatSOC is 0xFF (not available), so no
            // aux12vSocPct.
            SwcanFrame(
                Evidence.REAL,
                "10 24 80 40 00 00 61 FF FF 00 00",
                listOf("aux12vVoltage", "aux12vCurrentA"),
            ),
            // FL 260, RL 256, FR 264, RR 260 kPa
            SwcanFrame(
                Evidence.GUESS,
                "10 3D 40 40 00 00 41 40 42 41 00 00",
                listOf("tirePressureFlKpa", "tirePressureFrKpa", "tirePressureRlKpa", "tirePressureRrKpa"),
            ),
            // Locked from the interior panel; captured on the car 2026-09-29.
            SwcanFrame(Evidence.REAL, "0C 41 40 40 00 01 7A 01", listOf("doorLockState", "doorLockSource")),
            // power-electronics coolant 32 C
            SwcanFrame(Evidence.GUESS, "10 63 40 CB 00 48", listOf("peCoolantTempC")),
            // Cabin (roof surface) estimate 29.5 C; captured on the car 2026-09-29.
            SwcanFrame(Evidence.REAL, "10 44 00 99 10 00 00 00 8D 8B 46", listOf("cabinTempEstC")),
            // A/C on; captured on the car 2026-09-29.
            SwcanFrame(Evidence.REAL, "10 73 40 99 20 00 00 A0 00 00", listOf("acState")),
            // Blower 13 % (byte 2) while the climate ran; captured on the car 2026-09-29.
            SwcanFrame(Evidence.REAL, "10 81 40 99 20 00 22 00", listOf("blowerPct")),
            // Evaporator 10.0 C, compressor 4132 rpm while the A/C ran; captured on the car 2026-09-29.
            SwcanFrame(Evidence.REAL, "10 27 00 CB 00 64 10 24 00", listOf("acEvapTempC", "acCompressorRpm")),
            // cluster EV range 55 km (GMLAN PID 0x176)
            SwcanFrame(Evidence.GUESS, "10 2E C0 CB 00 1B 80 00 00 00 00 00", listOf("clusterEvRangeKm")),
            // Gas range 378 km (GMLAN PID 0x224). The car sends it from source 0x60, not the 0xCB the
            // community decoders show; captured on the car 2026-09-29.
            SwcanFrame(Evidence.REAL, "10 44 80 60 00 00 5E 8D", listOf("fuelRangeKm")),
            // 8.3 kWh used since the last full charge (GMLAN PID 0x141)
            SwcanFrame(Evidence.GUESS, "10 28 20 CB 00 00 00 53 00 00 00 00", listOf("cycleEnergyUsedKwh")),
            // 52.0 km on battery, 0 km on gas since the last full charge (GMLAN PID 0x225)
            SwcanFrame(
                Evidence.GUESS,
                "10 44 A0 CB 06 80 00 00 00 00 00 00",
                listOf("cycleEvDistanceKm", "cycleFuelDistanceKm"),
            ),
            // Traffic the app does not decode: must be ignored, not misread.
            SwcanFrame(Evidence.GUESS, "10 00 20 97 01 02 03 04", emptyList()),
            SwcanFrame(Evidence.GUESS, "621 00 40 00 00 00 00 00 00", emptyList()),
        )

    /** Frames on the bus per mode; the charge-current limit frame only appears while plugged in. */
    val SWCAN_FRAMES: Map<Mode, List<SwcanFrame>> =
        mapOf(
            Mode.DRIVING to SWCAN_COMMON,
            Mode.CHARGING to
                SWCAN_COMMON +
                // 12 A of offered levels [12, 8]
                SwcanFrame(Evidence.GUESS, "10 86 C0 CB 00 00 00 06 20 00 00 00", listOf("chargeCurrentLimitA")),
        )

    private val byKey: Map<String, Entry> = ENTRIES.associateBy { key(it.header, it.command) }

    fun key(
        header: String,
        command: String,
    ): String = "$header/$command"

    fun find(
        header: String,
        command: String,
    ): Entry? = byKey[key(header, command)]

    /**
     * Which telemetry field each scheduled PID feeds, mirroring LiveSampleReader. The scorecard test
     * fails if a PidSchedule entry is missing here, so a new PID can't silently skip the scorecard.
     */
    val FIELDS_BY_COMMAND: Map<String, List<String>> =
        """
        010D speedKph
        010C rpm
        0149 throttlePct
        0111 throttlePct
        0104 loadPct
        015B soc
        ATRV voltage
        0105 coolantC
        010F intakeAirTempC
        0142 controlModuleVoltage
        011F engineRunTimeSec
        015C engineOilTempC
        012F fuelLevelPct
        01A6 odometerKm odometerMiles
        2234B2 odometerKm odometerMiles
        22119F engineOilLifePct
        22119F01 engineOilLifePct
        221154 engineOilTempC
        222414 powerKw
        222429 packVoltage powerKw
        222883 motorACurrentA motorAPowerKw
        222884 motorBCurrentA motorBPowerKw
        222885 motorAVoltage motorAPowerKw
        222886 motorBVoltage motorBPowerKw
        222889 prndlState
        2228CB motorTempC
        221C26 inverterTempC
        222487 evDistanceThisCycleKm
        221940 transmissionTempC
        22194001 transmissionTempC
        22434F batteryTemp
        22436B chargerHvVoltage chargerPowerKw
        22436C chargerHvCurrent chargerPowerKw
        224373 chargingMode
        224531 chargingLevel
        2243AF hvBatteryRawSoc
        224329 minCellVoltage cellBalanceMv
        22432B maxCellVoltage cellBalanceMv
        22432A minCellNumber
        22432C maxCellNumber
        22435F socVariationPct
        228334 displayedSocPct
        224368 chargerAcVoltage chargerAcPowerKw
        224369 chargerAcCurrentA
        2240E9 packResistanceMohm
        2243A6 hvIsolationKohm
        2241A6 evRangeKm
        224389 lifetimeChargeEnergyKwh
        22439E batteryHeaterPct
        221C43 pemCoolantTempC
        2240D7 packSection1TempC
        2240D9 packSection2TempC
        2240DB packSection3TempC
        2240DD packSection4TempC
        2240DF packSection5TempC
        2240E1 packSection6TempC
        22368F motorBTempC
        2241B2 batteryCoolantPumpRpm
        2241B4 batteryCoolantValveRaw
        2241B6 batteryHeaterPowerW
        22801E outsideTempRawC
        22801F outsideTempC
        2243A5 hvBatteryChargeCount
        22437D lastChargeEnergyWh
        2241A3 capacityAh sohPct packEnergyKwh
        22C901 tirePressureFlKpa tirePressureFrKpa tirePressureRlKpa tirePressureRrKpa
        """.trimIndent()
            .lines()
            .filter(String::isNotBlank)
            .associate { line ->
                val parts = line.trim().split(" ")
                parts.first() to parts.drop(1)
            }
}
