package com.volttracker.obdpoc

import org.json.JSONException
import org.json.JSONObject
import java.util.EnumMap

/**
 * Last-known SW-CAN broadcast values for the current session.
 *
 * The listen window only runs every ~45 s, so between windows the live sample carries the value
 * last heard plus a per-group `...StaleMs` age. A value not re-heard within [maxAgeMs] is dropped
 * rather than shown as current — event-driven frames (locks, doors, windows) in particular only
 * appear when something changes, so an old "closed" must age out instead of looking live.
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
        held.entries.removeAll { now - it.value.atMs > maxAgeMs }
        if (held.isEmpty()) return
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
        putReading(sample, "heaterCoreTempC", SwcanField.HEATER_CORE_TEMP)
        putReading(sample, "coolantHeaterKw", SwcanField.COOLANT_HEATER_KW)
        putReading(sample, "peCoolantTempC", SwcanField.PE_COOLANT_TEMP)
        putReading(sample, "chargeCurrentLimitA", SwcanField.CHARGE_LIMIT)
        putReading(sample, "clusterEvRangeKm", SwcanField.CLUSTER_EV_RANGE)
        putReading(sample, "fuelRangeKm", SwcanField.FUEL_RANGE)
        putReading(sample, "cycleEnergyUsedKwh", SwcanField.CYCLE_ENERGY_USED)
        putReading(sample, "cycleEvDistanceKm", SwcanField.CYCLE_EV_DISTANCE)
        putReading(sample, "cycleFuelDistanceKm", SwcanField.CYCLE_FUEL_DISTANCE)
        putReading(sample, "cycleFuelUsedL", SwcanField.CYCLE_FUEL_USED)
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
    }
}
