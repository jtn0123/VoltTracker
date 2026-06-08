package com.volttracker.obdpoc

import com.volttracker.obdpoc.data.ObdLocalStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Tests the pure decision logic in [ObdService] — command parsing and connection-error mapping.
 * Vehicle-state classification moved to
 * `com.volttracker.obdpoc.classify.VehicleStateClassifierTest`. The connection/polling/threading
 * code is integration-level and not covered here.
 */
class ObdServiceTest {
    // ---- command parsing -----------------------------------------------------------

    @Test
    fun pidForCommandExtractsThePid() {
        assertEquals("0C", ObdElmDecode.pidForCommand("010C"))
        assertEquals("005B", ObdElmDecode.pidForCommand("22005B"))
        assertEquals("02", ObdElmDecode.pidForCommand("0902"))
        assertEquals("", ObdElmDecode.pidForCommand("ATRV"))
        assertEquals("", ObdElmDecode.pidForCommand(null))
    }

    @Test
    fun nameForCommandLabelsKnownCommands() {
        assertEquals("vehicle speed", ObdElmDecode.nameForCommand("010D"))
        assertEquals("engine rpm", ObdElmDecode.nameForCommand("010C"))
        assertEquals("adapter voltage", ObdElmDecode.nameForCommand("ATRV"))
        assertEquals("control module voltage", ObdElmDecode.nameForCommand("0142"))
        assertEquals("hv battery raw soc", ObdElmDecode.nameForCommand("2243AF"))
        assertEquals("candidate tire pressure front-left", ObdElmDecode.nameForCommand("22248E"))
        assertEquals("vin", ObdElmDecode.nameForCommand("0902"))
        assertEquals("", ObdElmDecode.nameForCommand("0199"))
    }

    @Test
    fun hasElmPromptDetectsThePromptChar() {
        assertTrue(ObdElmDecode.hasElmPrompt("41 0C 18 80\r>"))
        assertFalse(ObdElmDecode.hasElmPrompt("NO DATA"))
        assertFalse(ObdElmDecode.hasElmPrompt(null))
    }

    // ---- finishStatusFor -----------------------------------------------------------

    @Test
    fun finishStatusMapsSessionStates() {
        assertEquals(ObdLocalStore.STATUS_ERROR, ObdElmDecode.finishStatusFor("error"))
        assertEquals(ObdLocalStore.STATUS_ERROR, ObdElmDecode.finishStatusFor("blocked"))
        assertEquals(ObdLocalStore.STATUS_DISCONNECTED, ObdElmDecode.finishStatusFor("idle"))
        assertEquals(ObdLocalStore.STATUS_COMPLETE, ObdElmDecode.finishStatusFor("connected"))
        assertEquals(ObdLocalStore.STATUS_COMPLETE, ObdElmDecode.finishStatusFor("scanning"))
        assertEquals(ObdLocalStore.STATUS_COMPLETE, ObdElmDecode.finishStatusFor("scan-complete"))
    }

    @Test
    fun finishStatusIsNotCompleteForSessionThatNeverConnected() {
        // Regression: a session torn down while still connecting — e.g. the user tapped
        // Connect again before the link came up — never reached the adapter, so it must
        // not be recorded as "complete". It was, because "complete" was the catch-all.
        assertEquals(ObdLocalStore.STATUS_DISCONNECTED, ObdElmDecode.finishStatusFor("connecting"))
        assertEquals(
            ObdLocalStore.STATUS_DISCONNECTED,
            ObdElmDecode.finishStatusFor("initializing"),
        )
        assertEquals(ObdLocalStore.STATUS_DISCONNECTED, ObdElmDecode.finishStatusFor("active"))
        assertEquals(ObdLocalStore.STATUS_DISCONNECTED, ObdElmDecode.finishStatusFor(""))
        assertEquals(ObdLocalStore.STATUS_DISCONNECTED, ObdElmDecode.finishStatusFor(null))
    }

    // ---- friendlyConnectionMessage -------------------------------------------------

    @Test
    fun friendlyMessageForSerialFailure() {
        assertTrue(
            ObdElmDecode
                .friendlyConnectionMessage(IOException("read failed"))
                .contains("Adapter serial channel"),
        )
    }

    @Test
    fun friendlyMessageForPermissionFailure() {
        assertTrue(
            ObdElmDecode
                .friendlyConnectionMessage(IOException("permission denied"))
                .lowercase()
                .contains("permission"),
        )
    }

    @Test
    fun friendlyMessageFallsBackToRawMessage() {
        assertTrue(
            ObdElmDecode
                .friendlyConnectionMessage(IOException("weird glitch"))
                .contains("weird glitch"),
        )
    }

    // ---- summarizeForStorage (VIN redaction) ---------------------------------------

    @Test
    fun summarizeRedactsVinResponses() {
        val redacted = ObdElmDecode.summarizeForStorage("0902", "0902ABCDE")
        assertTrue(redacted.startsWith("[VIN redacted"))
        assertFalse(redacted.contains("ABCDE"))
    }

    @Test
    fun summarizeLeavesNonVinResponsesIntact() {
        assertEquals("41 0C 18 80", ObdElmDecode.summarizeForStorage("010C", "41 0C 18 80\r>"))
    }

    // ---- reconnectBackoffMs --------------------------------------------------------

    @Test
    fun reconnectBackoffGrowsExponentially() {
        assertEquals(2000L, ObdElmDecode.reconnectBackoffMs(1))
        assertEquals(4000L, ObdElmDecode.reconnectBackoffMs(2))
        assertEquals(8000L, ObdElmDecode.reconnectBackoffMs(3))
        assertEquals(16000L, ObdElmDecode.reconnectBackoffMs(4))
    }

    @Test
    fun reconnectBackoffIsCappedAtThirtySeconds() {
        assertEquals(30000L, ObdElmDecode.reconnectBackoffMs(5))
        assertEquals(30000L, ObdElmDecode.reconnectBackoffMs(6))
        assertEquals(30000L, ObdElmDecode.reconnectBackoffMs(100))
    }

    @Test
    fun reconnectBackoffIsZeroForNonPositiveAttempts() {
        assertEquals(0L, ObdElmDecode.reconnectBackoffMs(0))
        assertEquals(0L, ObdElmDecode.reconnectBackoffMs(-3))
    }

    // ---- initialConnectBackoffMs ---------------------------------------------------

    @Test
    fun initialConnectBackoffIsQuickAndCapped() {
        assertEquals(0L, ObdElmDecode.initialConnectBackoffMs(0))
        assertEquals(0L, ObdElmDecode.initialConnectBackoffMs(-1))
        assertEquals(500L, ObdElmDecode.initialConnectBackoffMs(1))
        assertEquals(1000L, ObdElmDecode.initialConnectBackoffMs(2))
        assertEquals(3000L, ObdElmDecode.initialConnectBackoffMs(6))
        assertEquals(3000L, ObdElmDecode.initialConnectBackoffMs(100))
    }

    @Test
    fun initialConnectBackoffIsNeverSlowerThanReconnectBackoff() {
        // The first connect has no link to "drop"; it should retry faster than a
        // mid-session reconnect so a transient RFCOMM glitch recovers quickly.
        for (attempt in 1..ObdProbes.MAX_RECONNECT_ATTEMPTS) {
            assertTrue(
                "attempt $attempt",
                ObdElmDecode.initialConnectBackoffMs(attempt) <=
                    ObdElmDecode.reconnectBackoffMs(attempt),
            )
        }
    }
}
