package com.volttracker.obdpoc

import com.volttracker.obdpoc.ui.VoltRoute
import com.volttracker.obdpoc.ui.components.VoltTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugLaunchTest {
    @Test
    fun namesAreCaseInsensitive() {
        val launch = DebugLaunch.from(" trips ", "Settings", demo = true)
        assertEquals(VoltTab.TRIPS, launch.tab)
        assertEquals(listOf(VoltRoute.SETTINGS), launch.routes)
        assertTrue(launch.startDemo)
    }

    @Test
    fun unknownOrMissingNamesOpenTheNormalStartScreen() {
        val launch = DebugLaunch.from("garage", null, demo = false)
        assertEquals(VoltTab.DRIVE, launch.tab)
        assertEquals(emptyList<VoltRoute>(), launch.routes)
        assertFalse(launch.startDemo)
        assertEquals(DebugLaunch(), DebugLaunch.from(null, "nowhere", demo = false))
    }
}
