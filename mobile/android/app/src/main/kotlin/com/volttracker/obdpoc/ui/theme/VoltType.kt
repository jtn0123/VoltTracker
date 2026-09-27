package com.volttracker.obdpoc.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.volttracker.obdpoc.R

/**
 * Bundled typefaces (SIL Open Font License 1.1 — texts in `assets/licenses/`):
 *  - Barlow: instrument-cluster numerals (DIN lineage) for every number.
 *  - Barlow Semi Condensed: the small upper-case labels.
 *  - Hanken Grotesk: body copy and titles.
 * Latin subsets, so Android's per-glyph fallback covers anything rarer (e.g. arrows).
 */
object VoltFonts {
    val barlow =
        FontFamily(
            Font(R.font.barlow_light, FontWeight.Light),
            Font(R.font.barlow_regular, FontWeight.Normal),
            Font(R.font.barlow_medium, FontWeight.Medium),
            Font(R.font.barlow_semibold, FontWeight.SemiBold),
        )
    val barlowSemiCondensed =
        FontFamily(
            Font(R.font.barlow_semi_condensed_medium, FontWeight.Medium),
            Font(R.font.barlow_semi_condensed_semibold, FontWeight.SemiBold),
        )
    val hanken =
        FontFamily(
            Font(R.font.hanken_grotesk_regular, FontWeight.Normal),
            Font(R.font.hanken_grotesk_medium, FontWeight.Medium),
            Font(R.font.hanken_grotesk_semibold, FontWeight.SemiBold),
        )
}

/** Tabular figures so live numbers don't jitter sideways as digits change. */
private const val TNUM = "tnum"

/** Type scale (mockups `base.css`/`drive.css`). Numbers are Barlow; prose is Hanken Grotesk. */
object VoltType {
    /** The hero numeral (speed / state of charge). */
    val display =
        TextStyle(
            fontFamily = VoltFonts.barlow,
            fontWeight = FontWeight.Light,
            fontSize = 120.sp,
            lineHeight = 108.sp,
            letterSpacing = (-0.04).em,
            fontFeatureSettings = TNUM,
        )
    val heroUnit =
        TextStyle(
            fontFamily = VoltFonts.hanken,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
        )
    val screenTitle =
        TextStyle(
            fontFamily = VoltFonts.hanken,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            letterSpacing = (-0.01).em,
        )
    val value =
        TextStyle(
            fontFamily = VoltFonts.barlow,
            fontWeight = FontWeight.Medium,
            fontSize = 22.sp,
            letterSpacing = (-0.01).em,
            fontFeatureSettings = TNUM,
        )
    val valueSmall =
        TextStyle(
            fontFamily = VoltFonts.barlow,
            fontWeight = FontWeight.Medium,
            fontSize = 17.sp,
            fontFeatureSettings = TNUM,
        )

    /** Small upper-case caps label ("RANGE", "THIS DRIVE"). */
    val label =
        TextStyle(
            fontFamily = VoltFonts.barlowSemiCondensed,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            letterSpacing = 0.12.em,
        )

    /** Status pill text ("ELECTRIC", "GAS · RANGE EXTENDER"). */
    val pill =
        TextStyle(
            fontFamily = VoltFonts.barlowSemiCondensed,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            letterSpacing = 0.1.em,
        )
    val body =
        TextStyle(
            fontFamily = VoltFonts.hanken,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
        )

    /** Emphasised body: list-row titles, card headings. */
    val bodyStrong =
        TextStyle(
            fontFamily = VoltFonts.hanken,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.5.sp,
        )
    val caption =
        TextStyle(
            fontFamily = VoltFonts.hanken,
            fontWeight = FontWeight.Normal,
            fontSize = 12.5.sp,
        )
}
