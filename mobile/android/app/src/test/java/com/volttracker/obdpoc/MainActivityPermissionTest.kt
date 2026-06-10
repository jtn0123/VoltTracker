package com.volttracker.obdpoc

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun grantWithoutPendingConnectDoesNotStartAService() {
        val activity = harnessActivity()
        grantBluetoothPermissions(activity)

        activity.onPermissionsResult()

        assertNull(shadowOf(activity).nextStartedService)
        assertEquals("ready", activity.lastState)
    }

    private fun harnessActivity(): HarnessActivity {
        val activity = Robolectric.buildActivity(HarnessActivity::class.java).create().get()
        val manager = activity.getSystemService(BluetoothManager::class.java)
        shadowOf(manager.adapter).setEnabled(true)
        return activity
    }

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

        override fun onCreate(savedInstanceState: Bundle?) {
            permissionGate = PermissionGate(this) { requestedPermissions += it }
        }

        override fun publishDeviceList() {}

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
            // Robolectric records the launch; nothing to render in unit tests.
        }
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
        const val NAME = "TestOBD"
    }
}
