package com.volttracker.obdpoc.ui.live

import com.volttracker.obdpoc.ui.car.BodyGroup
import com.volttracker.obdpoc.ui.car.CarControlsUi
import com.volttracker.obdpoc.ui.car.CarMemory
import com.volttracker.obdpoc.ui.car.CarUiState
import com.volttracker.obdpoc.ui.car.Opening
import com.volttracker.obdpoc.ui.car.Openings
import com.volttracker.obdpoc.ui.drive.TirePressures
import com.volttracker.obdpoc.ui.units.VoltUnits
import org.json.JSONObject
import java.util.Locale

/**
 * Folds one telemetry sample's SW-CAN body readings (doors, windows, climate, dash warnings,
 * remote start) and the car-control gate into [CarUiState]. The tyres, lock, 12 V, cabin
 * temperature and oil life live on the Drive state, which maps them; this records when each of
 * those groups was last heard, and remembers the tyres and oil life between drives ([CarMemory]).
 *
 * Like the Drive tiles: an absent key keeps the prior value and an unknown value reads as not
 * reported rather than a guess. Climate goes blank once its broadcast is older than [STALE_MS].
 * Doors, windows and dash warnings don't: the car only sends them when they change, so the last
 * report stays true until the next one, and the Car tab says how old it is.
 */
internal object CarBodyMapper {
    fun map(
        current: CarUiState,
        t: JSONObject,
    ): CarUiState {
        val at = t.optLong("updatedAt", 0L)
        val climateStale = stale(t, CLIMATE_STALE)
        return current.copy(
            openings = openings(t, current.openings),
            windowsPct = windows(t, current.windowsPct),
            acOn = onOff(t, "acState", CLIMATE_STALE, current.acOn),
            fanPct = if (climateStale) null else number(t, "blowerPct")?.toInt() ?: current.fanPct,
            acKw = if (climateStale) null else number(t, "acCompressorKw") ?: current.acKw,
            dashWarnings = dashWarnings(t, current.dashWarnings),
            remoteStartOn = onOff(t, "remoteStartState", null, current.remoteStartOn),
            outsideTempC =
                if (stale(t, "outsideTempStaleMs")) {
                    null
                } else {
                    number(t, "outsideTempC")
                        ?: current.outsideTempC
                },
            seenAtMs = seen(t, at, current.seenAtMs),
            nowMs = if (at > 0) at else current.nowMs,
            memory = if (t.optString("source", "") == DEMO_SOURCE) current.memory else remember(t, at, current.memory),
            controls = controls(t, current.controls),
        )
    }

    /** The host's `{enabled, pinLockedOut}` car-control state (`CarControlCommands.getCarControlStateJson`). */
    fun nativeState(
        current: CarControlsUi,
        json: JSONObject,
    ): CarControlsUi =
        current.copy(
            enabled = json.optBoolean("available") && json.optBoolean("enabled"),
            pinLockedOut = json.optBoolean("pinLockedOut"),
        )

    /** When each group was last fresh: the sample time less the broadcast's age. */
    private fun seen(
        t: JSONObject,
        at: Long,
        current: Map<BodyGroup, Long>,
    ): Map<BodyGroup, Long> {
        if (at <= 0) return current
        val next = current.toMutableMap()
        GROUP_KEYS.forEach { (group, keys) ->
            val (valueKeys, staleKey) = keys
            if (valueKeys.none { t.has(it) && !t.isNull(it) }) return@forEach
            val age = number(t, staleKey) ?: 0.0
            next[group] = maxOf(next[group] ?: 0L, at - age.toLong())
        }
        return next
    }

    /**
     * A real car's tyres and oil life, kept with when they were read: the demo's never are. A
     * reading's time is the sample's less its broadcast age, so a held one keeps its first time.
     */
    private fun remember(
        t: JSONObject,
        at: Long,
        current: CarMemory,
    ): CarMemory {
        if (at <= 0) return current
        val kpa = TIRE_KEYS.map { number(t, it) }
        val tiresAt = at - (number(t, "tirePressureStaleMs") ?: 0.0).toLong()
        val oil = number(t, "oilLifeRemainingPct") ?: number(t, "engineOilLifePct")
        val oilAt = at - (number(t, "oilLifeStaleMs") ?: 0.0).toLong()
        return current.copy(
            tires =
                if (kpa.all { it != null }) {
                    val psi = kpa.map { (it ?: 0.0) * VoltUnits.PSI_PER_KPA }
                    TirePressures(psi[0], psi[1], psi[2], psi[3])
                } else {
                    current.tires
                },
            tiresAtMs = if (kpa.all { it != null }) tiresAt else current.tiresAtMs,
            oilLifePct = oil?.toInt() ?: current.oilLifePct,
            oilAtMs = if (oil != null) oilAt else current.oilAtMs,
        )
    }

    /** Each opening the sample reports replaces what was known of it; the rest stay as they were. */
    private fun openings(
        t: JSONObject,
        current: Openings?,
    ): Openings? {
        val states = current?.states.orEmpty().toMutableMap()
        var reported = false
        OPENING_KEYS.forEach { (opening, key) ->
            when (t.optString(key, "").lowercase(Locale.US)) {
                "" -> return@forEach
                OPEN -> states[opening] = true
                CLOSED -> states[opening] = false
                else -> states.remove(opening)
            }
            reported = true
        }
        if (!reported) return current
        return if (states.isEmpty()) null else Openings(states)
    }

    /** Each window the sample reports replaces what was known of it; the rest stay as they were. */
    private fun windows(
        t: JSONObject,
        current: List<Int?>?,
    ): List<Int?>? {
        val pct = WINDOW_KEYS.map { number(t, it) }
        if (pct.all { it == null }) return current
        val known = current ?: WINDOW_KEYS.map { null }
        return pct.mapIndexed { i, value -> value?.toInt()?.coerceIn(0, PERCENT) ?: known[i] }
    }

    /** `dashWarnings`: comma-joined codes of the lights on, "" for none. */
    private fun dashWarnings(
        t: JSONObject,
        current: List<String>?,
    ): List<String>? {
        if (!t.has(WARNINGS_KEY) || t.isNull(WARNINGS_KEY)) return current
        return t
            .optString(WARNINGS_KEY, "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun onOff(
        t: JSONObject,
        key: String,
        staleKey: String?,
        current: Boolean?,
    ): Boolean? {
        if (staleKey != null && stale(t, staleKey)) return null
        return when (t.optString(key, "").lowercase(Locale.US)) {
            "on" -> true
            "off" -> false
            "" -> current
            else -> null
        }
    }

    /** The engine reports the gate only while controls are on; without it the gate is unknown. */
    private fun controls(
        t: JSONObject,
        current: CarControlsUi,
    ): CarControlsUi =
        current.copy(
            gate = text(t, "carControlGate"),
            gateDetail = text(t, "carControlGateDetail"),
            lastCommand = text(t, "carControlLastCommand") ?: current.lastCommand,
            lastOutcome = text(t, "carControlLastOutcome") ?: current.lastOutcome,
            lastDetail = if (t.has("carControlLastOutcome")) text(t, "carControlLastDetail") else current.lastDetail,
        )

    private fun stale(
        t: JSONObject,
        staleKey: String,
    ): Boolean = (number(t, staleKey) ?: 0.0) > STALE_MS

    private fun text(
        t: JSONObject,
        key: String,
    ): String? = if (t.isNull(key)) null else t.optString(key, "").ifBlank { null }

    private fun number(
        t: JSONObject,
        key: String,
    ): Double? = if (t.has(key) && !t.isNull(key)) t.optDouble(key).takeIf { it.isFinite() } else null

    /** Mirrors [LiveUiStateStore]'s broadcast staleness. */
    private const val STALE_MS = 120_000.0
    private const val PERCENT = 100
    private const val OPEN = "open"
    private const val CLOSED = "closed"
    private const val CLIMATE_STALE = "climateStaleMs"
    private const val WARNINGS_KEY = "dashWarnings"
    private const val DEMO_SOURCE = "demo"

    private val OPENING_KEYS =
        listOf(
            Opening.DRIVER_DOOR to "doorFlState",
            Opening.PASSENGER_DOOR to "doorFrState",
            Opening.REAR_LEFT_DOOR to "doorRlState",
            Opening.REAR_RIGHT_DOOR to "doorRrState",
            Opening.HOOD to "hoodState",
            Opening.HATCH to "trunkState",
        )
    private val WINDOW_KEYS = listOf("windowFlPct", "windowFrPct", "windowRlPct", "windowRrPct")

    /** FL, FR, RL, RR, the order [TirePressures] takes them. */
    private val TIRE_KEYS = listOf("tirePressureFlKpa", "tirePressureFrKpa", "tirePressureRlKpa", "tirePressureRrKpa")

    /** Each group's value keys (any one present counts) and its broadcast-age key. */
    private val GROUP_KEYS =
        mapOf(
            BodyGroup.TIRES to (TIRE_KEYS to "tirePressureStaleMs"),
            BodyGroup.LOCK to (listOf("doorLockState") to "doorLockStaleMs"),
            BodyGroup.DOORS to (OPENING_KEYS.map { it.second } to "doorStatusStaleMs"),
            BodyGroup.WINDOWS to (WINDOW_KEYS to "windowStaleMs"),
            BodyGroup.CLIMATE to (listOf("acState", "blowerPct", "cabinTempEstC") to CLIMATE_STALE),
            BodyGroup.AUX12 to (listOf("aux12vVoltage") to "aux12vStaleMs"),
            BodyGroup.OIL to (listOf("oilLifeRemainingPct") to "oilLifeStaleMs"),
            BodyGroup.WARNINGS to (listOf(WARNINGS_KEY) to "dashWarningStaleMs"),
        )
}
