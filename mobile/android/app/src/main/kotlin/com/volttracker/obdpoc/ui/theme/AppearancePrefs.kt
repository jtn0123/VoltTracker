package com.volttracker.obdpoc.ui.theme

import android.content.SharedPreferences
import androidx.core.content.edit

/** Persists the Settings → Appearance choice in the shared app prefs file (`ui_` namespace). */
object AppearancePrefs {
    const val PREFIX = "ui_"
    const val KEY_APPEARANCE = "${PREFIX}appearance"

    fun read(prefs: SharedPreferences): AppearanceMode = AppearanceMode.fromKey(prefs.getString(KEY_APPEARANCE, null))

    fun write(
        prefs: SharedPreferences,
        mode: AppearanceMode,
    ) {
        prefs.edit { putString(KEY_APPEARANCE, mode.key) }
    }
}
