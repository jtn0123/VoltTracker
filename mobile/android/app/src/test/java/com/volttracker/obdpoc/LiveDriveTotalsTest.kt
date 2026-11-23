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
        assertFalse("no charge yet", last.has("chargeStartSoc"))
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
    fun aSampleWithoutPublishedTotalsReadsAsNone() {
        assertNull(DriveTotals.fromJson(JSONObject().put("speedKph", 50)))
        val read =
            DriveTotals.fromJson(
                JSONObject().put("driveDistanceM", 1_200.0).put("driveStartedAtMs", 5_000L).put("chargeStartSoc", 55.0),
            )
        assertEquals(DriveTotals(distanceM = 1_200.0, driveStartedAtMs = 5_000L, chargeStartSoc = 55.0), read)
    }
}
