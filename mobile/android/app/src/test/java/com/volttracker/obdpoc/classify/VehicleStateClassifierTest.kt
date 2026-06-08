package com.volttracker.obdpoc.classify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Pins the [VehicleStateClassifier] decision table. The classifier is the single source of truth
 * for "what state is the car in?" — both the JS side and downstream consumers read its output
 * without re-running the rules, so silent drift in this file is a real product bug.
 */
class VehicleStateClassifierTest {
    private fun input(
        speedKph: Double?,
        rpm: Int?,
        adapterVoltage: Double?,
        packCurrentA: Double?,
        pluggedHint: Boolean?,
        engineRunningHint: Boolean?,
    ): ClassifierInput =
        ClassifierInput(
            speedKph,
            rpm,
            adapterVoltage,
            packCurrentA,
            pluggedHint,
            engineRunningHint,
            SAMPLE_AT,
        )

    @Test
    fun nullInputYieldsUnknown() {
        val result = VehicleStateClassifier.classify(null)
        assertEquals(VehicleState.UNKNOWN, result.state)
        assertEquals(Confidence.UNKNOWN, result.confidence)
        assertTrue(result.reasons.isEmpty())
    }

    @Test
    fun allNullSignalsYieldUnknown() {
        val result =
            VehicleStateClassifier.classify(input(null, null, null, null, null, null))
        assertEquals(VehicleState.UNKNOWN, result.state)
        assertEquals(Confidence.UNKNOWN, result.confidence)
    }

    @Test
    fun pluggedWithChargingCurrentIsCharging() {
        // Discharge-positive convention (see ClassifierInput.packCurrentA javadoc): real
        // charging current is NEGATIVE (flowing INTO the pack).
        val result =
            VehicleStateClassifier.classify(input(0.0, 0, 12.6, -8.0, true, false))
        assertEquals(VehicleState.CHARGING, result.state)
        assertEquals(Confidence.CERTAIN, result.confidence)
        assertTrue(result.reasons.contains("plugged"))
        assertTrue(result.reasons.contains("current_into_pack"))
    }

    @Test
    fun pluggedWhileDischargingIsJustPlugged() {
        // V2L / accessory load with the car plugged in: positive current = discharging.
        // Should NOT be classified as CHARGING.
        val result =
            VehicleStateClassifier.classify(input(0.0, 0, 12.6, 8.0, true, false))
        assertEquals(VehicleState.PLUGGED, result.state)
        assertEquals(Confidence.OBSERVED, result.confidence)
    }

    @Test
    fun pluggedWithoutCurrentIsPlugged() {
        val result =
            VehicleStateClassifier.classify(input(0.0, 0, 12.6, 0.0, true, false))
        assertEquals(VehicleState.PLUGGED, result.state)
        assertEquals(Confidence.OBSERVED, result.confidence)
        assertTrue(result.reasons.contains("plugged_hint"))
    }

    @Test
    fun pluggedWithNullCurrentIsPlugged() {
        val result =
            VehicleStateClassifier.classify(input(0.0, 0, 12.6, null, true, false))
        assertEquals(VehicleState.PLUGGED, result.state)
        assertEquals(Confidence.OBSERVED, result.confidence)
    }

    @Test
    fun engineRunningAndMovingIsDrivingGas() {
        val result =
            VehicleStateClassifier.classify(input(60.0, 1500, 14.0, -20.0, false, true))
        assertEquals(VehicleState.DRIVING_GAS, result.state)
        assertEquals(Confidence.OBSERVED, result.confidence)
        assertTrue(result.reasons.contains("engine_running"))
        assertTrue(result.reasons.contains("moving"))
    }

    @Test
    fun engineRunningStationaryIsReady() {
        val result =
            VehicleStateClassifier.classify(input(0.0, 900, 13.8, 0.0, false, true))
        assertEquals(VehicleState.READY, result.state)
        assertEquals(Confidence.OBSERVED, result.confidence)
        assertTrue(result.reasons.contains("engine_running"))
        assertTrue(result.reasons.contains("stationary"))
    }

    @Test
    fun engineRunningInferredFromRpmAlone() {
        // engineRunningHint is null but rpm > 300, which is enough to call the engine running.
        val result =
            VehicleStateClassifier.classify(input(0.0, 800, 13.8, null, null, null))
        assertEquals(VehicleState.READY, result.state)
    }

    @Test
    fun movingWithEngineQuietIsDrivingEv() {
        val result =
            VehicleStateClassifier.classify(input(35.0, 0, 14.0, -45.0, false, false))
        assertEquals(VehicleState.DRIVING_EV, result.state)
        assertEquals(Confidence.OBSERVED, result.confidence)
        assertTrue(result.reasons.contains("moving"))
        assertTrue(result.reasons.contains("engine_quiet"))
    }

    @Test
    fun movingWithNullEngineHintsIsDrivingEv() {
        // No engine signals at all + speed > 5 kph → treat as EV cruise.
        val result =
            VehicleStateClassifier.classify(input(25.0, null, null, null, null, null))
        assertEquals(VehicleState.DRIVING_EV, result.state)
    }

    @Test
    fun highAdapterVoltageWhenIdleIsReadyWeak() {
        val result =
            VehicleStateClassifier.classify(input(0.0, 0, 13.6, 0.0, false, false))
        assertEquals(VehicleState.READY, result.state)
        assertEquals(Confidence.WEAK, result.confidence)
        assertTrue(result.reasons.contains("charging_voltage"))
    }

    @Test
    fun lowAdapterVoltageWhenIdleIsParkedWeak() {
        val result =
            VehicleStateClassifier.classify(input(0.0, 0, 12.5, 0.0, false, false))
        assertEquals(VehicleState.PARKED, result.state)
        assertEquals(Confidence.WEAK, result.confidence)
        assertTrue(result.reasons.contains("low_voltage"))
    }

    @Test
    fun midRangeAdapterVoltageIsUnknownWeak() {
        // 12.7 < V <= 13.0 is the ambiguous band — refuse to commit either way.
        val result =
            VehicleStateClassifier.classify(input(0.0, 0, 12.9, 0.0, false, false))
        assertEquals(VehicleState.UNKNOWN, result.state)
        assertEquals(Confidence.WEAK, result.confidence)
        assertTrue(result.reasons.contains("voltage_ambiguous"))
    }

    @Test
    fun onlyPackCurrentSignalIsUnknown() {
        // No speed, no rpm, no voltage, no plugged hint — pack current alone shouldn't
        // promote us to a confident state.
        val result =
            VehicleStateClassifier.classify(input(null, null, null, 5.0, null, null))
        assertEquals(VehicleState.UNKNOWN, result.state)
        assertEquals(Confidence.UNKNOWN, result.confidence)
        assertTrue(result.reasons.contains("no_signal"))
    }

    @Test
    fun enumPayloadKeysAreStableForEveryState() {
        for (state in VehicleState.values()) {
            val key = state.asPayloadKey()
            assertNotNull("payload key for $state", key)
            assertFalse("payload key for $state should not be empty", key.isEmpty())
            assertEquals(key, key.lowercase(Locale.ROOT))
        }
    }

    @Test
    fun enumPayloadKeysAreUniquePerState() {
        val seen = HashSet<String>()
        for (state in VehicleState.values()) {
            assertTrue("duplicate payload key for $state", seen.add(state.asPayloadKey()))
        }
    }

    @Test
    fun confidencePayloadKeysAreLowercase() {
        for (c in Confidence.values()) {
            assertEquals(c.name.lowercase(Locale.ROOT), c.asPayloadKey())
        }
    }

    @Test
    fun resultReasonsAreImmutable() {
        val result =
            VehicleStateClassifier.classify(input(60.0, 0, 14.0, -45.0, false, false))
        assertThrows(UnsupportedOperationException::class.java) {
            (result.reasons as MutableList<String>).add("tampering")
        }
    }

    @Test
    fun classifierInputAcceptsAllNullFields() {
        // Smoke: constructing the input with every nullable slot null must not throw.
        val input = input(null, null, null, null, null, null)
        assertEquals(SAMPLE_AT, input.sampleAtMs)
    }

    companion object {
        private const val SAMPLE_AT = 1_700_000_000_000L
    }
}
