package com.volttracker.obdpoc

import android.content.ComponentName
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pins the foreground-service notification built by [ObdNotifications]: its content tap must open
 * [MainActivity] via a PendingIntent, which launches from outside any activity task and therefore
 * requires [Intent.FLAG_ACTIVITY_NEW_TASK] (plus CLEAR_TOP to reuse a live dashboard, matching the
 * adapter-ready notification in [TroubleshooterBridge]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObdNotificationsTest {
    @Test
    fun contentIntentOpensMainActivityWithNewTaskAndClearTop() {
        val context = RuntimeEnvironment.getApplication()

        val notification = ObdNotifications(context).build("Background logging active")

        val contentIntent = notification.contentIntent
        assertNotNull("the foreground notification must carry a content tap intent", contentIntent)
        val open = shadowOf(contentIntent).savedIntent
        assertEquals(
            "the tap must open the dashboard activity",
            ComponentName(context, MainActivity::class.java),
            open.component,
        )
        assertTrue(
            "PendingIntent-launched activities require FLAG_ACTIVITY_NEW_TASK",
            open.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
        assertTrue(
            "the tap should clear back to an existing dashboard instance",
            open.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0,
        )
    }
}
