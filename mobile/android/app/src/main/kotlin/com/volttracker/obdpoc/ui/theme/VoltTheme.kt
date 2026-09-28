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
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * One VoltTracker theme's color tokens — the approved mockup token set (`src/themes*.css`; the
 * instances live in VoltPalettes.kt). Color carries meaning only:
 *  - [volt]: the accent — interactive/brand, selection, primary actions, drive power.
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
    /** Card shadow tint (`--shadow`); transparent on the dark themes, whose cards sit flat. */
    val cardShadow: Color,
) {
    /** Emphasized [ev] (chart highlights): lighter on dark, deeper on light. */
    val evBright: Color =
        lerp(ev, if (isDark) Color.White else Color.Black, if (isDark) EV_BRIGHT_DARK else EV_BRIGHT_LIGHT)

    /** Receded [ev] (chart fills behind the line): toward the canvas. */
    val evDim: Color = lerp(ev, if (isDark) Color.Black else Color.White, EV_DIM)

    /**
     * Demo / Testing (the classic dashboard's violet `--mixed`): deepened on the light theme so the
     * "DEMO" pill text keeps 4.5:1 on Latte's cards.
     */
    val demo: Color = if (isDark) DEMO_DARK else DEMO_LIGHT

    /** Neutral chart series with no status meaning (terrain/elevation). */
    val neutralSeries: Color = if (isDark) lerp(muted, faint, NEUTRAL_MIX) else faint

    /**
     * The accent is a neutral close to the body text (OLED Mono White), so accent-tinted selection
     * reads as plain text: on-states are drawn filled in [volt] with [onVolt] content instead.
     */
    val monoAccent: Boolean =
        maxOf(volt.red, volt.green, volt.blue) - minOf(volt.red, volt.green, volt.blue) < MONO_ACCENT_CHROMA &&
            contrastRatio(volt, text) < MONO_ACCENT_CONTRAST

    /**
     * Settings → High contrast: faint text takes the muted tone, muted moves toward the body text,
     * and hairlines / gauge tracks roughly double in strength. Status and accent colors stay put.
     */
    fun highContrast(): VoltPalette =
        copy(
            muted = lerp(muted, text, HC_MUTED_TO_TEXT),
            faint = muted,
            line = line.copy(alpha = (line.alpha * HC_LINE_GAIN).coerceAtMost(1f)),
            line2 = line2.copy(alpha = (line2.alpha * HC_LINE_GAIN).coerceAtMost(1f)),
            track = track.copy(alpha = (track.alpha * HC_LINE_GAIN).coerceAtMost(1f)),
        )

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
        /** OLED Black, Cyan accent — the default dark theme. */
        val Oled: VoltPalette get() = OledCyanPalette

        /** Saddle Leather — the alternative dark theme. */
        val Saddle: VoltPalette get() = SaddlePalette

        /** Latte — the light theme. */
        val Latte: VoltPalette get() = LattePalette

        /** OLED Black in [accent]. */
        fun oled(accent: OledAccent): VoltPalette = oledPalette(accent)

        private const val EV_BRIGHT_DARK = 0.45f
        private const val EV_BRIGHT_LIGHT = 0.3f
        private const val EV_DIM = 0.5f
        private const val NEUTRAL_MIX = 0.5f
        private const val MONO_ACCENT_CONTRAST = 1.5
        private const val MONO_ACCENT_CHROMA = 0.1f
        private const val HC_MUTED_TO_TEXT = 0.4f
        private const val HC_LINE_GAIN = 2.2f
        private val DEMO_DARK = Color(0xFFA78BFA)
        private val DEMO_LIGHT = Color(0xFF6D4AC4)
    }
}

/** The palette in effect — supplied by [VoltTheme]; dark outside any theme. */
val LocalVoltPalette = staticCompositionLocalOf { VoltPalette.Oled }

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

    /** The one brand/interactive accent (the theme's `--volt`). */
    val accent: Color
        @Composable @ReadOnlyComposable
        get() = p.volt

    /** See [VoltPalette.monoAccent]: selection must be drawn filled, not accent-tinted. */
    val monoAccent: Boolean
        @Composable @ReadOnlyComposable
        get() = p.monoAccent

    /** Filled-accent track (e.g. a switch that is on). */
    val accentDim: Color
        @Composable @ReadOnlyComposable
        get() = p.volt.copy(alpha = ACCENT_DIM_ALPHA)

    /** Demo / Testing data (the header's "Demo" pill). */
    val demo: Color
        @Composable @ReadOnlyComposable
        get() = p.demo

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

    /** Card shadow tint; transparent in the dark themes (flat cards). */
    val cardShadow: Color
        @Composable @ReadOnlyComposable
        get() = p.cardShadow

    private const val ACCENT_DIM_ALPHA = 0.35f
}

/** Whether a given appearance choice renders dark, given the system's current setting. */
fun AppearanceMode.resolvesDark(systemDark: Boolean): Boolean =
    when (this) {
        AppearanceMode.SYSTEM -> systemDark
        AppearanceMode.DARK -> true
        AppearanceMode.LIGHT -> false
    }

/**
 * The palette for a light/dark decision: Latte when light; when dark, the chosen [style] —
 * OLED Black in [accent], or Saddle Leather (which has its own accent and ignores [accent]).
 */
fun voltPalette(
    dark: Boolean,
    style: DarkStyle = DarkStyle.OLED,
    accent: OledAccent = OledAccent.CYAN,
    highContrast: Boolean = false,
): VoltPalette {
    val base =
        when {
            !dark -> VoltPalette.Latte
            style == DarkStyle.SADDLE -> VoltPalette.Saddle
            else -> VoltPalette.oled(accent)
        }
    return if (highContrast) base.highContrast() else base
}

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
 * fixed [appearance]; dark renders in [darkStyle] (and, for OLED Black, [accent]) — all three
 * are Settings → Appearance choices, as is [highContrast] (see [VoltPalette.highContrast]).
 */
@Composable
fun VoltTheme(
    appearance: AppearanceMode = AppearanceMode.SYSTEM,
    darkStyle: DarkStyle = DarkStyle.OLED,
    accent: OledAccent = OledAccent.CYAN,
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = appearance.resolvesDark(isSystemInDarkTheme())
    val palette = remember(dark, darkStyle, accent, highContrast) { voltPalette(dark, darkStyle, accent, highContrast) }
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
