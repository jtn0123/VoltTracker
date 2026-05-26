package com.volttracker.obdpoc.materialize;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Unit tests for {@link ChargeSessionMaterializer}. */
public class ChargeSessionMaterializerTest {

    private static final long T_BASE = 1_700_000_000_000L;
    private static final long ONE_MINUTE_MS = 60_000L;

    @Test
    public void noChargingSignalProducesEmpty() {
        // Driving the whole time — voltage low, speed high.
        StubData data = new StubData();
        for (int i = 0; i < 10; i++) {
            data.telemetry.add(tel(T_BASE + i * ONE_MINUTE_MS, 12.3, 45.0));
        }

        List<ChargeSession> sessions = ChargeSessionMaterializer.materialize(input(), data);

        assertEquals(0, sessions.size());
    }

    @Test
    public void continuousHighVoltageStationaryBecomesOneCharge() {
        // 10 minutes of plugged samples: voltage 14.2, speed 0.
        StubData data = new StubData();
        for (int i = 0; i <= 10; i++) {
            data.telemetry.add(tel(T_BASE + i * ONE_MINUTE_MS, 14.2, 0.0));
        }

        List<ChargeSession> sessions = ChargeSessionMaterializer.materialize(input(), data);

        assertEquals(1, sessions.size());
        ChargeSession session = sessions.get(0);
        assertEquals(T_BASE, session.startedAtMs);
        assertEquals(T_BASE + 10 * ONE_MINUTE_MS, session.endedAtMs);
        assertEquals(14.2, session.voltageStart, 0.001);
        assertEquals(14.2, session.voltageEnd, 0.001);
        assertEquals(0, session.interruptionCount);
        // Voltage-only inference is documented as WEAK.
        assertEquals(Confidence.WEAK, session.confidence);
        assertEquals("unknown", session.chargerType);
    }

    /**
     * A gap above the 30-minute split threshold yields two sessions. Below 30 minutes but above 5
     * the gap is counted as an interruption inside one session (covered by the
     * driving-then-charging test below).
     */
    @Test
    public void longGapSplitsIntoTwoSessions() {
        StubData data = new StubData();
        // First 5 minutes of charging.
        for (int i = 0; i <= 5; i++) {
            data.telemetry.add(tel(T_BASE + i * ONE_MINUTE_MS, 14.2, 0.0));
        }
        // 45-minute gap with no telemetry (above SPLIT_GAP_MS = 30 min).
        long resumeAt = T_BASE + (5 + 45) * ONE_MINUTE_MS;
        for (int i = 0; i <= 5; i++) {
            data.telemetry.add(tel(resumeAt + i * ONE_MINUTE_MS, 14.0, 0.0));
        }

        List<ChargeSession> sessions = ChargeSessionMaterializer.materialize(input(), data);

        assertEquals(2, sessions.size());
        assertTrue(sessions.get(0).endedAtMs < sessions.get(1).startedAtMs);
        // Both should be self-contained, no carryover interruption count from the first.
        assertEquals(0, sessions.get(0).interruptionCount);
        assertEquals(0, sessions.get(1).interruptionCount);
    }

    @Test
    public void mixedDrivingAndChargingMaterializesOnlyTheChargeWindow() {
        StubData data = new StubData();
        // 10 minutes of driving — voltage low, moving.
        for (int i = 0; i < 10; i++) {
            data.telemetry.add(tel(T_BASE + i * ONE_MINUTE_MS, 12.2, 40.0));
        }
        // Then 10 minutes of charging — voltage high, stationary.
        long chargeStart = T_BASE + 10 * ONE_MINUTE_MS;
        for (int i = 0; i <= 10; i++) {
            data.telemetry.add(tel(chargeStart + i * ONE_MINUTE_MS, 14.4, 0.0));
        }
        // Then 5 more minutes of driving.
        long driveAgain = chargeStart + 11 * ONE_MINUTE_MS;
        for (int i = 0; i < 5; i++) {
            data.telemetry.add(tel(driveAgain + i * ONE_MINUTE_MS, 12.3, 35.0));
        }

        List<ChargeSession> sessions = ChargeSessionMaterializer.materialize(input(), data);

        assertEquals(1, sessions.size());
        ChargeSession session = sessions.get(0);
        assertEquals(chargeStart, session.startedAtMs);
        assertEquals(chargeStart + 10 * ONE_MINUTE_MS, session.endedAtMs);
        assertEquals(14.4, session.voltageStart, 0.001);
    }

    @Test
    public void belowMinimumDurationIsRejected() {
        // Single plugged sample — duration 0, must be rejected.
        StubData data = new StubData();
        data.telemetry.add(tel(T_BASE, 14.2, 0.0));

        assertEquals(0, ChargeSessionMaterializer.materialize(input(), data).size());
    }

    @Test
    public void negativePackCurrentBeatsAuxVoltage_andYieldsObservedConfidence() {
        // Pack current flowing INTO the pack (negative under the Volt sign convention) at
        // stationary speed is the high-confidence "really plugged in" signal — it should
        // override the noisier aux voltage and report OBSERVED confidence (not WEAK).
        StubData data = new StubData();
        for (int i = 0; i <= 10; i++) {
            data.telemetry.add(telWithPackCurrent(T_BASE + i * ONE_MINUTE_MS, 0.0, -25.0));
        }

        List<ChargeSession> sessions = ChargeSessionMaterializer.materialize(input(), data);

        assertEquals(1, sessions.size());
        assertEquals(Confidence.OBSERVED, sessions.get(0).confidence);
    }

    @Test
    public void positivePackCurrentSuppressesAuxVoltageFalsePositive() {
        // Aux voltage is up (14.4 V) AND speed is zero — the legacy heuristic would call
        // this "plugged". But pack current is positive (discharging via DC-DC into the
        // 12V battery, or simply at rest) so pack current overrides and rejects the window.
        StubData data = new StubData();
        for (int i = 0; i < 20; i++) {
            data.telemetry.add(telFull(T_BASE + i * ONE_MINUTE_MS, 0.0, 14.4, +5.0));
        }

        List<ChargeSession> sessions = ChargeSessionMaterializer.materialize(input(), data);

        assertEquals(0, sessions.size());
    }

    @Test
    public void singleTelemetrySampleProducesEmpty() {
        // One plugged sample — zero duration, must be rejected (mirrors round-5 D1).
        StubData data = new StubData();
        data.telemetry.add(tel(T_BASE, 14.2, 0.0));

        assertEquals(0, ChargeSessionMaterializer.materialize(input(), data).size());
    }

    @Test
    public void allZeroVoltageProducesEmpty() {
        // A long run of low aux voltage with no pack-current signal — never "plugged".
        StubData data = new StubData();
        for (int i = 0; i < 30; i++) {
            data.telemetry.add(tel(T_BASE + i * ONE_MINUTE_MS, 0.0, 0.0));
        }

        assertEquals(0, ChargeSessionMaterializer.materialize(input(), data).size());
    }

    @Test
    public void allNullVoltageProducesEmpty() {
        // Adapter dropped voltage column entirely (probe failed) AND no pack-current samples.
        // The materializer must NOT manufacture a charge session out of pure absence.
        StubData data = new StubData();
        for (int i = 0; i < 30; i++) {
            data.telemetry.add(telWithNullVoltage(T_BASE + i * ONE_MINUTE_MS, 0.0));
        }

        assertEquals(0, ChargeSessionMaterializer.materialize(input(), data).size());
    }

    @Test
    public void nullSpeedKphIsNotTreatedAsPlugged() {
        // Adapter voltage above the threshold but speedKph unknown (null). Conservative behaviour
        // per the materializer Javadoc: do NOT infer plugged when motion data is missing — the
        // car could be moving with a high alternator load and we can't tell. A long run of
        // null-speed samples must therefore yield zero charge sessions.
        StubData data = new StubData();
        for (int i = 0; i < 20; i++) {
            data.telemetry.add(telWithNullSpeed(T_BASE + i * ONE_MINUTE_MS, 14.4));
        }

        List<ChargeSession> sessions = ChargeSessionMaterializer.materialize(input(), data);

        assertEquals(0, sessions.size());
    }

    // ---- helpers ------------------------------------------------------------------

    private static MaterializerInput input() {
        return new MaterializerInput(1L, T_BASE, T_BASE + 60 * ONE_MINUTE_MS);
    }

    private static TelemetrySample tel(long atMs, double voltage, double speedKph) {
        return new TelemetrySample(atMs, speedKph, 0, voltage, null, null);
    }

    private static TelemetrySample telWithNullSpeed(long atMs, double voltage) {
        return new TelemetrySample(atMs, null, 0, voltage, null, null);
    }

    private static TelemetrySample telWithNullVoltage(long atMs, double speedKph) {
        return new TelemetrySample(atMs, speedKph, 0, null, null, null);
    }

    private static TelemetrySample telWithPackCurrent(
            long atMs, double speedKph, double packCurrentA) {
        // adapterVoltage left null so this test isolates the pack-current path.
        return new TelemetrySample(atMs, speedKph, 0, null, null, packCurrentA, null, null);
    }

    private static TelemetrySample telFull(
            long atMs, double speedKph, double adapterVoltage, double packCurrentA) {
        return new TelemetrySample(
                atMs, speedKph, 0, adapterVoltage, null, packCurrentA, null, null);
    }

    static final class StubData implements MaterializerData {
        final List<TelemetrySample> telemetry = new ArrayList<>();
        final List<PidObservation> pids = new ArrayList<>();
        final List<LocationSample> locations = new ArrayList<>();

        @Override
        public List<LocationSample> readLocationSamples(long sessionId) {
            return Collections.unmodifiableList(locations);
        }

        @Override
        public List<PidObservation> readPidObservations(long sessionId) {
            return Collections.unmodifiableList(pids);
        }

        @Override
        public List<TelemetrySample> readTelemetrySamples(long sessionId) {
            return Collections.unmodifiableList(telemetry);
        }
    }
}
