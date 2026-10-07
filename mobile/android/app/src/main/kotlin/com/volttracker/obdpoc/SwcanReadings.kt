package com.volttracker.obdpoc

import org.json.JSONException
import org.json.JSONObject
import java.util.EnumMap

/**
 * Last-known SW-CAN broadcast values for the current session.
 *
 * The listen window only runs every ~45 s, so between windows the live sample carries the value
 * last heard plus a per-group `...StaleMs` age. A value not re-heard within [maxAgeMs] is dropped
 * rather than shown as current: a lock command, say, is a one-off event, so an old "locked" ages
 * out instead of looking live.
 *
 * Some groups are the exception and hold for the session ([HELD_GROUPS]). The car sends tires and
 * the energy split rarely: tires once, at the start of a 21-minute drive on 2026-10-04, and never
 * again in 21 later windows; the energy split in 3 of 22 windows. A pressure barely moves within a
 * drive, and the energy counts only creep. Doors, windows and the washer and bulb warnings are only
 * sent when they change, so the last report stays the car's state until the next one. Their
 * `...StaleMs` age still goes out, so the screen can say how old the reading is. An "open" door,
 * hood or hatch is the exception to the exception: it reads "unknown" after [OPEN_TRUST_MS], since
 * outside the Car tab the windows usually miss the close (2026-10-06: a rear door heard opening,
 * never closing, would have read open for the rest of the drive).
 *
 * A held group's age is its OLDEST member's: "doors closed, as of 2 h ago" must not borrow the age
 * of one door heard a minute ago. A field the car flagged not valid ([SwcanReading.INVALID]) drops
 * its old value, and the sample says so, so a screen holding the old one drops it too: a door, hood
 * or hatch reads "unknown", and `tireSensorsInvalid` and `windowsInvalid` list the tyres and windows
 * (`fl,rr`). The dash-warning
 * summary only says "none" for the broadcasts actually heard, so `dashWarningsComplete` says
 * whether all of them have been.
 *
 * All values are UNCONFIRMED-ON-CAR decodes (see [SwcanFrameDecoder]). Only touched on the
 * polling thread.
 */
class SwcanReadings(
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
) {
    private class Held(
        val value: Any,
        val atMs: Long,
    )

    private val held = EnumMap<SwcanField, Held>(SwcanField::class.java)

    fun clear() {
        held.clear()
    }

    fun size(): Int = held.size

    /** All four tyre pressures are held and valid: the tyre hunt can stop. */
    fun hasAllTires(): Boolean = TIRE_CODES.keys.all { held[it]?.value is Double }

    fun record(
        readings: List<SwcanReading>,
        atMs: Long,
    ) {
        for (reading in readings) {
            held[reading.field] = Held(reading.value, atMs)
        }
    }

    /** Writes every still-fresh value and its group age into [sample]. */
    @Throws(JSONException::class)
    fun appendTo(
        sample: JSONObject,
        now: Long,
    ) {
        held.entries.removeAll { it.key.group !in HELD_GROUPS && now - it.value.atMs > maxAgeMs }
        if (held.isEmpty()) return
        for (entry in held.entries) {
            val reading = entry.value
            if (entry.key.group == SwcanGroup.DOORS && reading.value == OPEN && now - reading.atMs > OPEN_TRUST_MS) {
                entry.setValue(Held(UNKNOWN, reading.atMs))
            }
        }
        putReading(sample, "aux12vVoltage", SwcanField.AUX12V_VOLTAGE)
        putReading(sample, "aux12vSocPct", SwcanField.AUX12V_SOC)
        putReading(sample, "aux12vCurrentA", SwcanField.AUX12V_CURRENT)
        putReading(sample, "tirePressureFlKpa", SwcanField.TIRE_FL)
        putReading(sample, "tirePressureFrKpa", SwcanField.TIRE_FR)
        putReading(sample, "tirePressureRlKpa", SwcanField.TIRE_RL)
        putReading(sample, "tirePressureRrKpa", SwcanField.TIRE_RR)
        putReading(sample, "doorLockState", SwcanField.LOCK_STATE)
        putReading(sample, "doorLockSource", SwcanField.LOCK_SOURCE)
        putReading(sample, "doorFlState", SwcanField.DOOR_FL, invalidAs = UNKNOWN)
        putReading(sample, "doorFrState", SwcanField.DOOR_FR, invalidAs = UNKNOWN)
        putReading(sample, "doorRlState", SwcanField.DOOR_RL, invalidAs = UNKNOWN)
        putReading(sample, "doorRrState", SwcanField.DOOR_RR, invalidAs = UNKNOWN)
        putReading(sample, "hoodState", SwcanField.HOOD, invalidAs = UNKNOWN)
        putReading(sample, "trunkState", SwcanField.TRUNK, invalidAs = UNKNOWN)
        putReading(sample, "alarmState", SwcanField.ALARM)
        putReading(sample, "windowFlPct", SwcanField.WINDOW_FL)
        putReading(sample, "windowFrPct", SwcanField.WINDOW_FR)
        putReading(sample, "windowRlPct", SwcanField.WINDOW_RL)
        putReading(sample, "windowRrPct", SwcanField.WINDOW_RR)
        putReading(sample, "cabinTempEstC", SwcanField.CABIN_TEMP)
        putReading(sample, "blowerPct", SwcanField.BLOWER)
        putReading(sample, "acState", SwcanField.AC_STATE)
        putReading(sample, "acCompressorRpm", SwcanField.AC_COMPRESSOR_RPM)
        putReading(sample, "acEvapTempC", SwcanField.AC_EVAP_TEMP)
        putReading(sample, "acCompressorKw", SwcanField.AC_COMPRESSOR_KW)
        putReading(sample, "heaterCoreTempC", SwcanField.HEATER_CORE_TEMP)
        putReading(sample, "coolantHeaterKw", SwcanField.COOLANT_HEATER_KW)
        putReading(sample, "remoteStartState", SwcanField.REMOTE_START)
        putReading(sample, "peCoolantTempC", SwcanField.PE_COOLANT_TEMP)
        putReading(sample, "chargeCurrentLimitA", SwcanField.CHARGE_LIMIT)
        putReading(sample, "clusterEvRangeKm", SwcanField.CLUSTER_EV_RANGE)
        putReading(sample, "fuelRangeKm", SwcanField.FUEL_RANGE)
        putReading(sample, "cycleEnergyUsedKwh", SwcanField.CYCLE_ENERGY_USED)
        putReading(sample, "cycleEvDistanceKm", SwcanField.CYCLE_EV_DISTANCE)
        putReading(sample, "cycleFuelDistanceKm", SwcanField.CYCLE_FUEL_DISTANCE)
        putReading(sample, "cycleFuelUsedL", SwcanField.CYCLE_FUEL_USED)
        putReading(sample, "cycleDrivingKwh", SwcanField.CYCLE_DRIVING_ENERGY)
        putReading(sample, "cycleClimateKwh", SwcanField.CYCLE_CLIMATE_ENERGY)
        putReading(sample, "cycleConditioningKwh", SwcanField.CYCLE_CONDITIONING_ENERGY)
        putReading(sample, "batteryEnergyLeftKwh", SwcanField.BATTERY_ENERGY_LEFT)
        putReading(sample, "wheelSpeedFlKph", SwcanField.WHEEL_FL)
        putReading(sample, "wheelSpeedFrKph", SwcanField.WHEEL_FR)
        putReading(sample, "wheelSpeedRlKph", SwcanField.WHEEL_RL)
        putReading(sample, "wheelSpeedRrKph", SwcanField.WHEEL_RR)
        putReading(sample, "tripAKm", SwcanField.TRIP_A)
        putReading(sample, "tripBKm", SwcanField.TRIP_B)
        putReading(sample, "transOilTempC", SwcanField.TRANS_OIL_TEMP)
        putReading(sample, "oilLifeRemainingPct", SwcanField.OIL_LIFE)
        putReading(sample, "powerMode", SwcanField.POWER_MODE)
        putReading(sample, "seatHeatFlLevel", SwcanField.SEAT_HEAT_FL)
        putReading(sample, "seatHeatFrLevel", SwcanField.SEAT_HEAT_FR)
        putReading(sample, "seatHeatRlLevel", SwcanField.SEAT_HEAT_RL)
        putReading(sample, "seatHeatRrLevel", SwcanField.SEAT_HEAT_RR)
        putReading(sample, "chargePortDoor", SwcanField.CHARGE_PORT_DOOR)
        putReading(sample, "refuelState", SwcanField.REFUEL_STATE)
        putInvalid(sample, "tireSensorsInvalid", TIRE_CODES)
        putInvalid(sample, "windowsInvalid", WINDOW_CODES)
        putDashWarnings(sample, "dashWarnings")
        putDashWarningsComplete(sample, "dashWarningsComplete")
        putGroupStaleMs(sample, "aux12vStaleMs", SwcanGroup.AUX_12V, now)
        putGroupStaleMs(sample, "tirePressureStaleMs", SwcanGroup.TIRES, now)
        putGroupStaleMs(sample, "doorLockStaleMs", SwcanGroup.LOCKS, now)
        putGroupStaleMs(sample, "doorStatusStaleMs", SwcanGroup.DOORS, now)
        putGroupStaleMs(sample, "alarmStaleMs", SwcanGroup.ALARM, now)
        putGroupStaleMs(sample, "windowStaleMs", SwcanGroup.WINDOWS, now)
        putGroupStaleMs(sample, "climateStaleMs", SwcanGroup.CLIMATE, now)
        putGroupStaleMs(sample, "peCoolantStaleMs", SwcanGroup.POWER_ELECTRONICS, now)
        putGroupStaleMs(sample, "chargeLimitStaleMs", SwcanGroup.CHARGE_LIMIT, now)
        putGroupStaleMs(sample, "rangeStaleMs", SwcanGroup.RANGE, now)
        putGroupStaleMs(sample, "driveCycleStaleMs", SwcanGroup.DRIVE_CYCLE, now)
        putGroupStaleMs(sample, "energySplitStaleMs", SwcanGroup.ENERGY, now)
        putGroupStaleMs(sample, "wheelSpeedStaleMs", SwcanGroup.WHEELS, now)
        putGroupStaleMs(sample, "tripOdometerStaleMs", SwcanGroup.TRIPS, now)
        putGroupStaleMs(sample, "transOilStaleMs", SwcanGroup.DRIVETRAIN, now)
        putGroupStaleMs(sample, "oilLifeStaleMs", SwcanGroup.MAINTENANCE, now)
        putGroupStaleMs(sample, "powerModeStaleMs", SwcanGroup.POWER_MODE, now)
        putGroupStaleMs(sample, "seatHeatStaleMs", SwcanGroup.SEAT_HEAT, now)
        putGroupStaleMs(sample, "portDoorsStaleMs", SwcanGroup.PORT_DOORS, now)
        putGroupStaleMs(sample, "dashWarningStaleMs", SwcanGroup.WARNINGS, now)
    }

    /**
     * The codes of every dash light the warning broadcasts say is on, comma-joined, "" once at
     * least one of them has reported and none is lit; absent before any has. [completeKey] is true
     * once every warning broadcast has reported, so "none" covers every light the app can read.
     */
    private fun putDashWarnings(
        sample: JSONObject,
        key: String,
    ) {
        val reports = warningReports()
        if (reports.isEmpty()) return
        sample.put(key, reports.filter { it.isNotEmpty() }.joinToString(","))
    }

    /** Whether every warning broadcast has reported, so an empty list means none are on. */
    private fun putDashWarningsComplete(
        sample: JSONObject,
        key: String,
    ) {
        val reports = warningReports()
        if (reports.isEmpty()) return
        sample.put(key, reports.size == WARNING_FIELDS.size)
    }

    private fun warningReports(): List<String> = WARNING_FIELDS.mapNotNull { held[it]?.value as? String }

    /** Which of [codes]' fields the car last flagged not valid, as `fl,rr`; absent when none is. */
    private fun putInvalid(
        sample: JSONObject,
        key: String,
        codes: Map<SwcanField, String>,
    ) {
        val invalid = codes.filterKeys { held[it]?.value === SwcanReading.INVALID }.values
        if (invalid.isEmpty()) return
        sample.put(key, invalid.joinToString(","))
    }

    /** [field]'s value under [key]; when the car flagged it not valid, [invalidAs], or nothing. */
    private fun putReading(
        sample: JSONObject,
        key: String,
        field: SwcanField,
        invalidAs: Any? = null,
    ) {
        val value = held[field]?.value ?: return
        if (value !== SwcanReading.INVALID) {
            sample.put(key, value)
        } else if (invalidAs != null) {
            sample.put(key, invalidAs)
        }
    }

    /**
     * Age of [group]'s readings; omitted when the group has none. A held group reports its oldest
     * member, since its summary ("all closed") rests on every one of them; the others, which all
     * age out within [maxAgeMs], report their freshest.
     */
    private fun putGroupStaleMs(
        sample: JSONObject,
        key: String,
        group: SwcanGroup,
        now: Long,
    ) {
        val times = held.filterKeys { it.group == group }.values.map { it.atMs }
        val at = (if (group in HELD_GROUPS) times.minOrNull() else times.maxOrNull()) ?: return
        sample.put(key, maxOf(0L, now - at))
    }

    companion object {
        /** Four listen intervals: survives one or two empty windows, then blanks. */
        const val DEFAULT_MAX_AGE_MS = 180_000L

        /** How long an "open" door, hood or hatch is believed without hearing it close. */
        const val OPEN_TRUST_MS = 120_000L
        private const val OPEN = "open"
        private const val UNKNOWN = "unknown"

        /** Groups the car sends too rarely to age out; they hold until the session's [clear]. */
        private val HELD_GROUPS =
            setOf(SwcanGroup.TIRES, SwcanGroup.ENERGY, SwcanGroup.DOORS, SwcanGroup.WINDOWS, SwcanGroup.WARNINGS)

        private val WARNING_FIELDS = SwcanField.entries.filter { it.group == SwcanGroup.WARNINGS }

        private val TIRE_CODES =
            mapOf(
                SwcanField.TIRE_FL to "fl",
                SwcanField.TIRE_FR to "fr",
                SwcanField.TIRE_RL to "rl",
                SwcanField.TIRE_RR to "rr",
            )
        private val WINDOW_CODES =
            mapOf(
                SwcanField.WINDOW_FL to "fl",
                SwcanField.WINDOW_FR to "fr",
                SwcanField.WINDOW_RL to "rl",
                SwcanField.WINDOW_RR to "rr",
            )
    }
}
