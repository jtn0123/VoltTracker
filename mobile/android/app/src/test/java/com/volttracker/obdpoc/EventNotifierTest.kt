package com.volttracker.obdpoc

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Direct coverage for [EventNotifier] (M1): the production class that posts the platform
 * notifications [EventNotificationCoordinatorTest] only records. Pins the POST_NOTIFICATIONS gate
 * (API 33+), channel creation, per-event-kind dispatch + ids, and the no-op-when-blocked path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventNotifierTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        manager = context.getSystemService(NotificationManager::class.java)
    }

    private fun grantPostNotifications() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun createChannelRegistersTheAlertsChannel() {
        EventNotifier(context).createChannel()

        val channel = manager.getNotificationChannel(EventNotifier.CHANNEL_ID)
        assertNotNull("the alerts channel must be created", channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    @Test
    fun chargeCompletePostsANotificationWhenPermissionGranted() {
        grantPostNotifications()

        EventNotifier(context).notify(EventNotificationDecider.Event.ChargeComplete(7.5))

        val posted = shadowOf(manager).getNotification(EventNotifier.NOTIFICATION_ID_CHARGE)
        assertNotNull("a charge-complete alert must be posted", posted)
        assertEquals(1, shadowOf(manager).size())
    }

    @Test
    fun eachEventKindPostsUnderItsOwnId() {
        grantPostNotifications()
        val notifier = EventNotifier(context)

        notifier.notify(EventNotificationDecider.Event.ChargeComplete(0.0))
        notifier.notify(EventNotificationDecider.Event.NewDtc(listOf("P0420")))
        notifier.notify(EventNotificationDecider.Event.LowSoc(15.0, 20.0))
        notifier.notify(EventNotificationDecider.Event.HighPackTemp(48.0, 45.0))

        // Distinct ids per kind, so all four coexist rather than replacing one another.
        assertEquals(4, shadowOf(manager).size())
        assertNotNull(shadowOf(manager).getNotification(EventNotifier.NOTIFICATION_ID_CHARGE))
        assertNotNull(shadowOf(manager).getNotification(EventNotifier.NOTIFICATION_ID_NEW_DTC))
        assertNotNull(shadowOf(manager).getNotification(EventNotifier.NOTIFICATION_ID_LOW_SOC))
        assertNotNull(shadowOf(manager).getNotification(EventNotifier.NOTIFICATION_ID_HIGH_TEMP))
    }

    @Test
    fun chargeInterruptedAndTargetReachedPostUnderTheirOwnIds() {
        grantPostNotifications()
        val notifier = EventNotifier(context)

        // Target-reached (M2) is its own id; charge-interrupted (M3) shares the charge id so it
        // replaces (not stacks with) a charge-complete of the same drive.
        notifier.notify(EventNotificationDecider.Event.TargetSocReached(80.0, 80.0))
        notifier.notify(EventNotificationDecider.Event.ChargeInterrupted(47.0, 80.0))

        assertEquals(2, shadowOf(manager).size())
        assertNotNull(shadowOf(manager).getNotification(EventNotifier.NOTIFICATION_ID_TARGET_SOC))
        assertNotNull(shadowOf(manager).getNotification(EventNotifier.NOTIFICATION_ID_CHARGE))
    }

    @Test
    fun chargeInterruptedReplacesAChargeCompleteOfTheSameDrive() {
        grantPostNotifications()
        val notifier = EventNotifier(context)

        notifier.notify(EventNotificationDecider.Event.ChargeComplete(5.0))
        notifier.notify(EventNotificationDecider.Event.ChargeInterrupted(40.0, 80.0))

        // Both ride NOTIFICATION_ID_CHARGE, so only the latest charge alert remains.
        assertEquals(1, shadowOf(manager).size())
        assertNotNull(shadowOf(manager).getNotification(EventNotifier.NOTIFICATION_ID_CHARGE))
    }

    @Test
    fun aSecondAlertOfTheSameKindReplacesTheFirst() {
        grantPostNotifications()
        val notifier = EventNotifier(context)

        notifier.notify(EventNotificationDecider.Event.LowSoc(18.0, 20.0))
        notifier.notify(EventNotificationDecider.Event.LowSoc(12.0, 20.0))

        // Same id -> the newer alert replaces the prior one rather than stacking.
        assertEquals(1, shadowOf(manager).size())
        assertNotNull(shadowOf(manager).getNotification(EventNotifier.NOTIFICATION_ID_LOW_SOC))
    }

    @Test
    fun maintenanceDuePostsUnderItsOwnBandAndDistinctServicesCoexist() {
        grantPostNotifications()
        val notifier = EventNotifier(context)

        notifier.notify(EventNotificationDecider.Event.MaintenanceDue("Oil change", "1000 km"))
        notifier.notify(EventNotificationDecider.Event.MaintenanceDue("Tire rotation", "12 days"))

        // Two distinct services -> two coexisting alerts (different per-service ids in the band).
        assertEquals(2, shadowOf(manager).size())
    }

    @Test
    fun maintenanceDueReusesTheSameIdForTheSameService() {
        grantPostNotifications()
        val notifier = EventNotifier(context)

        notifier.notify(EventNotificationDecider.Event.MaintenanceDue("Oil change", "1000 km"))
        notifier.notify(EventNotificationDecider.Event.MaintenanceDue("Oil change", "1500 km"))

        // Same service label -> same id -> the second replaces the first rather than stacking.
        assertEquals(1, shadowOf(manager).size())
    }

    @Test
    fun postIsANoOpWhenPostNotificationsIsDenied() {
        // Robolectric denies runtime permissions by default; on API 33+ canPost() returns false, so
        // the alert is dropped (logged) instead of crashing or surfacing.
        EventNotifier(context).notify(EventNotificationDecider.Event.ChargeComplete(5.0))

        assertEquals("a denied POST_NOTIFICATIONS must drop the alert", 0, shadowOf(manager).size())
    }

    @Test
    fun postEnsuresTheChannelExistsEvenWithoutAnExplicitCreateChannelCall() {
        grantPostNotifications()

        EventNotifier(context).notify(EventNotificationDecider.Event.NewDtc(listOf("P0AA6", "P1FFF")))

        assertNotNull(
            "notify() must lazily ensure the channel",
            manager.getNotificationChannel(EventNotifier.CHANNEL_ID),
        )
        assertTrue(shadowOf(manager).size() >= 1)
    }
}
