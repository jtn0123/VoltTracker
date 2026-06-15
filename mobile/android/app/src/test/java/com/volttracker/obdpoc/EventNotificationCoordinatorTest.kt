package com.volttracker.obdpoc

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Exercises [EventNotificationCoordinator] end to end: it reads the broadcast telemetry / scan
 * payloads, drives the decider, posts via the notifier, and persists the new-DTC baseline. A
 * recording [EventNotifier] captures what would be posted without touching the platform
 * NotificationManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventNotificationCoordinatorTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var eventPrefs: EventNotificationPrefs
    private lateinit var notifier: RecordingNotifier
    private var now = 2_000_000L

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("event-coordinator-test", Context.MODE_PRIVATE)
        prefs.edit { clear() }
        eventPrefs = EventNotificationPrefs(prefs)
        notifier = RecordingNotifier(context)
    }

    private fun coordinator(): EventNotificationCoordinator {
        val coord =
            EventNotificationCoordinator(
                eventPrefs,
                notifier,
                AutoScanController(eventPrefs, nowMs = { now }),
                nowMs = { now },
            )
        coord.onSessionStart()
        return coord
    }

    private fun obdSample(
        atMs: Long,
        packCurrentA: Double? = null,
        packVoltage: Double? = null,
        speedKph: Double? = 0.0,
        soc: Double? = null,
        batteryTemp: Double? = null,
    ): JSONObject {
        val payload = JSONObject()
        payload.put("source", "obd")
        payload.put("updatedAt", atMs)
        packCurrentA?.let { payload.put("packCurrentA", it) }
        packVoltage?.let { payload.put("packVoltage", it) }
        speedKph?.let { payload.put("speedKph", it) }
        soc?.let { payload.put("soc", it) }
        batteryTemp?.let { payload.put("batteryTemp", it) }
        return payload
    }

    @Test
    fun chargeCompleteFromLiveTelemetryPostsOnce() {
        val coord = coordinator()
        for (i in 0..3) {
            coord.onTelemetry(obdSample(i * 600_000L, packCurrentA = -20.0, packVoltage = 355.0))
        }
        val events = coord.onTelemetry(obdSample(2_500_000L, packCurrentA = 5.0, speedKph = 30.0))

        assertEquals(1, events.size)
        assertEquals(1, notifier.posted.size)
        assertTrue(notifier.posted[0] is EventNotificationDecider.Event.ChargeComplete)
    }

    @Test
    fun lowSocAlertFiresOnceOnCrossingWhenEnabled() {
        eventPrefs.setLowSocEnabled(true)
        eventPrefs.setLowSocThresholdPct(20.0)
        val coord = coordinator()

        assertTrue(coord.onTelemetry(obdSample(0L, soc = 30.0)).isEmpty())
        assertEquals(1, coord.onTelemetry(obdSample(1L, soc = 18.0)).size)
        // Still low, but already alerted.
        assertTrue(coord.onTelemetry(obdSample(2L, soc = 15.0)).isEmpty())
        assertEquals(1, notifier.posted.size)
        assertTrue(notifier.posted[0] is EventNotificationDecider.Event.LowSoc)
    }

    @Test
    fun scanPayloadWithNewCodePostsAndPersistsBaseline() {
        eventPrefs.setLastScanDtcCodes(listOf("P0AA6"))
        val coord = coordinator()
        val scan = JSONObject()
        scan.put("source", "scan")
        scan.put("dtcCodes", JSONArray(listOf("P0AA6", "P1FFF")))

        val events = coord.onTelemetry(scan)

        assertEquals(1, events.size)
        val event = events[0] as EventNotificationDecider.Event.NewDtc
        assertEquals(listOf("P1FFF"), event.newCodes)
        // The full scanned set becomes the new persisted baseline.
        assertEquals(setOf("P0AA6", "P1FFF"), eventPrefs.lastScanDtcCodes())
    }

    @Test
    fun scanPayloadWithNoNewCodesDoesNotPostButUpdatesBaseline() {
        eventPrefs.setLastScanDtcCodes(listOf("P0AA6", "P1FFF"))
        val coord = coordinator()
        val scan = JSONObject()
        scan.put("source", "scan")
        scan.put("dtcCodes", JSONArray(listOf("P0AA6")))

        val events = coord.onTelemetry(scan)

        assertTrue(events.isEmpty())
        assertTrue(notifier.posted.isEmpty())
        assertEquals(setOf("P0AA6"), eventPrefs.lastScanDtcCodes())
    }

    @Test
    fun sessionRestartResetsChargeAndThresholdState() {
        eventPrefs.setLowSocEnabled(true)
        eventPrefs.setLowSocThresholdPct(20.0)
        val coord = coordinator()
        coord.onTelemetry(obdSample(0L, soc = 30.0))
        assertEquals(1, coord.onTelemetry(obdSample(1L, soc = 18.0)).size)

        // New session re-arms the alert; the same crossing fires again.
        coord.onSessionStart()
        coord.onTelemetry(obdSample(2L, soc = 30.0))
        assertEquals(1, coord.onTelemetry(obdSample(3L, soc = 18.0)).size)
    }

    @Test
    fun togglingLowSocOnMidSessionTakesEffectWithoutSessionRestart() {
        // Pref starts OFF; a crossing must NOT fire. Enabling it mid-session must take effect on the
        // next sample (the decider re-reads prefs each evaluation), without a session restart.
        eventPrefs.setLowSocEnabled(false)
        eventPrefs.setLowSocThresholdPct(20.0)
        val coord = coordinator()

        assertTrue(coord.onTelemetry(obdSample(0L, soc = 30.0)).isEmpty())
        assertTrue("disabled toggle suppresses the alert", coord.onTelemetry(obdSample(1L, soc = 18.0)).isEmpty())

        eventPrefs.setLowSocEnabled(true)
        // SOC must first recover above the threshold (+hysteresis) so the alert is armed, then cross.
        assertTrue(coord.onTelemetry(obdSample(2L, soc = 30.0)).isEmpty())
        assertEquals(1, coord.onTelemetry(obdSample(3L, soc = 15.0)).size)
        assertTrue(notifier.posted[0] is EventNotificationDecider.Event.LowSoc)
    }

    @Test
    fun togglingChargeCompleteOffMidSessionSuppressesTheAlert() {
        eventPrefs.setChargeCompleteEnabled(true)
        val coord = coordinator()
        for (i in 0..3) {
            coord.onTelemetry(obdSample(i * 600_000L, packCurrentA = -20.0, packVoltage = 355.0))
        }
        // Disable mid-charge: the charging->idle transition must no longer post a charge-complete.
        eventPrefs.setChargeCompleteEnabled(false)
        val events = coord.onTelemetry(obdSample(2_500_000L, packCurrentA = 5.0, speedKph = 30.0))

        assertTrue("disabling mid-session suppresses the charge-complete alert", events.isEmpty())
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun autoScanRunsGenericMode03AndFeedsNewDtcOnConnect() {
        eventPrefs.setAutoScanOnConnectEnabled(true)
        val coord = coordinator()
        val engine = FakeDtcEngine()

        coord.maybeRunAutoDtcScan(engine)

        assertTrue("auto-scan sent generic Mode 03", engine.commands.contains("03"))
        assertEquals(1, notifier.posted.size)
        val event = notifier.posted[0] as EventNotificationDecider.Event.NewDtc
        assertTrue(event.newCodes.contains("P0420"))
        assertTrue(eventPrefs.lastScanDtcCodes().contains("P0420"))
    }

    @Test
    fun autoScanIsNoOpWhenDisabled() {
        val coord = coordinator()
        val engine = FakeDtcEngine()
        coord.maybeRunAutoDtcScan(engine)
        assertTrue("disabled auto-scan must not send commands", engine.commands.isEmpty())
        assertTrue(notifier.posted.isEmpty())
    }

    private class RecordingNotifier(
        context: Context,
    ) : EventNotifier(context) {
        val posted: MutableList<EventNotificationDecider.Event> = ArrayList()

        override fun notify(event: EventNotificationDecider.Event) {
            posted.add(event)
        }
    }

    /** An engine whose Mode 03 read returns one stored DTC (a generic 43 01 P0420 reply). */
    private class FakeDtcEngine : ObdPollingEngine(ObdService()) {
        val commands: MutableList<String?> = ArrayList()

        override fun sendRecoverableCommand(
            command: String?,
            timeoutMs: Long,
        ): String {
            commands.add(command)
            // Mode 03 positive response: 43 01 04 20 -> one DTC P0420.
            return if (command == "03") "43 01 04 20 \r>" else "OK\r>"
        }
    }
}
