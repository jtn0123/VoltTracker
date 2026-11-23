package com.volttracker.obdpoc.service

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Android crashes an app whose foreground-launched service stops before calling startForeground.
 * The launcher must therefore use startForegroundService only for sessions that will reach it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObdServiceLauncherTest {
    private val realActions =
        listOf(
            ObdService.ACTION_CONNECT,
            ObdService.ACTION_SCAN,
            ObdService.ACTION_TPMS_SCAN,
            ObdService.ACTION_CLEAR_DTC,
        )

    /** Records how each start was launched, with exactly the [granted] runtime permissions. */
    private class RecordingContext(
        private val granted: Set<String>,
    ) : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val foreground = mutableListOf<String?>()
        val plain = mutableListOf<String?>()

        override fun startForegroundService(service: Intent): ComponentName? {
            foreground += service.action
            return null
        }

        override fun startService(service: Intent): ComponentName? {
            plain += service.action
            return null
        }

        override fun checkSelfPermission(permission: String): Int =
            if (permission in granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    }

    private fun launch(
        context: Context,
        action: String,
    ) = ObdServiceLauncher.start(context, Intent(action))

    @Test
    fun realSessionsLaunchInTheForegroundWithEitherNearbyDevicesPermission() {
        for (permission in listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)) {
            val context = RecordingContext(setOf(permission))
            for (action in realActions) launch(context, action)

            assertEquals(permission, realActions, context.foreground)
            assertTrue(permission, context.plain.isEmpty())
        }
    }

    @Test
    fun aSessionRefusedForMissingNearbyDevicesLaunchesAsAPlainService() {
        // The service refuses it before startForeground (Android 14 would throw for the type), and
        // the stop that follows crashed the app when the launch was a foreground one.
        val context = RecordingContext(setOf(Manifest.permission.ACCESS_FINE_LOCATION))
        for (action in realActions) launch(context, action)

        assertTrue(context.foreground.isEmpty())
        assertEquals(realActions, context.plain)
    }

    @Test
    fun theDemoAlwaysLaunchesAsAPlainService() {
        for (granted in listOf(emptySet(), setOf(Manifest.permission.BLUETOOTH_CONNECT))) {
            val context = RecordingContext(granted)
            launch(context, ObdService.ACTION_DEMO)

            assertTrue("$granted", context.foreground.isEmpty())
            assertEquals("$granted", listOf(ObdService.ACTION_DEMO), context.plain)
        }
    }
}
