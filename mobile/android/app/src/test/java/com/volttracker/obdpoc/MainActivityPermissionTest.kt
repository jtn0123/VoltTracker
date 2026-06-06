package com.volttracker.obdpoc

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
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

    /** Records any runtime-permission request the activity launches during startup. */
    class RecordingActivity : MainActivity() {
        val requestedPermissions = mutableListOf<Array<String>>()

        override fun launchPermissionRequest(permissions: Array<String>) {
            requestedPermissions += permissions
        }
    }
}
