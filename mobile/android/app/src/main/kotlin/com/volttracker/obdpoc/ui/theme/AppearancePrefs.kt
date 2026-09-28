package com.volttracker.obdpoc.ui.theme

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persists the Compose display choices in the shared app prefs file (`ui_` namespace): the
 * Settings → Appearance theme, the Drive view (Focus / Detailed) and the energy-flow card.
 */
object AppearancePrefs {
    const val PREFIX = "ui_"
    const val KEY_APPEARANCE = "${PREFIX}appearance"
    const val KEY_DRIVE_DETAILED = "${PREFIX}drive_detailed"
    const val KEY_DRIVE_ENERGY_FLOW = "${PREFIX}drive_energy_flow"

    fun read(prefs: SharedPreferences): AppearanceMode = AppearanceMode.fromKey(prefs.getString(KEY_APPEARANCE, null))

    fun write(
        prefs: SharedPreferences,
        mode: AppearanceMode,
    ) {
        prefs.edit { putString(KEY_APPEARANCE, mode.key) }
    }

    /** Drive opens on Focus (the Arc ring) unless the user switched to Detailed. */
    fun readDriveDetailed(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_DRIVE_DETAILED, false)

    fun writeDriveDetailed(
        prefs: SharedPreferences,
        detailed: Boolean,
    ) {
        prefs.edit { putBoolean(KEY_DRIVE_DETAILED, detailed) }
    }

    /** The optional energy-flow card on Drive's Focus view; off (Direction A as drawn) until turned on. */
    fun readDriveEnergyFlow(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_DRIVE_ENERGY_FLOW, false)

    fun writeDriveEnergyFlow(
        prefs: SharedPreferences,
        show: Boolean,
    ) {
        prefs.edit { putBoolean(KEY_DRIVE_ENERGY_FLOW, show) }
    }
}
