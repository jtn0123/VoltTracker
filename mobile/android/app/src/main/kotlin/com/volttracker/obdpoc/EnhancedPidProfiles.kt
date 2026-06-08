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
            "battery.cell_group_voltage.22434f",
            "battery",
            HS_CAN,
            "ATSH7E4",
            "22434F",
            "cell group voltage candidate",
            "V",
            "diagnostic_only",
            STAGE_EXPERIMENTAL,
            "medium",
            RETRY_REJECTED_MS,
            STATUS_CANDIDATE,
            "Volt community enhanced candidate",
            "High value for pack health, but only safe as a narrow candidate probe.",
        )
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
            STATUS_DIAGNOSTIC_ONLY,
            "community TPMS candidate",
            "Do not promote until a real car returns a positive 62 frame.",
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
            STATUS_DIAGNOSTIC_ONLY,
            "GM TPMS receiver candidate",
            "Do not promote until a real car returns a positive 62 frame.",
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
