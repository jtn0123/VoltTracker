package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.charge.ChargeHistory
import com.volttracker.obdpoc.ui.charge.ChargeSession
import com.volttracker.obdpoc.ui.charge.ChargeUiState
import org.json.JSONArray
import org.json.JSONObject

/**
 * The demo's unplugged Charge tab with one inferred charge on top: the SOC rose 22 % → 86 %
 * between two drives with no charge seen. It is parsed from the row exactly as
 * ObdStoreChargeSummary writes it, so the screens get what the store hands them. Synthetic
 * values only.
 */
internal object InferredChargeFixture {
    private const val HOUR = 3_600_000L
    private val demo = ChargeUiState.demo.copy(charging = false)
    private val newestMeasured = demo.sessions.first()

    /** The observation window: 9 h after the newest measured charge ended, for 10 h. */
    val fromMs: Long = checkNotNull(newestMeasured.endedAtMs) + 9 * HOUR
    val toMs: Long = fromMs + 10 * HOUR
    val id = "inferred:41:$fromMs:42:$toMs"

    val row: JSONObject
        get() =
            JSONObject()
                .put("id", id)
                .put("startedAtMs", fromMs)
                .put("endedAtMs", toMs)
                .put("chargerType", "inferred")
                .put("startSoc", 22.0)
                .put("endSoc", 86.0)
                .put("powerKw", JSONObject.NULL)
                // 64 % of the 14 kWh the store assumes usable.
                .put("energyKwh", 8.96)
                .put("confidence", 0.7)
                .put("provenance", "inferred")

    val inferred: ChargeSession get() = ChargeHistory.parse(JSONArray().put(row)).single()

    /** The measured charge the review images compare against: the demo's newest. */
    val measured: ChargeSession get() = newestMeasured

    val state: ChargeUiState get() = demo.copy(sessions = listOf(inferred) + demo.sessions)
}
