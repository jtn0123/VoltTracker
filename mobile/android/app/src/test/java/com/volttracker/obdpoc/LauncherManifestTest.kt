package com.volttracker.obdpoc

import android.content.ComponentName
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The native Compose dashboard is the app's launcher. The classic WebView [MainActivity] stays in
 * the app for the tools the native screens lack, but only as an in-app destination: it must not
 * answer the home-screen launch or be reachable by other apps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LauncherManifestTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun composeDashboardIsTheOnlyLauncherActivity() {
        val launch =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(context.packageName)

        val launchers = context.packageManager.queryIntentActivities(launch, 0).map { it.activityInfo.name }

        assertEquals(listOf(ComposeDashboardActivity::class.java.name), launchers)
    }

    @Test
    fun composeDashboardIsExportedSoTheLauncherCanStartIt() {
        val info =
            context.packageManager.getActivityInfo(
                ComponentName(context, ComposeDashboardActivity::class.java),
                0,
            )
        assertTrue(info.exported)
    }

    @Test
    fun classicDashboardIsReachableOnlyFromInsideTheApp() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertFalse(info.exported)
    }
}
