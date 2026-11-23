package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.LiveDriveTotals
import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import com.volttracker.obdpoc.ui.units.METERS_PER_MILE
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Drive and Charge figures after the screen was off for longer than the resume ring
 * (300 samples) covers: they come from the service's totals, not from the replayed samples.
 */
class LiveTotalsResumeTest {
    private val service = LiveDriveTotals()

    /** One live sample as the service broadcasts it: as read, plus its running totals. */
    private fun broadcast(
        at: Long,
        block: JSONObject.() -> Unit,
    ): JSONObject = service.publish(JSONObject().put("updatedAt", at).put("source", "obd").apply(block))

    private fun connectedStore() =
        LiveUiStateStore().apply {
            onStatus(JSONObject().put("state", "connected").put("adapter", "OBDLink MX+"))
        }

    @Test
    fun aDriveResumedAfterMoreThanTheRingKeepsItsWholeDistanceEnergyAndPeak() {
        // A 1 000-sample drive, a second apart, ~55.6 m per sample; the peak is mid-drive.
        val drive =
            (0 until 1_000).map { i ->
                broadcast(1_000L + i * 1_000L) {
                    put("vehicleState", "driving_ev")
                    put("speedKph", if (i == 500) 120 else 60)
                    put("powerKw", 18.0)
                    put("latitude", 42.0 + i * 0.0005)
                    put("longitude", -83.0)
                }
            }
        val store = connectedStore()
        // On screen for the first 100 samples, then the screen goes off; on resume the
        // dashboard gets only the ring's last 300.
        drive.take(100).forEach(store::onTelemetry)
        store.onTelemetryBackfill(drive.takeLast(300))

        val published = drive.last()
        val shown = store.state.value.drive
        assertEquals(published.getDouble("driveDistanceM") / METERS_PER_MILE, shown.tripMiles, 1e-9)
        // ~34.5 mi: the 600-sample gap is counted, not dropped.
        assertTrue(shown.tripMiles > 34.0)
        assertEquals(published.getDouble("driveEnergyKwh"), shown.tripKwh ?: 0.0, 1e-9)
        // 120 km/h, reached while the screen was off.
        assertEquals(74, shown.tripMaxMph)
        assertEquals("17 min", shown.tripDuration)
    }

    @Test
    fun aChargePluggedInWhileTheScreenWasOffKeepsItsStartSocAndTime() {
        val store = connectedStore()
        store.onTelemetry(broadcast(1_000L) { put("vehicleState", "parked").put("soc", 30.0) })
        // Plugged in while paused: 1 000 samples 2.5 s apart, SOC climbing from 30 %.
        val charge =
            (0 until 1_000).map { i ->
                broadcast(10_000L + i * 2_500L) {
                    put("vehicleState", "charging")
                    put("chargerPowerKw", 3.6)
                    put("soc", 30.0 + i * 0.01)
                }
            }
        store.onTelemetryBackfill(charge.takeLast(300))

        val shown = store.state.value.charge
        assertTrue(shown.charging)
        assertEquals(30.0, shown.fromSoc ?: 0.0, 1e-9)
        assertEquals(10_000L, shown.startedAtMs)
    }
}
