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
    ) = EventNotificationDecider.Settings(
        chargeCompleteEnabled = chargeComplete,
        newDtcEnabled = newDtc,
        lowSocEnabled = lowSoc,
        lowSocThresholdPct = lowSocThreshold,
        highPackTempEnabled = highTemp,
        highPackTempThresholdC = highTempThreshold,
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
        val result = decider.onScan(listOf("P0AA6", "P1FFF"), listOf("P0AA6"))
        val event = result.event!!
        assertEquals(listOf("P1FFF"), event.newCodes)
        assertEquals(listOf("P0AA6", "P1FFF"), result.allCodes)
    }

    @Test
    fun newDtcDoesNotFireWhenNoNewCodesAppear() {
        val decider = EventNotificationDecider(settings())
        val result = decider.onScan(listOf("P0AA6"), listOf("P0AA6", "P1FFF"))
        assertEquals(null, result.event)
        // The new baseline is exactly what this scan saw (a cleared code drops out).
        assertEquals(listOf("P0AA6"), result.allCodes)
    }

    @Test
    fun newDtcNormalizesCaseAndWhitespaceBeforeDiffing() {
        val decider = EventNotificationDecider(settings())
        val result = decider.onScan(listOf("  p0aa6  ", "P1FFF"), listOf("P0AA6"))
        assertEquals(listOf("P1FFF"), result.event!!.newCodes)
    }

    @Test
    fun newDtcSuppressedWhenToggleOffButStillReturnsBaseline() {
        val decider = EventNotificationDecider(settings(newDtc = false))
        val result = decider.onScan(listOf("P0AA6", "P1FFF"), listOf("P0AA6"))
        assertEquals(null, result.event)
        assertEquals(listOf("P0AA6", "P1FFF"), result.allCodes)
    }

    @Test
    fun firstEverScanWithCodesFiresAgainstEmptyBaseline() {
        val decider = EventNotificationDecider(settings())
        val result = decider.onScan(listOf("P0AA6"), emptyList())
        assertEquals(listOf("P0AA6"), result.event!!.newCodes)
    }
}
