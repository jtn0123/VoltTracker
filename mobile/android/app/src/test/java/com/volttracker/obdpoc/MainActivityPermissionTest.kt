package com.volttracker.obdpoc

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityPermissionTest {
    @Test
    fun freshLaunchDoesNotRequestRuntimePermissions() {
        val controller: ActivityController<RecordingActivity> =
            Robolectric.buildActivity(RecordingActivity::class.java).create()
        try {
            assertTrue(
                "fresh launch should let the dashboard explain permissions first",
                controller.get().requestedPermissions.isEmpty(),
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun connectWithoutBluetoothPermissionParksTheRequestAndExplains() {
        val activity = harnessActivity()

        activity.startObdService(ObdService.ACTION_CONNECT, ADDRESS, NAME)

        assertTrue("the system permission prompt should launch", activity.requestedPermissions.isNotEmpty())
        assertNull("no service may start before the grant", shadowOf(activity).nextStartedService)
        assertEquals("blocked", activity.lastState)
        assertTrue(
            "detail should name the Android permission instead of a bare 'blocked': ${activity.lastDetail}",
            activity.lastDetail!!.contains("Nearby devices"),
        )
    }

    @Test
    fun grantingPermissionResumesTheParkedConnectAutomatically() {
        val activity = harnessActivity()
        activity.startObdService(ObdService.ACTION_CONNECT, ADDRESS, NAME)

        grantBluetoothPermissions(activity)
        activity.onPermissionsResult()

        val started = shadowOf(activity).nextStartedService
        assertEquals(ObdService.ACTION_CONNECT, started?.action)
        assertEquals(ADDRESS, started?.getStringExtra(ObdService.EXTRA_ADDRESS))
        assertEquals(NAME, started?.getStringExtra(ObdService.EXTRA_NAME))
        assertEquals("connecting", activity.lastState)
        assertTrue(activity.lastDetail!!.contains(NAME))
    }

    @Test
    fun parkedConnectIsConsumedBySinglePermissionResult() {
        val activity = harnessActivity()
        activity.startObdService(ObdService.ACTION_CONNECT, ADDRESS, NAME)
        grantBluetoothPermissions(activity)

        activity.onPermissionsResult()
        assertEquals(ObdService.ACTION_CONNECT, shadowOf(activity).nextStartedService?.action)
        activity.onPermissionsResult()

        assertNull(
            "a later permission result must not replay the old connect",
            shadowOf(activity).nextStartedService,
        )
    }

    @Test
    fun deniedPermissionExplainsHowToRetry() {
        val activity = harnessActivity()
        activity.canAskAgain = true
        activity.startObdService(ObdService.ACTION_CONNECT, ADDRESS, NAME)

        activity.onPermissionsResult()

        assertEquals("blocked", activity.lastState)
        assertTrue(
            "denial should point back at Connect: ${activity.lastDetail}",
            activity.lastDetail!!.contains("Tap Connect"),
        )
        assertFalse(activity.openedAppSettings)
        assertNull(shadowOf(activity).nextStartedService)
    }

    @Test
    fun permanentlyDeniedConnectOpensAppSettings() {
        val activity = harnessActivity()
        activity.canAskAgain = false
        activity.startObdService(ObdService.ACTION_CONNECT, ADDRESS, NAME)

        activity.onPermissionsResult()

        assertTrue("app settings should open so the user can re-enable the permission", activity.openedAppSettings)
        assertEquals("blocked", activity.lastState)
        assertTrue(
            "detail should walk through the settings screen: ${activity.lastDetail}",
            activity.lastDetail!!.contains("Nearby devices"),
        )
    }

    @Test
    fun permanentDenialWithoutAConnectAttemptOnlyExplains() {
        val activity = harnessActivity()
        activity.canAskAgain = false

        activity.onPermissionsResult()

        assertFalse("no active connect, so don't yank the user into settings", activity.openedAppSettings)
        assertEquals("blocked", activity.lastState)
        assertTrue(activity.lastDetail!!.contains("Nearby devices"))
    }

    @Test
    fun disabledBluetoothBlocksConnectAndLaunchesEnablePrompt() {
        val activity = rawHarnessActivity()
        grantBluetoothPermissions(activity)
        val manager = activity.getSystemService(BluetoothManager::class.java)
        shadowOf(manager.adapter).setEnabled(false)

        activity.startObdService(ObdService.ACTION_CONNECT, ADDRESS, NAME)

        assertEquals("blocked", activity.lastState)
        assertEquals("Turn on Bluetooth to connect to the OBD adapter.", activity.lastDetail)
        assertEquals(listOf(BluetoothAdapter.ACTION_REQUEST_ENABLE), activity.startedActivityActions)
        assertNull(shadowOf(activity).nextStartedService)
    }

    @Test
    fun disabledBluetoothReportsManualSettingsWhenEnableAndSettingsLaunchesFail() {
        val activity = rawHarnessActivity()
        grantBluetoothPermissions(activity)
        val manager = activity.getSystemService(BluetoothManager::class.java)
        shadowOf(manager.adapter).setEnabled(false)
        activity.startActivityFailure = IllegalStateException("settings unavailable")

        activity.startObdService(ObdService.ACTION_CONNECT, ADDRESS, NAME)

        assertEquals("blocked", activity.lastState)
        assertEquals("Open Android Bluetooth settings, then try again.", activity.lastDetail)
        assertEquals(
            listOf(BluetoothAdapter.ACTION_REQUEST_ENABLE, android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
            activity.startedActivityActions,
        )
        assertNull(shadowOf(activity).nextStartedService)
    }

    @Test
    fun grantWithoutPendingConnectDoesNotStartAService() {
        val activity = harnessActivity()
        grantBluetoothPermissions(activity)

        activity.onPermissionsResult()

        assertNull(shadowOf(activity).nextStartedService)
        assertEquals("ready", activity.lastState)
    }

    @Test
    fun stopObdServiceFallsBackWhenServiceDispatchIsDenied() {
        val activity = harnessActivity()
        activity.startServiceFailure = SecurityException("service dispatch denied")

        activity.stopObdService()

        assertEquals(1, activity.startServiceCalls)
        assertEquals("ready", activity.lastState)
        assertTrue(
            "fallback should tell the user the stop request was noted, got: ${activity.lastDetail}",
            activity.lastDetail?.contains("Stop request noted") == true,
        )
    }

    @Test
    fun appVisibilityReportIsBestEffortWhenServiceDispatchIsDenied() {
        val activity = harnessActivity()
        activity.startServiceFailure = SecurityException("service dispatch denied")
        val method =
            MainActivity::class.java.getDeclaredMethod(
                "reportAppVisibility",
                Boolean::class.javaPrimitiveType,
            )
        method.isAccessible = true

        method.invoke(activity, true)
        method.invoke(activity, false)

        assertEquals(2, activity.startServiceCalls)
    }

    @Test
    fun serviceStartFailureAfterPermissionsPublishesBlockedStatus() {
        val activity = harnessActivity()
        grantBluetoothPermissions(activity)
        activity.startServiceFailure = SecurityException("foreground service denied")

        activity.startObdService(ObdService.ACTION_CONNECT, ADDRESS, NAME)

        assertEquals(1, activity.startForegroundServiceCalls)
        assertEquals("blocked", activity.lastState)
        assertEquals("Android blocked OBD logging startup. Check permissions and try again.", activity.lastDetail)
    }

    @Test
    fun publishRestoreProgressSendsDashboardPayloadThroughPublisher() {
        val activity = rawHarnessActivity()
        val webView = WebView(activity)
        val publisher = DashboardPublisher(webView, { true }, Runnable::run)
        publisher.setPageReady(true)
        activity.installDashboardPublisher(publisher)

        activity.publishRestoreProgress(
            visible = true,
            busy = true,
            title = "Reading backup",
            detail = "Copying bytes",
            tone = "busy",
            phase = "copy",
            bytesDone = 50L,
            bytesTotal = 100L,
            rowsDone = -1L,
            rowsTotal = -1L,
            percent = 50,
            etaSeconds = 4L,
            operation = "restore",
        )

        val script = shadowOf(webView).getLastEvaluatedJavascript()
        assertNotNull(script)
        assertTrue(script!!.contains("window.VoltTrackerNative.setRestoreProgress("))
        assertTrue(script.contains("\\\"title\\\":\\\"Reading backup\\\""))
        assertTrue(script.contains("\\\"bytesDone\\\":50"))
        assertTrue(script.contains("\\\"operation\\\":\\\"restore\\\""))

        activity.publishDashboardPayload("setStatus", "{\"state\":\"ready\"}")

        val statusScript = shadowOf(webView).getLastEvaluatedJavascript()
        assertNotNull(statusScript)
        assertTrue(statusScript!!.contains("window.VoltTrackerNative.setStatus("))
        assertTrue(statusScript.contains("\\\"state\\\":\\\"ready\\\""))
    }

    @Test
    fun rememberDevicePublishesRefreshesAndStatus() {
        val activity = rawHarnessActivity()
        activity.deviceCatalog = DeviceCatalog(activity, activity.getSharedPreferences("remember-device-test", 0))

        activity.rememberDevice(ADDRESS, NAME)

        assertEquals(1, activity.publishDeviceListCalls)
        assertEquals(1, activity.publishStorageSummaryCalls)
        assertEquals("ready", activity.lastState)
        assertEquals("Remembered TestOBD.", activity.lastDetail)
    }

    private fun MainActivity.installDashboardPublisher(publisher: DashboardPublisher) {
        val field = MainActivity::class.java.getDeclaredField("dashboardPublisher")
        field.isAccessible = true
        field.set(this, publisher)
    }

    private fun harnessActivity(): HarnessActivity {
        val activity = rawHarnessActivity()
        val manager = activity.getSystemService(BluetoothManager::class.java)
        shadowOf(manager.adapter).setEnabled(true)
        return activity
    }

    private fun rawHarnessActivity(): HarnessActivity =
        Robolectric.buildActivity(HarnessActivity::class.java).create().get()

    private fun grantBluetoothPermissions(activity: HarnessActivity) {
        shadowOf(activity).grantPermissions(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        )
    }

    /** Records any runtime-permission request the activity launches during startup. */
    class RecordingActivity : MainActivity() {
        val requestedPermissions = mutableListOf<Array<String>>()

        override fun launchPermissionRequest(permissions: Array<String>) {
            requestedPermissions += permissions
        }
    }

    /**
     * Minimal harness mirroring [PermissionGateTest.HarnessActivity]: skips the WebView/storage
     * setup, records published statuses, and stubs the navigation side effects.
     */
    open class HarnessActivity : MainActivity() {
        val requestedPermissions = mutableListOf<Array<String>>()
        var lastState: String? = null
        var lastDetail: String? = null
        var canAskAgain = true
        var openedAppSettings = false
        var startServiceFailure: RuntimeException? = null
        var startServiceCalls = 0
        var startForegroundServiceCalls = 0
        var startActivityFailure: RuntimeException? = null
        val startedActivityActions = mutableListOf<String?>()
        var publishDeviceListCalls = 0
        var publishStorageSummaryCalls = 0

        override fun onCreate(savedInstanceState: Bundle?) {
            permissionGate = PermissionGate(this) { requestedPermissions += it }
        }

        override fun publishDeviceList() {
            publishDeviceListCalls += 1
        }

        override fun publishStorageSummary() {
            publishStorageSummaryCalls += 1
        }

        override fun publishStatus(
            state: String?,
            detail: String?,
            blocked: Boolean,
        ) {
            lastState = state
            lastDetail = detail
        }

        override fun canAskForBluetoothConnectAgain(): Boolean = canAskAgain

        override fun openAppPermissionSettings(): Boolean {
            openedAppSettings = true
            return true
        }

        override fun startActivity(intent: Intent?) {
            startedActivityActions += intent?.action
            startActivityFailure?.let { throw it }
            // Robolectric records the launch; nothing to render in unit tests.
        }

        override fun startForegroundService(service: Intent?): ComponentName? {
            startForegroundServiceCalls += 1
            startServiceFailure?.let { throw it }
            return super.startForegroundService(service)
        }

        override fun startService(service: Intent?): ComponentName? {
            startServiceCalls += 1
            startServiceFailure?.let { throw it }
            return super.startService(service)
        }
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
        const val NAME = "TestOBD"
    }
}
