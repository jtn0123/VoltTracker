package com.volttracker.obdpoc

import com.volttracker.obdpoc.service.ObdService
import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ComposeDashboardSupportTest {
    @Before
    fun resetSnapshot() {
        LiveDashboardSnapshot.reset()
    }

    @After
    fun clearSnapshot() {
        LiveDashboardSnapshot.reset()
    }

    // --- decideConnectAction -------------------------------------------------

    @Test
    fun noRememberedAdapterOpensClassicDashboard() {
        assertEquals(
            ConnectAction.OPEN_CLASSIC,
            ComposeDashboardSupport.decideConnectAction("", hasConnectPermission = true, bluetoothEnabled = true),
        )
        assertEquals(
            ConnectAction.OPEN_CLASSIC,
            ComposeDashboardSupport.decideConnectAction(null, hasConnectPermission = true, bluetoothEnabled = true),
        )
    }

    @Test
    fun missingPermissionRequestsIt() {
        assertEquals(
            ConnectAction.REQUEST_PERMISSION,
            ComposeDashboardSupport.decideConnectAction(
                "AA:BB:CC:DD:EE:FF",
                hasConnectPermission = false,
                bluetoothEnabled = true,
            ),
        )
    }

    @Test
    fun disabledBluetoothAsksToEnableIt() {
        assertEquals(
            ConnectAction.REQUEST_ENABLE_BLUETOOTH,
            ComposeDashboardSupport.decideConnectAction(
                "AA:BB:CC:DD:EE:FF",
                hasConnectPermission = true,
                bluetoothEnabled = false,
            ),
        )
    }

    @Test
    fun readyStateConnects() {
        assertEquals(
            ConnectAction.CONNECT,
            ComposeDashboardSupport.decideConnectAction(
                "AA:BB:CC:DD:EE:FF",
                hasConnectPermission = true,
                bluetoothEnabled = true,
            ),
        )
    }

    // --- routeServiceBroadcast ----------------------------------------------

    @Test
    fun telemetryBroadcastFeedsTheStore() {
        val store = LiveUiStateStore()
        val handled =
            ComposeDashboardSupport.routeServiceBroadcast(
                ObdService.BROADCAST_TELEMETRY,
                JSONObject().put("updatedAt", 1_000L).put("speedKph", 64).toString(),
                store,
            )

        assertTrue(handled)
        assertEquals(39, store.state.value.drive.speedMph)
    }

    @Test
    fun statusBroadcastFeedsTheStore() {
        val store = LiveUiStateStore()
        val handled =
            ComposeDashboardSupport.routeServiceBroadcast(
                ObdService.BROADCAST_STATUS,
                JSONObject().put("state", "connected").put("adapter", "OBDLink MX+").toString(),
                store,
            )

        assertTrue(handled)
        assertTrue(store.state.value.drive.connected)
    }

    @Test
    fun garbageAndForeignBroadcastsAreIgnored() {
        val store = LiveUiStateStore()

        assertFalse(ComposeDashboardSupport.routeServiceBroadcast(ObdService.BROADCAST_TELEMETRY, null, store))
        assertFalse(ComposeDashboardSupport.routeServiceBroadcast(ObdService.BROADCAST_TELEMETRY, "not json", store))
        assertFalse(
            ComposeDashboardSupport.routeServiceBroadcast(
                "com.volttracker.obdpoc.action.SOMETHING_ELSE",
                JSONObject().put("speedKph", 64).toString(),
                store,
            ),
        )
        assertEquals(0, store.state.value.drive.speedMph)
    }

    // --- replayServiceSnapshot ----------------------------------------------

    @Test
    fun emptySnapshotReplaysNothing() {
        val store = LiveUiStateStore()
        ComposeDashboardSupport.replayServiceSnapshot(store)

        assertFalse(store.state.value.drive.connected)
        assertTrue(
            store.state.value.drive.speedTrace
                .isEmpty(),
        )
    }

    @Test
    fun snapshotReplayRestoresStatusAndHistory() {
        LiveDashboardSnapshot.recordStatus(
            JSONObject().put("state", "connected").put("adapter", "OBDLink MX+"),
        )
        LiveDashboardSnapshot.recordTelemetry(
            JSONObject().put("updatedAt", 1_000L).put("speedKph", 32).put("soc", 70),
        )
        LiveDashboardSnapshot.recordTelemetry(
            JSONObject().put("updatedAt", 2_000L).put("speedKph", 64).put("soc", 69),
        )

        val store = LiveUiStateStore()
        ComposeDashboardSupport.replayServiceSnapshot(store)
        val drive = store.state.value.drive

        assertTrue(drive.connected)
        assertEquals(2, drive.speedTrace.size)
        assertEquals(39, drive.speedMph)
    }
}
