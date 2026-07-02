package com.volttracker.obdpoc

/**
 * Depth selector for [DiagnosticScanRunner].
 *
 * [FULL] runs the complete sweep (protocol/VIN, DTCs, freeze frames, live data, and every Volt HV /
 * charger / transmission / brake / TPMS discovery header). [QUICK] is a fast trouble-code check:
 * protocol detection, VIN, and the generic Mode 03/07/0A DTC reads only — it skips the slow
 * manufacturer-specific module discovery so a "what codes are stored?" answer comes back in seconds.
 *
 * The wire name crosses the JS↔native bridge (carried on the scan intent) and is parsed leniently:
 * anything unrecognized falls back to [FULL] so an older/garbled value can never silently downgrade
 * a scan the user asked to be thorough.
 */
enum class DiagnosticScanProfile(
    val wireName: String,
) {
    FULL("full"),
    QUICK("quick"),
    ;

    companion object {
        fun fromWire(value: String?): DiagnosticScanProfile {
            val trimmed = value?.trim()
            return entries.firstOrNull { it.wireName.equals(trimmed, ignoreCase = true) } ?: FULL
        }
    }
}
