package com.volttracker.obdpoc

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Covers the Activity-side event-notification bridge delegate without constructing MainActivity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventNotificationHostDelegateTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var eventPrefs: EventNotificationPrefs

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("event-host-delegate-test", Context.MODE_PRIVATE)
        prefs.edit { clear() }
        eventPrefs = EventNotificationPrefs(prefs)
    }

    @Test
    fun stateJsonDelegatesToPrefsWhenReady() {
        eventPrefs.setLowSocEnabled(true)
        eventPrefs.setLowSocThresholdPct(17.0)
        val delegate = readyDelegate()

        val state = JSONObject(delegate.getEventNotificationStateJson())

        assertTrue(state.getBoolean("chargeComplete"))
        assertTrue(state.getBoolean("lowSoc"))
        assertEquals(17.0, state.getDouble("lowSocThresholdPct"), 0.001)
    }

    @Test
    fun unavailablePrefsReturnErrorAndBlockedStatusOnMutation() {
        var statusState = ""
        var statusDetail = ""
        var statusBlocked = false
        var appStatePublishes = 0
        val delegate =
            EventNotificationHostDelegate(
                prefs = { null },
                publishStatus = { state, detail, blocked ->
                    statusState = state
                    statusDetail = detail
                    statusBlocked = blocked
                },
                publishAppState = { appStatePublishes += 1 },
                notReadyMessage = { "Settings are still loading." },
            )

        val state = JSONObject(delegate.getEventNotificationStateJson())
        assertFalse(state.getBoolean("ok"))
        assertEquals("event_notifications_unavailable", state.getString("error"))

        delegate.setNewDtcEnabled(false)

        assertEquals("blocked", statusState)
        assertEquals("Settings are still loading.", statusDetail)
        assertTrue(statusBlocked)
        assertEquals(0, appStatePublishes)
    }

    @Test
    fun settersMutatePrefsAndPublishAppStateOncePerChange() {
        var appStatePublishes = 0
        val delegate = readyDelegate { appStatePublishes += 1 }

        delegate.setChargeCompleteEnabled(false)
        delegate.setNewDtcEnabled(false)
        delegate.setLowSocEnabled(true, 14.0)
        delegate.setHighPackTempEnabled(true, 52.0)
        delegate.setChargeTargetSoc(82.0)
        delegate.setAutoScanOnConnectEnabled(true)
        delegate.setMaintenanceDueEnabled(true)

        val reloaded = EventNotificationPrefs(prefs)
        assertFalse(reloaded.chargeCompleteEnabled())
        assertFalse(reloaded.newDtcEnabled())
        assertTrue(reloaded.lowSocEnabled())
        assertEquals(14.0, reloaded.lowSocThresholdPct(), 0.001)
        assertTrue(reloaded.highPackTempEnabled())
        assertEquals(52.0, reloaded.highPackTempThresholdC(), 0.001)
        assertEquals(82.0, reloaded.targetSocPct(), 0.001)
        assertTrue(reloaded.autoScanOnConnectEnabled())
        assertTrue(reloaded.maintenanceDueEnabled())
        assertEquals(7, appStatePublishes)
    }

    @Test
    fun enablingAnAlertRequestsMissingAndroidNotificationPermission() {
        var permissionRequests = 0
        val delegate =
            EventNotificationHostDelegate(
                prefs = { eventPrefs },
                publishStatus = { _, _, _ -> },
                publishAppState = {},
                notReadyMessage = { "not ready" },
                hasNotificationPermission = { false },
                ensureNotificationPermission = { permissionRequests += 1 },
            )

        assertFalse(JSONObject(delegate.getEventNotificationStateJson()).getBoolean("notificationsGranted"))
        delegate.setLowSocEnabled(true, 18.0)
        delegate.setLowSocEnabled(false, 18.0)

        assertEquals("only enabling requests the runtime grant", 1, permissionRequests)
    }

    private fun readyDelegate(publishAppState: () -> Unit = {}): EventNotificationHostDelegate =
        EventNotificationHostDelegate(
            prefs = { eventPrefs },
            publishStatus = { _, _, _ -> throw AssertionError("should not publish blocked status when ready") },
            publishAppState = publishAppState,
            notReadyMessage = { "not ready" },
        )
}
