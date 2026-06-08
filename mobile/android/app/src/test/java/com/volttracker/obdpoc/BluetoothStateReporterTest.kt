package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the SDP-refresh-on-repeat-failure logic. We don't unit-test the BT IntentFilter wiring
 * (that's Android-runtime territory) — just the status-broadcast handler that decides when to
 * trigger an SDP refresh. Uses a recording [SdpProbe] subclass.
 */
@RunWith(RobolectricTestRunner::class)
// Project targetSdk is 36; Robolectric 4.x ships SDK 34 as its newest. Pin so CI doesn't try to
// load an SDK Robolectric doesn't have (which fails with UnsupportedOperationException in
// DefaultSdkProvider). All the other Robolectric tests in this module pin the same way.
@Config(sdk = [34])
class BluetoothStateReporterTest {
    /** Records [SdpProbe.trigger] calls without touching the real BT stack. */
    private class RecordingSdpProbe : SdpProbe(null) {
        val triggered: MutableList<String> = ArrayList()

        override fun trigger(address: String?): Boolean {
            triggered.add(address ?: "")
            return true
        }
    }

    @Test
    fun connectingWithoutFailureClassDoesNotIncrementStreak() {
        val probe = RecordingSdpProbe()
        val reporter = newReporter(probe)
        reporter.handleStatusForTest("connecting", null)
        assertEquals(0, reporter.connectingWithFailureStreakForTest())
        assertTrue(probe.triggered.isEmpty())
    }

    @Test
    fun singleFailureDoesNotTriggerSdpRefresh() {
        val probe = RecordingSdpProbe()
        val reporter = newReporter(probe)
        reporter.handleStatusForTest("connecting", "INSTANT_DROP")
        assertEquals(1, reporter.connectingWithFailureStreakForTest())
        assertTrue(probe.triggered.isEmpty())
    }

    @Test
    fun twoConsecutiveFailuresTriggerSdpRefresh() {
        val probe = RecordingSdpProbe()
        val reporter = newReporter(probe)
        reporter.handleStatusForTest("connecting", "CONNECT_TIMEOUT")
        reporter.handleStatusForTest("connecting", "CONNECT_TIMEOUT")
        assertEquals(2, reporter.connectingWithFailureStreakForTest())
        assertEquals(1, probe.triggered.size)
    }

    @Test
    fun nonConnectingStateResetsStreak() {
        val probe = RecordingSdpProbe()
        val reporter = newReporter(probe)
        reporter.handleStatusForTest("connecting", "INSTANT_DROP")
        reporter.handleStatusForTest("connected", null)
        assertEquals(0, reporter.connectingWithFailureStreakForTest())
        // Next failure starts over; one isolated failure still shouldn't fire the probe.
        reporter.handleStatusForTest("connecting", "INSTANT_DROP")
        assertEquals(1, reporter.connectingWithFailureStreakForTest())
        assertTrue(probe.triggered.isEmpty())
    }

    @Test
    fun connectingWithoutFailureClassBetweenFailuresBreaksStreak() {
        // Regression: previously the streak only reset on *non*-connecting states, so a
        // "connecting + failure → connecting (no failureClass) → connecting + failure" sequence
        // would be treated as two consecutive failures and trigger a spurious SDP refresh.
        val probe = RecordingSdpProbe()
        val reporter = newReporter(probe)
        reporter.handleStatusForTest("connecting", "INSTANT_DROP")
        reporter.handleStatusForTest("connecting", null)
        reporter.handleStatusForTest("connecting", "INSTANT_DROP")
        assertEquals(1, reporter.connectingWithFailureStreakForTest())
        assertTrue(probe.triggered.isEmpty())
    }

    @Test
    fun streakContinuesToFireOnEachFailureAfterThreshold() {
        // Three consecutive failures → two triggers (one at the 2nd failure, one at the 3rd).
        val probe = RecordingSdpProbe()
        val reporter = newReporter(probe)
        reporter.handleStatusForTest("connecting", "BT_OFF")
        reporter.handleStatusForTest("connecting", "BT_OFF")
        reporter.handleStatusForTest("connecting", "BT_OFF")
        assertEquals(3, reporter.connectingWithFailureStreakForTest())
        assertEquals(2, probe.triggered.size)
    }

    private companion object {
        fun newReporter(probe: RecordingSdpProbe): BluetoothStateReporter {
            // Service is null here; the streak-tracking + SDP-trigger logic doesn't touch the
            // service (it just calls probe.trigger). The status handler short-circuits before
            // touching the recorder, which lives on the service.
            return BluetoothStateReporter(null, probe)
        }
    }
}
