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
        putReading(sample, "doorFlState", SwcanField.DOOR_FL)
        putReading(sample, "doorFrState", SwcanField.DOOR_FR)
        putReading(sample, "doorRlState", SwcanField.DOOR_RL)
        putReading(sample, "doorRrState", SwcanField.DOOR_RR)
        putReading(sample, "hoodState", SwcanField.HOOD)
        putReading(sample, "trunkState", SwcanField.TRUNK)
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
        putDashWarnings(sample, "dashWarnings")
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
        putGroupStaleMs(sample, "dashWarningStaleMs", SwcanGroup.WARNINGS, now)
    }

    /**
     * The codes of every dash light the warning broadcasts say is on, comma-joined, "" once at
     * least one of them has reported and none is lit; absent before any has.
     */
    private fun putDashWarnings(
        sample: JSONObject,
        key: String,
    ) {
        val reports = WARNING_FIELDS.mapNotNull { held[it]?.value as? String }
        if (reports.isEmpty()) return
        sample.put(key, reports.filter { it.isNotEmpty() }.joinToString(","))
    }

    private fun putReading(
        sample: JSONObject,
        key: String,
        field: SwcanField,
    ) {
        val value = held[field]?.value ?: return
        sample.put(key, value)
    }

    /** Age of the freshest value in [group]; omitted when the group has no value. */
    private fun putGroupStaleMs(
        sample: JSONObject,
        key: String,
        group: SwcanGroup,
        now: Long,
    ) {
        val newest = held.filterKeys { it.group == group }.values.maxOfOrNull { it.atMs } ?: return
        sample.put(key, maxOf(0L, now - newest))
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
    }
}
