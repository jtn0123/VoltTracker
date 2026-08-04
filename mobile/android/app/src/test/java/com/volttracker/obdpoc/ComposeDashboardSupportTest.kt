package com.volttracker.obdpoc

import com.volttracker.obdpoc.service.ObdService
import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // --- updateBanner --------------------------------------------------------

    @Test
    fun updateBannerCoversEveryCheckOutcome() {
        val build =
            com.volttracker.obdpoc.update.UpdateFeed.AvailableBuild(
                tag = "v0.36.0",
                title = "v0.36.0",
                versionCode = 36_000,
                assetName = "volttracker-v0.36.0-release.apk",
                downloadUrl = "https://example.invalid/apk",
                sizeBytes = 1L,
                pageUrl = "https://example.invalid/release",
            )

        val available =
            ComposeDashboardSupport.updateBanner(
                com.volttracker.obdpoc.update.UpdateManager.CheckResult
                    .UpdateAvailable(build),
            )
        assertEquals("v0.36.0 is available", available.statusLabel)
        assertEquals("v0.36.0", available.availableTag)

        val upToDate =
            ComposeDashboardSupport.updateBanner(com.volttracker.obdpoc.update.UpdateManager.CheckResult.UpToDate)
        assertEquals("Up to date", upToDate.statusLabel)
        assertNull(upToDate.availableTag)
        val noBuilds =
            ComposeDashboardSupport.updateBanner(com.volttracker.obdpoc.update.UpdateManager.CheckResult.NoBuilds)
        assertEquals("No published builds yet", noBuilds.statusLabel)
        assertNull(noBuilds.availableTag)
        val unknown =
            ComposeDashboardSupport.updateBanner(
                com.volttracker.obdpoc.update.UpdateManager.CheckResult
                    .Unknown(build),
            )
        assertEquals("Newest is v0.36.0 — can't compare to this build", unknown.statusLabel)
        assertNull(unknown.availableTag)
        val offline =
            ComposeDashboardSupport.updateBanner(com.volttracker.obdpoc.update.UpdateManager.CheckResult.Offline)
        assertEquals("Couldn't reach GitHub — check connection", offline.statusLabel)
        assertNull(offline.availableTag)
        val failed =
            ComposeDashboardSupport.updateBanner(
                com.volttracker.obdpoc.update.UpdateManager.CheckResult
                    .Failed("boom"),
            )
        assertEquals("boom", failed.statusLabel)
        assertNull(failed.availableTag)
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
