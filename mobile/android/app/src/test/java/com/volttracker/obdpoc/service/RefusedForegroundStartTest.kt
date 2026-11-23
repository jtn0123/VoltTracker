package com.volttracker.obdpoc.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A start [ObdService] refuses must still call startForeground when [ObdServiceLauncher] launched
 * it with startForegroundService, or Android crashes the app as the service stops.
 */
class RefusedForegroundStartTest {
    private val realActions =
        listOf(
            ObdService.ACTION_CONNECT,
            ObdService.ACTION_SCAN,
            ObdService.ACTION_TPMS_SCAN,
            ObdService.ACTION_CLEAR_DTC,
        )

    private fun settled(
        action: String?,
        sdkInt: Int = 34,
        alreadyInForeground: Boolean = false,
        nearby: Boolean = true,
        location: Boolean = false,
    ): List<Int?> {
        val entries = mutableListOf<Int?>()
        RefusedForegroundStart.settle(action, alreadyInForeground, nearby, location, sdkInt) { entries += it }
        return entries
    }

    @Test
    fun aRealStartLaunchedInTheForegroundEntersItWithTheSessionsType() {
        for (action in realActions) {
            assertEquals(action, listOf<Int?>(ForegroundServicePolicy.TYPE_CONNECTED_DEVICE), settled(action))
            assertEquals(
                action,
                listOf<Int?>(ForegroundServicePolicy.TYPE_CONNECTED_DEVICE or ForegroundServicePolicy.TYPE_LOCATION),
                settled(action, location = true),
            )
            assertEquals("untyped below Android 10", listOf<Int?>(null), settled(action, sdkInt = 28))
        }
    }

    @Test
    fun aStartThatOwesNoStartForegroundNeverEntersTheForeground() {
        assertTrue("the demo launches as a plain service", settled(ObdService.ACTION_DEMO).isEmpty())
        for (action in realActions) {
            assertTrue(
                "$action without Nearby devices launches as a plain service",
                settled(action, nearby = false).isEmpty(),
            )
            assertTrue("$action below Android 8 has no foreground launch", settled(action, sdkInt = 25).isEmpty())
            assertTrue("$action while already in the foreground", settled(action, alreadyInForeground = true).isEmpty())
        }
    }

    @Test
    fun aPlatformRefusalIsDroppedBecauseTheServiceStopsEitherWay() {
        for (refusal in listOf(SecurityException("type"), IllegalStateException("background start"))) {
            RefusedForegroundStart.settle(ObdService.ACTION_CONNECT, false, true, false, 34) { throw refusal }
        }
    }
}
