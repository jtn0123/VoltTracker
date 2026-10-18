package com.volttracker.obdpoc.ui.live

import com.volttracker.obdpoc.ui.drive.Gen2Status
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Reads the gen 2 status keys (Gen2Readings) out of one telemetry sample. Each sample carries a
 * status for as long as its read is fresh, so a missing key means the car stopped reporting it and
 * the field clears rather than holding an old "Charging".
 */
internal object Gen2StatusMapper {
    private const val CHARGE_RECORDS = 5

    fun map(t: JSONObject): Gen2Status =
        Gen2Status(
            engineStartReason = text(t, "engineStartReason"),
            auxTransPumpPct = whole(t, "auxTransPumpPct"),
            peCoolantPumpPct = whole(t, "peCoolantPumpPct"),
            batteryConditioning = text(t, "batteryConditioning"),
            maintenance12v = text(t, "aux12vMaintenanceCharge"),
            chargeState = text(t, "chargeState"),
            chargeSystem = text(t, "chargeSystemStatus"),
            chargeEnableCheck = text(t, "chargeEnableCheck"),
            chargeControlCheck = text(t, "chargeControlCheck"),
            chargeStart = text(t, "chargeStartTime"),
            chargeComplete = text(t, "chargeCompleteTime"),
            chargeStart120v = text(t, "chargeStartTime120v"),
            chargeComplete120v = text(t, "chargeCompleteTime120v"),
            chargeRecords =
                (1..CHARGE_RECORDS)
                    .map { text(t, "chargeRecord$it") }
                    .takeIf { records -> records.any { it != null } }
                    .orEmpty(),
            acPowerW = whole(t, "acPowerW"),
            cabinHeatPowerW = whole(t, "cabinHeatPowerW"),
            remoteStartSecondsLeft = whole(t, "remoteStartSecondsLeft"),
            remoteStartsLeft = whole(t, "remoteStartsLeft"),
        )

    private fun text(
        t: JSONObject,
        key: String,
    ): String? = if (t.isNull(key)) null else t.optString(key).ifBlank { null }

    private fun whole(
        t: JSONObject,
        key: String,
    ): Int? = if (t.isNull(key)) null else t.optDouble(key).takeIf { it.isFinite() }?.roundToInt()
}
