package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupTraceTest {
    @Before
    fun clearLogs() {
        ShadowLog.clear()
    }

    @Test
    fun resetLogsReasonWithStableFields() {
        StartupTrace.reset("fresh-start")

        val messages = startupLogMessages()
        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("mark=reset:fresh-start"))
        assertTrue(messages.single().contains("elapsedMs="))
        assertTrue(messages.single().contains("uptimeMs="))
    }

    @Test
    fun markLogsNamedStartupMarker() {
        StartupTrace.reset("baseline")
        ShadowLog.clear()

        StartupTrace.mark("dashboard_ready")

        val message = startupLogMessages().single()
        assertTrue(message.contains("mark=dashboard_ready"))
        assertTrue(message.contains("elapsedMs="))
        assertTrue(message.contains("uptimeMs="))
    }

    @Test
    fun measureReturnsValueAndBracketsWork() {
        val result =
            StartupTrace.measure("phase_start", "phase_end") {
                "done"
            }

        assertEquals("done", result)
        val messages = startupLogMessages()
        assertEquals(2, messages.size)
        assertTrue(messages[0].contains("mark=phase_start"))
        assertTrue(messages[1].contains("mark=phase_end"))
    }

    @Test
    fun measureStillLogsEndMarkerWhenWorkThrows() {
        try {
            StartupTrace.measure("boom_start", "boom_end") {
                throw IllegalStateException("boom")
            }
            fail("expected measure to rethrow the block failure")
        } catch (ex: IllegalStateException) {
            assertEquals("boom", ex.message)
        }

        val messages = startupLogMessages()
        assertEquals(2, messages.size)
        assertTrue(messages[0].contains("mark=boom_start"))
        assertTrue(messages[1].contains("mark=boom_end"))
    }

    private fun startupLogMessages(): List<String> = ShadowLog.getLogsForTag(StartupTrace.TAG).map { it.msg }
}
