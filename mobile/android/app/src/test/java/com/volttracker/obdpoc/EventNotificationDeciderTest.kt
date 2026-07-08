package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the firing decisions of [EventNotificationDecider]: charge-complete fires exactly once with
 * the integrated energy, new-DTC fires only for codes not in the previous scan, and the SOC / pack-
 * temp threshold alerts fire only on a CROSSING (not every sample over/under the line).
 */
class EventNotificationDeciderTest {
    private fun settings(
        chargeComplete: Boolean = true,
        newDtc: Boolean = true,
        lowSoc: Boolean = false,
        lowSocThreshold: Double = 20.0,
        highTemp: Boolean = false,
        highTempThreshold: Double = 45.0,
        targetSoc: Double = 100.0,
    ) = EventNotificationDecider.Settings(
        chargeCompleteEnabled = chargeComplete,
        newDtcEnabled = newDtc,
        lowSocEnabled = lowSoc,
        lowSocThresholdPct = lowSocThreshold,
        highPackTempEnabled = highTemp,
        highPackTempThresholdC = highTempThreshold,
        targetSocPct = targetSoc,
    )

    private fun sample(
        atMs: Long,
        packCurrentA: Double? = null,
        packVoltage: Double? = null,
        speedKph: Double? = 0.0,
        socPct: Double? = null,
        packTempC: Double? = null,
    ) = EventNotificationDecider.Sample(atMs, packCurrentA, packVoltage, speedKph, socPct, packTempC)

    // ---- charge complete ---------------------------------------------------------------

    @Test
    fun chargeCompleteFiresOnceWithIntegratedEnergyWhenChargingEnds() {
        val decider = EventNotificationDecider(settings())
        // Steady ~7.1 kW (-20 A into a 355 V pack) for one hour, three samples.
        assertTrue(decider.onSample(sample(0L, packCurrentA = -20.0, packVoltage = 355.0)).isEmpty())
        assertTrue(
            decider.onSample(sample(1_800_000L, packCurrentA = -20.0, packVoltage = 355.0)).isEmpty(),
        )
        assertTrue(
            decider.onSample(sample(3_600_000L, packCurrentA = -20.0, packVoltage = 355.0)).isEmpty(),
        )

        // Charge ends: car starts moving / discharges.
        val events = decider.onSample(sample(3_660_000L, packCurrentA = 5.0, speedKph = 30.0))

        assertEquals(1, events.size)
        val event = events[0] as EventNotificationDecider.Event.ChargeComplete
        // Trapezoidal integral of a constant 7.1 kW over 1 hour = ~7.1 kWh.
        assertEquals(7.1, event.energyKwh, 0.05)
    }

    @Test
    fun chargeCompleteFiresOnlyOnceAcrossSubsequentNonChargingSamples() {
        val decider = EventNotificationDecider(settings())
        for (i in 0..3) {
            decider.onSample(sample(i * 600_000L, packCurrentA = -20.0, packVoltage = 355.0))
        }
        val first = decider.onSample(sample(2_500_000L, packCurrentA = 2.0, speedKph = 40.0))
        val second = decider.onSample(sample(2_600_000L, packCurrentA = 2.0, speedKph = 40.0))

        assertEquals(1, first.size)
        assertTrue("a second non-charging sample must not re-fire", second.isEmpty())
    }

    @Test
    fun chargeCompleteDoesNotFireForASingleGlitchSampleBelowMinSamples() {
        val decider = EventNotificationDecider(settings())
        // Only one charging sample, then it stops — below MIN_CHARGE_SAMPLES.
        decider.onSample(sample(0L, packCurrentA = -20.0, packVoltage = 355.0))
        val events = decider.onSample(sample(60_000L, packCurrentA = 1.0, speedKph = 10.0))

        assertTrue("a one-sample blip is not a real charge", events.isEmpty())
    }

    @Test
    fun chargeCompleteSuppressedWhenToggleOff() {
        val decider = EventNotificationDecider(settings(chargeComplete = false))
        for (i in 0..3) {
            decider.onSample(sample(i * 600_000L, packCurrentA = -20.0, packVoltage = 355.0))
        }
        val events = decider.onSample(sample(2_500_000L, packCurrentA = 5.0, speedKph = 30.0))
        assertTrue(events.isEmpty())
    }

    // ---- charge interrupted vs complete (M3) -------------------------------------------

    @Test
    fun chargeEndingWellBelowTargetReportsInterruptedNotComplete() {
        // Target 80%; charging stops at 47% (cable knocked loose) -> interrupted, not "complete".
        val decider = EventNotificationDecider(settings(targetSoc = 80.0))
        for (i in 0..3) {
            decider.onSample(sample(i * 600_000L, packCurrentA = -20.0, packVoltage = 355.0, socPct = 47.0))
        }
        val events = decider.onSample(sample(2_500_000L, packCurrentA = 0.0, speedKph = 0.0, socPct = 47.0))

        assertEquals(1, events.size)
        val event = events[0] as EventNotificationDecider.Event.ChargeInterrupted
        assertEquals(47.0, event.socPct, 0.001)
        assertEquals(80.0, event.targetPct, 0.001)
    }

    @Test
    fun chargeEndingAtTargetReportsCompleteNotInterrupted() {
        // Reaches the 80% target, then stops -> a normal completed charge.
        val decider = EventNotificationDecider(settings(targetSoc = 80.0))
        for (i in 0..3) {
            decider.onSample(sample(i * 600_000L, packCurrentA = -20.0, packVoltage = 355.0, socPct = 80.0))
        }
        val events = decider.onSample(sample(2_500_000L, packCurrentA = 0.0, speedKph = 0.0, socPct = 80.0))

        // The target-reached ping already fired during charging; the end is a ChargeComplete.
        val end = events.last()
        assertTrue(
            "a charge that reached target ends as complete",
            end is EventNotificationDecider.Event.ChargeComplete,
        )
    }

    @Test
    fun chargeEndingJustBelowTargetWithinMarginStillCompletes() {
        // Stops at 77% with an 80% target — within the 5% interrupted margin, so the usual tail-end
        // cutoff counts as complete, not interrupted.
        val decider = EventNotificationDecider(settings(targetSoc = 80.0))
        for (i in 0..3) {
            decider.onSample(sample(i * 600_000L, packCurrentA = -20.0, packVoltage = 355.0, socPct = 77.0))
        }
        val events = decider.onSample(sample(2_500_000L, packCurrentA = 0.0, speedKph = 0.0, socPct = 77.0))

        val end = events.last()
        assertTrue("a stop within the margin is complete", end is EventNotificationDecider.Event.ChargeComplete)
    }

    @Test
    fun chargeEndingWithNoSocReadingFallsBackToComplete() {
        // Current seen but SOC never reported -> can't prove interrupted, so don't cry wolf.
        val decider = EventNotificationDecider(settings(targetSoc = 80.0))
        for (i in 0..3) {
            decider.onSample(sample(i * 600_000L, packCurrentA = -20.0, packVoltage = 355.0))
        }
        val events = decider.onSample(sample(2_500_000L, packCurrentA = 0.0, speedKph = 0.0))

        assertEquals(1, events.size)
        assertTrue(events[0] is EventNotificationDecider.Event.ChargeComplete)
    }

    @Test
    fun normalFullChargeUnderDefault100TargetCompletesNotInterrupted() {
        // Default target is 100%, but a full Gen-2 Volt pack reports only ~90% on the raw PID. A
        // normal completed charge peaking at 90% must NOT be misreported as interrupted just because
        // it sits >5% under the unreachable 100 target.
        val decider = EventNotificationDecider(settings())
        for (i in 0..3) {
            decider.onSample(sample(i * 600_000L, packCurrentA = -20.0, packVoltage = 355.0, socPct = 90.0))
        }
        val events = decider.onSample(sample(2_500_000L, packCurrentA = 0.0, speedKph = 0.0, socPct = 90.0))

        val end = events.last()
        assertTrue(
            "a normal ~90% full charge under the default 100 target is complete",
            end is EventNotificationDecider.Event.ChargeComplete,
        )
    }

    @Test
    fun genuineInterruptionUnderDefault100TargetStillReportsInterrupted() {
        // The reachable-target cap must not blind us to a real interruption: a charge yanked at 40%
        // under the default 100 target is still well below the ~90% a full pack reaches -> interrupted.
        val decider = EventNotificationDecider(settings())
        for (i in 0..3) {
            decider.onSample(sample(i * 600_000L, packCurrentA = -20.0, packVoltage = 355.0, socPct = 40.0))
        }
        val events = decider.onSample(sample(2_500_000L, packCurrentA = 0.0, speedKph = 0.0, socPct = 40.0))

        assertEquals(1, events.size)
        // The `as` cast is itself the "is interrupted" assertion. The reported target must be the
        // user's configured target (100), not the internal reachable cap (90) — the cap only gates
        // the interrupted-vs-complete decision, it must not leak into what the event reports.
        val event = events[0] as EventNotificationDecider.Event.ChargeInterrupted
        assertEquals(100.0, event.targetPct, 0.001)
    }

    @Test
    fun chargeInterruptedRidesTheChargeCompleteToggle() {
        val decider = EventNotificationDecider(settings(chargeComplete = false, targetSoc = 80.0))
        for (i in 0..3) {
            decider.onSample(sample(i * 600_000L, packCurrentA = -20.0, packVoltage = 355.0, socPct = 47.0))
        }
        val events = decider.onSample(sample(2_500_000L, packCurrentA = 0.0, speedKph = 0.0, socPct = 47.0))
        assertTrue("disabling charge-complete also suppresses interrupted", events.isEmpty())
    }

    // ---- target SOC reached (M2) -------------------------------------------------------

    @Test
    fun targetSocReachedFiresOnceWhenChargeCrossesTheTarget() {
        val decider = EventNotificationDecider(settings(targetSoc = 80.0))
        // Climbs through the target while charging.
        assertTrue(decider.onSample(sample(0L, packCurrentA = -20.0, packVoltage = 355.0, socPct = 70.0)).isEmpty())
        assertTrue(
            decider.onSample(sample(60_000L, packCurrentA = -20.0, packVoltage = 355.0, socPct = 79.0)).isEmpty(),
        )
        val crossing = decider.onSample(sample(120_000L, packCurrentA = -20.0, packVoltage = 355.0, socPct = 81.0))
        assertEquals(1, crossing.size)
        val event = crossing[0] as EventNotificationDecider.Event.TargetSocReached
        assertEquals(81.0, event.socPct, 0.001)
        assertEquals(80.0, event.targetPct, 0.001)
        // Still above target, but already fired — no repeat.
        assertTrue(
            decider.onSample(sample(180_000L, packCurrentA = -20.0, packVoltage = 355.0, socPct = 82.0)).isEmpty(),
        )
    }

    @Test
    fun targetSocReachedDoesNotFireBeforeMinChargeSamples() {
        // A single transient charging sample already above target must NOT ping: no ChargeComplete
        // (which guards phantom charges) would back it up. The ping waits for MIN_CHARGE_SAMPLES.
        val decider = EventNotificationDecider(settings(targetSoc = 80.0))
        val first = decider.onSample(sample(0L, packCurrentA = -20.0, packVoltage = 355.0, socPct = 85.0))
        assertTrue(first.none { it is EventNotificationDecider.Event.TargetSocReached })
        val second = decider.onSample(sample(60_000L, packCurrentA = -20.0, packVoltage = 355.0, socPct = 85.0))
        assertTrue(second.none { it is EventNotificationDecider.Event.TargetSocReached })
        // Third charging sample reaches MIN_CHARGE_SAMPLES -> now the (still-armed) crossing fires.
        val third = decider.onSample(sample(120_000L, packCurrentA = -20.0, packVoltage = 355.0, socPct = 85.0))
        assertTrue(third.any { it is EventNotificationDecider.Event.TargetSocReached })
    }

    @Test
    fun targetSocReachedSuppressedForAFull100PercentTarget() {
        // A 100% target is a full charge, so the charge-complete alert covers it — no separate ping.
        val decider = EventNotificationDecider(settings(targetSoc = 100.0))
        val events = decider.onSample(sample(0L, packCurrentA = -20.0, packVoltage = 355.0, socPct = 100.0))
        assertTrue(events.none { it is EventNotificationDecider.Event.TargetSocReached })
    }

    @Test
    fun targetSocReachedSuppressedWhenChargeCompleteToggleOff() {
        val decider = EventNotificationDecider(settings(chargeComplete = false, targetSoc = 80.0))
        val events = decider.onSample(sample(0L, packCurrentA = -20.0, packVoltage = 355.0, socPct = 85.0))
        assertTrue(events.none { it is EventNotificationDecider.Event.TargetSocReached })
    }

    @Test
    fun targetSocReArmsForANewChargeAfterTheFirstEnds() {
        val decider = EventNotificationDecider(settings(targetSoc = 80.0))
        // First charge crosses the target and ends. The ping only fires once MIN_CHARGE_SAMPLES
        // charging samples have accrued, so it lands on the third sample (the crossing one).
        decider.onSample(sample(0L, packCurrentA = -20.0, packVoltage = 355.0, socPct = 60.0))
        decider.onSample(sample(30_000L, packCurrentA = -20.0, packVoltage = 355.0, socPct = 70.0))
        assertEquals(
            1,
            decider.onSample(sample(60_000L, packCurrentA = -20.0, packVoltage = 355.0, socPct = 85.0)).size,
        )
        decider.onSample(sample(120_000L, packCurrentA = 0.0, speedKph = 0.0, socPct = 85.0))
        // A fresh charge re-arms the ping (again after MIN_CHARGE_SAMPLES samples).
        decider.onSample(sample(180_000L, packCurrentA = -20.0, packVoltage = 355.0, socPct = 60.0))
        decider.onSample(sample(210_000L, packCurrentA = -20.0, packVoltage = 355.0, socPct = 70.0))
        val crossing = decider.onSample(sample(240_000L, packCurrentA = -20.0, packVoltage = 355.0, socPct = 85.0))
        assertTrue(crossing.any { it is EventNotificationDecider.Event.TargetSocReached })
    }

    // ---- low SOC threshold -------------------------------------------------------------

    @Test
    fun lowSocFiresOnceOnCrossingNotEverySampleBelow() {
        val decider = EventNotificationDecider(settings(lowSoc = true, lowSocThreshold = 20.0))
        assertTrue(decider.onSample(sample(0L, socPct = 25.0)).isEmpty())
        val crossing = decider.onSample(sample(1L, socPct = 19.0))
        assertEquals(1, crossing.size)
        val event = crossing[0] as EventNotificationDecider.Event.LowSoc
        assertEquals(19.0, event.socPct, 0.001)
        assertEquals(20.0, event.thresholdPct, 0.001)
        // Still below, but already fired — no repeat.
        assertTrue(decider.onSample(sample(2L, socPct = 18.0)).isEmpty())
        assertTrue(decider.onSample(sample(3L, socPct = 17.0)).isEmpty())
    }

    @Test
    fun lowSocReArmsAfterRecoveryAndFiresOnNextCrossing() {
        val decider = EventNotificationDecider(settings(lowSoc = true, lowSocThreshold = 20.0))
        decider.onSample(sample(0L, socPct = 25.0))
        assertEquals(1, decider.onSample(sample(1L, socPct = 19.0)).size)
        // Recover well past threshold + hysteresis, then drop again.
        assertTrue(decider.onSample(sample(2L, socPct = 30.0)).isEmpty())
        assertEquals(1, decider.onSample(sample(3L, socPct = 19.0)).size)
    }

    @Test
    fun lowSocDoesNotReArmWithinHysteresisBand() {
        val decider = EventNotificationDecider(settings(lowSoc = true, lowSocThreshold = 20.0))
        decider.onSample(sample(0L, socPct = 25.0))
        assertEquals(1, decider.onSample(sample(1L, socPct = 19.0)).size)
        // Bobs back to 21 (within the 3% hysteresis), then to 19 again — must NOT re-fire.
        assertTrue(decider.onSample(sample(2L, socPct = 21.0)).isEmpty())
        assertTrue(decider.onSample(sample(3L, socPct = 19.0)).isEmpty())
    }

    @Test
    fun lowSocStartsArmedSoFirstSampleAlreadyBelowFires() {
        val decider = EventNotificationDecider(settings(lowSoc = true, lowSocThreshold = 20.0))
        val events = decider.onSample(sample(0L, socPct = 15.0))
        assertEquals(1, events.size)
    }

    @Test
    fun lowSocSuppressedWhenToggleOff() {
        val decider = EventNotificationDecider(settings(lowSoc = false, lowSocThreshold = 20.0))
        assertTrue(decider.onSample(sample(0L, socPct = 5.0)).isEmpty())
    }

    // ---- high pack temp threshold ------------------------------------------------------

    @Test
    fun highTempFiresOnceOnRisingCrossing() {
        val decider = EventNotificationDecider(settings(highTemp = true, highTempThreshold = 45.0))
        assertTrue(decider.onSample(sample(0L, packTempC = 40.0)).isEmpty())
        val crossing = decider.onSample(sample(1L, packTempC = 46.0))
        assertEquals(1, crossing.size)
        val event = crossing[0] as EventNotificationDecider.Event.HighPackTemp
        assertEquals(46.0, event.tempC, 0.001)
        assertTrue(decider.onSample(sample(2L, packTempC = 50.0)).isEmpty())
    }

    @Test
    fun highTempReArmsAfterCooling() {
        val decider = EventNotificationDecider(settings(highTemp = true, highTempThreshold = 45.0))
        decider.onSample(sample(0L, packTempC = 40.0))
        assertEquals(1, decider.onSample(sample(1L, packTempC = 46.0)).size)
        assertTrue(decider.onSample(sample(2L, packTempC = 40.0)).isEmpty())
        assertEquals(1, decider.onSample(sample(3L, packTempC = 46.0)).size)
    }

    // ---- new DTC -----------------------------------------------------------------------

    @Test
    fun newDtcFiresForCodesNotInPreviousSet() {
        val decider = EventNotificationDecider(settings())
        val result = decider.onScan(listOf("P0AA6", "P1FFF"), listOf("P0AA6"), hasBaseline = true)
        val event = result.event!!
        assertEquals(listOf("P1FFF"), event.newCodes)
        assertEquals(listOf("P0AA6", "P1FFF"), result.allCodes)
    }

    @Test
    fun newDtcDoesNotFireWhenNoNewCodesAppear() {
        val decider = EventNotificationDecider(settings())
        val result = decider.onScan(listOf("P0AA6"), listOf("P0AA6", "P1FFF"), hasBaseline = true)
        assertEquals(null, result.event)
        // The new baseline is exactly what this scan saw (a cleared code drops out).
        assertEquals(listOf("P0AA6"), result.allCodes)
    }

    @Test
    fun newDtcNormalizesCaseAndWhitespaceBeforeDiffing() {
        val decider = EventNotificationDecider(settings())
        val result = decider.onScan(listOf("  p0aa6  ", "P1FFF"), listOf("P0AA6"), hasBaseline = true)
        assertEquals(listOf("P1FFF"), result.event!!.newCodes)
    }

    @Test
    fun newDtcSuppressedWhenToggleOffButStillReturnsBaseline() {
        val decider = EventNotificationDecider(settings(newDtc = false))
        val result = decider.onScan(listOf("P0AA6", "P1FFF"), listOf("P0AA6"), hasBaseline = true)
        assertEquals(null, result.event)
        assertEquals(listOf("P0AA6", "P1FFF"), result.allCodes)
    }

    @Test
    fun firstEverScanSuppressesNewDtcButStillEstablishesBaseline() {
        // First scan ever (no baseline): pre-existing codes are NOT "new", so the event is suppressed
        // and only the baseline is established (the full scanned set is returned to persist). (B3.)
        val decider = EventNotificationDecider(settings())
        val result = decider.onScan(listOf("P0AA6"), emptyList(), hasBaseline = false)
        assertEquals("first scan must not raise a false new-DTC alert", null, result.event)
        assertEquals("the scanned set still becomes the persisted baseline", listOf("P0AA6"), result.allCodes)
    }

    @Test
    fun newDtcFiresOnceABaselineExistsEvenWhenThatBaselineWasEmpty() {
        // A prior scan found zero codes (baseline exists and is empty); a newly-appearing code now IS
        // new and must fire — distinct from the first-scan-ever case above.
        val decider = EventNotificationDecider(settings())
        val result = decider.onScan(listOf("P0AA6"), emptyList(), hasBaseline = true)
        assertEquals(listOf("P0AA6"), result.event!!.newCodes)
    }
}
