package com.volttracker.obdpoc

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.ParcelUuid
import com.volttracker.obdpoc.service.ObdService
import com.volttracker.obdpoc.service.SessionRecorder
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

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
@Suppress("DEPRECATION")
class BluetoothStateReporterTest {
    private lateinit var service: ObdService
    private lateinit var logDir: File
    private lateinit var sessionLog: ObdSessionLog

    @Before
    fun setUpService() {
        service = Robolectric.setupService(ObdService::class.java)
        logDir = File(System.getProperty("java.io.tmpdir"), "bt-reporter-${System.nanoTime()}")
        sessionLog = ObdSessionLog(logDir)
        service.recorder = SessionRecorder(Any(), sessionLog, null)
        service.recorder.openSession("obd", "AA:BB:CC:DD:EE:FF", "ELM327", System.currentTimeMillis())
    }

    @After
    fun tearDownService() {
        sessionLog.close()
        logDir.deleteRecursively()
        try {
            service.onDestroy()
        } catch (ignored: RuntimeException) {
            // Service was not foreground-started in these tests.
        }
    }

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

    @Test
    fun systemBroadcastsLogAdapterDeviceBondAndUuidEvents() {
        val reporter = BluetoothStateReporter(service, null)
        setActiveAddress(reporter, "AA:BB:CC:DD:EE:FF")
        val adapter = BluetoothAdapter.getDefaultAdapter()!!
        val device = adapter.getRemoteDevice("AA:BB:CC:DD:EE:FF")

        invokeSystemBroadcast(
            reporter,
            Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON)
                .putExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, BluetoothAdapter.STATE_TURNING_ON),
        )
        invokeSystemBroadcast(
            reporter,
            Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, device),
        )
        invokeSystemBroadcast(
            reporter,
            Intent(BluetoothDevice.ACTION_ACL_DISCONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, device),
        )
        invokeSystemBroadcast(
            reporter,
            Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                .putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED)
                .putExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )
        invokeSystemBroadcast(
            reporter,
            Intent(BluetoothDevice.ACTION_UUID)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                .putExtra(
                    BluetoothDevice.EXTRA_UUID,
                    arrayOf(ParcelUuid(UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"))),
                ),
        )

        assertEquals(1, countEvents("bluetooth_state_changed"))
        assertEquals(1, countEvents("bluetooth_acl_connected"))
        assertEquals(1, countEvents("bluetooth_acl_disconnected"))
        assertEquals(1, countEvents("bluetooth_bond_state_changed"))
        assertEquals(1, countEvents("sdp_refresh"))
        val bond = eventPayloads("bluetooth_bond_state_changed").single()
        assertEquals("bonded", bond.optString("bondState"))
        assertEquals("bonding", bond.optString("previous"))
    }

    @Test
    fun adapterStateBroadcastsCoverAllNamedStates() {
        val reporter = BluetoothStateReporter(service, null)

        invokeSystemBroadcast(
            reporter,
            Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
                .putExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, BluetoothAdapter.STATE_TURNING_OFF),
        )
        invokeSystemBroadcast(
            reporter,
            Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                .putExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, 12345),
        )

        val states = eventPayloads("bluetooth_state_changed").map { it.optString("state") }
        val previous = eventPayloads("bluetooth_state_changed").map { it.optString("previous") }
        assertTrue(states.contains("off"))
        assertTrue(states.contains("error"))
        assertTrue(previous.contains("turning_off"))
        assertTrue(previous.contains("unknown(12345)"))
    }

    @Test
    fun aclConnectedForActiveAdapterRunsTheReconnectWakeHook() {
        // B3: the service wires this hook to the engine's extended-reconnect wake-up so an
        // adapter reappearing mid-drive triggers an immediate attempt without the Activity.
        var wakeUps = 0
        val reporter = BluetoothStateReporter(service, null) { wakeUps += 1 }
        setActiveAddress(reporter, "AA:BB:CC:DD:EE:FF")
        val adapter = BluetoothAdapter.getDefaultAdapter()!!
        val active = adapter.getRemoteDevice("AA:BB:CC:DD:EE:FF")
        val other = adapter.getRemoteDevice("AA:BB:CC:DD:EE:00")

        invokeSystemBroadcast(
            reporter,
            Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, other),
        )
        assertEquals("a different device's ACL event must not wake the reconnect wait", 0, wakeUps)

        invokeSystemBroadcast(
            reporter,
            Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, active),
        )
        assertEquals("the active adapter's ACL event must run the wake hook", 1, wakeUps)

        invokeSystemBroadcast(
            reporter,
            Intent(BluetoothDevice.ACTION_ACL_DISCONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, active),
        )
        assertEquals("only ACL-connected events wake the reconnect wait", 1, wakeUps)
    }

    @Test
    fun systemBroadcastForDifferentDeviceIsIgnored() {
        val reporter = BluetoothStateReporter(service, null)
        setActiveAddress(reporter, "AA:BB:CC:DD:EE:FF")
        val other = BluetoothAdapter.getDefaultAdapter()!!.getRemoteDevice("AA:BB:CC:DD:EE:00")

        invokeSystemBroadcast(
            reporter,
            Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, other),
        )

        assertEquals(0, countEvents("bluetooth_acl_connected"))
    }

    @Test
    fun systemBroadcastBeforeAnyPreConnectIsIgnored() {
        // Regression: before onPreConnect sets activeAddress, the old
        // `!isNullOrEmpty && !equals` guard let device-scoped broadcasts for ANY nearby device
        // through, flooding the session log. With no adapter being tracked (activeAddress null) they
        // must be dropped, not logged.
        val reporter = BluetoothStateReporter(service, null)
        val device = BluetoothAdapter.getDefaultAdapter()!!.getRemoteDevice("AA:BB:CC:DD:EE:FF")

        invokeSystemBroadcast(
            reporter,
            Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, device),
        )
        invokeSystemBroadcast(
            reporter,
            Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                .putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED)
                .putExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )

        assertEquals(0, countEvents("bluetooth_acl_connected"))
        assertEquals(0, countEvents("bluetooth_bond_state_changed"))
    }

    @Test
    fun nullLifecycleCallsAndBlankPreflightAreNoOps() {
        val reporter = BluetoothStateReporter(service, RecordingSdpProbe())

        reporter.register(null)
        reporter.unregister(null)
        reporter.onPreConnect(null)
        reporter.onPreConnect("   ")

        assertEquals(0, countEvents("bluetooth_preflight"))
        assertEquals(0, reporter.connectingWithFailureStreakForTest())
    }

    @Test
    fun registerAndUnregisterResetTrackedFailureState() {
        val reporter = BluetoothStateReporter(service, RecordingSdpProbe())
        setActiveAddress(reporter, "AA:BB:CC:DD:EE:FF")
        reporter.handleStatusForTest("connecting", "CONNECT_TIMEOUT")
        assertEquals(1, reporter.connectingWithFailureStreakForTest())

        reporter.register(service)
        reporter.unregister(service)

        assertEquals(0, reporter.connectingWithFailureStreakForTest())
    }

    @Test
    fun preflightInvalidAddressLogsStructuredReason() {
        val reporter = BluetoothStateReporter(service, null)

        reporter.onPreConnect("not-a-mac")

        val event = eventPayloads("bluetooth_preflight").single()
        assertEquals("not-a-mac", event.optString("address"))
        assertEquals("invalid_address", event.optString("reason"))
        assertTrue(event.has("adapterState"))
    }

    @Test
    fun emptyAndMalformedStatusBroadcastsDoNotChangeFailureStreak() {
        val reporter = BluetoothStateReporter(service, RecordingSdpProbe())

        invokeStatusBroadcast(reporter, Intent(ObdService.BROADCAST_STATUS))
        invokeStatusBroadcast(
            reporter,
            Intent(ObdService.BROADCAST_STATUS).putExtra(ObdService.EXTRA_JSON, "{bad json"),
        )

        assertEquals(0, reporter.connectingWithFailureStreakForTest())
    }

    private companion object {
        fun newReporter(probe: RecordingSdpProbe): BluetoothStateReporter {
            // Service is null here; the streak-tracking + SDP-trigger logic doesn't touch the
            // service (it just calls probe.trigger). The status handler short-circuits before
            // touching the recorder, which lives on the service.
            return BluetoothStateReporter(null, probe)
        }

        fun setActiveAddress(
            reporter: BluetoothStateReporter,
            address: String,
        ) {
            val field = BluetoothStateReporter::class.java.getDeclaredField("activeAddress")
            field.isAccessible = true
            field.set(reporter, address)
        }

        fun invokeSystemBroadcast(
            reporter: BluetoothStateReporter,
            intent: Intent,
        ) {
            val method =
                BluetoothStateReporter::class.java.getDeclaredMethod(
                    "handleSystemBroadcast",
                    Intent::class.java,
                )
            method.isAccessible = true
            method.invoke(reporter, intent)
        }

        fun invokeStatusBroadcast(
            reporter: BluetoothStateReporter,
            intent: Intent,
        ) {
            val method =
                BluetoothStateReporter::class.java.getDeclaredMethod(
                    "handleStatusBroadcast",
                    Intent::class.java,
                )
            method.isAccessible = true
            method.invoke(reporter, intent)
        }
    }

    private fun countEvents(event: String): Int = eventPayloads(event).size

    private fun eventPayloads(event: String): List<JSONObject> {
        val logFile =
            logDir.listFiles()?.firstOrNull { it.name.endsWith(".jsonl") }
                ?: return emptyList()
        return logFile
            .readLines()
            .mapNotNull { line -> if (line.isBlank()) null else JSONObject(line) }
            .filter { it.optString("type") == "event" }
            .map { it.optJSONObject("payload") ?: JSONObject() }
            .filter { it.optString("event") == event }
    }
}
