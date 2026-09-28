package com.volttracker.obdpoc.ui.diag

import java.util.Locale

/**
 * Plain-language names and severities for trouble codes: the classic dashboard's DTC tables
 * (`dtc-lookup.ts` + `dtc-causes.ts`), extracted at build time into `assets/dtc/dtc-table.tsv`
 * (one `code  description  severity  category` line per code). A code the table doesn't list
 * still gets its family's name and severity, like the classic dashboard.
 */
class DtcCatalog private constructor(
    private val entries: Map<String, Entry>,
) {
    private class Entry(
        val description: String?,
        val severity: DtcSeverity?,
        val category: String?,
    )

    /** How many codes the table names. */
    val size: Int get() = entries.size

    /** [code] (any case) named and graded, with the status and history the store reported. */
    fun describe(
        code: String,
        status: String = DtcCode.STATUS_STORED,
        firstSeenMs: Long = 0L,
        lastSeenMs: Long = 0L,
        seenCount: Int = 1,
    ): DtcCode {
        val key = normalize(code)
        val entry = entries[key]
        return DtcCode(
            code = key,
            status = status,
            description = entry?.description,
            category = entry?.category,
            severity = entry?.severity ?: familySeverity(key),
            firstSeenMs = firstSeenMs,
            lastSeenMs = lastSeenMs,
            seenCount = seenCount,
        )
    }

    companion object {
        /** Where the build puts the table in the APK's assets. */
        const val ASSET_PATH = "dtc/dtc-table.tsv"

        /** No table (it failed to load): every code falls back to its family. */
        val EMPTY = DtcCatalog(emptyMap())

        fun parse(tsv: String): DtcCatalog =
            DtcCatalog(
                tsv
                    .lineSequence()
                    .mapNotNull { line ->
                        val fields = line.split('\t')
                        val code = fields.getOrNull(0)?.let(::normalize)
                        if (code.isNullOrEmpty()) {
                            null
                        } else {
                            code to
                                Entry(
                                    description = fields.getOrNull(1)?.takeIf { it.isNotBlank() },
                                    severity = severityOf(fields.getOrNull(2)),
                                    category = fields.getOrNull(FIELD_CATEGORY)?.takeIf { it.isNotBlank() },
                                )
                        }
                    }.toMap(),
            )

        private const val FIELD_CATEGORY = 3

        private fun normalize(code: String): String = code.trim().uppercase(Locale.US)

        private fun severityOf(text: String?): DtcSeverity? =
            when (text?.trim()) {
                "critical" -> DtcSeverity.ALERT
                "warning" -> DtcSeverity.WARNING
                "info" -> DtcSeverity.INFO
                else -> null
            }

        /**
         * The classic dashboard's fallback for a code the table doesn't grade: chassis codes (ABS,
         * brakes, steering) can affect control, so ALERT; powertrain, body and network codes WARNING.
         */
        fun familySeverity(code: String): DtcSeverity =
            when (normalize(code).firstOrNull()) {
                'C' -> DtcSeverity.ALERT
                'P', 'B', 'U' -> DtcSeverity.WARNING
                else -> DtcSeverity.INFO
            }

        /** The system a code's SAE prefix points at, e.g. P03xx → "Ignition system or misfire". */
        fun familyName(code: String): String? {
            val key = normalize(code)
            if (key.length < MIN_CODE_LENGTH) return null
            val hundreds = key.substring(1, 3)
            return when (key[0]) {
                'P' ->
                    POWERTRAIN_FAMILIES[hundreds] ?: when (key[1]) {
                        '1' -> "Manufacturer-specific powertrain (GM / Volt)"
                        '2' -> "Generic powertrain (extended SAE)"
                        '3' -> "Manufacturer-specific powertrain (extended)"
                        else -> null
                    }
                'B' -> "Body / restraints code"
                'C' -> "Chassis / ABS / brakes code"
                'U' -> "Network / communication bus code"
                else -> null
            }
        }

        private const val MIN_CODE_LENGTH = 4

        private val POWERTRAIN_FAMILIES =
            mapOf(
                "00" to "Fuel/air metering - auxiliary emission controls",
                "01" to "Fuel/air metering",
                "02" to "Fuel/air metering - injector circuit",
                "03" to "Ignition system or misfire",
                "04" to "Auxiliary emissions (catalyst, EVAP, EGR)",
                "05" to "Vehicle speed / idle / auxiliary inputs",
                "06" to "Computer / control-module output",
                "07" to "Transmission",
                "08" to "Transmission",
                "09" to "Transmission",
                "0A" to "Hybrid propulsion (drive motors / inverter)",
                "0B" to "Hybrid battery / cell",
                "0C" to "Hybrid charging / extension",
                "0D" to "Hybrid auxiliary (heaters / A/C / charge ports)",
                "34" to "Generic powertrain (cylinder deactivation / SAE)",
            )
    }
}
