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

    @JvmField
    val ALL: List<EnhancedPidProfile> = EnhancedPidCatalog.build()

    @JvmStatic
    fun find(
        header: String?,
        command: String?,
    ): EnhancedPidProfile? {
        val cleanHeader = normalizeHeader(header)
        val cleanCommand = clean(command)
        var broadcastFallback: EnhancedPidProfile? = null
        for (profile in ALL) {
            if (profile.command != cleanCommand) {
                continue
            }
            if (normalizeHeader(profile.header) == cleanHeader) {
                return profile
            }
            if (broadcastFallback == null && profile.header.isEmpty()) {
                broadcastFallback = profile
            }
        }
        return broadcastFallback
    }

    /**
     * Headers arrive in two spellings: the catalog stores the full ELM command ("ATSH7E4")
     * while SessionRecorder tracks the bare CAN id it sets ("7E4"). Strip a leading "ATSH"
     * from both sides so a lookup with either spelling matches the same profile.
     */
    private fun normalizeHeader(header: String?): String {
        val clean = clean(header)
        return if (clean.startsWith("ATSH")) clean.substring(4).trim() else clean
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
            raw.contains("ERROR")
        ) {
            return false
        }
        // A UDS negative response is a frame that STARTS with 7F followed by the echoed
        // service id (e.g. "7F 22 31"). A bare contains("7F") check would misclassify
        // positive frames whose payload merely contains 0x7F — with ATS0, "410D7F" is a
        // legitimate 127 km/h reading, and a 0x7F digram can even straddle two bytes.
        // "7F xx 78" (requestCorrectlyReceived-ResponsePending) is not a final rejection:
        // the real positive frame may follow on a later line, so pending lines are skipped
        // and the verdict falls to whether a positive marker is present at all.
        if (cleanCommand.length >= 2) {
            val negativePrefix = "7F" + cleanCommand.substring(0, 2)
            for (line in raw.split(Regex("[\\r\\n]+"))) {
                val compactLine = line.trim().replace(" ", "")
                if (compactLine.startsWith(negativePrefix) &&
                    !compactLine.startsWith(negativePrefix + "78")
                ) {
                    return false
                }
            }
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

    private fun clean(value: String?): String = value?.trim()?.uppercase(Locale.US) ?: ""
}
