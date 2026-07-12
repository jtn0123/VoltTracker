package com.volttracker.obdpoc

import android.content.Context
import android.content.Intent
import android.os.Looper
import com.volttracker.obdpoc.service.ObdService
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardBroadcastCoordinatorTest {
    @Test
    fun telemetryPublishesOnlyTheHotSampleAndMarksStorageDirty() {
        val seam = RecordingSeam()
        withRegisteredCoordinator(seam) { context ->
            context.sendBroadcast(obdIntent(ObdService.BROADCAST_TELEMETRY, "{\"sampleCount\":7}"))
        }

        assertEquals(listOf("{\"sampleCount\":7}"), seam.telemetry)
        assertEquals(listOf("updateTelemetry" to "{\"sampleCount\":7}"), seam.dashboardCalls)
        assertEquals(1, seam.storageDirtyCalls)
        assertEquals("telemetry must not duplicate the sample inside app state", 0, seam.appStateCalls)
    }

    @Test
    fun statusBeforeDashboardReadySkipsStorageButStillPublishesState() {
        val seam = RecordingSeam(ready = false)
        withRegisteredCoordinator(seam) { context ->
            context.sendBroadcast(obdIntent(ObdService.BROADCAST_STATUS, "{\"state\":\"idle\"}"))
        }

        assertEquals(0, seam.storageCalls)
        assertEquals(0, seam.throttledStorageCalls)
        assertEquals(1, seam.appStateCalls)
        assertEquals(1, seam.readyNotifyCalls)
        assertEquals("setStatus", seam.dashboardCalls.single().first)
    }

    @Test
    fun idleStatusPublishesStorageImmediately() {
        val seam = RecordingSeam(ready = true)
        withRegisteredCoordinator(seam) { context ->
            context.sendBroadcast(obdIntent(ObdService.BROADCAST_STATUS, "{\"state\":\"idle\"}"))
        }

        assertEquals(1, seam.storageCalls)
        assertEquals(0, seam.throttledStorageCalls)
        assertEquals(1, seam.appStateCalls)
    }

    @Test
    fun activeStatusUsesThrottledStoragePublish() {
        val seam = RecordingSeam(ready = true)
        withRegisteredCoordinator(seam) { context ->
            context.sendBroadcast(obdIntent(ObdService.BROADCAST_STATUS, "{\"state\":\"connected\"}"))
        }

        assertEquals(0, seam.storageCalls)
        assertEquals(1, seam.throttledStorageCalls)
        assertEquals(1, seam.appStateCalls)
    }

    @Test
    fun bluetoothOnBroadcastRoutesToAutoConnect() {
        val seam = RecordingSeam()
        withRegisteredCoordinator(seam) { context ->
            context.sendBroadcast(
                Intent(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
                    .putExtra(
                        android.bluetooth.BluetoothAdapter.EXTRA_STATE,
                        android.bluetooth.BluetoothAdapter.STATE_ON,
                    ),
            )
        }

        assertEquals(listOf(AutoConnectController.TRIGGER_BLUETOOTH_ON to null), seam.autoConnectCalls)
    }

    private fun withRegisteredCoordinator(
        seam: RecordingSeam,
        block: (Context) -> Unit,
    ) {
        val context: Context = RuntimeEnvironment.getApplication()
        val coordinator = DashboardBroadcastCoordinator(seam)
        coordinator.register(context)
        try {
            block(context)
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            coordinator.unregisterAll(context)
        }
    }

    private fun obdIntent(
        action: String,
        json: String,
    ): Intent =
        Intent(action)
            .setPackage(RuntimeEnvironment.getApplication().packageName)
            .putExtra(ObdService.EXTRA_JSON, json)

    private class RecordingSeam(
        private val ready: Boolean = true,
    ) : DashboardBroadcastCoordinator.Seam {
        val telemetry = mutableListOf<String>()
        val dashboardCalls = mutableListOf<Pair<String, String?>>()
        val autoConnectCalls = mutableListOf<Pair<String, String?>>()
        var status = JSONObject()
        var storageDirtyCalls = 0
        var storageCalls = 0
        var throttledStorageCalls = 0
        var appStateCalls = 0
        var readyNotifyCalls = 0

        override fun storeTelemetry(json: String) {
            telemetry += json
        }

        override fun storeStatus(json: String): String {
            status = JSONObject(json)
            return status.optString("state", "")
        }

        override fun callDashboard(
            functionName: String,
            jsonPayload: String?,
        ) {
            dashboardCalls += functionName to jsonPayload
        }

        override fun markStorageSummaryDirty() {
            storageDirtyCalls += 1
        }

        override fun publishStorageSummary() {
            storageCalls += 1
        }

        override fun publishStorageSummaryThrottled() {
            throttledStorageCalls += 1
        }

        override fun isDashboardReady(): Boolean = ready

        override fun publishAppState() {
            appStateCalls += 1
        }

        override fun onAdapterStatusForReadyNotify(status: JSONObject) {
            assertTrue(status === this.status)
            readyNotifyCalls += 1
        }

        override fun lastStatus(): JSONObject = status

        override fun maybeAutoConnect(
            trigger: String,
            observedAddress: String?,
        ) {
            autoConnectCalls += trigger to observedAddress
        }

        override fun bluetoothDeviceAddress(intent: Intent): String = "AA:BB"
    }
}
