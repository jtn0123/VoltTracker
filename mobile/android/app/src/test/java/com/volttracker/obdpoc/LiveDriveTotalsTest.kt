package com.volttracker.obdpoc

import com.volttracker.obdpoc.ui.drive.DrivePhase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The running drive/charge totals the service publishes on every live sample. */
class LiveDriveTotalsTest {
    // ~0.0005° of latitude ≈ 55.6 m: well inside the fold's 2 m..250 m step window.
    private fun driving(
        atS: Int,
        step: Int,
        speedKph: Int = 60,
    ): JSONObject =
        JSONObject()
            .put("updatedAt", atS * 1_000L)
            .put("vehicleState", "driving_ev")
            .put("speedKph", speedKph)
            .put("powerKw", 18.0)
            .put("latitude", 42.0 + step * 0.0005)
            .put("longitude", -83.0)

    @Test
    fun totalsCoverEverySampleNotJustTheResumeRing() {
        val totals = LiveDriveTotals()
        var last = JSONObject()
        // 1 000 samples a second apart: far more than the 300-sample resume ring holds.
        for (i in 0 until 1_000) last = totals.publish(driving(atS = 1 + i, step = i, speedKph = 40 + i % 50))

        assertEquals(999 * 55.6, last.getDouble("driveDistanceM"), 100.0)
        // 18 kW for 999 s.
        assertEquals(18.0 * 999 / 3600.0, last.getDouble("driveEnergyKwh"), 1e-9)
        assertEquals(89.0, last.getDouble("driveMaxSpeedKph"), 0.0)
        assertEquals(1_000L, last.getLong("driveStartedAtMs"))
        assertEquals(1_000_000L, last.getLong("driveLastAtMs"))
        assertFalse("no charge yet", last.has("chargeStartSoc"))
    }

    @Test
    fun aParkedSampleKeepsTheFinishedDriveItsEndAndItsStartSoc() {
        val totals = LiveDriveTotals()
        for (i in 0..5) {
            totals.publish(driving(atS = 1 + i, step = i).put("soc", 62.0 - i).put("displayedSocPct", 70.0 - i))
        }
        val parked = totals.publish(JSONObject().put("updatedAt", 60_000L).put("vehicleState", "parked"))

        assertEquals(5 * 55.6, parked.getDouble("driveDistanceM"), 1.0)
        assertEquals(1_000L, parked.getLong("driveStartedAtMs"))
        // The drive ended at its last driving sample, not at the parked one.
        assertEquals(6_000L, parked.getLong("driveLastAtMs"))
        // On the sample's own `soc` scale, the one the WebView shows it beside.
        assertEquals(62.0, parked.getDouble("driveStartSoc"), 0.0)
    }

    @Test
    fun writeToAddsExactlyTheBookkeepingKeys() {
        val out = JSONObject()
        DriveTotals(1.0, 2.0, 3.0, 4L, 5L, 6.0, 7.0, 8L).writeTo(out)

        assertEquals(DriveTotals.KEYS, out.keys().asSequence().toSet())
        assertEquals(DriveTotals(1.0, 2.0, 3.0, 4L, 5L, 6.0, 7.0, 8L), DriveTotals.fromJson(out))
    }

    @Test
    fun publishReturnsACopyAndLeavesTheRecordedSampleAsRead() {
        val totals = LiveDriveTotals()
        val sample = driving(atS = 1, step = 0)
        val before = sample.toString()

        val live = totals.publish(sample)

        assertEquals("the stored sample must not gain the totals", before, sample.toString())
        assertEquals(60, live.getInt("speedKph"))
        assertTrue(live.has("driveDistanceM"))
    }

    @Test
    fun aNewDriveRestartsTheTotalsAndAChargeRecordsWhereItStarted() {
        val totals = LiveDriveTotals()
        for (i in 0..5) totals.publish(driving(atS = 1 + i, step = i))
        totals.publish(JSONObject().put("updatedAt", 20_000L).put("vehicleState", "parked"))
        val charging =
            totals.publish(
                JSONObject()
                    .put("updatedAt", 30_000L)
                    .put("vehicleState", "charging")
                    .put("chargerPowerKw", 3.6)
                    .put("soc", 40.0)
                    .put("displayedSocPct", 41.5),
            )
        // The charge keeps the drive's figures on show, and starts on the driver's SOC scale.
        assertEquals(5 * 55.6, charging.getDouble("driveDistanceM"), 1.0)
        assertEquals(41.5, charging.getDouble("chargeStartSoc"), 0.0)
        assertEquals(30_000L, charging.getLong("chargeStartedAtMs"))

        val nextDrive = totals.publish(driving(atS = 100, step = 0))
        assertEquals(0.0, nextDrive.getDouble("driveDistanceM"), 0.0)
        assertEquals(0.0, nextDrive.getDouble("driveEnergyKwh"), 0.0)
        assertEquals(100_000L, nextDrive.getLong("driveStartedAtMs"))
        assertEquals(41.5, nextDrive.getDouble("chargeStartSoc"), 0.0)
    }

    @Test
    fun resetStartsAFreshAdapterSession() {
        val totals = LiveDriveTotals()
        for (i in 0..5) totals.publish(driving(atS = 10 + i, step = i))
        totals.publish(JSONObject().put("updatedAt", 20_000L).put("vehicleState", "charging").put("soc", 40))

        totals.reset()
        // An earlier clock is accepted again after a reset.
        val first = totals.publish(driving(atS = 1, step = 0))

        assertEquals(0.0, first.getDouble("driveDistanceM"), 0.0)
        assertEquals(1_000L, first.getLong("driveStartedAtMs"))
        assertFalse(first.has("chargeStartSoc"))
        assertFalse(first.has("chargeStartedAtMs"))
    }

    @Test
    fun phaseRuleTrustsTheClassifierThenGearAndSpeedButNotPower() {
        fun next(
            current: DrivePhase,
            block: JSONObject.() -> Unit,
        ) = DrivePhaseRule.next(JSONObject().apply(block), current)

        assertEquals(DrivePhase.CHARGING, next(DrivePhase.PARKED) { put("vehicleState", "charging") })
        assertEquals(DrivePhase.DRIVE, next(DrivePhase.PARKED) { put("vehicleState", "driving_gas") })
        assertEquals(DrivePhase.PARKED, next(DrivePhase.DRIVE) { put("prndlState", "P") })
        assertEquals(DrivePhase.DRIVE, next(DrivePhase.PARKED) { put("prndlState", "R") })
        assertEquals(DrivePhase.DRIVE, next(DrivePhase.PARKED) { put("speedKph", 5) })
        assertEquals(DrivePhase.PARKED, next(DrivePhase.CHARGING) { put("chargerPowerKw", 3.6) })
        assertEquals(DrivePhase.PARKED, next(DrivePhase.PARKED) { put("chargerPowerKw", 3.6) })
    }

    @Test
    fun readyAtAStopWithNoUsableGearReadingDoesNotEndTheDrive() {
        fun next(
            current: DrivePhase,
            block: JSONObject.() -> Unit,
        ) = DrivePhaseRule.next(JSONObject().apply(block), current)

        // The classifier says "ready" at every stop while the car is on; without a gear reading
        // (no 222889 reply, or a code decoded as "?") that is a red light, not the end of a drive.
        assertEquals(DrivePhase.DRIVE, next(DrivePhase.DRIVE) { put("vehicleState", "ready").put("speedKph", 0) })
        assertEquals(DrivePhase.DRIVE, next(DrivePhase.DRIVE) { put("vehicleState", "ready").put("prndlState", "?") })
        // P, "parked" and "plugged" still end it; "ready" still parks a car that was not driving.
        assertEquals(DrivePhase.PARKED, next(DrivePhase.DRIVE) { put("vehicleState", "ready").put("prndlState", "P") })
        assertEquals(DrivePhase.PARKED, next(DrivePhase.DRIVE) { put("vehicleState", "parked") })
        assertEquals(DrivePhase.PARKED, next(DrivePhase.DRIVE) { put("vehicleState", "plugged") })
        assertEquals(DrivePhase.PARKED, next(DrivePhase.CHARGING) { put("vehicleState", "ready") })
        assertEquals(DrivePhase.PARKED, next(DrivePhase.UNKNOWN) { put("vehicleState", "ready") })
    }

    @Test
    fun aStopWithoutAGearReadingKeepsTheDriveTotalsRunning() {
        val totals = LiveDriveTotals()
        for (i in 0..5) totals.publish(driving(atS = 1 + i, step = i))
        // A red light: stationary, classifier "ready", gear PID unanswered.
        totals.publish(
            JSONObject()
                .put("updatedAt", 8_000L)
                .put("vehicleState", "ready")
                .put("speedKph", 0)
                .put("latitude", 42.0 + 5 * 0.0005)
                .put("longitude", -83.0),
        )
        val moving = totals.publish(driving(atS = 9, step = 6))

        assertEquals(1_000L, moving.getLong("driveStartedAtMs"))
        assertEquals(6 * 55.6, moving.getDouble("driveDistanceM"), 1.0)
    }

    @Test
    fun aSampleWithoutPublishedTotalsReadsAsNone() {
        assertNull(DriveTotals.fromJson(JSONObject().put("speedKph", 50)))
        val read =
            DriveTotals.fromJson(
                JSONObject().put("driveDistanceM", 1_200.0).put("driveStartedAtMs", 5_000L).put("chargeStartSoc", 55.0),
            )
        assertEquals(DriveTotals(distanceM = 1_200.0, driveStartedAtMs = 5_000L, chargeStartSoc = 55.0), read)
    }
}
