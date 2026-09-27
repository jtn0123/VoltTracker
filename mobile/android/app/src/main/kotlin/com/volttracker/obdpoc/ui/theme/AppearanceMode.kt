package com.volttracker.obdpoc.ui.theme

/**
 * Settings → Appearance. [SYSTEM] follows Android's dark-theme setting; the others pin a theme.
 * [key] is the persisted form (see [AppearancePrefs]); unknown or missing keys read as [SYSTEM].
 */
enum class AppearanceMode(
    val key: String,
    val label: String,
) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    ;

    companion object {
        fun fromKey(key: String?): AppearanceMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}
