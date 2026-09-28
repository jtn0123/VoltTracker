package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.settings.SettingsUiState
import com.volttracker.obdpoc.ui.theme.AppearanceMode
import com.volttracker.obdpoc.ui.theme.DarkStyle
import com.volttracker.obdpoc.ui.theme.OledAccent

/**
 * A full Settings → Appearance choice for screenshot matrices; [key] names the PNG
 * (`…-oled.png`, `…-oled-lime.png`, `…-saddle.png`, `…-latte.png`).
 */
enum class ThemeCase(
    val key: String,
    val appearance: AppearanceMode,
    val darkStyle: DarkStyle = DarkStyle.OLED,
    val accent: OledAccent = OledAccent.CYAN,
) {
    OLED("oled", AppearanceMode.DARK),
    OLED_BLUE("oled-blue", AppearanceMode.DARK, accent = OledAccent.BLUE),
    OLED_LIME("oled-lime", AppearanceMode.DARK, accent = OledAccent.LIME),
    OLED_WHITE("oled-white", AppearanceMode.DARK, accent = OledAccent.WHITE),
    OLED_VIOLET("oled-violet", AppearanceMode.DARK, accent = OledAccent.VIOLET),
    SADDLE("saddle", AppearanceMode.DARK, DarkStyle.SADDLE),
    LATTE("latte", AppearanceMode.LIGHT),
    ;

    fun applyTo(settings: SettingsUiState): SettingsUiState =
        settings.copy(appearance = appearance, darkStyle = darkStyle, accent = accent)

    companion object {
        /** One case per shipped theme: OLED Black (Cyan), Saddle Leather, Latte. */
        val THEMES = listOf(OLED, SADDLE, LATTE)

        /** The OLED accents other than the default Cyan. */
        val OTHER_ACCENTS = listOf(OLED_BLUE, OLED_LIME, OLED_WHITE, OLED_VIOLET)
    }
}
