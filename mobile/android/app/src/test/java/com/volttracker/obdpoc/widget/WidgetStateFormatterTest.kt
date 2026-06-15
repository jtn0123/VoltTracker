package com.volttracker.obdpoc.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure widget presentation logic: SOC formatting, status selection (charging vs connected
 * vs disconnected vs no-data), and the "updated …" freshness/stale thresholds. No Android framework
 * is touched, so this runs as a plain JUnit4 test.
 */
class WidgetStateFormatterTest {
    private val nowMs = 1_700_000_000_000L

    private fun snapshot(
        soc: Int = 75,
        charging: Boolean = false,
        connected: Boolean = true,
        vehicleState: String = "",
        ageMs: Long = 0L,
        sampleAgeMs: Long = ageMs,
    ) = WidgetSnapshot(
        socPct = soc,
        charging = charging,
        connected = connected,
        vehicleState = vehicleState,
        updatedAtMs = nowMs - ageMs,
        lastSampleAtMs = nowMs - sampleAgeMs,
    )

    @Test
    fun socTextFormatsPercentAndPlaceholder() {
        assertEquals("82%", WidgetStateFormatter.socText(snapshot(soc = 82)))
        assertEquals("0%", WidgetStateFormatter.socText(snapshot(soc = 0)))
        assertEquals("—", WidgetStateFormatter.socText(snapshot(soc = WidgetSnapshot.UNKNOWN_SOC)))
    }

    @Test
    fun statusNoDataWhenSnapshotEmpty() {
        assertEquals(WidgetStateFormatter.Status.NO_DATA, WidgetStateFormatter.status(WidgetSnapshot.EMPTY))
    }

    @Test
    fun statusChargingWhenFlagSet() {
        assertEquals(
            WidgetStateFormatter.Status.CHARGING,
            WidgetStateFormatter.status(snapshot(charging = true)),
        )
    }

    @Test
    fun statusChargingWhenVehicleStateCharging() {
        assertEquals(
            WidgetStateFormatter.Status.CHARGING,
            WidgetStateFormatter.status(snapshot(charging = false, vehicleState = "charging")),
        )
    }

    @Test
    fun statusConnectedWhenConnectedNotCharging() {
        assertEquals(
            WidgetStateFormatter.Status.CONNECTED,
            WidgetStateFormatter.status(snapshot(connected = true, charging = false)),
        )
    }

    @Test
    fun statusDisconnectedWhenHasDataButNotConnected() {
        assertEquals(
            WidgetStateFormatter.Status.DISCONNECTED,
            WidgetStateFormatter.status(snapshot(connected = false, charging = false)),
        )
    }

    @Test
    fun freshnessNoneWhenEmpty() {
        val (freshness, value) = WidgetStateFormatter.freshness(WidgetSnapshot.EMPTY, nowMs)
        assertEquals(WidgetStateFormatter.Freshness.NONE, freshness)
        assertEquals(0, value)
    }

    @Test
    fun freshnessJustNowUnderOneMinute() {
        val (freshness, _) = WidgetStateFormatter.freshness(snapshot(ageMs = 30_000L), nowMs)
        assertEquals(WidgetStateFormatter.Freshness.JUST_NOW, freshness)
    }

    @Test
    fun freshnessMinutes() {
        val (freshness, value) = WidgetStateFormatter.freshness(snapshot(ageMs = 5L * 60_000L), nowMs)
        assertEquals(WidgetStateFormatter.Freshness.MINUTES, freshness)
        assertEquals(5, value)
    }

    @Test
    fun freshnessHours() {
        val (freshness, value) = WidgetStateFormatter.freshness(snapshot(ageMs = 3L * 3_600_000L), nowMs)
        assertEquals(WidgetStateFormatter.Freshness.HOURS, freshness)
        assertEquals(3, value)
    }

    @Test
    fun freshnessDays() {
        val (freshness, value) = WidgetStateFormatter.freshness(snapshot(ageMs = 2L * 24L * 3_600_000L), nowMs)
        assertEquals(WidgetStateFormatter.Freshness.DAYS, freshness)
        assertEquals(2, value)
    }

    @Test
    fun freshnessTreatsFutureTimestampAsJustNow() {
        val future =
            WidgetSnapshot(
                50,
                charging = false,
                connected = true,
                vehicleState = "",
                updatedAtMs =
                    nowMs + 60_000L,
            )
        val (freshness, value) = WidgetStateFormatter.freshness(future, nowMs)
        assertEquals(WidgetStateFormatter.Freshness.JUST_NOW, freshness)
        assertEquals(0, value)
    }

    @Test
    fun staleThresholdBoundary() {
        assertFalse(WidgetStateFormatter.isStale(snapshot(ageMs = 14L * 60_000L), nowMs))
        assertTrue(WidgetStateFormatter.isStale(snapshot(ageMs = 16L * 60_000L), nowMs))
        assertFalse("empty is never stale", WidgetStateFormatter.isStale(WidgetSnapshot.EMPTY, nowMs))
    }

    @Test
    fun steadyChargeIsNotStaleWhenSamplesKeepArrivingDespiteFlatDisplay() {
        // B7: the display last CHANGED 20 min ago (flat SOC during a steady charge), but fresh
        // identical samples keep arriving (last sample 30 s ago). Freshness/stale must key off the
        // SAMPLE time, so the widget is NOT wrongly flagged stale and reads "just now".
        val flatButLive = snapshot(charging = true, ageMs = 20L * 60_000L, sampleAgeMs = 30_000L)
        assertFalse("a flat-but-live charge is not stale", WidgetStateFormatter.isStale(flatButLive, nowMs))
        val (freshness, _) = WidgetStateFormatter.freshness(flatButLive, nowMs)
        assertEquals(WidgetStateFormatter.Freshness.JUST_NOW, freshness)
    }

    @Test
    fun goesStaleWhenSamplesActuallyStopArriving() {
        // The complement: when NO fresh sample has arrived (sample time itself is old), the widget
        // correctly goes stale even if the display change time happens to be recent.
        val sampleStopped = snapshot(ageMs = 1L * 60_000L, sampleAgeMs = 16L * 60_000L)
        assertTrue("no fresh samples -> stale", WidgetStateFormatter.isStale(sampleStopped, nowMs))
    }

    @Test
    fun formatCombinesAllFields() {
        val display = WidgetStateFormatter.format(snapshot(soc = 64, charging = true, ageMs = 20L * 60_000L), nowMs)
        assertEquals("64%", display.socText)
        assertEquals(WidgetStateFormatter.Status.CHARGING, display.status)
        assertEquals(WidgetStateFormatter.Freshness.MINUTES, display.freshness)
        assertEquals(20, display.freshnessValue)
        assertTrue(display.stale)
    }
}
