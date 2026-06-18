package com.volttracker.obdpoc

/** Scope selector for one-shot diagnostic scans. */
enum class DiagnosticScanProfile(
    val wireName: String,
) {
    /** Current behavior: generic codes, standard live probes, Volt modules, and optional discovery. */
    FULL("full"),

    /** Fast path for "are there codes?" without the expensive multi-header discovery sweep. */
    QUICK_DTC("quick_dtc"),

    /** Middle path for battery/charger evidence without powertrain/brake/TPMS discovery. */
    BATTERY("battery"),
    ;

    companion object {
        fun fromWireName(value: String?): DiagnosticScanProfile {
            val normalized = value?.trim()?.lowercase()?.replace('-', '_') ?: ""
            return entries.firstOrNull { it.wireName == normalized } ?: FULL
        }
    }
}
