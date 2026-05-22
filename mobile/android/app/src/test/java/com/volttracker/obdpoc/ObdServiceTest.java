package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.volttracker.obdpoc.data.ObdLocalStore;

import org.junit.Test;

import java.io.IOException;

/**
 * Tests the pure decision logic in {@link ObdService} — vehicle-state classification,
 * command parsing, and connection-error mapping. The connection/polling/threading code
 * is integration-level and not covered here.
 */
public class ObdServiceTest {

    // ---- classifyVehicleState ------------------------------------------------------

    @Test
    public void parkedWhenStationaryEngineOffNoCharge() {
        assertEquals("parked",
                ObdElmDecode.classifyVehicleState(12.0f, 0, 0f, 0, false));
    }

    @Test
    public void readyParkedWhenDcDcConverterIsUp() {
        // Voltage >= 13.0 with the car stationary and engine off means the car is "Ready".
        assertEquals("ready-parked",
                ObdElmDecode.classifyVehicleState(14.0f, 0, 0f, 0, false));
    }

    @Test
    public void pluggedOrChargingWhenChargeHintSet() {
        assertEquals("plugged-or-charging",
                ObdElmDecode.classifyVehicleState(12.0f, 0, 0f, 0, true));
    }

    @Test
    public void awakeParkedWhenStationaryWithLoad() {
        assertEquals("awake-parked",
                ObdElmDecode.classifyVehicleState(12.0f, 0, 0f, 30, false));
    }

    @Test
    public void drivingEvWhenMovingWithEngineOff() {
        assertEquals("driving-ev",
                ObdElmDecode.classifyVehicleState(13.5f, 60, 0f, 20, false));
    }

    @Test
    public void drivingGasWhenMovingWithEngineRunning() {
        assertEquals("driving-gas",
                ObdElmDecode.classifyVehicleState(13.5f, 60, 1500f, 20, false));
    }

    @Test
    public void engineIdleWhenStationaryWithEngineRunning() {
        assertEquals("engine-idle",
                ObdElmDecode.classifyVehicleState(13.5f, 0, 900f, 20, false));
    }

    // ---- classifyVehicleStateConfidence --------------------------------------------

    @Test
    public void confidenceInferredWhenChargeHint() {
        assertEquals("inferred",
                ObdElmDecode.classifyVehicleStateConfidence(null, null, null, true));
    }

    @Test
    public void confidenceObservedWhenAllSignalsPresent() {
        assertEquals("observed",
                ObdElmDecode.classifyVehicleStateConfidence(12.0f, 0, 0f, false));
    }

    @Test
    public void confidencePartialWhenSomeSignalsPresent() {
        assertEquals("partial",
                ObdElmDecode.classifyVehicleStateConfidence(12.0f, null, null, false));
    }

    @Test
    public void confidenceUnknownWhenNoSignals() {
        assertEquals("unknown",
                ObdElmDecode.classifyVehicleStateConfidence(null, null, null, false));
    }

    // ---- command parsing -----------------------------------------------------------

    @Test
    public void pidForCommandExtractsThePid() {
        assertEquals("0C", ObdElmDecode.pidForCommand("010C"));
        assertEquals("005B", ObdElmDecode.pidForCommand("22005B"));
        assertEquals("02", ObdElmDecode.pidForCommand("0902"));
        assertEquals("", ObdElmDecode.pidForCommand("ATRV"));
        assertEquals("", ObdElmDecode.pidForCommand(null));
    }

    @Test
    public void nameForCommandLabelsKnownCommands() {
        assertEquals("vehicle speed", ObdElmDecode.nameForCommand("010D"));
        assertEquals("engine rpm", ObdElmDecode.nameForCommand("010C"));
        assertEquals("adapter voltage", ObdElmDecode.nameForCommand("ATRV"));
        assertEquals("vin", ObdElmDecode.nameForCommand("0902"));
        assertEquals("", ObdElmDecode.nameForCommand("0199"));
    }

    @Test
    public void hasElmPromptDetectsThePromptChar() {
        assertTrue(ObdElmDecode.hasElmPrompt("41 0C 18 80\r>"));
        assertFalse(ObdElmDecode.hasElmPrompt("NO DATA"));
        assertFalse(ObdElmDecode.hasElmPrompt(null));
    }

    // ---- finishStatusFor -----------------------------------------------------------

    @Test
    public void finishStatusMapsSessionStates() {
        assertEquals(ObdLocalStore.STATUS_ERROR, ObdElmDecode.finishStatusFor("error"));
        assertEquals(ObdLocalStore.STATUS_ERROR, ObdElmDecode.finishStatusFor("blocked"));
        assertEquals(ObdLocalStore.STATUS_DISCONNECTED, ObdElmDecode.finishStatusFor("idle"));
        assertEquals(ObdLocalStore.STATUS_COMPLETE, ObdElmDecode.finishStatusFor("connected"));
        assertEquals(ObdLocalStore.STATUS_COMPLETE, ObdElmDecode.finishStatusFor("scanning"));
        assertEquals(ObdLocalStore.STATUS_COMPLETE, ObdElmDecode.finishStatusFor("scan-complete"));
    }

    @Test
    public void finishStatusIsNotCompleteForSessionThatNeverConnected() {
        // Regression: a session torn down while still connecting — e.g. the user tapped
        // Connect again before the link came up — never reached the adapter, so it must
        // not be recorded as "complete". It was, because "complete" was the catch-all.
        assertEquals(ObdLocalStore.STATUS_DISCONNECTED, ObdElmDecode.finishStatusFor("connecting"));
        assertEquals(ObdLocalStore.STATUS_DISCONNECTED, ObdElmDecode.finishStatusFor("initializing"));
        assertEquals(ObdLocalStore.STATUS_DISCONNECTED, ObdElmDecode.finishStatusFor("active"));
        assertEquals(ObdLocalStore.STATUS_DISCONNECTED, ObdElmDecode.finishStatusFor(""));
        assertEquals(ObdLocalStore.STATUS_DISCONNECTED, ObdElmDecode.finishStatusFor(null));
    }

    // ---- friendlyConnectionMessage -------------------------------------------------

    @Test
    public void friendlyMessageForSerialFailure() {
        assertTrue(ObdElmDecode.friendlyConnectionMessage(new IOException("read failed"))
                .contains("Adapter serial channel"));
    }

    @Test
    public void friendlyMessageForPermissionFailure() {
        assertTrue(ObdElmDecode.friendlyConnectionMessage(new IOException("permission denied"))
                .toLowerCase().contains("permission"));
    }

    @Test
    public void friendlyMessageFallsBackToRawMessage() {
        assertTrue(ObdElmDecode.friendlyConnectionMessage(new IOException("weird glitch"))
                .contains("weird glitch"));
    }

    // ---- summarizeForStorage (VIN redaction) ---------------------------------------

    @Test
    public void summarizeRedactsVinResponses() {
        String redacted = ObdElmDecode.summarizeForStorage("0902", "0902ABCDE");
        assertTrue(redacted.startsWith("[VIN redacted"));
        assertFalse(redacted.contains("ABCDE"));
    }

    @Test
    public void summarizeLeavesNonVinResponsesIntact() {
        assertEquals("41 0C 18 80", ObdElmDecode.summarizeForStorage("010C", "41 0C 18 80\r>"));
    }

    // ---- reconnectBackoffMs --------------------------------------------------------

    @Test
    public void reconnectBackoffGrowsExponentially() {
        assertEquals(2000L, ObdElmDecode.reconnectBackoffMs(1));
        assertEquals(4000L, ObdElmDecode.reconnectBackoffMs(2));
        assertEquals(8000L, ObdElmDecode.reconnectBackoffMs(3));
        assertEquals(16000L, ObdElmDecode.reconnectBackoffMs(4));
    }

    @Test
    public void reconnectBackoffIsCappedAtThirtySeconds() {
        assertEquals(30000L, ObdElmDecode.reconnectBackoffMs(5));
        assertEquals(30000L, ObdElmDecode.reconnectBackoffMs(6));
        assertEquals(30000L, ObdElmDecode.reconnectBackoffMs(100));
    }

    @Test
    public void reconnectBackoffIsZeroForNonPositiveAttempts() {
        assertEquals(0L, ObdElmDecode.reconnectBackoffMs(0));
        assertEquals(0L, ObdElmDecode.reconnectBackoffMs(-3));
    }

    // ---- initialConnectBackoffMs ---------------------------------------------------

    @Test
    public void initialConnectBackoffIsQuickAndCapped() {
        assertEquals(0L, ObdElmDecode.initialConnectBackoffMs(0));
        assertEquals(0L, ObdElmDecode.initialConnectBackoffMs(-1));
        assertEquals(500L, ObdElmDecode.initialConnectBackoffMs(1));
        assertEquals(1000L, ObdElmDecode.initialConnectBackoffMs(2));
        assertEquals(3000L, ObdElmDecode.initialConnectBackoffMs(6));
        assertEquals(3000L, ObdElmDecode.initialConnectBackoffMs(100));
    }

    @Test
    public void initialConnectBackoffIsNeverSlowerThanReconnectBackoff() {
        // The first connect has no link to "drop"; it should retry faster than a
        // mid-session reconnect so a transient RFCOMM glitch recovers quickly.
        for (int attempt = 1; attempt <= ObdProbes.MAX_RECONNECT_ATTEMPTS; attempt++) {
            assertTrue("attempt " + attempt,
                    ObdElmDecode.initialConnectBackoffMs(attempt)
                            <= ObdElmDecode.reconnectBackoffMs(attempt));
        }
    }
}
