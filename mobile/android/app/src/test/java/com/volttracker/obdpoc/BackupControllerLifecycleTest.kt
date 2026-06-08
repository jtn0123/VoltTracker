package com.volttracker.obdpoc

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupControllerLifecycleTest {
    @Test
    fun restorePickerIsBlockedWhileLoggingIsActive() {
        val controller =
            Robolectric.buildActivity(HarnessActivity::class.java).create()
        try {
            val activity = controller.get()
            activity.loggingActive = true

            activity.backupController!!.launchRestorePicker()

            assertNull(activity.launchedRestoreIntent)
            assertEquals("blocked", activity.lastState)
            assertEquals("Stop logging before restoring a backup.", activity.lastDetail)
        } finally {
            destroyQuietly(controller)
        }
    }

    @Test
    fun restorePickerStartsSafIntentWhenIdle() {
        val controller =
            Robolectric.buildActivity(HarnessActivity::class.java).create()
        try {
            val activity = controller.get()
            activity.loggingActive = false

            activity.backupController!!.launchRestorePicker()

            val started = activity.launchedRestoreIntent
            assertNotNull(started)
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, started!!.action)
            assertEquals("application/octet-stream", started.type)
            assertEquals("ready", activity.lastState)
            assertEquals("Choose a Volt Tracker backup file.", activity.lastDetail)
            assertArrayEquals(
                arrayOf(
                    "application/octet-stream",
                    "application/vnd.sqlite3",
                    "application/x-sqlite3",
                ),
                started.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
            )
        } finally {
            destroyQuietly(controller)
        }
    }

    @Test
    fun restorePickerCancelReportsNoFileSelected() {
        val controller =
            Robolectric.buildActivity(HarnessActivity::class.java).create()
        try {
            val activity = controller.get()

            activity.backupController!!.onRestorePickerResult(Activity.RESULT_CANCELED, null)

            assertEquals("ready", activity.lastState)
            assertEquals("Restore cancelled - no file selected.", activity.lastDetail)
        } finally {
            destroyQuietly(controller)
        }
    }

    @Test
    fun stopObdServiceSurfacesRejectedServiceStart() {
        val controller =
            Robolectric.buildActivity(HarnessActivity::class.java).create()
        try {
            val activity = controller.get()
            activity.rejectStartService = true

            activity.stopObdService()

            assertEquals("ready", activity.lastState)
            assertEquals(
                "Stop request noted; reopen the app if logging is still active.",
                activity.lastDetail,
            )
        } finally {
            destroyQuietly(controller)
        }
    }

    class HarnessActivity : MainActivity() {
        @JvmField var loggingActive = false

        @JvmField var rejectStartService = false

        @JvmField var launchedRestoreIntent: Intent? = null

        @JvmField var lastState: String? = null

        @JvmField var lastDetail: String? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            backupController = BackupController(this, DataBackup(this), null)
        }

        override fun isLoggingActive(): Boolean = loggingActive

        override fun publishStatus(
            state: String?,
            detail: String?,
            blocked: Boolean,
        ) {
            lastState = state
            lastDetail = detail
        }

        override fun launchRestoreFilePicker(intent: Intent) {
            launchedRestoreIntent = intent
        }

        override fun startService(service: Intent?): ComponentName? {
            if (rejectStartService) {
                throw IllegalStateException("background service start blocked")
            }
            return ComponentName(this, ObdService::class.java)
        }
    }

    companion object {
        private fun destroyQuietly(controller: ActivityController<out MainActivity>) {
            try {
                controller.destroy()
            } catch (ignored: RuntimeException) {
                // WebView teardown can race Robolectric background work; not part of this assertion.
            }
        }
    }
}
