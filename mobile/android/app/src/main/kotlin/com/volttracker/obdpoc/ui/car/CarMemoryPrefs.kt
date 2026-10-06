package com.volttracker.obdpoc.ui.car

import android.content.SharedPreferences
import com.volttracker.obdpoc.ui.drive.TirePressures
import java.util.Locale

/**
 * Stores [CarMemory] (the last tyre pressures and oil life, with when they were read) in the
 * shared prefs file, so the Car tab can show them before the car sends new ones, even after the
 * app restarts. Tyres are kept as psi, comma-joined front left, front right, rear left, rear right.
 */
object CarMemoryPrefs {
    const val PREFIX = "car_memory_"
    private const val TIRES = "${PREFIX}tires_psi"
    private const val TIRES_AT = "${PREFIX}tires_at"
    private const val OIL = "${PREFIX}oil_pct"
    private const val OIL_AT = "${PREFIX}oil_at"
    private const val TIRE_COUNT = 4

    /** Every key this owns, for the shared file's ownership check. */
    val KEYS = listOf(TIRES, TIRES_AT, OIL, OIL_AT)

    fun read(prefs: SharedPreferences): CarMemory {
        val psi =
            prefs
                .getString(TIRES, null)
                ?.split(',')
                ?.mapNotNull { it.trim().toDoubleOrNull() }
                ?.takeIf { it.size == TIRE_COUNT }
        return CarMemory(
            tires = psi?.let { TirePressures(it[0], it[1], it[2], it[3]) },
            tiresAtMs = if (psi != null) prefs.getLong(TIRES_AT, 0L) else 0L,
            oilLifePct = if (prefs.contains(OIL)) prefs.getInt(OIL, 0) else null,
            oilAtMs = prefs.getLong(OIL_AT, 0L),
        )
    }

    /** Writes what [memory] holds, only where it differs from what is stored. */
    fun write(
        prefs: SharedPreferences,
        memory: CarMemory,
    ) {
        val edit = prefs.edit()
        var changed = false
        val tires = memory.tires?.all?.joinToString(",") { String.format(Locale.US, "%.1f", it) }
        if (tires != null &&
            (tires != prefs.getString(TIRES, null) || memory.tiresAtMs != prefs.getLong(TIRES_AT, 0L))
        ) {
            edit.putString(TIRES, tires).putLong(TIRES_AT, memory.tiresAtMs)
            changed = true
        }
        val oil = memory.oilLifePct
        if (oil != null && (oil != prefs.getInt(OIL, -1) || memory.oilAtMs != prefs.getLong(OIL_AT, 0L))) {
            edit.putInt(OIL, oil).putLong(OIL_AT, memory.oilAtMs)
            changed = true
        }
        if (changed) edit.apply()
    }
}
