package com.volttracker.obdpoc.sim

/**
 * What the virtual Volt answers for each (header, command), and how much we trust that answer.
 *
 * Every reply is tagged with where it came from, so the scorecard can tell "the app can't decode
 * what the car sends" apart from "we have never seen the car answer this at all":
 *
 * - [Evidence.REAL] — these exact bytes (or bytes of this exact shape) came off the target car.
 *   Sources: ObdProtocolTest "real captures", docs/pid-validation-2026-06-03.md,
 *   docs/field-test-2026-05-19.md, docs/obd-log-findings-2026-06-16.md.
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
            entry("7DF", "015B", Evidence.GUESS, both("415B99")),
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
            entry("7E1", "222883", Evidence.GUESS, split("62288300C8", "6228830000")),
            entry("7E1", "222884", Evidence.GUESS, split("6228840064", "6228840000")),
            entry("7E1", "222885", Evidence.GUESS, both("6228858980")),
            entry("7E1", "222886", Evidence.GUESS, both("6228868980")),
            entry("7E1", "222889", Evidence.GUESS, both("62288904")),
            entry("7E1", "222487", Evidence.GUESS, both("62248704D2")),
            // --- Mode 22 on 7E2 (transmission) -----------------------------------------------
            entry("7E2", "221940", Evidence.DEAD, both(null)),
            entry("7E2", "22194001", Evidence.DEAD, both(null)),
            // --- Mode 22 on 7E4 (battery / charger) --------------------------------------------
            entry("7E4", "22434F", Evidence.REAL, both("62434F43")),
            entry("7E4", "22436B", Evidence.REAL, chargingOnly("62436B02A0")),
            entry("7E4", "22436C", Evidence.REAL, chargingOnly("62436C00C8")),
            entry("7E4", "224373", Evidence.SEEN, split("6243730000", "624373FFFF")),
            entry("7E4", "224531", Evidence.SEEN, split("62453100", "62453102")),
            entry("7E4", "2243AF", Evidence.SEEN, both("6243AF89F9")),
            entry("7E4", "224329", Evidence.REAL, both("6243291687")),
            entry("7E4", "22432B", Evidence.REAL, both("62432B16A8")),
            entry("7E4", "22432A", Evidence.PLACEHOLDER, both("62432A00")),
            entry("7E4", "22432C", Evidence.PLACEHOLDER, both("62432C00")),
            entry("7E4", "22435F", Evidence.GUESS, both("62435F05")),
            entry("7E4", "2241B2", Evidence.REAL, both("6241B208E5")),
            entry("7E4", "2241B4", Evidence.GUESS, both("6241B402")),
            entry("7E4", "2241B6", Evidence.GUESS, both("6241B60000")),
            entry("7E4", "22801E", Evidence.GUESS, both("62801E78")),
            entry("7E4", "22801F", Evidence.GUESS, both("62801F78")),
            entry("7E4", "2243A5", Evidence.SEEN, both("6243A50456")),
            entry("7E4", "22437D", Evidence.SEEN, both("62437D0339")),
            entry("7E4", "2241A3", Evidence.GUESS, both("6241A30205")),
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
        2241B2 batteryCoolantPumpRpm
        2241B4 batteryCoolantValveRaw
        2241B6 batteryHeaterPowerW
        22801E outsideTempRawC
        22801F outsideTempC
        2243A5 hvBatteryChargeCount
        22437D lastChargeEnergyWh
        2241A3 capacityAh sohPct packEnergyKwh
        """.trimIndent()
            .lines()
            .filter(String::isNotBlank)
            .associate { line ->
                val parts = line.trim().split(" ")
                parts.first() to parts.drop(1)
            }
}
