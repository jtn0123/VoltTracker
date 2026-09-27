package com.volttracker.obdpoc.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * VoltTracker "Clean EV" palette — the approved redesign tokens (mockups `src/base.css`),
 * one instance per theme. Color carries meaning only:
 *  - [volt] (teal): interactive/brand — selection, primary actions, drive power.
 *  - [ev] (green): electric energy — battery, charging, regen, healthy.
 *  - [gas] (amber): the gas engine is running.
 *  - [warn] / [bad]: warnings and faults.
 */
@Immutable
data class VoltPalette(
    val isDark: Boolean,
    val bg: Color,
    val bgGlow: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    /** Hairline divider / card border. */
    val line: Color,
    /** Stronger hairline (ghost-button border, chart zero line). */
    val line2: Color,
    /** Empty gauge / meter track. */
    val track: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val volt: Color,
    val onVolt: Color,
    val ev: Color,
    val gas: Color,
    val warn: Color,
    val bad: Color,
    val carBody: Color,
    val carLine: Color,
    val carGlass: Color,
    val mapLand: Color,
    val mapRoad: Color,
    val mapWater: Color,
    /** Soft companions of [ev] kept for the existing chart/range call sites. */
    val evBright: Color,
    val evDim: Color,
    /** Neutral chart series with no status meaning (terrain/elevation). */
    val neutralSeries: Color,
) {
    // Same names as [VoltColors], so drawing code can swap `VoltColors.x` for a captured `pal.x`.
    val surfaceElevated: Color get() = surface2
    val hairline: Color get() = line
    val textPrimary: Color get() = text
    val textSecondary: Color get() = muted
    val textTertiary: Color get() = faint
    val accent: Color get() = volt
    val onAccent: Color get() = onVolt
    val energy: Color get() = ev
    val energyBright: Color get() = evBright
    val energyDim: Color get() = evDim
    val regen: Color get() = ev
    val drive: Color get() = volt
    val alert: Color get() = bad

    companion object {
        val Dark =
            VoltPalette(
                isDark = true,
                bg = Color(0xFF0B0F14),
                bgGlow = Color(0xFF0F151C),
                surface = Color(0xFF141A21),
                surface2 = Color(0xFF1B222B),
                surface3 = Color(0xFF222A34),
                line = Color.White.copy(alpha = 0.06f),
                line2 = Color.White.copy(alpha = 0.11f),
                track = Color.White.copy(alpha = 0.07f),
                text = Color(0xFFE8EDF2),
                muted = Color(0xFF9AA5B1),
                faint = Color(0xFF6E7A87),
                volt = Color(0xFF2BD4C4),
                onVolt = Color(0xFF04201D),
                ev = Color(0xFF5FD37A),
                gas = Color(0xFFFF9F43),
                warn = Color(0xFFF2C94C),
                bad = Color(0xFFFF6B6B),
                carBody = Color(0xFF1E262F),
                carLine = Color.White.copy(alpha = 0.14f),
                carGlass = Color(0xFF0F1419),
                mapLand = Color(0xFF121820),
                mapRoad = Color(0xFF1F2831),
                mapWater = Color(0xFF0C1E26),
                evBright = Color(0xFFA8EDB9),
                evDim = Color(0xFF2E6B41),
                neutralSeries = Color(0xFF8793A0),
            )

        private val ink = Color(0xFF0C141C)

        val Light =
            VoltPalette(
                isDark = false,
                bg = Color(0xFFF6F8FA),
                bgGlow = Color(0xFFFFFFFF),
                surface = Color(0xFFFFFFFF),
                surface2 = Color(0xFFEFF3F6),
                surface3 = Color(0xFFE4E9EE),
                line = ink.copy(alpha = 0.07f),
                line2 = ink.copy(alpha = 0.12f),
                track = ink.copy(alpha = 0.075f),
                text = Color(0xFF0E151C),
                muted = Color(0xFF56616D),
                faint = Color(0xFF86909B),
                volt = Color(0xFF0E9F91),
                onVolt = Color(0xFFFFFFFF),
                ev = Color(0xFF22A046),
                gas = Color(0xFFE07A12),
                warn = Color(0xFFB98A00),
                bad = Color(0xFFD93F3F),
                carBody = Color(0xFFE7ECF0),
                carLine = ink.copy(alpha = 0.18f),
                carGlass = Color(0xFFCBD4DC),
                mapLand = Color(0xFFEDF1F4),
                mapRoad = Color(0xFFFFFFFF),
                mapWater = Color(0xFFD3E6EE),
                evBright = Color(0xFF157A33),
                evDim = Color(0xFF9FD6AE),
                neutralSeries = Color(0xFF86909B),
            )
    }
}

/** The palette in effect — supplied by [VoltTheme]; dark outside any theme. */
val LocalVoltPalette = staticCompositionLocalOf { VoltPalette.Dark }

/**
 * Theme-aware color tokens for call sites. Each reads the current [VoltPalette], so a screen
 * written against these names renders correctly in both the dark and light themes. Code that
 * draws outside composition (Canvas lambdas) should read `VoltColors.x` into a local first.
 */
object VoltColors {
    private val p: VoltPalette
        @Composable @ReadOnlyComposable
        get() = LocalVoltPalette.current

    val isDark: Boolean
        @Composable @ReadOnlyComposable
        get() = p.isDark
    val bg: Color
        @Composable @ReadOnlyComposable
        get() = p.bg
    val bgGlow: Color
        @Composable @ReadOnlyComposable
        get() = p.bgGlow
    val surface: Color
        @Composable @ReadOnlyComposable
        get() = p.surface
    val surfaceElevated: Color
        @Composable @ReadOnlyComposable
        get() = p.surface2
    val surface3: Color
        @Composable @ReadOnlyComposable
        get() = p.surface3
    val hairline: Color
        @Composable @ReadOnlyComposable
        get() = p.line
    val line2: Color
        @Composable @ReadOnlyComposable
        get() = p.line2
    val track: Color
        @Composable @ReadOnlyComposable
        get() = p.track
    val textPrimary: Color
        @Composable @ReadOnlyComposable
        get() = p.text
    val textSecondary: Color
        @Composable @ReadOnlyComposable
        get() = p.muted
    val textTertiary: Color
        @Composable @ReadOnlyComposable
        get() = p.faint

    /** The one brand/interactive accent (Volt teal). */
    val accent: Color
        @Composable @ReadOnlyComposable
        get() = p.volt

    /** Filled-accent track (e.g. a switch that is on). */
    val accentDim: Color
        @Composable @ReadOnlyComposable
        get() = p.volt.copy(alpha = ACCENT_DIM_ALPHA)

    /** Text/icon color on top of the accent (buttons, filled chips). */
    val onAccent: Color
        @Composable @ReadOnlyComposable
        get() = p.onVolt

    /** EV / battery / charging — electric energy. */
    val energy: Color
        @Composable @ReadOnlyComposable
        get() = p.ev
    val energyBright: Color
        @Composable @ReadOnlyComposable
        get() = p.evBright
    val energyDim: Color
        @Composable @ReadOnlyComposable
        get() = p.evDim

    /** Regenerative braking (power flowing back into the pack) is EV energy. */
    val regen: Color
        @Composable @ReadOnlyComposable
        get() = p.ev

    /** Discharge / drive power (power flowing out) is the accent. */
    val drive: Color
        @Composable @ReadOnlyComposable
        get() = p.volt

    /** Gas engine running. */
    val gas: Color
        @Composable @ReadOnlyComposable
        get() = p.gas
    val neutralSeries: Color
        @Composable @ReadOnlyComposable
        get() = p.neutralSeries
    val warn: Color
        @Composable @ReadOnlyComposable
        get() = p.warn
    val alert: Color
        @Composable @ReadOnlyComposable
        get() = p.bad
    val carBody: Color
        @Composable @ReadOnlyComposable
        get() = p.carBody
    val carLine: Color
        @Composable @ReadOnlyComposable
        get() = p.carLine
    val carGlass: Color
        @Composable @ReadOnlyComposable
        get() = p.carGlass
    val mapLand: Color
        @Composable @ReadOnlyComposable
        get() = p.mapLand
    val mapRoad: Color
        @Composable @ReadOnlyComposable
        get() = p.mapRoad
    val mapWater: Color
        @Composable @ReadOnlyComposable
        get() = p.mapWater

    private const val ACCENT_DIM_ALPHA = 0.35f
}

/** Whether a given appearance choice renders dark, given the system's current setting. */
fun AppearanceMode.resolvesDark(systemDark: Boolean): Boolean =
    when (this) {
        AppearanceMode.SYSTEM -> systemDark
        AppearanceMode.DARK -> true
        AppearanceMode.LIGHT -> false
    }

/** The palette for a light/dark decision. */
fun voltPalette(dark: Boolean): VoltPalette = if (dark) VoltPalette.Dark else VoltPalette.Light

private fun materialScheme(p: VoltPalette): ColorScheme =
    if (p.isDark) {
        darkColorScheme(
            primary = p.volt,
            onPrimary = p.onVolt,
            background = p.bg,
            onBackground = p.text,
            surface = p.surface,
            onSurface = p.text,
            surfaceVariant = p.surface2,
            onSurfaceVariant = p.muted,
            surfaceContainer = p.surface2,
            outline = p.line2,
            error = p.bad,
        )
    } else {
        lightColorScheme(
            primary = p.volt,
            onPrimary = p.onVolt,
            background = p.bg,
            onBackground = p.text,
            surface = p.surface,
            onSurface = p.text,
            surfaceVariant = p.surface2,
            onSurfaceVariant = p.muted,
            surfaceContainer = p.surface,
            outline = p.line2,
            error = p.bad,
        )
    }

/**
 * VoltTracker's Compose theme. Follows the system dark/light setting unless the user picked a
 * fixed [appearance] (Settings → Appearance).
 */
@Composable
fun VoltTheme(
    appearance: AppearanceMode = AppearanceMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val palette = voltPalette(appearance.resolvesDark(isSystemInDarkTheme()))
    CompositionLocalProvider(LocalVoltPalette provides palette) {
        MaterialTheme(
            colorScheme = materialScheme(palette),
            typography = voltTypography(),
            content = content,
        )
    }
}

private fun voltTypography(): Typography {
    val base = Typography()
    val body = VoltFonts.hanken
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = VoltFonts.barlow),
        displayMedium = base.displayMedium.copy(fontFamily = VoltFonts.barlow),
        displaySmall = base.displaySmall.copy(fontFamily = VoltFonts.barlow),
        headlineLarge = base.headlineLarge.copy(fontFamily = body),
        headlineMedium = base.headlineMedium.copy(fontFamily = body),
        headlineSmall = base.headlineSmall.copy(fontFamily = body),
        titleLarge = base.titleLarge.copy(fontFamily = body),
        titleMedium = base.titleMedium.copy(fontFamily = body),
        titleSmall = base.titleSmall.copy(fontFamily = body),
        bodyLarge = VoltType.body,
        bodyMedium = base.bodyMedium.copy(fontFamily = body),
        bodySmall = base.bodySmall.copy(fontFamily = body),
        labelLarge = base.labelLarge.copy(fontFamily = body),
        labelMedium = base.labelMedium.copy(fontFamily = body),
        labelSmall = VoltType.label,
    )
}
