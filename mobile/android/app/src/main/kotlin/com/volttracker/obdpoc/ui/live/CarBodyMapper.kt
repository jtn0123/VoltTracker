package com.volttracker.obdpoc.ui.live

import com.volttracker.obdpoc.ui.car.BodyGroup
import com.volttracker.obdpoc.ui.car.CarControlsUi
import com.volttracker.obdpoc.ui.car.CarUiState
import com.volttracker.obdpoc.ui.car.Openings
import org.json.JSONObject
import java.util.Locale

/**
 * Folds one telemetry sample's SW-CAN body readings (doors, windows, climate, remote start) and
 * the car-control gate into [CarUiState]. The tyres, lock, 12 V and cabin temperature live on the
 * Drive state, which maps them; this only records when each of those groups was last heard.
 *
 * Like the Drive tiles: an absent key keeps the prior value, a broadcast older than
 * [STALE_MS] clears it, and an unknown value reads as not reported rather than a guess.
 */
internal object CarBodyMapper {
    fun map(
        current: CarUiState,
        t: JSONObject,
    ): CarUiState {
        val at = t.optLong("updatedAt", 0L)
        return current.copy(
            openings = openings(t, current.openings),
            windowsPct = windows(t, current.windowsPct),
            acOn = onOff(t, "acState", CLIMATE_STALE, current.acOn),
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
            val (valueKey, staleKey) = keys
            if (!t.has(valueKey) || t.isNull(valueKey)) return@forEach
            val age = number(t, staleKey) ?: 0.0
            if (age <= STALE_MS) next[group] = maxOf(next[group] ?: 0L, at - age.toLong())
        }
        return next
    }

    private fun openings(
        t: JSONObject,
        current: Openings?,
    ): Openings? {
        if (stale(t, "doorStatusStaleMs")) return null
        val states = OPENING_KEYS.map { (key, _) -> t.optString(key, "").lowercase(Locale.US) }
        if (states.all { it.isEmpty() }) return current
        if (states.any { it != OPEN && it != CLOSED }) return null
        return Openings(OPENING_KEYS.filterIndexed { i, _ -> states[i] == OPEN }.map { it.second })
    }

    private fun windows(
        t: JSONObject,
        current: List<Int>?,
    ): List<Int>? {
        if (stale(t, "windowStaleMs")) return null
        val pct = WINDOW_KEYS.map { number(t, it) }
        if (pct.all { it == null }) return current
        if (pct.any { it == null }) return null
        return pct.map { (it ?: 0.0).toInt().coerceIn(0, PERCENT) }
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

    /** FL is the driver's door on a US car. */
    private val OPENING_KEYS =
        listOf(
            "doorFlState" to "Driver door",
            "doorFrState" to "Passenger door",
            "doorRlState" to "Rear left door",
            "doorRrState" to "Rear right door",
            "hoodState" to "Hood",
            "trunkState" to "Hatch",
        )
    private val WINDOW_KEYS = listOf("windowFlPct", "windowFrPct", "windowRlPct", "windowRrPct")
    private val GROUP_KEYS =
        mapOf(
            BodyGroup.TIRES to ("tirePressureFlKpa" to "tirePressureStaleMs"),
            BodyGroup.LOCK to ("doorLockState" to "doorLockStaleMs"),
            BodyGroup.DOORS to ("doorFlState" to "doorStatusStaleMs"),
            BodyGroup.WINDOWS to ("windowFlPct" to "windowStaleMs"),
            BodyGroup.CLIMATE to ("cabinTempEstC" to CLIMATE_STALE),
            BodyGroup.AUX12 to ("aux12vVoltage" to "aux12vStaleMs"),
        )
}
