package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Verifies the SDP-refresh-on-repeat-failure logic. We don't unit-test the BT IntentFilter wiring
 * (that's Android-runtime territory) — just the status-broadcast handler that decides when to
 * trigger an SDP refresh. Uses a recording {@link SdpProbe} subclass.
 */
@RunWith(RobolectricTestRunner.class)
// Project targetSdk is 36; Robolectric 4.x ships SDK 34 as its newest. Pin so CI doesn't try to
// load an SDK Robolectric doesn't have (which fails with UnsupportedOperationException in
// DefaultSdkProvider). All the other Robolectric tests in this module pin the same way.
@Config(sdk = 34)
public class BluetoothStateReporterTest {

    /** Records {@link SdpProbe#trigger(String)} calls without touching the real BT stack. */
    private static class RecordingSdpProbe extends SdpProbe {
        final List<String> triggered = new ArrayList<>();

        RecordingSdpProbe() {
            super(null);
        }

        @Override
        boolean trigger(String address) {
            triggered.add(address == null ? "" : address);
            return true;
        }
    }

    private static BluetoothStateReporter newReporter(RecordingSdpProbe probe) {
        // Service is null here; the streak-tracking + SDP-trigger logic doesn't touch the service
        // (it just calls probe.trigger). The status handler short-circuits before touching the
        // recorder, which lives on the service.
        return new BluetoothStateReporter(null, probe);
    }

    @Test
    public void connectingWithoutFailureClassDoesNotIncrementStreak() {
        RecordingSdpProbe probe = new RecordingSdpProbe();
        BluetoothStateReporter reporter = newReporter(probe);
        reporter.handleStatusForTest("connecting", null);
        assertEquals(0, reporter.connectingWithFailureStreakForTest());
        assertTrue(probe.triggered.isEmpty());
    }

    @Test
    public void singleFailureDoesNotTriggerSdpRefresh() {
        RecordingSdpProbe probe = new RecordingSdpProbe();
        BluetoothStateReporter reporter = newReporter(probe);
        reporter.handleStatusForTest("connecting", "INSTANT_DROP");
        assertEquals(1, reporter.connectingWithFailureStreakForTest());
        assertTrue(probe.triggered.isEmpty());
    }

    @Test
    public void twoConsecutiveFailuresTriggerSdpRefresh() {
        RecordingSdpProbe probe = new RecordingSdpProbe();
        BluetoothStateReporter reporter = newReporter(probe);
        reporter.handleStatusForTest("connecting", "CONNECT_TIMEOUT");
        reporter.handleStatusForTest("connecting", "CONNECT_TIMEOUT");
        assertEquals(2, reporter.connectingWithFailureStreakForTest());
        assertEquals(1, probe.triggered.size());
    }

    @Test
    public void nonConnectingStateResetsStreak() {
        RecordingSdpProbe probe = new RecordingSdpProbe();
        BluetoothStateReporter reporter = newReporter(probe);
        reporter.handleStatusForTest("connecting", "INSTANT_DROP");
        reporter.handleStatusForTest("connected", null);
        assertEquals(0, reporter.connectingWithFailureStreakForTest());
        // Next failure starts over; one isolated failure still shouldn't fire the probe.
        reporter.handleStatusForTest("connecting", "INSTANT_DROP");
        assertEquals(1, reporter.connectingWithFailureStreakForTest());
        assertTrue(probe.triggered.isEmpty());
    }

    @Test
    public void connectingWithoutFailureClassBetweenFailuresBreaksStreak() {
        // Regression: previously the streak only reset on *non*-connecting states, so a
        // "connecting + failure → connecting (no failureClass) → connecting + failure" sequence
        // would be treated as two consecutive failures and trigger a spurious SDP refresh.
        RecordingSdpProbe probe = new RecordingSdpProbe();
        BluetoothStateReporter reporter = newReporter(probe);
        reporter.handleStatusForTest("connecting", "INSTANT_DROP");
        reporter.handleStatusForTest("connecting", null);
        reporter.handleStatusForTest("connecting", "INSTANT_DROP");
        assertEquals(1, reporter.connectingWithFailureStreakForTest());
        assertTrue(probe.triggered.isEmpty());
    }

    @Test
    public void streakContinuesToFireOnEachFailureAfterThreshold() {
        // Three consecutive failures → two triggers (one at the 2nd failure, one at the 3rd).
        RecordingSdpProbe probe = new RecordingSdpProbe();
        BluetoothStateReporter reporter = newReporter(probe);
        reporter.handleStatusForTest("connecting", "BT_OFF");
        reporter.handleStatusForTest("connecting", "BT_OFF");
        reporter.handleStatusForTest("connecting", "BT_OFF");
        assertEquals(3, reporter.connectingWithFailureStreakForTest());
        assertEquals(2, probe.triggered.size());
    }
}
