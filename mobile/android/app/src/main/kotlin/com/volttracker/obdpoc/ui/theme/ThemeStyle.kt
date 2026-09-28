package com.volttracker.obdpoc.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Settings → Appearance → Dark style: which palette a dark [AppearanceMode] renders. [key] is the
 * persisted form (see [AppearancePrefs]); unknown or missing keys read as [OLED], the default.
 */
enum class DarkStyle(
    val key: String,
    val label: String,
) {
    /** Pure black canvas with a user-chosen [OledAccent] (mockups `[data-theme^=oled]`). */
    OLED("oled", "OLED Black"),

    /** Warm brown-black with a tan accent (mockups `[data-theme=saddle]`). */
    SADDLE("saddle", "Saddle Leather"),
    ;

    companion object {
        fun fromKey(key: String?): DarkStyle = entries.firstOrNull { it.key == key } ?: OLED
    }
}

/**
 * The accent of the OLED Black theme (mockups `[data-theme=oled-<accent>]`). [volt] is the
 * accent and [onVolt] the text/icon color on it. [ev] is set only where the accent would read as
 * the EV green: Volt Lime moves EV to mint so the two stay distinct. Red is deliberately not
 * offered — it collides with the fault color. Unknown or missing keys read as [CYAN].
 */
enum class OledAccent(
    val key: String,
    val label: String,
    val volt: Color,
    val onVolt: Color,
    val ev: Color = Color.Unspecified,
) {
    CYAN("cyan", "Cyan", Color(0xFF00E5D1), Color(0xFF00201D)),
    BLUE("blue", "Blue", Color(0xFF3D9BFF), Color(0xFF001226)),
    LIME("lime", "Volt Lime", Color(0xFFB8F040), Color(0xFF1A2600), ev = Color(0xFF3DF0B0)),
    WHITE("white", "Mono White", Color(0xFFFFFFFF), Color(0xFF000000)),
    VIOLET("violet", "Violet", Color(0xFFA78BFF), Color(0xFF140A33)),
    ;

    companion object {
        fun fromKey(key: String?): OledAccent = entries.firstOrNull { it.key == key } ?: CYAN
    }
}
