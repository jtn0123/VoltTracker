package com.volttracker.obdpoc

import android.app.Activity
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityPermissionTest {
    @Test
    fun freshLaunchDoesNotRequestRuntimePermissions() {
        val controller: ActivityController<MainActivity> =
            Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity: Activity = controller.get()
            val request: ShadowActivity.PermissionsRequest? =
                shadowOf(activity).lastRequestedPermission

            assertNull("fresh launch should let the dashboard explain permissions first", request)
        } finally {
            controller.destroy()
        }
    }
}
