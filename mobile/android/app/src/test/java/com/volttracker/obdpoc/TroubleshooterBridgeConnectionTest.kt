package com.volttracker.obdpoc

import android.os.Bundle
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Behavior tests for [TroubleshooterBridge.startTestConnection] — the one-shot probe the dashboard
 * fires to check whether a remembered adapter is reachable. The sibling [TroubleshooterBridgeTest]
 * deliberately skips this path because it drives the [MainActivity] service plumbing; here we pin
 * that plumbing with a recording [HarnessActivity] so the dispatch is observable:
 *
 * - With no remembered adapter, the bridge must surface a `blocked` status and touch nothing else.
 * - With a remembered adapter, the bridge must remember the device, start an
 *   [ObdService.ACTION_CONNECT] session against it, and schedule a delayed auto-stop on the main
 *   looper that calls [MainActivity.stopObdService].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TroubleshooterBridgeConnectionTest {
    private var controller: ActivityController<HarnessActivity>? = null
    private lateinit var activity: HarnessActivity
    private lateinit var bridge: TroubleshooterBridge

    @Before
    fun setUp() {
        val controller = Robolectric.buildActivity(HarnessActivity::class.java).create()
        this.controller = controller
        activity = controller.get()
        bridge = TroubleshooterBridge(activity)
    }

    @After
    fun tearDown() {
        bridge.shutdown()
        controller?.destroy()
    }

    @Test
    fun startTestConnectionBlocksWhenNoAdapterRemembered() {
        // getLastOrCandidateDevice() returns an empty address when nothing is remembered and no
        // bonded OBD candidate exists, so the bridge must bail with a blocked status before
        // touching the service.
        bridge.startTestConnection()

        assertEquals("missing adapter must surface a blocked status", "blocked", activity.lastState)
        assertTrue(
            "blocked detail should tell the user to connect once first, got: ${activity.lastDetail}",
            activity.lastDetail?.contains("Connect once first") == true,
        )
        assertNull("no service connect should be started without a remembered adapter", activity.connectAddress)
        assertEquals("no device should be remembered on the blocked path", 0, activity.rememberCalls)
    }

    @Test
    fun startTestConnectionStartsProbeAndSchedulesAutoStop() {
        // Seed a remembered adapter so getLastOrCandidateDevice() returns a real address. The
        // bridge must remember it, kick off an ACTION_CONNECT probe, and post a delayed stop.
        activity.requireDeviceCatalog().remember(REMEMBERED_ADDRESS, REMEMBERED_NAME)

        bridge.startTestConnection()

        assertEquals("the probe must remember the device first", 1, activity.rememberCalls)
        assertEquals("the probe must connect to the remembered adapter", REMEMBERED_ADDRESS, activity.connectAddress)
        assertEquals("the probe must pass the remembered name through", REMEMBERED_NAME, activity.connectName)
        assertEquals(
            "the probe must use the live-connect action",
            ObdService.ACTION_CONNECT,
            activity.connectAction,
        )
        assertEquals("the auto-stop must not have run before the delay elapses", 0, activity.stopCalls)

        // The auto-stop is postDelayed(~25 s) onto the main looper; run pending main-looper work
        // regardless of its scheduled delay to observe the stop dispatch.
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()

        assertEquals("the scheduled auto-stop must eventually stop the service", 1, activity.stopCalls)
    }

    @Test
    fun startTestConnectionReplacesAnyPendingStopOnReentry() {
        // A second startTestConnection before the first auto-stop fires must not leave two stops
        // queued — the bridge clears the pending stop handler before re-posting. Draining the
        // looper afterwards should therefore run exactly one stop.
        activity.requireDeviceCatalog().remember(REMEMBERED_ADDRESS, REMEMBERED_NAME)

        bridge.startTestConnection()
        bridge.startTestConnection()
        assertEquals("each probe re-issues the connect", REMEMBERED_ADDRESS, activity.connectAddress)
        assertEquals("no auto-stop should have fired before the looper drains", 0, activity.stopCalls)

        shadowOf(Looper.getMainLooper()).runToEndOfTasks()

        assertEquals("only the surviving scheduled stop should run", 1, activity.stopCalls)
    }

    /**
     * [MainActivity] override that records the bridge's outbound dispatches instead of touching the
     * real service / store plumbing. Mirrors the recording-harness pattern in [TroubleshooterBridgeTest].
     */
    class HarnessActivity : MainActivity() {
        var lastState: String? = null
        var lastDetail: String? = null
        var rememberCalls = 0
        var connectAction: String? = null
        var connectAddress: String? = null
        var connectName: String? = null
        var stopCalls = 0

        override fun onCreate(savedInstanceState: Bundle?) {
            deviceCatalog = DeviceCatalog(this, getSharedPreferences("troubleshooter-connection-test", 0))
        }

        override fun publishStatus(
            state: String?,
            detail: String?,
            blocked: Boolean,
        ) {
            lastState = state
            lastDetail = detail
        }

        override fun rememberDevice(
            address: String?,
            name: String?,
        ) {
            rememberCalls += 1
        }

        override fun startObdService(
            action: String?,
            address: String?,
            name: String?,
        ) {
            connectAction = action
            connectAddress = address
            connectName = name
        }

        override fun stopObdService() {
            stopCalls += 1
        }
    }

    private companion object {
        private const val REMEMBERED_ADDRESS = "AA:BB:CC:DD:EE:FF"
        private const val REMEMBERED_NAME = "Veepeak OBDCheck"
    }
}
