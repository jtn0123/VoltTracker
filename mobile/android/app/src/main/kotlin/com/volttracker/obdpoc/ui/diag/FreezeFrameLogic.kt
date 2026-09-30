package com.volttracker.obdpoc.ui.diag

import com.volttracker.obdpoc.FreezeFrame
import com.volttracker.obdpoc.ui.units.VoltUnits
import java.util.Locale
import kotlin.math.roundToInt

/** The freeze frame a scan read: the code it was captured with, when it was read, and its readings. */
data class FreezeFrameSnapshot(
    val dtc: String,
    val capturedAtMs: Long,
    val readings: List<FreezeFrame.Reading>,
)

/** One Health › Freeze frame row: "Speed" · "45 mph". */
data class FreezeFrameRow(
    val label: String,
    val value: String,
)

/** The snapshot's readings in plain words and the chosen units, in the car's order. */
fun freezeFrameRows(
    snapshot: FreezeFrameSnapshot?,
    units: VoltUnits,
): List<FreezeFrameRow> =
    snapshot?.readings.orEmpty().map {
        FreezeFrameRow(readingLabel(it.name), readingValue(it, units))
    }

private fun readingLabel(name: String): String =
    when (name) {
        "vehicle speed" -> "Speed"
        "engine rpm" -> "Engine RPM"
        "coolant temperature" -> "Coolant"
        "intake air temperature" -> "Intake air"
        "throttle position" -> "Throttle"
        "control module voltage" -> "12 V system"
        "engine run time" -> "Engine run time"
        else -> name.replaceFirstChar { it.titlecase(Locale.US) }
    }

private fun readingValue(
    reading: FreezeFrame.Reading,
    units: VoltUnits,
): String {
    val v = reading.value
    return when (reading.unit) {
        "km/h" -> units.speedText(v * MPH_PER_KPH)
        "deg C" -> units.tempText(v * 9.0 / 5.0 + 32.0)
        "rpm" -> "${v.roundToInt()} rpm"
        "%" -> "${v.roundToInt()}%"
        "V" -> "%.1f V".format(Locale.US, v)
        "s" -> runTime(v.roundToInt())
        else -> "%.1f %s".format(Locale.US, v, reading.unit).trim()
    }
}

private fun runTime(seconds: Int): String = if (seconds < 60) "$seconds s" else "${seconds / 60} min ${seconds % 60} s"

/** The note under the readings: what they are, or why there aren't any. */
fun DiagUiState.freezeFrameNote(): String =
    when {
        freezeFrame?.readings?.isNotEmpty() == true ->
            "What the car was doing the moment it set ${freezeFrameDtc()}. Clearing codes erases it."
        else ->
            "The car saved a snapshot with this code, but its readings haven't been read yet. " +
                "Run Scan now with the car on."
    }

private const val MPH_PER_KPH = 0.621371
