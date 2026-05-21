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
                ObdService.classifyVehicleState(12.0f, 0, 0f, 0, false));
    }

    @Test
    public void readyParkedWhenDcDcConverterIsUp() {
        // Voltage >= 13.0 with the car stationary and engine off means the car is "Ready".
        assertEquals("ready-parked",
                ObdService.classifyVehicleState(14.0f, 0, 0f, 0, false));
    }

    @Test
    public void pluggedOrChargingWhenChargeHintSet() {
        assertEquals("plugged-or-charging",
                ObdService.classifyVehicleState(12.0f, 0, 0f, 0, true));
    }

    @Test
    public void awakeParkedWhenStationaryWithLoad() {
        assertEquals("awake-parked",
                ObdService.classifyVehicleState(12.0f, 0, 0f, 30, false));
    }

    @Test
    public void drivingEvWhenMovingWithEngineOff() {
        assertEquals("driving-ev",
                ObdService.classifyVehicleState(13.5f, 60, 0f, 20, false));
    }

    @Test
    public void drivingGasWhenMovingWithEngineRunning() {
        assertEquals("driving-gas",
                ObdService.classifyVehicleState(13.5f, 60, 1500f, 20, false));
    }

    @Test
    public void engineIdleWhenStationaryWithEngineRunning() {
        assertEquals("engine-idle",
                ObdService.classifyVehicleState(13.5f, 0, 900f, 20, false));
    }

    // ---- classifyVehicleStateConfidence --------------------------------------------

    @Test
    public void confidenceInferredWhenChargeHint() {
        assertEquals("inferred",
                ObdService.classifyVehicleStateConfidence(null, null, null, true));
    }

    @Test
    public void confidenceObservedWhenAllSignalsPresent() {
        assertEquals("observed",
                ObdService.classifyVehicleStateConfidence(12.0f, 0, 0f, false));
    }

    @Test
    public void confidencePartialWhenSomeSignalsPresent() {
        assertEquals("partial",
                ObdService.classifyVehicleStateConfidence(12.0f, null, null, false));
    }

    @Test
    public void confidenceUnknownWhenNoSignals() {
        assertEquals("unknown",
                ObdService.classifyVehicleStateConfidence(null, null, null, false));
    }

    // ---- command parsing -----------------------------------------------------------

    @Test
    public void pidForCommandExtractsThePid() {
        assertEquals("0C", ObdService.pidForCommand("010C"));
        assertEquals("005B", ObdService.pidForCommand("22005B"));
        assertEquals("02", ObdService.pidForCommand("0902"));
        assertEquals("", ObdService.pidForCommand("ATRV"));
        assertEquals("", ObdService.pidForCommand(null));
    }

    @Test
    public void nameForCommandLabelsKnownCommands() {
        assertEquals("vehicle speed", ObdService.nameForCommand("010D"));
        assertEquals("engine rpm", ObdService.nameForCommand("010C"));
        assertEquals("adapter voltage", ObdService.nameForCommand("ATRV"));
        assertEquals("vin", ObdService.nameForCommand("0902"));
        assertEquals("", ObdService.nameForCommand("0199"));
    }

    @Test
    public void hasElmPromptDetectsThePromptChar() {
        assertTrue(ObdService.hasElmPrompt("41 0C 18 80\r>"));
        assertFalse(ObdService.hasElmPrompt("NO DATA"));
        assertFalse(ObdService.hasElmPrompt(null));
    }

    // ---- finishStatusFor -----------------------------------------------------------

    @Test
    public void finishStatusMapsSessionStates() {
        assertEquals(ObdLocalStore.STATUS_ERROR, ObdService.finishStatusFor("error"));
        assertEquals(ObdLocalStore.STATUS_ERROR, ObdService.finishStatusFor("blocked"));
        assertEquals(ObdLocalStore.STATUS_DISCONNECTED, ObdService.finishStatusFor("idle"));
        assertEquals(ObdLocalStore.STATUS_COMPLETE, ObdService.finishStatusFor("connected"));
    }

    // ---- friendlyConnectionMessage -------------------------------------------------

    @Test
    public void friendlyMessageForSerialFailure() {
        assertTrue(ObdService.friendlyConnectionMessage(new IOException("read failed"))
                .contains("Adapter serial channel"));
    }

    @Test
    public void friendlyMessageForPermissionFailure() {
        assertTrue(ObdService.friendlyConnectionMessage(new IOException("permission denied"))
                .toLowerCase().contains("permission"));
    }

    @Test
    public void friendlyMessageFallsBackToRawMessage() {
        assertTrue(ObdService.friendlyConnectionMessage(new IOException("weird glitch"))
                .contains("weird glitch"));
    }

    // ---- summarizeForStorage (VIN redaction) ---------------------------------------

    @Test
    public void summarizeRedactsVinResponses() {
        String redacted = ObdService.summarizeForStorage("0902", "0902ABCDE");
        assertTrue(redacted.startsWith("[VIN redacted"));
        assertFalse(redacted.contains("ABCDE"));
    }

    @Test
    public void summarizeLeavesNonVinResponsesIntact() {
        assertEquals("41 0C 18 80", ObdService.summarizeForStorage("010C", "41 0C 18 80\r>"));
    }

    // ---- reconnectBackoffMs --------------------------------------------------------

    @Test
    public void reconnectBackoffGrowsExponentially() {
        assertEquals(2000L, ObdService.reconnectBackoffMs(1));
        assertEquals(4000L, ObdService.reconnectBackoffMs(2));
        assertEquals(8000L, ObdService.reconnectBackoffMs(3));
        assertEquals(16000L, ObdService.reconnectBackoffMs(4));
    }

    @Test
    public void reconnectBackoffIsCappedAtThirtySeconds() {
        assertEquals(30000L, ObdService.reconnectBackoffMs(5));
        assertEquals(30000L, ObdService.reconnectBackoffMs(6));
        assertEquals(30000L, ObdService.reconnectBackoffMs(100));
    }

    @Test
    public void reconnectBackoffIsZeroForNonPositiveAttempts() {
        assertEquals(0L, ObdService.reconnectBackoffMs(0));
        assertEquals(0L, ObdService.reconnectBackoffMs(-3));
    }
}
