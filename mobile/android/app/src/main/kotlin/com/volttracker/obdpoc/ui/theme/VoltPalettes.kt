package com.volttracker.obdpoc.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified

/*
 * The approved theme token sets (mockups `src/themes.css` / `src/themes2.css`), one VoltPalette
 * each. Every CSS `--token` maps 1:1 onto the VoltPalette field of the same name; `rgba()`
 * hairlines keep their tint and alpha. `--shadow` is [VoltPalette.cardShadow]: none on the dark
 * themes, the warm two-layer card shadow on Latte.
 */

/** OLED Black (`[data-theme^=oled]`) with the default Cyan accent. */
internal val OledCyanPalette =
    VoltPalette(
        isDark = true,
        bg = Color(0xFF000000),
        bgGlow = Color(0xFF050505),
        surface = Color(0xFF0E0F11),
        surface2 = Color(0xFF16181B),
        surface3 = Color(0xFF1E2024),
        line = Color.White.copy(alpha = 0.07f),
        line2 = Color.White.copy(alpha = 0.12f),
        track = Color.White.copy(alpha = 0.08f),
        text = Color(0xFFF4F6F8),
        muted = Color(0xFF9A9FA6),
        faint = Color(0xFF6A7078),
        volt = OledAccent.CYAN.volt,
        onVolt = OledAccent.CYAN.onVolt,
        ev = Color(0xFF3DF08A),
        gas = Color(0xFFFFA53D),
        warn = Color(0xFFFFD23F),
        bad = Color(0xFFFF5A5F),
        carBody = Color(0xFF17191C),
        carLine = Color.White.copy(alpha = 0.16f),
        carGlass = Color(0xFF060708),
        mapLand = Color(0xFF0A0B0C),
        mapRoad = Color(0xFF1A1C1F),
        mapWater = Color(0xFF061318),
        cardShadow = Color.Transparent,
    )

/** Every OLED accent variant, built once so the theme hands out stable instances. */
private val OledByAccent: Map<OledAccent, VoltPalette> =
    OledAccent.entries.associateWith { accent ->
        if (accent == OledAccent.CYAN) return@associateWith OledCyanPalette
        OledCyanPalette.copy(
            volt = accent.volt,
            onVolt = accent.onVolt,
            ev = if (accent.ev.isSpecified) accent.ev else OledCyanPalette.ev,
        )
    }

/** The OLED Black palette in [accent]. */
internal fun oledPalette(accent: OledAccent): VoltPalette = OledByAccent.getValue(accent)

private val leather = Color(0xFFFFEBD2)

/** Saddle Leather (`[data-theme=saddle]`): warm brown-black, tan accent. */
internal val SaddlePalette =
    VoltPalette(
        isDark = true,
        bg = Color(0xFF0C0A08),
        bgGlow = Color(0xFF12100C),
        surface = Color(0xFF18140F),
        surface2 = Color(0xFF201B15),
        surface3 = Color(0xFF29231B),
        line = leather.copy(alpha = 0.07f),
        line2 = leather.copy(alpha = 0.12f),
        track = leather.copy(alpha = 0.08f),
        text = Color(0xFFF2ECE3),
        muted = Color(0xFFAA9E8E),
        faint = Color(0xFF7C715F),
        volt = Color(0xFFC8955A),
        onVolt = Color(0xFF1F1206),
        ev = Color(0xFF7ED492),
        gas = Color(0xFFFF7A45),
        warn = Color(0xFFEFC75E),
        bad = Color(0xFFFF5F57),
        carBody = Color(0xFF231D16),
        carLine = leather.copy(alpha = 0.16f),
        carGlass = Color(0xFF0A0806),
        mapLand = Color(0xFF14110D),
        mapRoad = Color(0xFF241E17),
        mapWater = Color(0xFF0D1517),
        cardShadow = Color.Transparent,
    )

private val coffee = Color(0xFF462D14)

/** Latte (`[data-theme=latte]`): the light theme — cream canvas, coffee-brown accent. */
internal val LattePalette =
    VoltPalette(
        isDark = false,
        bg = Color(0xFFEFE6DA),
        bgGlow = Color(0xFFF7F0E6),
        surface = Color(0xFFFBF6EF),
        surface2 = Color(0xFFE9DDCD),
        surface3 = Color(0xFFDFD0BC),
        line = coffee.copy(alpha = 0.09f),
        line2 = coffee.copy(alpha = 0.15f),
        track = coffee.copy(alpha = 0.09f),
        text = Color(0xFF2A1E14),
        muted = Color(0xFF6E5D4C),
        faint = Color(0xFF9A8671),
        volt = Color(0xFF8A5A2B),
        onVolt = Color(0xFFFFFFFF),
        ev = Color(0xFF2F8A4A),
        gas = Color(0xFFD0561A),
        warn = Color(0xFFA87B00),
        bad = Color(0xFFC0392B),
        carBody = Color(0xFFE6D9C7),
        carLine = coffee.copy(alpha = 0.22f),
        carGlass = Color(0xFFCDBBA3),
        mapLand = Color(0xFFE8DDCE),
        mapRoad = Color(0xFFFBF6EF),
        mapWater = Color(0xFFC9DADA),
        // `0 8px 24px rgba(70,45,20,.08)` rendered through a 6dp elevation's ambient + spot.
        cardShadow = coffee.copy(alpha = 0.2f),
    )
