package com.volttracker.obdpoc

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.edit
import com.volttracker.obdpoc.classify.ClassifierInput
import com.volttracker.obdpoc.classify.VehicleStateClassifier
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercises the real classifier -> coordinator -> Android notification path without a car. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChargeNotificationRegressionTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var prefs: EventNotificationPrefs
    private lateinit var coordinator: EventNotificationCoordinator

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val storage = context.getSharedPreferences("charge-notification-regression", Context.MODE_PRIVATE)
        storage.edit { clear() }
        prefs = EventNotificationPrefs(storage)
        coordinator = EventNotificationCoordinator(prefs, EventNotifier(context), AutoScanController(prefs))
        coordinator.onSessionStart()
    }

    @Test
    fun engineGenerationInParkCannotPostTargetOrCompleteAlerts() {
        prefs.setTargetSocPct(80.0)
        repeat(3) { i -> coordinator.onTelemetry(sample(i, soc = 85.0)) }
        coordinator.onTelemetry(sample(3, currentA = 0.0, soc = 85.0))

        assertEquals("engine generation is not a plug-in charge", 0, shadowOf(manager).size())
    }

    @Test
    fun engineGenerationInParkCannotPostCheckThePlugAlert() {
        repeat(3) { i -> coordinator.onTelemetry(sample(i, soc = 45.0)) }
        coordinator.onTelemetry(sample(3, currentA = 0.0, soc = 45.0))

        assertEquals("stopping engine generation must not ask the owner to check the plug", 0, shadowOf(manager).size())
    }

    @Test
    fun missingOrUnknownClassificationCannotArmChargeAlertsFromRawCurrent() {
        for (state in listOf(null, "unknown", "invalid")) {
            coordinator.onSessionStart()
            repeat(3) { i ->
                val payload = sample(i, soc = 45.0, chargeState = "Charging")
                if (state == null) payload.remove("vehicleState") else payload.put("vehicleState", state)
                coordinator.onTelemetry(payload)
            }
            coordinator.onTelemetry(sample(3, currentA = 0.0, soc = 45.0))
        }

        assertEquals("missing confirmation must not fall back to current-only detection", 0, shadowOf(manager).size())
    }

    @Test
    fun confirmedPlugInChargeStillPostsComplete() {
        repeat(3) { i -> coordinator.onTelemetry(sample(i, soc = 90.0, chargeState = "Charging")) }
        coordinator.onTelemetry(sample(3, currentA = 0.0, soc = 90.0, chargeState = "Complete"))

        assertPostedTitle(EventNotifier.NOTIFICATION_ID_CHARGE, R.string.notification_charge_complete_title)
    }

    @Test
    fun confirmedPlugInChargeStillPostsInterrupted() {
        repeat(3) { i -> coordinator.onTelemetry(sample(i, soc = 45.0, chargeState = "Charging")) }
        coordinator.onTelemetry(sample(3, currentA = 0.0, soc = 45.0))

        assertPostedTitle(EventNotifier.NOTIFICATION_ID_CHARGE, R.string.notification_charge_interrupted_title)
    }

    @Test
    fun confirmedPlugInChargeStillPostsTargetReached() {
        prefs.setTargetSocPct(80.0)
        repeat(3) { i -> coordinator.onTelemetry(sample(i, soc = 85.0, chargeState = "Charging")) }

        assertPostedTitle(EventNotifier.NOTIFICATION_ID_TARGET_SOC, R.string.notification_target_soc_title)
    }

    @Test
    fun engineInParkStillReceivesLowSocAlerts() {
        prefs.setLowSocEnabled(true)
        coordinator.onTelemetry(sample(0, soc = 10.0))

        assertPostedTitle(EventNotifier.NOTIFICATION_ID_LOW_SOC, R.string.notification_low_soc_title)
    }

    private fun assertPostedTitle(
        id: Int,
        titleRes: Int,
    ) {
        val posted = shadowOf(manager).getNotification(id)
        assertNotNull("a genuine alert must still reach Android", posted)
        assertEquals(context.getString(titleRes), posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    private fun sample(
        minute: Int,
        currentA: Double = -20.0,
        soc: Double,
        chargeState: String = "None",
    ): JSONObject {
        val atMs = 1_000L + minute * 60_000L
        val classified =
            VehicleStateClassifier.classify(
                ClassifierInput(0.0, 1_400, 14.5, currentA, null, true, atMs, chargeState),
            )
        return JSONObject()
            .put("source", "obd")
            .put("updatedAt", atMs)
            .put("rpm", 1_400)
            .put("gear", "P")
            .put("speedKph", 0.0)
            .put("packCurrentA", currentA)
            .put("packVoltage", 355.0)
            .put("soc", soc)
            .put("chargeState", chargeState)
            .put("vehicleState", classified.state.asPayloadKey())
    }
}
