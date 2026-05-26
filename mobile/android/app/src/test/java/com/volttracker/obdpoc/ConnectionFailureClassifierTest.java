package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;

import com.volttracker.obdpoc.ConnectionFailureClassifier.Input;
import java.io.IOException;
import org.junit.Test;

/**
 * Drives each heuristic branch of {@link ConnectionFailureClassifier} against a curated input. The
 * classifier is pure (no Android imports) so this test runs on the host JVM without Robolectric.
 */
public class ConnectionFailureClassifierTest {

    @Test
    public void btOffWinsEvenWhenMessageMatchesOtherBranches() {
        // Even a "connection refused" message must not override the bluetoothEnabled signal —
        // a disabled adapter is the user-actionable root cause and should bubble up first.
        Input input =
                ConnectionFailureClassifier.ofRaw(
                        "rfcomm_connect", 0L, "connection refused", "java.io.IOException", false);
        assertEquals(FailureClass.BT_OFF, ConnectionFailureClassifier.classify(input));
    }

    @Test
    public void sdpFailureMatchesServiceDiscoveryMessages() {
        Input input =
                ConnectionFailureClassifier.ofRaw(
                        "rfcomm_connect",
                        250L,
                        "service discovery failed",
                        "java.io.IOException",
                        true);
        assertEquals(FailureClass.SDP_FAILURE, ConnectionFailureClassifier.classify(input));
    }

    @Test
    public void remoteRefusedMatchesConnectionRefusedMessages() {
        Input input =
                ConnectionFailureClassifier.ofRaw(
                        "rfcomm_connect", 140L, "connection refused", "java.io.IOException", true);
        assertEquals(FailureClass.REMOTE_REFUSED, ConnectionFailureClassifier.classify(input));
    }

    @Test
    public void bondLostMatchesPermissionDeniedMessages() {
        Input input =
                ConnectionFailureClassifier.ofRaw(
                        "rfcomm_connect",
                        90L,
                        "java.io.ioexception: permission denied",
                        "java.io.IOException",
                        true);
        assertEquals(FailureClass.BOND_LOST, ConnectionFailureClassifier.classify(input));
    }

    @Test
    public void bondLostMatchesBondStateChangedMessages() {
        Input input =
                ConnectionFailureClassifier.ofRaw(
                        "rfcomm_connect",
                        90L,
                        "remote bond state changed",
                        "java.io.IOException",
                        true);
        assertEquals(FailureClass.BOND_LOST, ConnectionFailureClassifier.classify(input));
    }

    @Test
    public void connectPhaseFailureWithoutOtherSignalsIsConnectTimeout() {
        Input input =
                ConnectionFailureClassifier.ofRaw(
                        "rfcomm_connect",
                        1500L,
                        "io error during connect",
                        "java.io.IOException",
                        true);
        assertEquals(FailureClass.CONNECT_TIMEOUT, ConnectionFailureClassifier.classify(input));
    }

    @Test
    public void getStreamsPhaseFailureIsConnectTimeout() {
        Input input =
                ConnectionFailureClassifier.ofRaw(
                        "get_streams", 8L, "stream closed", "java.io.IOException", true);
        assertEquals(FailureClass.CONNECT_TIMEOUT, ConnectionFailureClassifier.classify(input));
    }

    @Test
    public void firstReadFailureUnderThresholdIsInstantDrop() {
        // The reproducer fingerprint from the user's three failed sessions: socket opens,
        // first read returns -1 within ~250 ms.
        Input input =
                ConnectionFailureClassifier.ofRaw(
                        "first_read",
                        240L,
                        "read failed, socket might closed or timeout, read ret: -1",
                        "java.io.IOException",
                        true);
        assertEquals(FailureClass.INSTANT_DROP, ConnectionFailureClassifier.classify(input));
    }

    @Test
    public void firstReadFailureOverThresholdIsConnectTimeout() {
        // Identical message, but the read took 1500 ms — that's a slow / wedged adapter still
        // talking, not the instant-drop signature. Falls through the instant_drop guard and
        // lands in the generic timeout bucket.
        Input input =
                ConnectionFailureClassifier.ofRaw(
                        "first_read",
                        1500L,
                        "read failed, socket might closed or timeout, read ret: -1",
                        "java.io.IOException",
                        true);
        assertEquals(FailureClass.CONNECT_TIMEOUT, ConnectionFailureClassifier.classify(input));
    }

    @Test
    public void unknownPhaseAndUnknownMessageBucketsAsUnknown() {
        Input input =
                ConnectionFailureClassifier.ofRaw(
                        "post_connect", 100L, "kaboom unexpected", "java.io.IOException", true);
        assertEquals(FailureClass.UNKNOWN, ConnectionFailureClassifier.classify(input));
    }

    @Test
    public void nullInputDefaultsToUnknown() {
        assertEquals(FailureClass.UNKNOWN, ConnectionFailureClassifier.classify((Input) null));
    }

    @Test
    public void ofConvenienceLowercasesMessage() {
        // Mixed-case message must still match the lowercased heuristic.
        IOException ex = new IOException("Service Discovery Failed");
        assertEquals(
                FailureClass.SDP_FAILURE,
                ConnectionFailureClassifier.classify("rfcomm_connect", 100L, ex, true));
    }

    @Test
    public void ofConvenienceHandlesNullExceptionMessage() {
        // Messages can be null; ensure that does not NPE. A null-message rfcomm_connect failure
        // still has an actionable bucket (CONNECT_TIMEOUT) because the phase signal is enough to
        // classify it even without a message hint.
        IOException ex = new IOException();
        assertEquals(
                FailureClass.CONNECT_TIMEOUT,
                ConnectionFailureClassifier.classify("rfcomm_connect", 100L, ex, true));
    }
}
