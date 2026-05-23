package com.volttracker.obdpoc.classify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Pins the {@link VehicleStateClassifier} decision table. The classifier is the single source of
 * truth for "what state is the car in?" — both the JS side and downstream Java consumers read its
 * output without re-running the rules, so silent drift in this file is a real product bug.
 */
public class VehicleStateClassifierTest {

    private static final long SAMPLE_AT = 1_700_000_000_000L;

    private static ClassifierInput input(
            Double speedKph,
            Integer rpm,
            Double adapterVoltage,
            Double packCurrentA,
            Boolean pluggedHint,
            Boolean engineRunningHint) {
        return new ClassifierInput(
                speedKph,
                rpm,
                adapterVoltage,
                packCurrentA,
                pluggedHint,
                engineRunningHint,
                SAMPLE_AT);
    }

    @Test
    public void nullInputYieldsUnknown() {
        ClassifierResult result = VehicleStateClassifier.classify(null);
        assertEquals(VehicleState.UNKNOWN, result.state);
        assertEquals(Confidence.UNKNOWN, result.confidence);
        assertTrue(result.reasons.isEmpty());
    }

    @Test
    public void allNullSignalsYieldUnknown() {
        ClassifierResult result =
                VehicleStateClassifier.classify(input(null, null, null, null, null, null));
        assertEquals(VehicleState.UNKNOWN, result.state);
        assertEquals(Confidence.UNKNOWN, result.confidence);
    }

    @Test
    public void pluggedWithChargingCurrentIsCharging() {
        ClassifierResult result =
                VehicleStateClassifier.classify(input(0.0, 0, 12.6, 8.0, true, false));
        assertEquals(VehicleState.CHARGING, result.state);
        assertEquals(Confidence.CERTAIN, result.confidence);
        assertTrue(result.reasons.contains("plugged"));
        assertTrue(result.reasons.contains("current_into_pack"));
    }

    @Test
    public void pluggedWithoutCurrentIsPlugged() {
        ClassifierResult result =
                VehicleStateClassifier.classify(input(0.0, 0, 12.6, 0.0, true, false));
        assertEquals(VehicleState.PLUGGED, result.state);
        assertEquals(Confidence.OBSERVED, result.confidence);
        assertTrue(result.reasons.contains("plugged_hint"));
    }

    @Test
    public void pluggedWithNullCurrentIsPlugged() {
        ClassifierResult result =
                VehicleStateClassifier.classify(input(0.0, 0, 12.6, null, true, false));
        assertEquals(VehicleState.PLUGGED, result.state);
        assertEquals(Confidence.OBSERVED, result.confidence);
    }

    @Test
    public void engineRunningAndMovingIsDrivingGas() {
        ClassifierResult result =
                VehicleStateClassifier.classify(input(60.0, 1500, 14.0, -20.0, false, true));
        assertEquals(VehicleState.DRIVING_GAS, result.state);
        assertEquals(Confidence.OBSERVED, result.confidence);
        assertTrue(result.reasons.contains("engine_running"));
        assertTrue(result.reasons.contains("moving"));
    }

    @Test
    public void engineRunningStationaryIsReady() {
        ClassifierResult result =
                VehicleStateClassifier.classify(input(0.0, 900, 13.8, 0.0, false, true));
        assertEquals(VehicleState.READY, result.state);
        assertEquals(Confidence.OBSERVED, result.confidence);
        assertTrue(result.reasons.contains("engine_running"));
        assertTrue(result.reasons.contains("stationary"));
    }

    @Test
    public void engineRunningInferredFromRpmAlone() {
        // engineRunningHint is null but rpm > 300, which is enough to call the engine running.
        ClassifierResult result =
                VehicleStateClassifier.classify(input(0.0, 800, 13.8, null, null, null));
        assertEquals(VehicleState.READY, result.state);
    }

    @Test
    public void movingWithEngineQuietIsDrivingEv() {
        ClassifierResult result =
                VehicleStateClassifier.classify(input(35.0, 0, 14.0, -45.0, false, false));
        assertEquals(VehicleState.DRIVING_EV, result.state);
        assertEquals(Confidence.OBSERVED, result.confidence);
        assertTrue(result.reasons.contains("moving"));
        assertTrue(result.reasons.contains("engine_quiet"));
    }

    @Test
    public void movingWithNullEngineHintsIsDrivingEv() {
        // No engine signals at all + speed > 5 kph → treat as EV cruise.
        ClassifierResult result =
                VehicleStateClassifier.classify(input(25.0, null, null, null, null, null));
        assertEquals(VehicleState.DRIVING_EV, result.state);
    }

    @Test
    public void highAdapterVoltageWhenIdleIsReadyWeak() {
        ClassifierResult result =
                VehicleStateClassifier.classify(input(0.0, 0, 13.6, 0.0, false, false));
        assertEquals(VehicleState.READY, result.state);
        assertEquals(Confidence.WEAK, result.confidence);
        assertTrue(result.reasons.contains("charging_voltage"));
    }

    @Test
    public void lowAdapterVoltageWhenIdleIsParkedWeak() {
        ClassifierResult result =
                VehicleStateClassifier.classify(input(0.0, 0, 12.5, 0.0, false, false));
        assertEquals(VehicleState.PARKED, result.state);
        assertEquals(Confidence.WEAK, result.confidence);
        assertTrue(result.reasons.contains("low_voltage"));
    }

    @Test
    public void midRangeAdapterVoltageIsUnknownWeak() {
        // 12.7 < V <= 13.0 is the ambiguous band — refuse to commit either way.
        ClassifierResult result =
                VehicleStateClassifier.classify(input(0.0, 0, 12.9, 0.0, false, false));
        assertEquals(VehicleState.UNKNOWN, result.state);
        assertEquals(Confidence.WEAK, result.confidence);
        assertTrue(result.reasons.contains("voltage_ambiguous"));
    }

    @Test
    public void onlyPackCurrentSignalIsUnknown() {
        // No speed, no rpm, no voltage, no plugged hint — pack current alone shouldn't
        // promote us to a confident state.
        ClassifierResult result =
                VehicleStateClassifier.classify(input(null, null, null, 5.0, null, null));
        assertEquals(VehicleState.UNKNOWN, result.state);
        assertEquals(Confidence.UNKNOWN, result.confidence);
        assertTrue(result.reasons.contains("no_signal"));
    }

    @Test
    public void enumPayloadKeysAreStableForEveryState() {
        for (VehicleState state : VehicleState.values()) {
            String key = state.asPayloadKey();
            assertNotNull("payload key for " + state, key);
            assertFalse("payload key for " + state + " should not be empty", key.isEmpty());
            assertEquals(key, key.toLowerCase(java.util.Locale.ROOT));
        }
    }

    @Test
    public void enumPayloadKeysAreUniquePerState() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (VehicleState state : VehicleState.values()) {
            assertTrue("duplicate payload key for " + state, seen.add(state.asPayloadKey()));
        }
    }

    @Test
    public void confidencePayloadKeysAreLowercase() {
        for (Confidence c : Confidence.values()) {
            assertEquals(c.name().toLowerCase(java.util.Locale.ROOT), c.asPayloadKey());
        }
    }

    @Test
    public void resultReasonsAreImmutable() {
        ClassifierResult result =
                VehicleStateClassifier.classify(input(60.0, 0, 14.0, -45.0, false, false));
        try {
            result.reasons.add("tampering");
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // expected — List.copyOf returns an unmodifiable list.
        }
    }

    @Test
    public void classifierInputAcceptsAllNullFields() {
        // Smoke: constructing the input with every nullable slot null must not throw.
        ClassifierInput in = input(null, null, null, null, null, null);
        assertEquals(SAMPLE_AT, in.sampleAtMs);
    }
}
