package com.volttracker.obdpoc

/**
 * How much the decode of one raw PRNDL code is trusted.
 *
 * - [CONFIRMED]: backed by a clear, repeated pattern across the field logs (see [VoltGear]).
 * - [TENTATIVE]: a best guess from a handful of sessions; still needs a check on the car.
 * - [UNKNOWN]: a code never seen in the field logs — shown as unknown, never guessed.
 */
enum class GearConfidence(
    val wireName: String,
) {
    CONFIRMED("confirmed"),
    TENTATIVE("tentative"),
    UNKNOWN("unknown"),
}

/** One decoded gear-selector reading: the raw code, the letter shown, and how sure we are. */
class GearReading(
    @JvmField val raw: Int,
    @JvmField val letter: String,
    @JvmField val confidence: GearConfidence,
) {
    val isPark: Boolean get() = letter == VoltGear.PARK

    /** True for a decoded non-Park gear (R, N, D or L) — never for an unknown code. */
    val isNonPark: Boolean get() = confidence != GearConfidence.UNKNOWN && !isPark
}

/**
 * Decodes the Volt's mode-22 PRNDL PID (`222889`, HPCM2 on `7E1`) into a gear letter.
 *
 * The encoding is not published anywhere we could find (OVMS decodes a different HS-CAN frame,
 * `0x1F5` byte 3, with 1=P 2=R 3=N 4=D 5=L — not this code). The table below is inferred from 55
 * real drive sessions on the 2017 Volt (2026-06/09 logs; the PID is polled only every ~37 s):
 *
 * | raw | letter | confidence | evidence |
 * |-----|--------|------------|----------|
 * | 8 | P | confirmed | last value in 42/55 sessions; 3080 still vs 116 moving samples (the moving ones are the ~37 s poll lag while pulling away) |
 * | 3 | D | confirmed | first value in 49/55 sessions; 46k moving samples up to 152 km/h — the everyday forward gear. Cannot tell D from L on its own (see 5) |
 * | 7 | R | tentative | short low-speed runs right after P at a session start and right before P at the end (3→7→8 ×9: reversing into a space; 8→7→3: backing out) |
 * | 6 | N | tentative | 3 runs of ~1–2 min stationary at low draw, between D and D/P |
 * | 2 | L | tentative | 3 brief runs, always 3→2→3, captured during hard deceleration with the strongest regen of any code (median −25 kW coasting) |
 * | 5 | L | tentative | 4 late sessions of long forward driving incl. highway (to 139 km/h): a forward gear that is not the everyday one. Could equally be D with 3 = L (the codes 8/7/6/5 = P/R/N/D would then follow the lever order) — confirm on the car |
 *
 * Any other code decodes to [UNKNOWN] with a `?` letter; the raw value is always kept (the live
 * sample's `prndlRaw`) so the table can be confirmed from future logs.
 */
object VoltGear {
    const val PARK: String = "P"
    const val UNKNOWN_LETTER: String = "?"

    /**
     * A gear reading older than this is treated as no reading (the PID is polled only about
     * every 37 s, so this allows for a missed poll or two). Mirrors gear.ts `GEAR_FRESH_MS`.
     */
    const val FRESH_MS: Long = 120_000L

    /** Raw code for Park — the only code the trip splitter acts on. */
    const val PARK_RAW: Int = 8

    private val TABLE: Map<Int, Pair<String, GearConfidence>> =
        mapOf(
            PARK_RAW to (PARK to GearConfidence.CONFIRMED),
            3 to ("D" to GearConfidence.CONFIRMED),
            7 to ("R" to GearConfidence.TENTATIVE),
            6 to ("N" to GearConfidence.TENTATIVE),
            2 to ("L" to GearConfidence.TENTATIVE),
            5 to ("L" to GearConfidence.TENTATIVE),
        )

    /** Decodes [raw]; null only when there is no reading at all. */
    @JvmStatic
    fun decode(raw: Int?): GearReading? {
        if (raw == null) return null
        val entry = TABLE[raw] ?: return GearReading(raw, UNKNOWN_LETTER, GearConfidence.UNKNOWN)
        return GearReading(raw, entry.first, entry.second)
    }

    /**
     * Text to show for a gear reading: the letter, or `? (code N)` for a code never seen, so an
     * unknown gear reads as unknown rather than a guess. Matches the WebView dashboard's gear.ts.
     */
    @JvmStatic
    fun displayText(
        letter: String,
        raw: Int?,
    ): String = if (letter == UNKNOWN_LETTER && raw != null) "$UNKNOWN_LETTER (code $raw)" else letter

    /** True when [raw] is the Park code. */
    @JvmStatic
    fun isPark(raw: Int?): Boolean = raw == PARK_RAW

    /** True when [raw] decodes to a known gear other than Park (R/N/D/L). */
    @JvmStatic
    fun isKnownNonPark(raw: Int?): Boolean = decode(raw)?.isNonPark == true
}
