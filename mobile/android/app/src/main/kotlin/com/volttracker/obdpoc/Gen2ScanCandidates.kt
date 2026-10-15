package com.volttracker.obdpoc

import java.util.Locale

/**
 * Gen 2 reads an established Volt app makes that are not polled live: the trip counters (where
 * they reset is unknown), and the BCM 12 V and charge pilot reads, which a 2017 may not answer.
 * [EnhancedPidCatalog] lists them so a Detailed signals scan shows whether this car does;
 * [ObdVoltMode22ParserRegistry] decodes them.
 */
internal object Gen2ScanCandidates {
    private class Candidate(
        val command: String,
        val category: String,
        val name: String,
        val unit: String,
    )

    private val CANDIDATES =
        listOf(
            Candidate("224470", "ev", "energy used", "kWh"),
            Candidate("2241C5", "ev", "EV distance counter", "km"),
            Candidate("2241C6", "fuel", "gas distance counter", "km"),
            Candidate("2241AD", "fuel", "fuel used", "L"),
            Candidate("224533", "ev", "charger current command", "A"),
            Candidate("224545", "ev", "charge pilot voltage", "V"),
            Candidate("224546", "ev", "charge pilot duty", "%"),
            Candidate("22451E", "hvac", "blower speed", "%"),
            Candidate("220042", "aux", "module supply voltage", "V"),
            Candidate("2290FB", "aux", "12V battery current", "A"),
            Candidate("2290B1", "aux", "12V battery voltage", "V"),
            Candidate("2290F8", "aux", "12V battery charge", "%"),
        )

    /** The scan-only commands, in catalog order. */
    val COMMANDS: List<String> = CANDIDATES.map { it.command }

    internal fun catalogProfiles(): List<EnhancedPidProfile> =
        CANDIDATES.map { candidate ->
            EnhancedPidProfile(
                "gen2." + candidate.command.lowercase(Locale.US),
                candidate.category,
                "hs-can",
                "elm327",
                // The 2290xx reads live on the body control module; the rest on the hybrid pack's 7E4.
                if (candidate.command.startsWith("2290")) "ATSH241" else "ATSH7E4",
                candidate.command,
                ObdElmDecode.pidForCommand(candidate.command),
                candidate.name,
                candidate.unit,
                "diagnostic_only",
                EnhancedPidProfiles.STAGE_EXPERIMENTAL,
                "low",
                RETRY_MS,
                EnhancedPidProfiles.STATUS_CANDIDATE,
                "Established Volt app",
                "Gen 2 read, unproven on a 2017; promote to live polling once a scan shows sane values.",
            )
        }

    private const val RETRY_MS = 7L * 24L * 60L * 60L * 1000L
}
