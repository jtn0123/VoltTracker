package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.drive.DrivePhase
import com.volttracker.obdpoc.ui.live.DriveSessionTracker
import com.volttracker.obdpoc.ui.live.DriveSessionTracker.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The running trip/charge figures the Drive tiles show, folded from live samples. */
class DriveSessionTrackerTest {
    // ~0.001° of latitude ≈ 111 m.
    private fun drive(
        atS: Int,
        lat: Double,
        powerKw: Double = 18.0,
        speedKph: Double = 60.0,
    ) = Sample(
        atMs = atS * 1000L,
        phase = DrivePhase.DRIVE,
        speedKph = speedKph,
        powerKw = powerKw,
        lat = lat,
        lon = -83.0,
    )

    @Test
    fun distanceEnergyAndPeakSpeedAccumulateWhileDriving() {
        val tracker = DriveSessionTracker()
        for (i in 0..10) tracker.apply(drive(atS = 1 + i * 5, lat = 42.0 + i * 0.001, speedKph = 40.0 + i))

        // 10 steps of ~111 m.
        assertEquals(1.11, tracker.distanceM / 1000.0, 0.01)
        assertEquals(0.69, tracker.driveMiles, 0.01)
        // 18 kW for 50 s.
        assertEquals(0.25, tracker.energyKwh, 0.001)
        assertEquals(50.0, tracker.maxKph, 0.0)
        assertEquals(2.76, tracker.miPerKwh ?: 0.0, 0.01)
        assertEquals(1_000L, tracker.driveStartedAtMs)
        assertEquals(50_000L, tracker.durationMs(51_000L))
        assertEquals(0L, tracker.durationMs(500L))
    }

    @Test
    fun gpsJitterFixJumpsAndGapsAreIgnored() {
        val tracker = DriveSessionTracker()
        tracker.apply(drive(atS = 1, lat = 42.0))
        tracker.apply(drive(atS = 2, lat = 42.000001)) // < 2 m jitter
        tracker.apply(drive(atS = 3, lat = 42.01)) // ≥ 250 m jump
        assertEquals(0.0, tracker.distanceM, 0.0)
        // A 30 s gap is not integrated as energy.
        val before = tracker.energyKwh
        tracker.apply(drive(atS = 33, lat = 42.011))
        assertEquals(before, tracker.energyKwh, 0.0)
        assertNull(tracker.miPerKwh)
    }

    @Test
    fun replayedOrUntimedSamplesAreAppliedOnce() {
        val tracker = DriveSessionTracker()
        tracker.apply(drive(atS = 1, lat = 42.0))
        tracker.apply(drive(atS = 2, lat = 42.001))
        val miles = tracker.driveMiles
        val kwh = tracker.energyKwh
        tracker.apply(drive(atS = 2, lat = 42.001))
        tracker.apply(drive(atS = 1, lat = 42.0))
        tracker.apply(drive(atS = 0, lat = 42.5))
        assertEquals(miles, tracker.driveMiles, 0.0)
        assertEquals(kwh, tracker.energyKwh, 0.0)
    }

    @Test
    fun parkingClosesTheDriveIntoLastDriveAndTheNextDriveStartsFresh() {
        val tracker = DriveSessionTracker()
        for (i in 0..5) tracker.apply(drive(atS = 1 + i, lat = 42.0 + i * 0.001))
        tracker.apply(Sample(atMs = 10_000L, phase = DrivePhase.PARKED))
        val last = tracker.lastDrive
        assertNotNull(last)
        assertEquals(0.34, last?.miles ?: 0.0, 0.01)
        assertEquals(6_000L, last?.endedAtMs)

        tracker.apply(drive(atS = 20, lat = 43.0))
        assertEquals(0.0, tracker.driveMiles, 0.0)
        assertEquals(20_000L, tracker.driveStartedAtMs)
        assertEquals(last, tracker.lastDrive)
    }

    @Test
    fun aShortHopIsNotALastDrive() {
        val tracker = DriveSessionTracker()
        tracker.apply(drive(atS = 1, lat = 42.0))
        tracker.apply(drive(atS = 2, lat = 42.0001))
        tracker.apply(Sample(atMs = 3_000L, phase = DrivePhase.PARKED))
        assertNull(tracker.lastDrive)
    }

    @Test
    fun parkedToDriveWithDistanceSinceStartKeepsTheEarlierDrive() {
        val tracker = DriveSessionTracker()
        for (i in 0..5) tracker.apply(drive(atS = 1 + i, lat = 42.0 + i * 0.001))
        tracker.apply(Sample(atMs = 8_000L, phase = DrivePhase.CHARGING, soc = 40.0))
        tracker.apply(drive(atS = 100, lat = 43.0))
        assertEquals(0.34, tracker.lastDrive?.miles ?: 0.0, 0.01)
    }

    @Test
    fun chargingRecordsTheStartSocAndTheEnergyAddedFromTheSocGained() {
        val tracker = DriveSessionTracker()
        tracker.apply(Sample(atMs = 2_000L, phase = DrivePhase.CHARGING, chargerKw = 3.6, soc = 41.0, sohPct = 90.0))
        for (i in 1..10) {
            tracker.apply(
                Sample(atMs = 2_000L + i * 10_000L, phase = DrivePhase.CHARGING, chargerKw = 3.6, soc = 41.0 + i * 0.5),
            )
        }
        assertEquals(41.0, tracker.chargeFromSoc ?: 0.0, 0.0)
        assertEquals(2_000L, tracker.chargeStartedAtMs)
        // 5 % of a 14 kWh pack at 90 % health = 0.63 kWh — agrees with the SOC on screen.
        assertEquals(0.63, tracker.chargeAddedKwh, 1e-9)
        // Pack power while plugged in is never counted as driving energy.
        assertEquals(0.0, tracker.energyKwh, 0.0)
    }

    @Test
    fun aCompressedDemoChargeStillShowsTheEnergyItsSocGainImplies() {
        // Regression: the demo climbs ~3.6 % in 30 s at 3.6 kW; integrating the charger power
        // gave 0.03 kWh ("0.0 kWh · $0.00") while the SOC visibly rose.
        val tracker = DriveSessionTracker()
        for (s in 0..29) {
            tracker.apply(
                Sample(
                    atMs = 1_000L + s * 1000L,
                    phase = DrivePhase.CHARGING,
                    chargerKw = 3.6,
                    soc =
                        74.2 + s * 0.12,
                ),
            )
        }
        assertEquals(29 * 0.12 / 100 * 14.0, tracker.chargeAddedKwh, 1e-9)
        assertTrue(tracker.chargeAddedKwh >= 0.45)
    }

    @Test
    fun withoutSocReadingsTheAddedEnergyIntegratesChargerPower() {
        val tracker = DriveSessionTracker()
        for (i in 0..10) {
            tracker.apply(Sample(atMs = 2_000L + i * 10_000L, phase = DrivePhase.CHARGING, chargerKw = 3.6))
        }
        // 3.6 kW for 100 s = 0.1 kWh.
        assertEquals(0.1, tracker.chargeAddedKwh, 1e-9)
    }

    @Test
    fun resetClearsEverything() {
        val tracker = DriveSessionTracker()
        for (i in 0..5) tracker.apply(drive(atS = 1 + i, lat = 42.0 + i * 0.001))
        tracker.apply(Sample(atMs = 8_000L, phase = DrivePhase.CHARGING, soc = 40.0, chargerKw = 3.6))
        tracker.reset()
        assertEquals(0.0, tracker.driveMiles, 0.0)
        assertNull(tracker.lastDrive)
        assertNull(tracker.chargeFromSoc)
        assertNull(tracker.chargeStartedAtMs)
        assertEquals(0.0, tracker.chargeAddedKwh, 0.0)
        // After a reset, an earlier timestamp is accepted again (a new adapter session).
        tracker.apply(drive(atS = 1, lat = 42.0))
        assertEquals(1_000L, tracker.driveStartedAtMs)
    }

    @Test
    fun aChargeRecordsItsSocCurveAndTheNextChargeStartsFresh() {
        val tracker = DriveSessionTracker()
        for (s in 0..60 step 5) {
            tracker.apply(
                Sample(
                    atMs = 1_000L + s * 1000L,
                    phase = DrivePhase.CHARGING,
                    chargerKw = 3.6,
                    soc =
                        40.0 + s / 10.0,
                ),
            )
        }
        // A reading every 15 s: 0, 15, 30, 45, 60.
        assertEquals(listOf(40f, 41.5f, 43f, 44.5f, 46f), tracker.chargeSocPoints.map { it.soc })
        tracker.apply(Sample(atMs = 100_000L, phase = DrivePhase.PARKED))
        tracker.apply(Sample(atMs = 101_000L, phase = DrivePhase.CHARGING, chargerKw = 3.6, soc = 50.0))
        assertEquals(listOf(50f), tracker.chargeSocPoints.map { it.soc })
        tracker.reset()
        assertEquals(0, tracker.chargeSocPoints.size)
    }

    @Test
    fun aLongChargeThinsItsCurveInsteadOfGrowingWithoutBound() {
        val tracker = DriveSessionTracker()
        // Ten hours at 1 Hz.
        for (s in 0..36_000) {
            tracker.apply(Sample(atMs = 1_000L + s * 1000L, phase = DrivePhase.CHARGING, soc = s / 400.0))
        }
        val points = tracker.chargeSocPoints
        assertTrue(points.size in 100..240)
        assertEquals(1_000L, points.first().atMs)
        assertTrue(points.zipWithNext().all { (a, b) -> b.atMs > a.atMs })
    }
}
