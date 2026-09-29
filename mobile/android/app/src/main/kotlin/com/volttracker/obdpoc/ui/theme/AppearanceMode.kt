package com.volttracker.obdpoc.ui.theme

/**
 * Settings → Appearance. [SYSTEM] follows Android's dark-theme setting; the others pin a theme.
 * [key] is the persisted form (see [AppearancePrefs]); unknown or missing keys read as [SYSTEM].
 */
enum class AppearanceMode(
    val key: String,
    val label: String,
    /** The Settings line under "Mode" while this choice is selected. */
    val hint: String,
) {
    SYSTEM("system", "System", "Follows your phone's dark theme"),
    DARK("dark", "Dark", "Always dark, whatever your phone is set to"),
    LIGHT("light", "Light", "Always light (Latte), whatever your phone is set to"),
    ;

    companion object {
        fun fromKey(key: String?): AppearanceMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}
