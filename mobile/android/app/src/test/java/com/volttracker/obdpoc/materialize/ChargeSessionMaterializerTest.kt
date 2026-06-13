package com.volttracker.obdpoc.materialize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [ChargeSessionMaterializer]. */
class ChargeSessionMaterializerTest {
    @Test
    fun noChargingSignalProducesEmpty() {
        // Driving the whole time — voltage low, speed high.
        val data = StubData()
        for (i in 0 until 10) {
            data.telemetry.add(tel(T_BASE + i * ONE_MINUTE_MS, 12.3, 45.0))
        }

        val sessions = ChargeSessionMaterializer.materialize(input(), data)

        assertEquals(0, sessions.size)
    }

    @Test
    fun continuousHighVoltageStationaryBecomesOneCharge() {
        // 10 minutes of plugged samples: voltage 14.2, speed 0.
        val data = StubData()
        for (i in 0..10) {
            data.telemetry.add(tel(T_BASE + i * ONE_MINUTE_MS, 14.2, 0.0))
        }

        val sessions = ChargeSessionMaterializer.materialize(input(), data)

        assertEquals(1, sessions.size)
        val session = sessions[0]
        assertEquals(T_BASE, session.startedAtMs)
        assertEquals(T_BASE + 10 * ONE_MINUTE_MS, session.endedAtMs)
        assertEquals(14.2, session.voltageStart!!, 0.001)
        assertEquals(14.2, session.voltageEnd!!, 0.001)
        assertEquals(0, session.interruptionCount)
        // Voltage-only inference is documented as WEAK.
        assertEquals(Confidence.WEAK, session.confidence)
        assertEquals("unknown", session.chargerType)
    }

    /**
     * A gap above the 30-minute split threshold yields two sessions. Below 30 minutes but above 5
     * the gap is counted as an interruption inside one session (covered by the
     * driving-then-charging test below).
     */
    @Test
    fun longGapSplitsIntoTwoSessions() {
        val data = StubData()
        // First 5 minutes of charging.
        for (i in 0..5) {
            data.telemetry.add(tel(T_BASE + i * ONE_MINUTE_MS, 14.2, 0.0))
        }
        // 45-minute gap with no telemetry (above SPLIT_GAP_MS = 30 min).
        val resumeAt = T_BASE + (5 + 45) * ONE_MINUTE_MS
        for (i in 0..5) {
            data.telemetry.add(tel(resumeAt + i * ONE_MINUTE_MS, 14.0, 0.0))
        }

        val sessions = ChargeSessionMaterializer.materialize(input(), data)

        assertEquals(2, sessions.size)
        assertTrue(sessions[0].endedAtMs < sessions[1].startedAtMs)
        // Both should be self-contained, no carryover interruption count from the first.
        assertEquals(0, sessions[0].interruptionCount)
        assertEquals(0, sessions[1].interruptionCount)
    }

    @Test
    fun mixedDrivingAndChargingMaterializesOnlyTheChargeWindow() {
        val data = StubData()
        // 10 minutes of driving — voltage low, moving.
        for (i in 0 until 10) {
            data.telemetry.add(tel(T_BASE + i * ONE_MINUTE_MS, 12.2, 40.0))
        }
        // Then 10 minutes of charging — voltage high, stationary.
        val chargeStart = T_BASE + 10 * ONE_MINUTE_MS
        for (i in 0..10) {
            data.telemetry.add(tel(chargeStart + i * ONE_MINUTE_MS, 14.4, 0.0))
        }
        // Then 5 more minutes of driving.
        val driveAgain = chargeStart + 11 * ONE_MINUTE_MS
        for (i in 0 until 5) {
            data.telemetry.add(tel(driveAgain + i * ONE_MINUTE_MS, 12.3, 35.0))
        }

        val sessions = ChargeSessionMaterializer.materialize(input(), data)

        assertEquals(1, sessions.size)
        val session = sessions[0]
        assertEquals(chargeStart, session.startedAtMs)
        assertEquals(chargeStart + 10 * ONE_MINUTE_MS, session.endedAtMs)
        assertEquals(14.4, session.voltageStart!!, 0.001)
    }

    @Test
    fun belowMinimumDurationIsRejected() {
        // Single plugged sample — duration 0, must be rejected.
        val data = StubData()
        data.telemetry.add(tel(T_BASE, 14.2, 0.0))

        assertEquals(0, ChargeSessionMaterializer.materialize(input(), data).size)
    }

    @Test
    fun negativePackCurrentBeatsAuxVoltage_andYieldsObservedConfidence() {
        // Pack current flowing INTO the pack (negative under the Volt sign convention) at
        // stationary speed is the high-confidence "really plugged in" signal — it should
        // override the noisier aux voltage and report OBSERVED confidence (not WEAK).
        val data = StubData()
        for (i in 0..10) {
            data.telemetry.add(telWithPackCurrent(T_BASE + i * ONE_MINUTE_MS, 0.0, -25.0))
        }

        val sessions = ChargeSessionMaterializer.materialize(input(), data)

        assertEquals(1, sessions.size)
        assertEquals(Confidence.OBSERVED, sessions[0].confidence)
    }

    @Test
    fun splitFinalizesWeakRunWithItsOwnConfidenceNotTheNextRuns() {
        // First run: aux-voltage-only evidence (documented WEAK). Then a gap above SPLIT_GAP_MS,
        // then a pack-current run (OBSERVED). The first session must be finalized with the flag
        // state as of ITS OWN samples — before the fix it inherited the next run's pack-current
        // flag and was wrongly reported OBSERVED.
        val data = StubData()
        for (i in 0..5) {
            data.telemetry.add(tel(T_BASE + i * ONE_MINUTE_MS, 14.2, 0.0))
        }
        val resumeAt = T_BASE + (5 + 45) * ONE_MINUTE_MS
        for (i in 0..5) {
            data.telemetry.add(telWithPackCurrent(resumeAt + i * ONE_MINUTE_MS, 0.0, -25.0))
        }

        val sessions = ChargeSessionMaterializer.materialize(input(), data)

        assertEquals(2, sessions.size)
        assertEquals(
            "weak-evidence first run must not borrow the next run's pack-current flag",
            Confidence.WEAK,
            sessions[0].confidence,
        )
        assertEquals(Confidence.OBSERVED, sessions[1].confidence)
    }

    @Test
    fun positivePackCurrentSuppressesAuxVoltageFalsePositive() {
        // Aux voltage is up (14.4 V) AND speed is zero — the legacy heuristic would call
        // this "plugged". But pack current is positive (discharging via DC-DC into the
        // 12V battery, or simply at rest) so pack current overrides and rejects the window.
        val data = StubData()
        for (i in 0 until 20) {
            data.telemetry.add(telFull(T_BASE + i * ONE_MINUTE_MS, 0.0, 14.4, +5.0))
        }

        val sessions = ChargeSessionMaterializer.materialize(input(), data)

        assertEquals(0, sessions.size)
    }

    @Test
    fun singleTelemetrySampleProducesEmpty() {
        // One plugged sample — zero duration, must be rejected (mirrors round-5 D1).
        val data = StubData()
        data.telemetry.add(tel(T_BASE, 14.2, 0.0))

        assertEquals(0, ChargeSessionMaterializer.materialize(input(), data).size)
    }

    @Test
    fun allZeroVoltageProducesEmpty() {
        // A long run of low aux voltage with no pack-current signal — never "plugged".
        val data = StubData()
        for (i in 0 until 30) {
            data.telemetry.add(tel(T_BASE + i * ONE_MINUTE_MS, 0.0, 0.0))
        }

        assertEquals(0, ChargeSessionMaterializer.materialize(input(), data).size)
    }

    @Test
    fun allNullVoltageProducesEmpty() {
        // Adapter dropped voltage column entirely (probe failed) AND no pack-current samples.
        // The materializer must NOT manufacture a charge session out of pure absence.
        val data = StubData()
        for (i in 0 until 30) {
            data.telemetry.add(telWithNullVoltage(T_BASE + i * ONE_MINUTE_MS, 0.0))
        }

        assertEquals(0, ChargeSessionMaterializer.materialize(input(), data).size)
    }

    @Test
    fun nullSpeedKphIsNotTreatedAsPlugged() {
        // Adapter voltage above the threshold but speedKph unknown (null). Conservative behaviour
        // per the materializer Javadoc: do NOT infer plugged when motion data is missing — the
        // car could be moving with a high alternator load and we can't tell. A long run of
        // null-speed samples must therefore yield zero charge sessions.
        val data = StubData()
        for (i in 0 until 20) {
            data.telemetry.add(telWithNullSpeed(T_BASE + i * ONE_MINUTE_MS, 14.4))
        }

        val sessions = ChargeSessionMaterializer.materialize(input(), data)

        assertEquals(0, sessions.size)
    }

    @Test
    fun transientBreakFoldsIntoOneSessionInsteadOfSplitting() {
        // One physical charge with a single glitchy moving sample in the middle (a GPS speed
        // spike / converter blip). Before the debounce this split into two sessions — the
        // over-counting the user reported. It must now materialize as ONE session that records
        // the blip as an interruption.
        val data = StubData()
        for (i in 0..10) {
            data.telemetry.add(tel(T_BASE + i * ONE_MINUTE_MS, 14.2, 0.0))
        }
        // Single transient moving sample at minute 11.
        data.telemetry.add(tel(T_BASE + 11 * ONE_MINUTE_MS, 12.2, 45.0))
        // Charging resumes well within the debounce window and runs another 10 minutes.
        for (i in 12..22) {
            data.telemetry.add(tel(T_BASE + i * ONE_MINUTE_MS, 14.2, 0.0))
        }

        val sessions = ChargeSessionMaterializer.materialize(input(), data)

        assertEquals(1, sessions.size)
        assertEquals(T_BASE, sessions[0].startedAtMs)
        assertEquals(T_BASE + 22 * ONE_MINUTE_MS, sessions[0].endedAtMs)
        assertTrue(
            "the transient break should be recorded as an interruption",
            sessions[0].interruptionCount >= 1,
        )
    }

    @Test
    fun sustainedDriveStillSplitsChargesAndIsNotStitched() {
        // The debounce must not stitch a genuine drive into a charge: charge, then a multi-minute
        // drive (well past BREAK_DEBOUNCE_MS), then charge again -> two separate sessions.
        val data = StubData()
        for (i in 0..6) {
            data.telemetry.add(tel(T_BASE + i * ONE_MINUTE_MS, 14.2, 0.0))
        }
        for (i in 7..13) {
            data.telemetry.add(tel(T_BASE + i * ONE_MINUTE_MS, 12.2, 45.0))
        }
        for (i in 14..20) {
            data.telemetry.add(tel(T_BASE + i * ONE_MINUTE_MS, 14.2, 0.0))
        }

        val sessions = ChargeSessionMaterializer.materialize(input(), data)

        assertEquals(2, sessions.size)
        assertTrue(sessions[0].endedAtMs <= sessions[1].startedAtMs)
        // The charge before the drive ends at the last plugged sample, not mid-drive.
        assertEquals(T_BASE + 6 * ONE_MINUTE_MS, sessions[0].endedAtMs)
        assertEquals(T_BASE + 14 * ONE_MINUTE_MS, sessions[1].startedAtMs)
    }

    @Test
    fun sparseTwoSampleWindowIsRejectedAsNoise() {
        // Two plugged samples a minute apart with a breaking sample between them: enough duration
        // to clear MIN_DURATION_MS, but too few samples to be a real charge. MIN_SAMPLES rejects it.
        val data = StubData()
        data.telemetry.add(telWithPackCurrent(T_BASE, 0.0, -25.0))
        data.telemetry.add(tel(T_BASE + 30_000L, 12.2, 45.0))
        data.telemetry.add(telWithPackCurrent(T_BASE + ONE_MINUTE_MS, 0.0, -25.0))

        assertEquals(0, ChargeSessionMaterializer.materialize(input(), data).size)
    }

    @Test
    fun movingSampleBreaksPluggedCandidateInsteadOfStitchingDrive() {
        val data = StubData()
        data.telemetry.add(telWithPackCurrent(T_BASE, 0.0, -8.0))
        data.telemetry.add(telFull(T_BASE + ONE_MINUTE_MS, 45.0, 13.9, +18.0))
        data.telemetry.add(telWithPackCurrent(T_BASE + 2 * ONE_MINUTE_MS, 0.0, -8.0))

        val sessions = ChargeSessionMaterializer.materialize(input(), data)

        assertEquals(0, sessions.size)
    }

    // ---- helpers ------------------------------------------------------------------

    class StubData : MaterializerData {
        val telemetry = mutableListOf<TelemetrySample>()
        val pids = mutableListOf<PidObservation>()
        val locations = mutableListOf<LocationSample>()

        override fun readLocationSamples(sessionId: Long): List<LocationSample> = locations

        override fun readPidObservations(sessionId: Long): List<PidObservation> = pids

        override fun readTelemetrySamples(sessionId: Long): List<TelemetrySample> = telemetry
    }

    companion object {
        private const val T_BASE = 1_700_000_000_000L
        private const val ONE_MINUTE_MS = 60_000L

        private fun input(): MaterializerInput = MaterializerInput(1L, T_BASE, T_BASE + 60 * ONE_MINUTE_MS)

        private fun tel(
            atMs: Long,
            voltage: Double,
            speedKph: Double,
        ): TelemetrySample = TelemetrySample(atMs, speedKph, 0, voltage, null, null)

        private fun telWithNullSpeed(
            atMs: Long,
            voltage: Double,
        ): TelemetrySample = TelemetrySample(atMs, null, 0, voltage, null, null)

        private fun telWithNullVoltage(
            atMs: Long,
            speedKph: Double,
        ): TelemetrySample = TelemetrySample(atMs, speedKph, 0, null, null, null)

        private fun telWithPackCurrent(
            atMs: Long,
            speedKph: Double,
            packCurrentA: Double,
        ): TelemetrySample =
            // adapterVoltage left null so this test isolates the pack-current path.
            TelemetrySample(atMs, speedKph, 0, null, null, packCurrentA, null, null)

        private fun telFull(
            atMs: Long,
            speedKph: Double,
            adapterVoltage: Double,
            packCurrentA: Double,
        ): TelemetrySample = TelemetrySample(atMs, speedKph, 0, adapterVoltage, null, packCurrentA, null, null)
    }
}
