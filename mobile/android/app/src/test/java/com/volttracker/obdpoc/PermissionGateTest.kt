package com.volttracker.obdpoc

import android.Manifest
import android.app.Activity
import android.os.Bundle
import com.volttracker.obdpoc.service.PermissionGate
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionGateTest {
    @Test
    fun connectionPermissionsDoNotRequireLocation() {
        val activity = activityWithConnectionPermissions()
        var requested: Array<String>? = null

        assertTrue(PermissionGate(activity) { requested = it }.ensureConnectionGranted())
        assertNull(requested)
    }

    @Test
    fun fullPermissionRequestStillIncludesLocation() {
        val activity = activityWithConnectionPermissions()
        var requested: Array<String>? = null

        assertFalse(PermissionGate(activity) { requested = it }.ensureGranted())
        assertArrayEquals(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            requested,
        )
    }

    @Test
    fun fullPermissionRequestDoesNotReAskFineWhenCoarseIsGranted() {
        val activity = activityWithConnectionPermissions()
        shadowOf(activity).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        var requested: Array<String>? = null

        assertTrue(PermissionGate(activity) { requested = it }.ensureGranted())
        assertNull(requested)
    }

    @Test
    fun enablingAnAlertRequestsOnlyNotificationPermission() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var requested: Array<String>? = null

        assertFalse(PermissionGate(activity) { requested = it }.ensureNotifications())
        assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), requested)
    }

    @Test
    fun permissionResultMentionsLocationWhenOnlyBluetoothWasGranted() {
        val activity = Robolectric.buildActivity(HarnessActivity::class.java).create().get()
        shadowOf(activity)
            .grantPermissions(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            )

        activity.onPermissionsResult()

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

        activity.onPermissionsResult()

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
            permissionGate = PermissionGate(this) {}
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
