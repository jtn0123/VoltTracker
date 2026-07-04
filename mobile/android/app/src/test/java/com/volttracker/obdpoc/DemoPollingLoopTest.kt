package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo drive/charge cycle is pure math on elapsed seconds (the loop itself
 * is wall-clock driven), so the phase helpers are tested directly: the charge
 * window must exist, feed a plausible Level-2 charger power, visibly raise the
 * SOC, and always zero the charger reading while driving — a stale nonzero
 * value would pin the dashboard's live charge card open after the phase flips.
 */
class DemoPollingLoopTest {
    @Test
    fun cycleSplitsIntoDriveThenChargePhases() {
        // First 60 s drive, next 30 s charge, then the cycle repeats.
        assertFalse(DemoPollingLoop.isChargingPhase(0.0))
        assertFalse(DemoPollingLoop.isChargingPhase(59.9))
        assertTrue(DemoPollingLoop.isChargingPhase(60.0))
        assertTrue(DemoPollingLoop.isChargingPhase(89.9))
        assertFalse(DemoPollingLoop.isChargingPhase(90.0))
        assertTrue(DemoPollingLoop.isChargingPhase(150.0))
    }

    @Test
    fun driveAndChargeClocksPartitionElapsedTime() {
        // Mid drive phase: all elapsed time is drive time.
        assertEquals(45.0, DemoPollingLoop.driveSeconds(45.0), 1e-9)
        assertEquals(0.0, DemoPollingLoop.chargeSeconds(45.0), 1e-9)
        // Mid charge phase: the drive clock freezes at 60 s.
        assertEquals(60.0, DemoPollingLoop.driveSeconds(75.0), 1e-9)
        assertEquals(15.0, DemoPollingLoop.chargeSeconds(75.0), 1e-9)
        // One full cycle plus 10 s of the next drive phase.
        assertEquals(70.0, DemoPollingLoop.driveSeconds(100.0), 1e-9)
        assertEquals(30.0, DemoPollingLoop.chargeSeconds(100.0), 1e-9)
    }

    @Test
    fun socDrainsWhileDrivingAndVisiblyRecoversOnTheCharger() {
        val startSoc = DemoPollingLoop.demoSoc(0.0)
        val endOfDrive = DemoPollingLoop.demoSoc(60.0)
        val endOfCharge = DemoPollingLoop.demoSoc(90.0)
        assertTrue("SOC must drain across the drive phase", endOfDrive < startSoc)
        assertTrue("SOC must recover across the charge phase", endOfCharge > endOfDrive)
        // The 30 s window must move the needle by whole percents, not noise.
        assertTrue(endOfCharge - endOfDrive >= 3.0)
    }

    @Test
    fun socStaysInsideTheFloorAndChargeCap() {
        assertTrue(DemoPollingLoop.demoSoc(1_000_000.0) <= 95.0)
        assertTrue(DemoPollingLoop.demoSoc(1_000_000.0) >= 13.4)
    }

    @Test
    fun socSawtoothSurvivesManyCyclesWithoutPlateauingAtTheCap() {
        // Regression: the original form summed drive/charge seconds across ALL
        // cycles (+3 % net per 90 s), pinning SOC at the 95 % cap after ~9
        // minutes and freezing the drive-phase drain. The periodic form must
        // still drain and recover deep into a long-running demo.
        val lateCycleStart = DemoPollingLoop.demoSoc(540.0)
        val lateDriveEnd = DemoPollingLoop.demoSoc(600.0)
        val lateChargeEnd = DemoPollingLoop.demoSoc(630.0)
        assertTrue("drive drain must survive past 540 s", lateCycleStart - lateDriveEnd >= 3.0)
        assertTrue("charge recovery must survive past 540 s", lateChargeEnd - lateDriveEnd >= 3.0)
        assertTrue("SOC must not drift toward the cap", lateChargeEnd < 80.0)
        assertEquals(
            "the cycle must be exactly periodic",
            DemoPollingLoop.demoSoc(0.0),
            DemoPollingLoop.demoSoc(540.0),
            1e-9,
        )
    }

    @Test
    fun chargerPowerIsLevel2WhileChargingAndZeroWhileDriving() {
        assertEquals(0.0, DemoPollingLoop.demoChargerPowerKw(30.0), 1e-9)
        val charging = DemoPollingLoop.demoChargerPowerKw(75.0)
        assertTrue("expected a plausible L2 draw, got $charging", charging in 6.5..8.0)
        assertEquals(0.0, DemoPollingLoop.demoChargerPowerKw(95.0), 1e-9)
    }
}
