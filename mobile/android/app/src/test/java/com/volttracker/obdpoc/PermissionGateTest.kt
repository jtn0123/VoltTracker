package com.volttracker.obdpoc

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionGateTest {
    @Test
    fun connectionPermissionsDoNotRequireLocation() {
        val activity = activityWithConnectionPermissions()

        assertTrue(PermissionGate(activity).ensureConnectionGranted())
        assertNull(shadowOf(activity).lastRequestedPermission)
    }

    @Test
    fun fullPermissionRequestStillIncludesLocation() {
        val activity = activityWithConnectionPermissions()

        assertFalse(PermissionGate(activity).ensureGranted())
        val request: ShadowActivity.PermissionsRequest =
            shadowOf(activity).lastRequestedPermission
        assertArrayEquals(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            request.requestedPermissions,
        )
    }

    @Test
    fun fullPermissionRequestDoesNotReAskFineWhenCoarseIsGranted() {
        val activity = activityWithConnectionPermissions()
        shadowOf(activity).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)

        assertTrue(PermissionGate(activity).ensureGranted())
        assertNull(shadowOf(activity).lastRequestedPermission)
    }

    @Test
    fun permissionResultMentionsLocationWhenOnlyBluetoothWasGranted() {
        val activity = Robolectric.buildActivity(HarnessActivity::class.java).create().get()
        shadowOf(activity)
            .grantPermissions(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            )

        activity.onRequestPermissionsResult(
            PermissionGate.REQUEST_CODE,
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
            intArrayOf(PackageManager.PERMISSION_GRANTED),
        )

        assertEquals("ready", activity.lastState)
        assertTrue(activity.lastDetail!!.contains("Location is still off"))
    }

    @Test
    fun permissionResultMentionsNotificationsWhenBluetoothAndLocationWereGranted() {
        val activity = Robolectric.buildActivity(HarnessActivity::class.java).create().get()
        shadowOf(activity)
            .grantPermissions(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )

        activity.onRequestPermissionsResult(
            PermissionGate.REQUEST_CODE,
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
            intArrayOf(PackageManager.PERMISSION_GRANTED),
        )

        assertEquals("ready", activity.lastState)
        assertTrue(activity.lastDetail!!.contains("Notifications are still off"))
    }

    private fun activityWithConnectionPermissions(): Activity {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        shadowOf(activity)
            .grantPermissions(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        return activity
    }

    open class HarnessActivity : MainActivity() {
        var lastState: String? = null
        var lastDetail: String? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            permissionGate = PermissionGate(this)
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
    }
}
