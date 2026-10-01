package com.volttracker.obdpoc

import java.util.Locale
import kotlin.math.abs

/**
 * Tire pressures from the body control module (request 0x241, reply 0x641): `22C901` answers
 * `62 C9 01` then one byte per tire. Community sources (Chevy SS / Torque Pro) scale it at
 * 1.373 kPa per count; the Volt's own SW-CAN tire frame uses 4 kPa per count. Which one this car
 * uses is unconfirmed, so [parse] keeps whichever scale puts the four tires nearest a normal
 * pressure — the two never overlap there (1.373 needs counts of ~100+, 4 needs ~25-90).
 * Wheel order follows the SW-CAN tire frame (FL, RL, FR, RR) until the car proves otherwise.
 */
object BcmTirePressure {
    const val COMMAND = "22C901"

    /** Four tire pressures in kPa; a wheel the car reports as invalid is null. */
    data class Tires(
        val flKpa: Double?,
        val frKpa: Double?,
        val rlKpa: Double?,
        val rrKpa: Double?,
        /** kPa per count that was chosen; logged so the field test can confirm it. */
        val kpaPerCount: Double,
    )

    fun parse(response: String?): Tires? {
        val hex = response?.uppercase(Locale.US)?.filter { it.isLetterOrDigit() } ?: return null
        val start = hex.indexOf(POSITIVE_REPLY)
        if (start < 0) return null
        val bytes =
            hex
                .substring(start + POSITIVE_REPLY.length)
                .chunked(2)
                .take(WHEELS)
                .map { it.toIntOrNull(HEX) ?: return null }
        if (bytes.size < WHEELS) return null
        val valid = bytes.filter { it in 1 until INVALID_RAW }
        if (valid.isEmpty()) return null
        val scale = SCALES.minBy { scale -> abs(valid.sorted()[valid.size / 2] * scale - TYPICAL_KPA) }
        val kpa = bytes.map { raw -> if (raw in 1 until INVALID_RAW) raw * scale else null }
        if (kpa.filterNotNull().any { it !in PLAUSIBLE_KPA }) return null
        return Tires(flKpa = kpa[0], rlKpa = kpa[1], frKpa = kpa[2], rrKpa = kpa[3], kpaPerCount = scale)
    }

    /**
     * Tire-probe catalog entries: both PIDs at the BCM's physical address and at 0x751, the header
     * community Torque configurations use. Untried candidates until a tire probe answers.
     */
    internal fun catalogProfiles(): List<EnhancedPidProfile> =
        listOf("241", "751").flatMap { node ->
            listOf(COMMAND to "pressures", TEMPERATURES_COMMAND to "temperatures").map { (command, what) ->
                EnhancedPidProfile(
                    "tpms.$node." + command.lowercase(Locale.US),
                    "tpms",
                    "hs-can",
                    "elm327",
                    "ATSH$node",
                    command,
                    ObdElmDecode.pidForCommand(command),
                    "body module tire $what",
                    "",
                    "diagnostic_only",
                    EnhancedPidProfiles.STAGE_TIRES,
                    "low",
                    CANDIDATE_RETRY_MS,
                    EnhancedPidProfiles.STATUS_CANDIDATE,
                    "GM BCM / community Torque TPMS PID",
                    "Read-only Mode 22; unconfirmed on the target car until a tire probe answers.",
                )
            }
        }

    private const val TEMPERATURES_COMMAND = "22C902"
    private const val CANDIDATE_RETRY_MS = 2L * 60L * 60L * 1000L
    private const val POSITIVE_REPLY = "62C901"
    private const val WHEELS = 4
    private const val HEX = 16
    private const val INVALID_RAW = 0xFE
    private const val TYPICAL_KPA = 240.0
    private val SCALES = listOf(1.373, 4.0)
    private val PLAUSIBLE_KPA = 50.0..450.0
}
