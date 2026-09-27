package com.volttracker.obdpoc.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.volttracker.obdpoc.AppPrefs
import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import com.volttracker.obdpoc.ui.theme.AppearanceMode
import com.volttracker.obdpoc.ui.theme.AppearancePrefs
import com.volttracker.obdpoc.ui.theme.LocalVoltPalette
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

/** The light/dark theme contract: the Appearance choice, its persistence, and the palettes. */
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
    fun appearanceRoundTripsThroughTheSharedPrefsFile() {
        val prefs =
            ApplicationProvider
                .getApplicationContext<Context>()
                .getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        assertEquals(AppearanceMode.SYSTEM, AppearancePrefs.read(prefs))
        AppearancePrefs.write(prefs, AppearanceMode.LIGHT)
        assertEquals(AppearanceMode.LIGHT, AppearancePrefs.read(prefs))
        assertEquals("light", prefs.getString(AppearancePrefs.KEY_APPEARANCE, null))
    }

    @Test
    fun storeCarriesTheAppearanceIntoSettingsState() {
        val store = LiveUiStateStore()
        store.onAppearance(AppearanceMode.DARK)
        assertEquals(AppearanceMode.DARK, store.state.value.settings.appearance)
    }

    @Test
    fun themeProvidesThePinnedPalette() {
        var provided: VoltPalette? = null
        compose.setContent {
            VoltTheme(appearance = AppearanceMode.LIGHT) { provided = LocalVoltPalette.current }
        }
        compose.waitForIdle()
        assertSame(VoltPalette.Light, provided)
    }

    @Test
    fun palettesDifferWhereTheMockupsDoAndKeepTheirMeaningColors() {
        assertSame(VoltPalette.Dark, voltPalette(dark = true))
        assertSame(VoltPalette.Light, voltPalette(dark = false))
        assertNotEquals(VoltPalette.Dark.bg, VoltPalette.Light.bg)
        // Legacy names alias the redesign tokens, so drawing code reads the same colors.
        listOf(VoltPalette.Dark, VoltPalette.Light).forEach { p ->
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
        }
    }
}
