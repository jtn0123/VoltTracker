package com.volttracker.obdpoc

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Collections
import java.util.Locale

/** Open enhanced/candidate PID catalog for the Volt profile. */
object EnhancedPidProfiles {
    const val STATUS_CANDIDATE: String = "candidate"
    const val STATUS_CONFIRMED: String = "confirmed"
    const val STATUS_REJECTED: String = "rejected_on_this_vehicle"
    const val STATUS_DIAGNOSTIC_ONLY: String = "diagnostic_only"
    const val STAGE_PASSIVE: String = "passive"
    const val STAGE_LOW_RISK: String = "low-risk"
    const val STAGE_TIRES: String = "tires"
    const val STAGE_EXPERIMENTAL: String = "experimental"

    private const val HS_CAN = "hs-can"
    private const val GMLAN = "gmlan"
    private const val BROADCAST = ""
    private const val RETRY_ONCE_PER_DRIVE_MS = 8L * 60L * 60L * 1000L
    private const val RETRY_CANDIDATE_MS = 2L * 60L * 60L * 1000L
    private const val RETRY_REJECTED_MS = 7L * 24L * 60L * 60L * 1000L

    @JvmField
    val ALL: List<EnhancedPidProfile>

    init {
        val profiles = ArrayList<EnhancedPidProfile>()
        add(
            profiles,
            "odometer.standard.01a6",
            "odometer",
            HS_CAN,
            BROADCAST,
            "01A6",
            "odometer",
            "km",
            "once_per_drive",
            STAGE_PASSIVE,
            "safe",
            RETRY_ONCE_PER_DRIVE_MS,
            STATUS_CANDIDATE,
            "SAE J1979 optional PID",
            "This car returned NO DATA; keep as candidate only.",
        )
        add(
            profiles,
            "odometer.passive.120",
            "odometer",
            HS_CAN,
            "CAN:120",
            "CAN:120",
            "odometer",
            "km",
            "passive",
            STAGE_PASSIVE,
            "safe",
            RETRY_ONCE_PER_DRIVE_MS,
            STATUS_CANDIDATE,
            "GM Volt reverse engineering wiki",
            "Broadcast frame 0x120 is the next safer odometer target; not an OBD PID.",
        )
        add(
            profiles,
            "maintenance.oil_life.22119f",
            "maintenance",
            HS_CAN,
            "ATSH7E0",
            "22119F",
            "engine oil life",
            "%",
            "diagnostic_only",
            STAGE_LOW_RISK,
            "low",
            RETRY_REJECTED_MS,
            STATUS_CANDIDATE,
            "ScanGauge GM CANSF oil life",
            "Real car returned a negative response.",
        )
        add(
            profiles,
            "maintenance.oil_life.22119f01",
            "maintenance",
            HS_CAN,
            "ATSH7E0",
            "22119F01",
            "engine oil life",
            "%",
            "diagnostic_only",
            STAGE_LOW_RISK,
            "low",
            RETRY_REJECTED_MS,
            STATUS_CANDIDATE,
            "ScanGauge GM CANSF oil life selector variant",
            "Real car returned a negative response.",
        )
        add(
            profiles,
            "maintenance.oil_temp.221154",
            "maintenance",
            HS_CAN,
            "ATSH7E0",
            "221154",
            "engine oil temperature",
            "C",
            "thermal",
            STAGE_LOW_RISK,
            "low",
            RETRY_CANDIDATE_MS,
            STATUS_CONFIRMED,
            "Volt community PID sheet",
            "Confirmed on the real car.",
        )
        add(
            profiles,
            "transmission.temp.221940",
            "transmission",
            HS_CAN,
            "ATSH7E2",
            "221940",
            "transmission temperature",
            "C",
            "diagnostic_only",
            STAGE_LOW_RISK,
            "low",
            RETRY_REJECTED_MS,
            STATUS_CANDIDATE,
            "ScanGauge and OBDLink GM trans temp references",
            "Real car returned NO DATA.",
        )
        add(
            profiles,
            "transmission.temp.22194001",
            "transmission",
            HS_CAN,
            "ATSH7E2",
            "22194001",
            "transmission temperature",
            "C",
            "diagnostic_only",
            STAGE_LOW_RISK,
            "low",
            RETRY_REJECTED_MS,
            STATUS_CANDIDATE,
            "OBDLink GM trans temp selector variant",
            "Real car returned NO DATA.",
        )
        add(
            profiles,
            "ambient.outside_temp.0146",
            "ambient",
            HS_CAN,
            BROADCAST,
            "0146",
            "outside air temperature",
            "C",
            "slow",
            STAGE_LOW_RISK,
            "low",
            RETRY_CANDIDATE_MS,
            STATUS_CANDIDATE,
            "SAE J1979 PID 46",
            "Standard optional PID; promote only after this car returns 41 46.",
        )
        add(
            profiles,
            "engine.intake_air_temp.010f",
            "engine",
            HS_CAN,
            BROADCAST,
            "010F",
            "intake air temperature",
            "C",
            "slow",
            STAGE_LOW_RISK,
            "low",
            RETRY_CANDIDATE_MS,
            STATUS_CANDIDATE,
            "SAE J1979 PID 0F",
            "Useful sanity signal for air/thermal context when supported.",
        )
        add(
            profiles,
            "engine.absolute_load.0143",
            "engine",
            HS_CAN,
            BROADCAST,
            "0143",
            "absolute engine load",
            "%",
            "warm",
            STAGE_LOW_RISK,
            "low",
            RETRY_CANDIDATE_MS,
            STATUS_CANDIDATE,
            "SAE J1979 PID 43",
            "Closest safe standard proxy while true torque remains enhanced/candidate.",
        )
        add(
            profiles,
            "ev.distance.remaining.224373",
            "ev",
            HS_CAN,
            "ATSH7E4",
            "224373",
            "EV range / charging mode candidate",
            "",
            "diagnostic_only",
            STAGE_EXPERIMENTAL,
            "medium",
            RETRY_REJECTED_MS,
            STATUS_CANDIDATE,
            "Prior VoltTracker field scan",
            "Treat carefully: prior evidence showed charging mode, not charger HV output power.",
        )
        add(
            profiles,
            "thermal.battery_coolant_pump_rpm",
            "thermal",
            HS_CAN,
            "ATSH7E4",
            "22435A",
            "battery coolant pump RPM candidate",
            "rpm",
            "diagnostic_only",
            STAGE_EXPERIMENTAL,
            "medium",
            RETRY_REJECTED_MS,
            STATUS_CANDIDATE,
            "Volt community enhanced candidate",
            "Do not poll live until a positive 62 frame and decode are verified.",
        )
        add(
            profiles,
            "thermal.battery_coolant_valve_state",
            "thermal",
            HS_CAN,
            "ATSH7E4",
            "22435B",
            "battery coolant valve state candidate",
            "",
            "diagnostic_only",
            STAGE_EXPERIMENTAL,
            "medium",
            RETRY_REJECTED_MS,
            STATUS_CANDIDATE,
            "Volt community enhanced candidate",
            "Good value if found; keep as candidate-only until decoded.",
        )
        add(
            profiles,
            "battery.temperature.22434f",
            "battery",
            HS_CAN,
            "ATSH7E4",
            "22434F",
            "HV battery temperature",
            "C",
            "thermal",
            STAGE_LOW_RISK,
            "low",
            RETRY_CANDIDATE_MS,
            STATUS_CONFIRMED,
            "Prior VoltTracker field scan",
            "Confirmed on the real car and polled on the thermal lane.",
        )
        addSensorExpansionProfiles(profiles)
        addTpms(profiles, "22248E", "front-left")
        addTpms(profiles, "22248F", "front-right")
        addTpms(profiles, "222490", "rear-right")
        addTpms(profiles, "222491", "rear-left")
        addTpms(profiles, "22C901", "grouped pressures")
        addTpms(profiles, "22C902", "grouped temperatures")
        addTpmsReceiver(profiles, "224051", "slot 1")
        addTpmsReceiver(profiles, "224052", "slot 2")
        addTpmsReceiver(profiles, "224053", "slot 3")
        addTpmsReceiver(profiles, "224054", "slot 4")
        ALL = Collections.unmodifiableList(profiles)
    }

    @JvmStatic
    fun find(
        header: String?,
        command: String?,
    ): EnhancedPidProfile? {
        val cleanHeader = clean(header)
        val cleanCommand = clean(command)
        for (profile in ALL) {
            if (profile.command == cleanCommand && profile.header == cleanHeader) {
                return profile
            }
        }
        for (profile in ALL) {
            if (profile.command == cleanCommand && profile.header.isEmpty()) {
                return profile
            }
        }
        return null
    }

    @JvmStatic
    fun forCategory(category: String?): List<EnhancedPidProfile> {
        val cleanCategory = category?.trim()?.lowercase(Locale.US) ?: ""
        val matches = ArrayList<EnhancedPidProfile>()
        for (profile in ALL) {
            if (profile.category == cleanCategory) {
                matches.add(profile)
            }
        }
        return Collections.unmodifiableList(matches)
    }

    @JvmStatic
    fun passiveProfiles(): List<EnhancedPidProfile> {
        val matches = ArrayList<EnhancedPidProfile>()
        for (profile in ALL) {
            if (profile.pollLane == "passive") {
                matches.add(profile)
            }
        }
        return Collections.unmodifiableList(matches)
    }

    @JvmStatic
    fun forStage(stage: String?): List<EnhancedPidProfile> {
        val normalized = normalizeStage(stage)
        val matches = ArrayList<EnhancedPidProfile>()
        for (profile in ALL) {
            if (profile.scanStage == normalized) {
                matches.add(profile)
            }
        }
        return Collections.unmodifiableList(matches)
    }

    @JvmStatic
    fun normalizeStage(stage: String?): String {
        val cleanStage = stage?.trim()?.lowercase(Locale.US) ?: ""
        if (cleanStage == STAGE_PASSIVE ||
            cleanStage == STAGE_LOW_RISK ||
            cleanStage == STAGE_TIRES ||
            cleanStage == STAGE_EXPERIMENTAL
        ) {
            return cleanStage
        }
        return STAGE_TIRES
    }

    @JvmStatic
    fun catalogJson(): JSONArray {
        val items = JSONArray()
        for (profile in ALL) {
            val item = JSONObject()
            try {
                item.put("key", profile.key)
                item.put("category", profile.category)
                item.put("network", profile.network)
                item.put("protocol", profile.protocol)
                item.put("header", profile.header)
                item.put("command", profile.command)
                item.put("pid", profile.pid)
                item.put("name", profile.name)
                item.put("unit", profile.unit)
                item.put("pollLane", profile.pollLane)
                item.put("scanStage", profile.scanStage)
                item.put("risk", profile.risk)
                item.put("retryAfterMs", profile.retryAfterMs)
                item.put("validationStatus", profile.validationStatus)
                item.put("source", profile.source)
                item.put("notes", profile.notes)
                items.put(item)
            } catch (ignored: JSONException) {
                // Static catalog strings are safe.
            }
        }
        return items
    }

    @JvmStatic
    fun isPositiveResponse(
        command: String?,
        rawResponse: String?,
    ): Boolean {
        val cleanCommand = clean(command)
        val raw = clean(rawResponse)
        if (cleanCommand.isEmpty() || raw.isEmpty()) {
            return false
        }
        if (raw.contains("NO DATA") ||
            raw.contains("CAN ERROR") ||
            raw.contains("UNABLE TO CONNECT") ||
            raw.contains("STOPPED") ||
            raw.contains("ERROR") ||
            raw.contains("7F")
        ) {
            return false
        }
        val compact = raw.replace(" ", "")
        if (cleanCommand.startsWith("01") && cleanCommand.length >= 4) {
            return compact.contains("41" + cleanCommand.substring(2, 4))
        }
        if (cleanCommand.startsWith("22") && cleanCommand.length >= 6) {
            val pid = cleanCommand.substring(2, minOf(cleanCommand.length, 6))
            return compact.contains("62$pid")
        }
        if (cleanCommand.startsWith("09") && cleanCommand.length >= 4) {
            return compact.contains("49" + cleanCommand.substring(2, 4))
        }
        return false
    }

    private fun addSensorExpansionProfiles(profiles: MutableList<EnhancedPidProfile>) {
        add(
            profiles,
            "battery.capacity_ah.2241a3",
            "battery",
            HS_CAN,
            "ATSH7E4",
            "2241A3",
            "HV battery capacity",
            "Ah",
            "once_per_drive",
            STAGE_EXPERIMENTAL,
            "low",
            RETRY_CANDIDATE_MS,
            STATUS_CONFIRMED,
            "OVMS vehicle_voltampera.cpp; gm-volt thread 333039; Bolt list",
            "Confirmed returning data on a 2018 Volt; live-polled rarely to feed capacityAh/sohPct.",
        )
        addCandidate(
            profiles,
            "battery.capacity_ah_fallback.2245f9",
            "battery",
            "ATSH7E4",
            "2245F9",
            "HV battery capacity fallback",
            "Ah",
            "Bolt 2019+ list; iternio bolt19.json",
            "Fallback capacity DID from newer Bolt profiles. Keep diagnostic-only until this Volt answers.",
        )
        addCandidate(
            profiles,
            "battery.cell_min_voltage.224329",
            "battery",
            "ATSH7E4",
            "224329",
            "minimum cell voltage",
            "V",
            "Bolt BECM list",
            "Pack-health candidate; promote only after sane 3.0-4.2 V positive frames.",
        )
        addCandidate(
            profiles,
            "battery.cell_min_number.22432a",
            "battery",
            "ATSH7E4",
            "22432A",
            "minimum cell number",
            "",
            "Bolt BECM list",
            "Expected 1-96. Used with min/max voltage to derive cell imbalance.",
        )
        addCandidate(
            profiles,
            "battery.cell_max_voltage.22432b",
            "battery",
            "ATSH7E4",
            "22432B",
            "maximum cell voltage",
            "V",
            "Bolt BECM list",
            "Pack-health candidate; promote only after sane 3.0-4.2 V positive frames.",
        )
        addCandidate(
            profiles,
            "battery.cell_max_number.22432c",
            "battery",
            "ATSH7E4",
            "22432C",
            "maximum cell number",
            "",
            "Bolt BECM list",
            "Expected 1-96. Used with min/max voltage to derive cell imbalance.",
        )
        addCandidate(
            profiles,
            "battery.soc_variation.22435f",
            "battery",
            "ATSH7E4",
            "22435F",
            "SOC variation",
            "%",
            "Bolt BECM list",
            "Balance proxy candidate. Derive cellDeltaMv separately when min/max cell voltage works.",
        )
        addCandidate(
            profiles,
            "battery.pack_resistance.2240e9",
            "battery",
            "ATSH7E4",
            "2240E9",
            "pack resistance",
            "ohm",
            "Bolt BECM list",
            "Diagnostic-only resistance candidate; validate units on-car before dashboard use.",
        )
        addCandidate(
            profiles,
            "battery.pack_min_voltage.22433b",
            "battery",
            "ATSH7E4",
            "22433B",
            "minimum pack voltage",
            "V",
            "Bolt BECM list",
            "Pack-health context candidate.",
        )
        addCandidate(
            profiles,
            "battery.pack_max_voltage.22433c",
            "battery",
            "ATSH7E4",
            "22433C",
            "maximum pack voltage",
            "V",
            "Bolt BECM list",
            "Pack-health context candidate.",
        )
        addCandidate(
            profiles,
            "battery.max_temp.224349",
            "battery",
            "ATSH7E4",
            "224349",
            "HV battery max temperature",
            "C",
            "Bolt BECM list",
            "Thermal-spread candidate.",
        )
        addCandidate(
            profiles,
            "battery.min_temp.22434a",
            "battery",
            "ATSH7E4",
            "22434A",
            "HV battery min temperature",
            "C",
            "Bolt BECM list",
            "Thermal-spread candidate.",
        )
        addCandidate(
            profiles,
            "battery.max_temp_module.22434b",
            "battery",
            "ATSH7E4",
            "22434B",
            "HV battery max-temp module",
            "",
            "Bolt BECM list",
            "Pairs with max pack temperature.",
        )
        addCandidate(
            profiles,
            "battery.min_temp_module.22434c",
            "battery",
            "ATSH7E4",
            "22434C",
            "HV battery min-temp module",
            "",
            "Bolt BECM list",
            "Pairs with min pack temperature.",
        )
        addCandidate(
            profiles,
            "thermal.power_electronics_coolant_temp.221c43",
            "thermal",
            "ATSH7E4",
            "221C43",
            "power electronics coolant loop temperature",
            "C",
            "Volt sheet; OVMS; Bolt list",
            "High-confidence read-only thermal probe.",
        )
        addCandidate(
            profiles,
            "thermal.battery_coolant_temp.2241a4",
            "thermal",
            "ATSH7E4",
            "2241A4",
            "battery coolant temperature",
            "C",
            "Bolt BECM list",
            "Cheap thermal candidate.",
        )
        addCandidate(
            profiles,
            "battery.min_soc_limit.22433f",
            "battery",
            "ATSH7E4",
            "22433F",
            "minimum SOC limit",
            "%",
            "Bolt BECM list",
            "Possible Hold/Mountain proxy. Watch while toggling drive modes before promotion.",
        )
        addCandidate(
            profiles,
            "battery.hd_pack_soc.2243af",
            "battery",
            "ATSH7E4",
            "2243AF",
            "HD raw SOC",
            "%",
            "Prior VoltTracker field scan",
            "Already positive on this car; decoder kept here so detail probe metadata is explicit.",
        )
        addCandidate(
            profiles,
            "hvac.ac_compressor_commanded_power.2241b1",
            "hvac",
            "ATSH7E4",
            "2241B1",
            "AC compressor commanded power",
            "W",
            "Bolt list",
            "Conflicts with Gen 1 41Bx mapping. Probe with HVAC on/off before trusting interpretation.",
        )
        addCandidate(
            profiles,
            "hvac.cabin_heater_commanded_power.2241b3",
            "hvac",
            "ATSH7E4",
            "2241B3",
            "cabin heater commanded power",
            "W",
            "Bolt list",
            "Conflicts with Gen 1 41Bx mapping. Probe with HVAC on/off before trusting interpretation.",
        )
        addCandidate(
            profiles,
            "hvac.battery_heater_commanded_power.2241b5",
            "hvac",
            "ATSH7E4",
            "2241B5",
            "battery heater commanded power",
            "W",
            "Bolt and Volt community lists",
            "Diagnostic-only companion to existing measured battery heater power 2241B6.",
        )
        addCandidate(
            profiles,
            "hvac.compressor_speed.2282b5",
            "hvac",
            "ATSH7E4",
            "2282B5",
            "AC compressor speed",
            "rpm",
            "Bolt list",
            "Alternate HVAC validation lead.",
        )
        addCandidate(
            profiles,
            "hvac.compressor_power.2282b7",
            "hvac",
            "ATSH7E4",
            "2282B7",
            "AC compressor power",
            "W",
            "Bolt list",
            "Alternate HVAC validation lead.",
        )
        addCandidate(
            profiles,
            "aux.apm_output_power.2241b0",
            "aux",
            "ATSH7E4",
            "2241B0",
            "APM output power",
            "W",
            "Bolt list",
            "12V system proxy; no published actual 12V battery current PID.",
        )
        addCandidate(
            profiles,
            "aux.apm_output_current.22437e",
            "aux",
            "ATSH7E4",
            "22437E",
            "APM output current",
            "A",
            "Gen 1 Volt sheet",
            "Low-medium confidence 12V system proxy.",
        )
        addCandidate(
            profiles,
            "battery.hv_isolation_kohm.2243a6",
            "battery",
            "ATSH7E4",
            "2243A6",
            "HV isolation resistance",
            "kOhm",
            "Bolt list",
            "Safety-related candidate tied to P1AF0 diagnostics.",
        )
        addCandidate(
            profiles,
            "battery.hv_isolation_ohm.2241ec",
            "battery",
            "ATSH7E4",
            "2241EC",
            "HV isolation resistance",
            "ohm",
            "Bolt list",
            "Lower-confidence alternate isolation-resistance PID.",
        )
        addCandidate(
            profiles,
            "aux.ignition_voltage.221141",
            "aux",
            "ATSH7E1",
            "221141",
            "ignition / 12V voltage",
            "V",
            "Bolt list",
            "Real 12V system voltage candidate, separate from adapter ATRV.",
        )
        addCandidate(
            profiles,
            "thermal.inverter_temp_1.221c26",
            "thermal",
            "ATSH7E1",
            "221C26",
            "inverter temperature 1",
            "C",
            "Bolt list",
            "Power-electronics thermal candidate.",
        )
        addCandidate(
            profiles,
            "thermal.inverter_temp_2.221c28",
            "thermal",
            "ATSH7E1",
            "221C28",
            "inverter temperature 2",
            "C",
            "Bolt list",
            "Power-electronics thermal candidate.",
        )
        addCandidate(
            profiles,
            "thermal.inverter_temp_3.221c2a",
            "thermal",
            "ATSH7E1",
            "221C2A",
            "inverter temperature 3",
            "C",
            "Bolt list",
            "Power-electronics thermal candidate.",
        )
        addCandidate(
            profiles,
            "thermal.motor_temp.2228cb",
            "thermal",
            "ATSH7E1",
            "2228CB",
            "motor temperature",
            "C",
            "Bolt list",
            "Single-motor label from Bolt; validate Volt MGA/MGB meaning before promotion.",
        )
        addCandidate(
            profiles,
            "brake.torque_demand.22242c",
            "brake",
            "ATSH7E1",
            "22242C",
            "brake torque demand",
            "Nm",
            "Gen 1 Volt sheet; Bolt list",
            "Scale conflict (/4 vs /2). Decoder uses Gen 1 /4 until real-car scale is validated.",
        )
        addCandidate(
            profiles,
            "brake.regen_active.2224b0",
            "brake",
            "ATSH7E1",
            "2224B0",
            "regen braking active",
            "",
            "Bolt list",
            "Boolean candidate; true regen/friction split is not published.",
        )
        addCandidate(
            profiles,
            "aux.14v_setpoint.221c47",
            "aux",
            "ATSH7E0",
            "221C47",
            "14V setpoint voltage",
            "V",
            "Bolt list",
            "12V charging setpoint candidate.",
        )
        addCandidate(
            profiles,
            "fuel.evap_vapor_pressure.0132",
            "fuel",
            BROADCAST,
            "0132",
            "EVAP vapor pressure",
            "Pa",
            "SAE J1979 PID 32",
            "Standard sealed-tank proxy; worth one read during scan only.",
        )
        addCandidate(
            profiles,
            "brake.pedal_position_1.224501",
            "brake",
            "ATSH7E6",
            "224501",
            "brake pedal position 1",
            "mm",
            "Bolt list",
            "New 7E6 brake-module candidate.",
        )
        addCandidate(
            profiles,
            "brake.pedal_position_2.224502",
            "brake",
            "ATSH7E6",
            "224502",
            "brake pedal position 2",
            "mm",
            "Bolt list",
            "New 7E6 brake-module candidate.",
        )
        addCellBecmProfiles(profiles)
    }

    private fun addCellBecmProfiles(profiles: MutableList<EnhancedPidProfile>) {
        for ((command, section) in listOf(
            "2240D7" to 1,
            "2240D9" to 2,
            "2240DB" to 3,
            "2240DD" to 4,
            "2240DF" to 5,
            "2240E1" to 6,
        )) {
            addCandidate(
                profiles,
                "battery.section_temp_$section.${command.lowercase(Locale.US)}",
                "battery",
                "ATSH7E7",
                command,
                "battery section $section temperature",
                "C",
                "OVMS Gen 1 code; Bolt list",
                "New 7E7 BECM cell-interface candidate.",
            )
        }
        for ((command, name) in listOf(
            "224181" to "cell voltage layout probe 1",
            "224182" to "cell voltage layout probe 2",
            "22419F" to "cell voltage layout probe 31",
            "2241E0" to "cell voltage Bolt-layout probe 96",
            "224200" to "cell voltage Gen1-layout probe 32",
            "224201" to "cell voltage Gen1-layout probe 33",
            "224240" to "cell voltage Gen1-layout probe 96",
            "22C218" to "average cell voltage",
            "2240D4" to "HD pack current",
        )) {
            addCandidate(
                profiles,
                "battery.cell_probe.${command.lowercase(Locale.US)}",
                "battery",
                "ATSH7E7",
                command,
                name,
                if (command == "2240D4") "A" else "V",
                "OVMS Gen 1 code; Bolt BECM list",
                "Probe a small layout sample first; full 96-cell snapshot should be a dedicated pass.",
            )
        }
    }

    private fun addCandidate(
        profiles: MutableList<EnhancedPidProfile>,
        key: String,
        category: String,
        header: String,
        command: String,
        name: String,
        unit: String,
        source: String,
        notes: String,
    ) {
        add(
            profiles,
            key,
            category,
            HS_CAN,
            header,
            command,
            name,
            unit,
            "diagnostic_only",
            STAGE_EXPERIMENTAL,
            "low",
            RETRY_REJECTED_MS,
            STATUS_CANDIDATE,
            source,
            notes,
        )
    }

    private fun addTpms(
        profiles: MutableList<EnhancedPidProfile>,
        command: String,
        wheel: String,
    ) {
        add(
            profiles,
            "tpms.7e0." + command.lowercase(Locale.US),
            "tpms",
            HS_CAN,
            "ATSH7E0",
            command,
            "candidate tire pressure $wheel",
            "kPa",
            "diagnostic_only",
            STAGE_TIRES,
            "medium",
            RETRY_REJECTED_MS,
            STATUS_REJECTED,
            "community TPMS candidate",
            "June 2026 real-car scan returned a negative response; do not re-probe without a new header/source.",
        )
    }

    private fun addTpmsReceiver(
        profiles: MutableList<EnhancedPidProfile>,
        command: String,
        slot: String,
    ) {
        add(
            profiles,
            "tpms.760." + command.lowercase(Locale.US),
            "tpms",
            GMLAN,
            "ATSH760",
            command,
            "candidate tire receiver $slot",
            "",
            "diagnostic_only",
            STAGE_TIRES,
            "medium",
            RETRY_REJECTED_MS,
            STATUS_REJECTED,
            "GM TPMS receiver candidate",
            "June 2026 real-car scan returned NO DATA; do not re-probe without a new header/source.",
        )
    }

    private fun add(
        profiles: MutableList<EnhancedPidProfile>,
        key: String,
        category: String,
        network: String,
        header: String,
        command: String,
        name: String,
        unit: String,
        pollLane: String,
        scanStage: String,
        risk: String,
        retryAfterMs: Long,
        validationStatus: String,
        source: String,
        notes: String,
    ) {
        profiles.add(
            EnhancedPidProfile(
                key,
                category,
                network,
                "elm327",
                header,
                command,
                ObdElmDecode.pidForCommand(command),
                name,
                unit,
                pollLane,
                scanStage,
                risk,
                retryAfterMs,
                validationStatus,
                source,
                notes,
            ),
        )
    }

    private fun clean(value: String?): String = value?.trim()?.uppercase(Locale.US) ?: ""
}
