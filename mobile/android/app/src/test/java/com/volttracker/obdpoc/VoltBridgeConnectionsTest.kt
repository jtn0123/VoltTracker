package com.volttracker.obdpoc

import android.os.Bundle
import android.os.Looper
import com.volttracker.obdpoc.data.ObdLocalStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Exercises [VoltBridgeConnections] directly against the [DashboardHost] seam, mirroring the
 * fake-host construction in [VoltBridgeDispatchTest]. The sub-bridge owns the address/name cleaning,
 * the valid-MAC gate, and the remembered-adapter lookups; the facade just forwards to it, so testing
 * it here pins behavior at the layer where the rules actually live.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoltBridgeConnectionsTest {
    private var controller: ActivityController<RecordingActivity>? = null
    private lateinit var activity: RecordingActivity
    private lateinit var connections: VoltBridgeConnections

    @Before
    fun setUp() {
        val controller = Robolectric.buildActivity(RecordingActivity::class.java).create()
        this.controller = controller
        activity = controller.get()
        connections = VoltBridgeConnections(activity)
    }

    @After
    fun tearDown() {
        activity.store.close()
        try {
            controller?.destroy()
        } catch (ignored: RuntimeException) {
            // Activity teardown can race in Robolectric; it must not fail the assertions above.
        }
    }

    /** Drains the main looper so the `runOnUiThread` body the bridge posts actually executes. */
    private fun drain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    // ---- connect / scan with an explicit address --------------------------------------------

    @Test
    fun connectCleansAddressAndNameThenStartsConnectService() {
        connections.connect("  $VALID_ADDRESS  ", "  Veepeak  ")
        drain()

        assertEquals(ObdService.ACTION_CONNECT, activity.lastServiceAction)
        assertEquals(VALID_ADDRESS, activity.lastServiceAddress)
        assertEquals("Veepeak", activity.lastServiceName)
        // The adapter is remembered even on the way to a successful connect.
        assertEquals(VALID_ADDRESS, activity.lastRememberedAddress)
    }

    @Test
    fun scanStartsScanService() {
        connections.scan(VALID_ADDRESS, "ELM327")
        drain()

        assertEquals(ObdService.ACTION_SCAN, activity.lastServiceAction)
        assertEquals(VALID_ADDRESS, activity.lastServiceAddress)
    }

    @Test
    fun connectRemembersTheAdapterButBlocksWhenAddressIsInvalid() {
        connections.connect("not-a-mac", "Bogus")
        drain()

        assertNull("invalid address must not start a service", activity.lastServiceAction)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
        assertEquals("Choose a valid Bluetooth adapter.", activity.lastStatusDetail)
        // The cleaned (still-invalid) address is remembered so the UI can show what was tried.
        assertEquals("not-a-mac", activity.lastRememberedAddress)
    }

    // ---- detailProbe -------------------------------------------------------------------------

    @Test
    fun detailProbeStartsTpmsScanWithNormalizedStage() {
        connections.detailProbe(VALID_ADDRESS, "OBDLink", "  passive  ")
        drain()

        assertEquals(ObdService.ACTION_TPMS_SCAN, activity.lastServiceAction)
        assertEquals(VALID_ADDRESS, activity.lastServiceAddress)
        assertEquals(EnhancedPidProfiles.STAGE_PASSIVE, activity.lastServiceStage)
    }

    @Test
    fun detailProbeFallsBackToTiresStageForUnknownInput() {
        connections.detailProbe(VALID_ADDRESS, "OBDLink", "nonsense")
        drain()

        assertEquals(EnhancedPidProfiles.STAGE_TIRES, activity.lastServiceStage)
    }

    @Test
    fun detailProbeBlocksForInvalidAddress() {
        connections.detailProbe("bad", "OBDLink", EnhancedPidProfiles.STAGE_TIRES)
        drain()

        assertNull(activity.lastServiceAction)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    // ---- rememberDevice ----------------------------------------------------------------------

    @Test
    fun rememberDeviceForwardsCleanedAddressAndNameWithoutStartingService() {
        connections.rememberDevice("  $VALID_ADDRESS  ", "  My Adapter  ")
        drain()

        assertEquals(VALID_ADDRESS, activity.lastRememberedAddress)
        assertEquals("My Adapter", activity.lastRememberedName)
        assertNull("rememberDevice must not start a service", activity.lastServiceAction)
    }

    // ---- *Last paths: remembered adapter -----------------------------------------------------

    @Test
    fun connectLastStartsConnectAgainstRememberedAdapter() {
        rememberLastDevice()

        connections.connectLast()
        drain()

        assertEquals(ObdService.ACTION_CONNECT, activity.lastServiceAction)
        assertEquals(VALID_ADDRESS, activity.lastServiceAddress)
        assertEquals("Saved adapter", activity.lastServiceName)
    }

    @Test
    fun connectLastBlocksWhenNoAdapterRemembered() {
        connections.connectLast()
        drain()

        assertNull(activity.lastServiceAction)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
        assertEquals(
            "No remembered adapter yet. Connect once to save it.",
            activity.lastStatusDetail,
        )
    }

    @Test
    fun scanLastStartsScanAgainstRememberedAdapter() {
        rememberLastDevice()

        connections.scanLast()
        drain()

        assertEquals(ObdService.ACTION_SCAN, activity.lastServiceAction)
        assertEquals(VALID_ADDRESS, activity.lastServiceAddress)
    }

    @Test
    fun scanLastBlocksWhenNoAdapterRemembered() {
        connections.scanLast()
        drain()

        assertNull(activity.lastServiceAction)
        assertEquals("blocked", activity.lastStatusState)
    }

    @Test
    fun detailProbeLastStartsTpmsScanWithNormalizedStageAgainstRememberedAdapter() {
        rememberLastDevice()

        connections.detailProbeLast("experimental")
        drain()

        assertEquals(ObdService.ACTION_TPMS_SCAN, activity.lastServiceAction)
        assertEquals(VALID_ADDRESS, activity.lastServiceAddress)
        assertEquals(EnhancedPidProfiles.STAGE_EXPERIMENTAL, activity.lastServiceStage)
    }

    @Test
    fun detailProbeLastBlocksWhenNoAdapterRemembered() {
        connections.detailProbeLast(EnhancedPidProfiles.STAGE_TIRES)
        drain()

        assertNull(activity.lastServiceAction)
        assertEquals("blocked", activity.lastStatusState)
    }

    @Test
    fun tryReconnectNowStartsConnectAgainstRememberedAdapter() {
        rememberLastDevice()

        connections.tryReconnectNow()
        drain()

        assertEquals(ObdService.ACTION_CONNECT, activity.lastServiceAction)
        assertEquals(VALID_ADDRESS, activity.lastServiceAddress)
    }

    @Test
    fun tryReconnectNowBlocksWhenNoAdapterRemembered() {
        connections.tryReconnectNow()
        drain()

        assertNull(activity.lastServiceAction)
        assertEquals("blocked", activity.lastStatusState)
        assertEquals(
            "No remembered adapter yet. Pick one and try Connect.",
            activity.lastStatusDetail,
        )
    }

    // ---- demo / disconnect / passthroughs ----------------------------------------------------

    @Test
    fun demoStartsDemoServiceWithoutAnAddress() {
        connections.demo()
        drain()

        assertEquals(ObdService.ACTION_DEMO, activity.lastServiceAction)
        assertNull("demo runs without a Bluetooth address", activity.lastServiceAddress)
        assertEquals("Demo stream", activity.lastServiceName)
    }

    @Test
    fun disconnectStopsTheService() {
        connections.disconnect()
        drain()

        assertTrue("disconnect must stop the OBD service", activity.stopServiceCalls > 0)
        assertNull("disconnect does not start a service", activity.lastServiceAction)
    }

    @Test
    fun cancelRetryForwardsToActivity() {
        connections.cancelRetry()
        drain()

        assertEquals(1, activity.cancelRetryCalls)
    }

    @Test
    fun openBluetoothSettingsForwardsToActivity() {
        connections.openBluetoothSettings()
        drain()

        assertEquals(1, activity.openBluetoothSettingsCalls)
    }

    /** Seeds the device catalog with a valid remembered adapter for the `*Last` dispatch paths. */
    private fun rememberLastDevice() {
        activity.requireDeviceCatalog().remember(VALID_ADDRESS, "Saved adapter")
    }

    /**
     * A [MainActivity] whose heavy collaborators are replaced by recording overrides — same approach
     * as [VoltBridgeDispatchTest.RecordingActivity], trimmed to the seams [VoltBridgeConnections]
     * reaches. `super.onCreate()` is deliberately skipped so no WebView/foreground service spins up.
     */
    class RecordingActivity : MainActivity() {
        lateinit var store: RecordingStore

        var lastServiceAction: String? = null
        var lastServiceAddress: String? = null
        var lastServiceName: String? = null
        var lastServiceStage: String? = null
        var stopServiceCalls = 0

        var lastStatusState: String? = null
        var lastStatusDetail: String? = null
        var lastStatusBlocked = false

        var lastRememberedAddress: String? = null
        var lastRememberedName: String? = null

        var cancelRetryCalls = 0
        var openBluetoothSettingsCalls = 0

        override fun onCreate(savedInstanceState: Bundle?) {
            // Skip super.onCreate(): we only need the catalog + a lightweight store the bridge
            // dispatches against, not the real WebView/ObdLocalStore.
            store = RecordingStore(this)
            getSharedPreferences("volt-bridge-connections-test", 0).edit().clear().commit()
            deviceCatalog = DeviceCatalog(this, getSharedPreferences("volt-bridge-connections-test", 0))
            localStore = store
        }

        override fun runOnBackground(task: Runnable) {
            task.run()
        }

        override fun startObdService(
            action: String?,
            address: String?,
            name: String?,
        ) {
            recordService(action, address, name, null)
        }

        override fun startObdService(
            action: String?,
            address: String?,
            name: String?,
            detailStage: String?,
        ) {
            recordService(action, address, name, detailStage)
        }

        private fun recordService(
            action: String?,
            address: String?,
            name: String?,
            stage: String?,
        ) {
            lastServiceAction = action
            lastServiceAddress = address
            lastServiceName = name
            lastServiceStage = stage
        }

        override fun stopObdService() {
            stopServiceCalls += 1
        }

        override fun publishStatus(
            state: String?,
            detail: String?,
            blocked: Boolean,
        ) {
            lastStatusState = state
            lastStatusDetail = detail
            lastStatusBlocked = blocked
        }

        override fun rememberDevice(
            address: String?,
            name: String?,
        ) {
            lastRememberedAddress = address
            lastRememberedName = name
            // Keep the catalog in sync so the `*Last` reads see the freshly remembered adapter.
            requireDeviceCatalog().remember(address, name)
        }

        override fun cancelRetryFromBridge() {
            cancelRetryCalls += 1
        }

        override fun openBluetoothSettingsFromBridge() {
            openBluetoothSettingsCalls += 1
        }
    }

    /** A minimal store; connections never mutates it, but MainActivity wires one up. */
    class RecordingStore(
        context: android.content.Context,
    ) : ObdLocalStore(context)

    private companion object {
        /** A MAC that passes [android.bluetooth.BluetoothAdapter.checkBluetoothAddress]. */
        private const val VALID_ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
