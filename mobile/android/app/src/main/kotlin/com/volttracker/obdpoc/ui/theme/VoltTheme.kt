package com.volttracker.obdpoc.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * VoltTracker "Clean EV" design language for the native Compose dashboard —
 * the same tokens as the WebView dashboard (assets/dashboard/css/base.css).
 *
 * Dark-first, cool near-black canvas; panels are subtle elevation steps, not
 * bordered boxes, and hierarchy comes from type scale and spacing. Color
 * carries meaning only:
 *  - [accent] (Volt teal): interactive/brand — selection, primary actions,
 *    drive power, route lines.
 *  - [energy] (green): EV / battery / charging / regen / healthy.
 *  - [gas] (amber): the gas engine is running.
 *  - [warn] / [alert]: warnings and faults.
 */
object VoltColors {
    val bg = Color(0xFF0B0F14)
    val surface = Color(0xFF141A21)
    val surfaceElevated = Color(0xFF1B222B)
    val hairline = Color(0xFF232B35)

    val textPrimary = Color(0xFFE8EDF2)
    val textSecondary = Color(0xFF9AA5B1)
    val textTertiary = Color(0xFF8793A0)

    /** The one brand/interactive accent (Volt teal). */
    val accent = Color(0xFF2BD4C4)

    /** Filled-accent track (e.g. a switch that is on). */
    val accentDim = Color(0xFF1B5E58)

    /** Text/icon color on top of the accent (buttons, filled chips). */
    val onAccent = Color(0xFF04201D)

    /** EV / battery / charging — electric energy. */
    val energy = Color(0xFF5FD37A)
    val energyBright = Color(0xFFA8EDB9)
    val energyDim = Color(0xFF2E6B41)

    /** Regenerative braking (power flowing back into the pack) is EV energy. */
    val regen = energy

    /** Discharge / drive power (power flowing out) — the accent, as on the web. */
    val drive = accent

    /** Gas engine running. */
    val gas = Color(0xFFFF9F43)

    /** Neutral chart series with no status meaning (terrain/elevation). */
    val neutralSeries = Color(0xFF8793A0)

    val warn = Color(0xFFF2C94C)
    val alert = Color(0xFFFF6B6B)
}

/** Type scale. The display size is the Tesla-style hero numeral. */
object VoltType {
    val display =
        TextStyle(
            fontWeight = FontWeight.ExtraLight,
            fontSize = 108.sp,
            letterSpacing = (-0.03).em,
        )
    val heroUnit =
        TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            letterSpacing = 0.08.em,
        )
    val screenTitle =
        TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = 21.sp,
            letterSpacing = 0.01.em,
        )
    val value =
        TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 26.sp,
            letterSpacing = (-0.01).em,
        )
    val valueSmall =
        TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 18.sp,
        )
    val label =
        TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            letterSpacing = 0.14.em,
        )
    val body =
        TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
        )
    val caption =
        TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
        )
}

private val voltDarkScheme =
    darkColorScheme(
        primary = VoltColors.accent,
        onPrimary = VoltColors.onAccent,
        background = VoltColors.bg,
        onBackground = VoltColors.textPrimary,
        surface = VoltColors.surface,
        onSurface = VoltColors.textPrimary,
        surfaceVariant = VoltColors.surfaceElevated,
        onSurfaceVariant = VoltColors.textSecondary,
        outline = VoltColors.hairline,
        error = VoltColors.alert,
    )

@Composable
fun VoltTheme(content: @Composable () -> Unit) {
    // Single dark scheme for now: the dashboard is a driving surface and the
    // legacy WebView UI is dark-first too. isSystemInDarkTheme() is read so a
    // future light palette can slot in without touching call sites.
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = voltDarkScheme,
        typography =
            Typography(
                bodyLarge = VoltType.body,
                labelSmall = VoltType.label,
            ),
        content = content,
    )
}
