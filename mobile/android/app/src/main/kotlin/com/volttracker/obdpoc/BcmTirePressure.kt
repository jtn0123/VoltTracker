package com.volttracker.obdpoc

import java.util.Locale

/**
 * Tire pressures from the body control module (request 0x241, reply 0x641). A gen 2 BCM keeps
 * one mode-22 DID per wheel, each a single-frame reply `62 50 0n HH LL`: an unsigned word in
 * 1/16 kPa (1/1600 bar), 0xFFFF for "no reading". That is how an established Volt app reads them
 * on a 2017; this car refuses the grouped `22C901` request community sheets list. The decode
 * itself lives in [ObdVoltMode22ParserRegistry]; this object names the wheels.
 */
object BcmTirePressure {
    /** Each wheel's DID, the live-sample key it fills (shared with the SW-CAN tire frame), and its label. */
    enum class Wheel(
        val command: String,
        val sampleKey: String,
        val label: String,
    ) {
        FRONT_LEFT("225008", "tirePressureFlKpa", "tire pressure front left"),
        REAR_LEFT("225007", "tirePressureRlKpa", "tire pressure rear left"),
        FRONT_RIGHT("225006", "tirePressureFrKpa", "tire pressure front right"),
        REAR_RIGHT("225005", "tirePressureRrKpa", "tire pressure rear right"),
    }

    /** Tire-probe catalog entries, one per wheel DID at the BCM's physical address. */
    internal fun catalogProfiles(): List<EnhancedPidProfile> =
        Wheel.entries.map { wheel ->
            EnhancedPidProfile(
                "tpms.241." + wheel.command.lowercase(Locale.US),
                "tpms",
                "hs-can",
                "elm327",
                "ATSH241",
                wheel.command,
                ObdElmDecode.pidForCommand(wheel.command),
                "body module ${wheel.label}",
                "kPa",
                "slow",
                EnhancedPidProfiles.STAGE_TIRES,
                "low",
                CANDIDATE_RETRY_MS,
                EnhancedPidProfiles.STATUS_CANDIDATE,
                "Gen 2 Volt BCM per-wheel tire DID",
                "Read-only Mode 22, polled live; confirm against the SW-CAN tire frame on the car.",
            )
        }

    private const val CANDIDATE_RETRY_MS = 2L * 60L * 60L * 1000L
}
