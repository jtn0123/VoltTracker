package com.volttracker.obdpoc.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.volttracker.obdpoc.ui.theme.OledAccent
import com.volttracker.obdpoc.ui.theme.VoltPalette
import com.volttracker.obdpoc.ui.theme.WCAG_AA_TEXT
import com.volttracker.obdpoc.ui.theme.contrastRatio
import com.volttracker.obdpoc.ui.theme.voltPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG contrast of every text/tone token against the surfaces it is drawn on, per theme: body and
 * caption text, status colors, the tinted pills, and on-accent content all clear AA (4.5:1).
 */
class PaletteContrastTest {
    private val themes: Map<String, VoltPalette> =
        mapOf("saddle" to VoltPalette.Saddle, "latte" to VoltPalette.Latte) +
            OledAccent.entries.associate { "oled-${it.key}" to VoltPalette.oled(it) }

    private fun assertAa(
        what: String,
        fg: Color,
        bg: Color,
    ) {
        val ratio = contrastRatio(fg, bg)
        assertTrue("$what is %.2f:1, below AA".format(ratio), ratio >= WCAG_AA_TEXT)
    }

    @Test
    fun ratioMatchesTheWcagReferenceValues() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrastRatio(Color.Gray, Color.Gray), 0.001)
        // Symmetric, and a translucent foreground is composited first.
        assertEquals(contrastRatio(Color.White, Color.Red), contrastRatio(Color.Red, Color.White), 0.001)
        assertEquals(1.0, contrastRatio(Color.White.copy(alpha = 0f), Color.Black), 0.001)
    }

    @Test
    fun textTokensClearAaOnTheCanvasAndEveryCard() {
        themes.forEach { (name, p) ->
            listOf("bg" to p.bg, "surface" to p.surface).forEach { (bgName, bg) ->
                assertAa("$name text on $bgName", p.text, bg)
                assertAa("$name muted on $bgName", p.muted, bg)
                assertAa("$name faint on $bgName", p.faint, bg)
            }
            assertAa("$name muted on surface2", p.muted, p.surface2)
            assertAa("$name text on surface3", p.text, p.surface3)
        }
    }

    @Test
    fun statusAndAccentColorsClearAaOnCardsAndInTheirPills() {
        themes.forEach { (name, p) ->
            val tones = mapOf("volt" to 0.13f, "ev" to 0.13f, "gas" to 0.14f, "bad" to 0.14f, "warn" to 0.15f)
            val colors = mapOf("volt" to p.volt, "ev" to p.ev, "gas" to p.gas, "bad" to p.bad, "warn" to p.warn)
            tones.forEach { (tone, alpha) ->
                val c = colors.getValue(tone)
                assertAa("$name $tone on bg", c, p.bg)
                assertAa("$name $tone on surface", c, p.surface)
                assertAa("$name $tone pill", c, c.copy(alpha = alpha).compositeOver(p.surface))
            }
        }
    }

    @Test
    fun onAccentContentClearsAaOnTheAccent() {
        themes.forEach { (name, p) -> assertAa("$name onVolt on volt", p.onVolt, p.volt) }
    }

    @Test
    fun onlyMonoWhiteIsAMonoAccent() {
        themes.forEach { (name, p) -> assertEquals(name, name == "oled-white", p.monoAccent) }
    }

    @Test
    fun highContrastPromotesFaintAndStrengthensLines() {
        themes.values.forEach { p ->
            val hc = p.highContrast()
            assertEquals(p.muted, hc.faint)
            assertTrue(contrastRatio(hc.muted, p.surface) > contrastRatio(p.muted, p.surface))
            assertTrue(hc.line.alpha > p.line.alpha)
            assertTrue(hc.line2.alpha > p.line2.alpha)
            assertTrue(hc.track.alpha > p.track.alpha)
            // Meaning colors never move.
            assertEquals(p.volt, hc.volt)
            assertEquals(p.ev, hc.ev)
            assertEquals(p.bad, hc.bad)
        }
        assertEquals(VoltPalette.Latte.highContrast(), voltPalette(dark = false, highContrast = true))
        assertFalse(voltPalette(dark = true) == voltPalette(dark = true, highContrast = true))
    }
}
