package com.volttracker.obdpoc

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import com.volttracker.obdpoc.data.ObdLocalStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class TripSummaryNotifierTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()
    private lateinit var store: ObdLocalStore
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        store = ObdLocalStore(context)
        store.clearAllData()
        notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
        store.close()
    }

    @Test
    fun meaningfulSavedDrivePostsQuietReceiptThatDeepLinksToTrip() {
        val sessionId = createDrive(34.05, 34.06)
        val trip = store.getTripsJson(10).getJSONObject(0)

        TripSummaryNotifier(context) { true }.notifyMaterializedTrip(store, sessionId)

        val notification = shadowOf(notificationManager).getNotification(TripSummaryNotifier.NOTIFICATION_ID_TRIP)
        assertNotNull(notification)
        requireNotNull(notification)
        assertEquals(TripSummaryNotifier.CHANNEL_ID, notification.channelId)
        assertTrue(
            notification.extras
                .getCharSequence("android.text")
                .toString()
                .contains("mi"),
        )
        val open = shadowOf(notification.contentIntent).savedIntent
        assertEquals(ComponentName(context, MainActivity::class.java), open.component)
        assertEquals(trip.getString("id"), open.getStringExtra(MainActivity.EXTRA_OPEN_TRIP))
        assertTrue(open.getBooleanExtra(MainActivity.EXTRA_OPEN_TRIP_RECEIPT, false))

        val channel = notificationManager.getNotificationChannel(TripSummaryNotifier.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun disabledOrVeryShortDriveDoesNotPostReceipt() {
        val shortSession = createDrive(34.05, 34.0501)
        TripSummaryNotifier(context) { true }.notifyMaterializedTrip(store, shortSession)
        assertNull(shadowOf(notificationManager).getNotification(TripSummaryNotifier.NOTIFICATION_ID_TRIP))

        val longSession = createDrive(34.05, 34.06)
        TripSummaryNotifier(context) { false }.notifyMaterializedTrip(store, longSession)
        assertNull(shadowOf(notificationManager).getNotification(TripSummaryNotifier.NOTIFICATION_ID_TRIP))
    }

    private fun createDrive(
        startLatitude: Double,
        endLatitude: Double,
    ): Long {
        val startedAt = System.currentTimeMillis() - 60_000L
        val sessionId = store.startSession(ObdLocalStore.MODE_OBD, "00:11", "OBDLink", startedAt)
        store.recordTelemetry(sessionId, sample(startLatitude, startedAt), startedAt)
        store.recordTelemetry(sessionId, sample(endLatitude, startedAt + 30_000L), startedAt + 30_000L)
        store.finishSession(sessionId, ObdLocalStore.STATUS_COMPLETE, startedAt + 60_000L, "0100")
        return sessionId
    }

    private fun sample(
        latitude: Double,
        atMs: Long,
    ): JSONObject =
        JSONObject()
            .put("source", "obd")
            .put("speedKph", 40)
            .put("latitude", latitude)
            .put("longitude", -118.25)
            .put("updatedAt", atMs)
}
