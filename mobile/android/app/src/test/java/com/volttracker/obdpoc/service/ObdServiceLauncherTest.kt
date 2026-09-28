package com.volttracker.obdpoc.service

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObdServiceLauncherTest {
    /** Records which start API the launcher chose instead of starting anything. */
    private class RecordingContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val calls = mutableListOf<String>()

        override fun startService(service: Intent): ComponentName? {
            calls += "startService:${service.action}"
            return null
        }

        override fun startForegroundService(service: Intent): ComponentName? {
            calls += "startForegroundService:${service.action}"
            return null
        }
    }

    @Test
    fun demoUsesPlainStartServiceSoItNeverOwesAStartForeground() {
        val context = RecordingContext()

        ObdServiceLauncher.start(context, Intent(ObdService.ACTION_DEMO))

        assertEquals(listOf("startService:${ObdService.ACTION_DEMO}"), context.calls)
    }

    @Test
    fun realSessionsUseStartForegroundService() {
        val context = RecordingContext()

        ObdServiceLauncher.start(context, Intent(ObdService.ACTION_CONNECT))

        assertEquals(listOf("startForegroundService:${ObdService.ACTION_CONNECT}"), context.calls)
    }
}
