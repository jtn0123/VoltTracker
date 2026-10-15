package com.volttracker.obdpoc.ui.drive

import com.volttracker.obdpoc.Gen2StatusDecoders

/**
 * What a gen 2 Volt reports about its engine, pumps, charger and body module beyond the core
 * readings (the reads in Gen2Readings). Each field is null while the car isn't reporting it.
 */
data class Gen2Status(
    /** Why the engine last started, "; "-joined; "None" when the car lists no reason. */
    val engineStartReason: String? = null,
    /** Auxiliary transmission fluid pump duty (%, `222C05`). */
    val auxTransPumpPct: Int? = null,
    /** Power electronics coolant pump duty (%, `224357`). */
    val peCoolantPumpPct: Int? = null,
    /** "Off", "Active cooling", "Active heating", "Passive cooling" or "Passive heating". */
    val batteryConditioning: String? = null,
    /** "Active" while the HV pack is topping up the 12 V battery, else "Off". */
    val maintenance12v: String? = null,
    /** "None", "Waiting", "Charging", "Complete" or "Error". */
    val chargeState: String? = null,
    /** "Not ready", "Ready" or "Shutting down". */
    val chargeSystem: String? = null,
    /** The charger's two self-checks: "Passed" or "Failed". */
    val chargeEnableCheck: String? = null,
    val chargeControlCheck: String? = null,
    /** The car's charge timetable, as "Tue 23:05". */
    val chargeStart: String? = null,
    val chargeComplete: String? = null,
    val chargeStart120v: String? = null,
    val chargeComplete120v: String? = null,
    /** How each of the last five charges ended, in the car's order; null where not read. */
    val chargeRecords: List<String?> = emptyList(),
    /** The A/C compressor and cabin heater loads as the hybrid control module reports them (W). */
    val acPowerW: Int? = null,
    val cabinHeatPowerW: Int? = null,
    /** The body module's remote-start countdown (s) and how many remote starts are left. */
    val remoteStartSecondsLeft: Int? = null,
    val remoteStartsLeft: Int? = null,
    /** How the car was last started: "Ignition switch", "Remote start", … */
    val remoteStartReason: String? = null,
    /** Battery coolant loop temperature (°F, `2241A4`). */
    val batteryCoolantF: Int? = null,
    /** The charger's AC input and output duty cycles (%), while charging. */
    val chargerAcDutyPct: Int? = null,
    val chargerOutputDutyPct: Int? = null,
    /** EGR valve command (%) and the two EGR cooler temperatures (°F), from the engine computer. */
    val egrCommandedPct: Int? = null,
    val egrCooler1F: Int? = null,
    val egrCooler2F: Int? = null,
    /** The body module's estimate of the 12 V battery's capacity (Ah). */
    val aux12vCapacityAh: Double? = null,
) {
    /** The first reason the car gives for the engine start; null when it lists none. */
    val firstStartReason: String?
        get() =
            engineStartReason
                ?.substringBefore(';')
                ?.trim()
                ?.takeUnless { it.isEmpty() || it == Gen2StatusDecoders.NO_REASON }
}
