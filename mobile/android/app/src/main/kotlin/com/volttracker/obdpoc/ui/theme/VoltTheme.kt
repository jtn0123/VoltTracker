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
 * VoltTracker design language for the native Compose dashboard.
 *
 * Dark-first, near-black canvas with a single lime-green energy accent —
 * calm, high-contrast, and glanceable at arm's length in a moving car.
 * Panels are subtle elevation steps, not bordered boxes; hierarchy comes
 * from type scale and spacing, not chrome.
 */
object VoltColors {
    val bg = Color(0xFF0A0A0E)
    val surface = Color(0xFF15151B)
    val surfaceElevated = Color(0xFF1C1C24)
    val hairline = Color(0xFF26262F)

    val textPrimary = Color(0xFFF2F3F5)
    val textSecondary = Color(0xFF8F8F9C)
    val textTertiary = Color(0xFF5C5C68)

    /** Energy / battery / charging — the one brand accent. */
    val energy = Color(0xFF8CE563)
    val energyBright = Color(0xFFC6F178)
    val energyDim = Color(0xFF3E6B2C)

    /** Regenerative braking (power flowing back in). */
    val regen = Color(0xFF57B8FF)

    /** Discharge / drive power (power flowing out). */
    val drive = Color(0xFFFFA45C)

    val warn = Color(0xFFFFC24B)
    val alert = Color(0xFFFF6B6B)

    /** Text/icon color on top of the energy accent (buttons, filled chips). */
    val onAccent = Color(0xFF10230A)
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
        primary = VoltColors.energy,
        onPrimary = Color(0xFF10230A),
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
