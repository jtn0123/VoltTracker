package com.volttracker.obdpoc

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Pins [AutoScanController] gating (M3): a scan runs once per connect when enabled, respects the
 * per-drive throttle, and is a no-op when disabled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoScanControllerTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var eventPrefs: EventNotificationPrefs
    private var now = 1_000_000L

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("auto-scan-test", Context.MODE_PRIVATE)
        prefs.edit { clear() }
        eventPrefs = EventNotificationPrefs(prefs)
    }

    @Test
    fun defaultsOffSoNoScanRuns() {
        val controller = AutoScanController(eventPrefs, nowMs = { now })
        controller.resetForConnect()
        val ran =
            controller.onConnected { throw AssertionError("auto-scan must not run by default") }
        assertFalse(ran)
    }

    @Test
    fun runsOncePerConnectWhenEnabled() {
        eventPrefs.setAutoScanOnConnectEnabled(true)
        val controller = AutoScanController(eventPrefs, nowMs = { now })
        controller.resetForConnect()
        var runs = 0

        assertTrue(controller.onConnected { runs += 1 })
        // A second onConnected within the same connect must not scan again.
        assertFalse(controller.onConnected { runs += 1 })

        assertEquals(1, runs)
    }

    @Test
    fun respectsThrottleAcrossConnects() {
        eventPrefs.setAutoScanOnConnectEnabled(true)
        val throttle = 30L * 60L * 1000L
        val controller = AutoScanController(eventPrefs, throttleMs = throttle, nowMs = { now })

        controller.resetForConnect()
        assertTrue(controller.onConnected { })

        // A fresh connect 10 minutes later is still inside the throttle window.
        now += 10L * 60L * 1000L
        controller.resetForConnect()
        assertFalse(controller.onConnected { throw AssertionError("throttled scan must not run") })

        // Past the throttle window it runs again.
        now += 25L * 60L * 1000L
        controller.resetForConnect()
        assertTrue(controller.onConnected { })
    }

    @Test
    fun disabledMidwayStopsScanning() {
        eventPrefs.setAutoScanOnConnectEnabled(true)
        val controller = AutoScanController(eventPrefs, nowMs = { now })
        controller.resetForConnect()
        assertTrue(controller.onConnected { })

        eventPrefs.setAutoScanOnConnectEnabled(false)
        now += 60L * 60L * 1000L
        controller.resetForConnect()
        assertFalse(controller.onConnected { throw AssertionError("disabled scan must not run") })
    }

    @Test
    fun lastScanTimestampPersistsAcrossControllerInstances() {
        eventPrefs.setAutoScanOnConnectEnabled(true)
        val first = AutoScanController(eventPrefs, nowMs = { now })
        first.resetForConnect()
        assertTrue(first.onConnected { })

        // A new controller (e.g. fresh service) reads the persisted timestamp and stays throttled.
        now += 5L * 60L * 1000L
        val second = AutoScanController(eventPrefs, nowMs = { now })
        second.resetForConnect()
        assertFalse(second.onConnected { throw AssertionError("persisted throttle must still apply") })
    }
}
