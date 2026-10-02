package com.volttracker.obdpoc.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.volttracker.obdpoc.AppPrefs
import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import com.volttracker.obdpoc.ui.theme.AppearanceMode
import com.volttracker.obdpoc.ui.theme.AppearancePrefs
import com.volttracker.obdpoc.ui.theme.DarkStyle
import com.volttracker.obdpoc.ui.theme.LocalVoltPalette
import com.volttracker.obdpoc.ui.theme.OledAccent
import com.volttracker.obdpoc.ui.theme.VoltPalette
import com.volttracker.obdpoc.ui.theme.VoltTheme
import com.volttracker.obdpoc.ui.theme.resolvesDark
import com.volttracker.obdpoc.ui.theme.voltPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The theme contract: the Appearance choices (mode, dark style, OLED accent), their persistence,
 * and which palette each combination renders.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class VoltThemeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun systemFollowsThePhoneWhileLightAndDarkArePinned() {
        assertTrue(AppearanceMode.SYSTEM.resolvesDark(systemDark = true))
        assertFalse(AppearanceMode.SYSTEM.resolvesDark(systemDark = false))
        assertTrue(AppearanceMode.DARK.resolvesDark(systemDark = false))
        assertFalse(AppearanceMode.LIGHT.resolvesDark(systemDark = true))
    }

    @Test
    fun unknownOrMissingKeysReadAsSystem() {
        assertEquals(AppearanceMode.SYSTEM, AppearanceMode.fromKey(null))
        assertEquals(AppearanceMode.SYSTEM, AppearanceMode.fromKey("sepia"))
        AppearanceMode.entries.forEach { assertEquals(it, AppearanceMode.fromKey(it.key)) }
    }

    @Test
    fun unknownOrMissingStyleAndAccentKeysReadAsOledCyan() {
        assertEquals(DarkStyle.OLED, DarkStyle.fromKey(null))
        assertEquals(DarkStyle.OLED, DarkStyle.fromKey("espresso"))
        assertEquals(OledAccent.CYAN, OledAccent.fromKey(null))
        // Red was explored but not shipped (it collides with the fault color).
        assertEquals(OledAccent.CYAN, OledAccent.fromKey("red"))
        DarkStyle.entries.forEach { assertEquals(it, DarkStyle.fromKey(it.key)) }
        OledAccent.entries.forEach { assertEquals(it, OledAccent.fromKey(it.key)) }
    }

    @Test
    fun appearanceRoundTripsThroughTheSharedPrefsFile() {
        val prefs = sharedPrefs()
        assertEquals(AppearanceMode.SYSTEM, AppearancePrefs.read(prefs))
        AppearancePrefs.write(prefs, AppearanceMode.LIGHT)
        assertEquals(AppearanceMode.LIGHT, AppearancePrefs.read(prefs))
        assertEquals("light", prefs.getString(AppearancePrefs.KEY_APPEARANCE, null))
    }

    @Test
    fun anInstallFromBeforeDarkStylesLandsOnOledCyanAndLatte() {
        // Only the old Clean EV key is stored: the new choices fall back to their defaults.
        val prefs = sharedPrefs()
        prefs.edit().putString(AppearancePrefs.KEY_APPEARANCE, "dark").commit()
        assertEquals(DarkStyle.OLED, AppearancePrefs.readDarkStyle(prefs))
        assertEquals(OledAccent.CYAN, AppearancePrefs.readAccent(prefs))
        assertSame(
            VoltPalette.Oled,
            voltPalette(dark = true, AppearancePrefs.readDarkStyle(prefs), AppearancePrefs.readAccent(prefs)),
        )
        assertSame(
            VoltPalette.Latte,
            voltPalette(dark = false, AppearancePrefs.readDarkStyle(prefs), AppearancePrefs.readAccent(prefs)),
        )
    }

    @Test
    fun darkStyleAndAccentRoundTripThroughTheSharedPrefsFile() {
        val prefs = sharedPrefs()
        AppearancePrefs.writeDarkStyle(prefs, DarkStyle.SADDLE)
        AppearancePrefs.writeAccent(prefs, OledAccent.LIME)
        assertEquals(DarkStyle.SADDLE, AppearancePrefs.readDarkStyle(prefs))
        assertEquals(OledAccent.LIME, AppearancePrefs.readAccent(prefs))
        assertEquals("saddle", prefs.getString(AppearancePrefs.KEY_DARK_STYLE, null))
        assertEquals("lime", prefs.getString(AppearancePrefs.KEY_ACCENT, null))
    }

    @Test
    fun storeCarriesTheAppearanceIntoSettingsState() {
        val store = LiveUiStateStore()
        store.onSettings { it.copy(appearance = AppearanceMode.DARK) }
        assertEquals(AppearanceMode.DARK, store.state.value.settings.appearance)
    }

    @Test
    fun themeProvidesThePinnedPalette() {
        val provided = mutableListOf<VoltPalette>()
        compose.setContent {
            VoltTheme(appearance = AppearanceMode.LIGHT, darkStyle = DarkStyle.SADDLE) {
                provided += LocalVoltPalette.current
            }
            VoltTheme(appearance = AppearanceMode.DARK, darkStyle = DarkStyle.SADDLE) {
                provided += LocalVoltPalette.current
            }
            VoltTheme(appearance = AppearanceMode.DARK, accent = OledAccent.VIOLET) {
                provided += LocalVoltPalette.current
            }
        }
        compose.waitForIdle()
        assertEquals(listOf(VoltPalette.Latte, VoltPalette.Saddle, VoltPalette.oled(OledAccent.VIOLET)), provided)
    }

    @Test
    fun highContrastIsAppliedOnTopOfThePinnedPalette() {
        var provided: VoltPalette? = null
        compose.setContent {
            VoltTheme(appearance = AppearanceMode.LIGHT, highContrast = true) { provided = LocalVoltPalette.current }
        }
        compose.waitForIdle()
        assertEquals(VoltPalette.Latte.highContrast(), provided)
    }

    @Test
    fun paletteSelectionFollowsModeThenStyleThenAccent() {
        // Light is always Latte; Saddle has its own accent; OLED takes the chosen one.
        OledAccent.entries.forEach { accent ->
            DarkStyle.entries.forEach { style -> assertSame(VoltPalette.Latte, voltPalette(false, style, accent)) }
            assertSame(VoltPalette.Saddle, voltPalette(true, DarkStyle.SADDLE, accent))
            assertSame(VoltPalette.oled(accent), voltPalette(true, DarkStyle.OLED, accent))
        }
        assertSame(VoltPalette.Oled, voltPalette(dark = true))
        assertSame(VoltPalette.Oled, VoltPalette.oled(OledAccent.CYAN))
    }

    @Test
    fun oledAccentsSwapOnlyTheAccentExceptLimeWhichShiftsEv() {
        val base = VoltPalette.Oled
        OledAccent.entries.forEach { accent ->
            val p = VoltPalette.oled(accent)
            assertEquals(accent.volt, p.volt)
            assertEquals(accent.onVolt, p.onVolt)
            assertEquals(base.bg, p.bg)
            assertEquals(base.bad, p.bad)
            val expectedEv = if (accent == OledAccent.LIME) Color(0xFF3DF0B0) else Color(0xFF3DF08A)
            assertEquals(expectedEv, p.ev)
            // Accent never reads as EV or as a fault.
            assertNotEquals(p.volt, p.ev)
            assertNotEquals(p.volt, p.bad)
        }
        assertEquals(Color(0xFFB8F040), VoltPalette.oled(OledAccent.LIME).volt)
        assertNotEquals(base.evBright, VoltPalette.oled(OledAccent.LIME).evBright)
        assertEquals(
            listOf("cyan", "blue", "lime", "white", "violet"),
            OledAccent.entries.map { it.key },
        )
    }

    @Test
    fun palettesCarryTheApprovedCssTokens() {
        with(VoltPalette.Oled) {
            assertTrue(isDark)
            assertEquals(Color.Black, bg)
            assertEquals(Color(0xFF0E0F11), surface)
            assertEquals(Color(0xFF00E5D1), volt)
            assertEquals(Color(0xFF00201D), onVolt)
            assertEquals(Color(0xFF061318), mapWater)
        }
        with(VoltPalette.Saddle) {
            assertTrue(isDark)
            assertEquals(Color(0xFF0C0A08), bg)
            assertEquals(Color(0xFFC8955A), volt)
            assertEquals(Color(0xFF7ED492), ev)
            assertEquals(Color(0xFFFF7A45), gas)
        }
        with(VoltPalette.Latte) {
            assertFalse(isDark)
            assertEquals(Color(0xFFEFE6DA), bg)
            assertEquals(Color(0xFF8A5A2B), volt)
            assertEquals(Color.White, onVolt)
            assertEquals(Color(0xFFB0342A), bad)
        }
        // Dark cards sit flat (`--shadow: none`); Latte keeps its soft card shadow.
        assertEquals(0f, VoltPalette.Oled.cardShadow.alpha)
        assertEquals(0f, VoltPalette.Saddle.cardShadow.alpha)
        assertTrue(VoltPalette.Latte.cardShadow.alpha > 0f)
    }

    @Test
    fun palettesKeepTheirMeaningColorsAndLegacyAliases() {
        val all = listOf(VoltPalette.Saddle, VoltPalette.Latte) + OledAccent.entries.map(VoltPalette::oled)
        all.forEach { p ->
            assertEquals(p.volt, p.accent)
            assertEquals(p.volt, p.drive)
            assertEquals(p.ev, p.energy)
            assertEquals(p.ev, p.regen)
            assertEquals(p.bad, p.alert)
            assertEquals(p.text, p.textPrimary)
            assertEquals(p.muted, p.textSecondary)
            assertEquals(p.faint, p.textTertiary)
            assertEquals(p.surface2, p.surfaceElevated)
            assertEquals(p.line, p.hairline)
            assertEquals(p.onVolt, p.onAccent)
            assertEquals(p.evBright, p.energyBright)
            assertEquals(p.evDim, p.energyDim)
            // The derived EV companions stay distinct from EV itself.
            assertNotEquals(p.ev, p.evBright)
            assertNotEquals(p.ev, p.evDim)
            // Every meaning color is its own.
            assertEquals(5, setOf(p.volt, p.ev, p.gas, p.warn, p.bad).size)
        }
    }

    private fun sharedPrefs() =
        ApplicationProvider
            .getApplicationContext<Context>()
            .getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
}
