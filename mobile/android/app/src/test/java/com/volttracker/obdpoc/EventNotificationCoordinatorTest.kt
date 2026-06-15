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
    fun autoScanRunsGenericMode03AndSilentlyBaselinesOnFirstConnect() {
        // First auto-scan ever (no baseline): the pre-existing P0420 is NOT "new", so it silently
        // establishes the baseline rather than posting a false new-DTC alert. (Report item B3.)
        eventPrefs.setAutoScanOnConnectEnabled(true)
        val coord = coordinator()
        val engine = FakeDtcEngine()

        coord.maybeRunAutoDtcScan(engine)

        assertTrue("auto-scan sent generic Mode 03", engine.commands.contains("03"))
        assertTrue("first scan must not raise a new-DTC alert", notifier.posted.isEmpty())
        assertTrue("but the baseline is established", eventPrefs.lastScanDtcCodes().contains("P0420"))
        assertTrue(eventPrefs.hasDtcBaseline())
    }

    @Test
    fun autoScanFeedsNewDtcOnceABaselineExists() {
        // A baseline already exists (e.g. a prior scan), so an auto-scan that surfaces a code not in
        // it DOES fire the new-DTC notification.
        eventPrefs.setAutoScanOnConnectEnabled(true)
        eventPrefs.setLastScanDtcCodes(listOf("P0AA6"))
        val coord = coordinator()
        val engine = FakeDtcEngine()

        coord.maybeRunAutoDtcScan(engine)

        assertEquals(1, notifier.posted.size)
        val event = notifier.posted[0] as EventNotificationDecider.Event.NewDtc
        assertTrue(event.newCodes.contains("P0420"))
        assertTrue(eventPrefs.lastScanDtcCodes().contains("P0420"))
    }

    // ---- reconnect / null-payload / clock-fallback (D5) --------------------------------

    @Test
    fun withinSessionReconnectDoesNotReplayACompletedCharge() {
        // A charge completes once. A mid-session disconnect (connected:false) and reconnect must NOT
        // re-fire the completed charge — the coordinator only resets per-session state on an explicit
        // onSessionStart (a real new logging session), not on a within-session drop+resume, which is
        // the field's most common event.
        val coord = coordinator()
        for (i in 0..3) {
            coord.onTelemetry(obdSample(i * 600_000L, packCurrentA = -20.0, packVoltage = 355.0))
        }
        val completed = coord.onTelemetry(obdSample(2_500_000L, packCurrentA = 5.0, speedKph = 30.0))
        assertEquals(1, completed.size)
        assertEquals(1, notifier.posted.size)

        // Adapter drops mid-session: a connected:false status-shaped payload (no OBD charging signal).
        val disconnect = JSONObject()
        disconnect.put("source", "obd")
        disconnect.put("connected", false)
        disconnect.put("updatedAt", 2_600_000L)
        assertTrue("a disconnect sample must not post anything", coord.onTelemetry(disconnect).isEmpty())

        // Reconnect and resume non-charging samples within the SAME session (no onSessionStart).
        assertTrue(coord.onTelemetry(obdSample(2_700_000L, packCurrentA = 4.0, speedKph = 25.0)).isEmpty())
        assertTrue(coord.onTelemetry(obdSample(2_800_000L, packCurrentA = 4.0, speedKph = 25.0)).isEmpty())

        assertEquals("the completed charge must not be replayed on reconnect", 1, notifier.posted.size)
    }

    @Test
    fun nonObdSourcePayloadIsIgnored() {
        // A GPS-sourced (or any non-obd, non-scan) payload must not drive the live-sample path.
        val coord = coordinator()
        val gps = JSONObject()
        gps.put("source", "gps")
        gps.put("latitude", 37.0)
        gps.put("longitude", -122.0)
        gps.put("soc", 5.0) // even a low SOC here must be ignored — it's not an OBD reading.
        eventPrefs.setLowSocEnabled(true)

        val events = coord.onTelemetry(gps)

        assertTrue("a non-obd payload must be ignored", events.isEmpty())
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun updatedAtZeroSampleUsesInjectedClockForChargeEnergyIntegration() {
        // When a sample carries updatedAt<=0 the coordinator falls back to its injected clock for the
        // capture time, so charge-energy integration still measures real elapsed time. Advance the
        // clock an hour between two steady 7.1 kW samples and confirm the completed charge reports
        // ~7.1 kWh rather than the zero it would get if the missing timestamp collapsed the interval.
        val coord = coordinator()
        // updatedAt omitted entirely -> optLong default 0 -> nowMs() fallback. Need >= MIN_CHARGE_SAMPLES.
        now = 0L
        coord.onTelemetry(chargingSampleNoTimestamp())
        now = 1_800_000L
        coord.onTelemetry(chargingSampleNoTimestamp())
        now = 3_600_000L
        coord.onTelemetry(chargingSampleNoTimestamp())
        // Charge ends.
        now = 3_660_000L
        val ended = JSONObject()
        ended.put("source", "obd")
        ended.put("packCurrentA", 5.0)
        ended.put("speedKph", 30.0)
        val events = coord.onTelemetry(ended)

        assertEquals(1, events.size)
        val event = events[0] as EventNotificationDecider.Event.ChargeComplete
        assertEquals("integration must use the injected clock, not a zero interval", 7.1, event.energyKwh, 0.1)
    }

    /** A steady ~7.1 kW charging OBD sample with NO updatedAt field, forcing the nowMs() fallback. */
    private fun chargingSampleNoTimestamp(): JSONObject {
        val payload = JSONObject()
        payload.put("source", "obd")
        payload.put("packCurrentA", -20.0)
        payload.put("packVoltage", 355.0)
        payload.put("speedKph", 0.0)
        return payload
    }

    @Test
    fun autoScanIsNoOpWhenDisabled() {
        val coord = coordinator()
        val engine = FakeDtcEngine()
        coord.maybeRunAutoDtcScan(engine)
        assertTrue("disabled auto-scan must not send commands", engine.commands.isEmpty())
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun settingsAreCachedAcrossSamplesAndRebuiltOnlyOnAToggle() {
        // G2: the coordinator must not re-read the toggle getters on every 1 Hz sample. Count the
        // settings getter reads via a counting prefs; many samples should read the toggle getters
        // once (the cached snapshot), and a mid-session toggle should rebuild exactly once more.
        val counting = CountingPrefs(prefs)
        val countingEventPrefs = EventNotificationPrefs(counting)
        val coord =
            EventNotificationCoordinator(
                countingEventPrefs,
                notifier,
                AutoScanController(countingEventPrefs, nowMs = { now }),
                nowMs = { now },
            )
        coord.onSessionStart()

        val readsAfterStart = counting.chargeCompleteReads
        repeat(20) { i -> coord.onTelemetry(obdSample(i.toLong(), soc = 50.0)) }
        assertEquals(
            "20 identical-version samples must not re-read the toggles",
            readsAfterStart,
            counting.chargeCompleteReads,
        )

        // A mid-session toggle bumps the version, so the very next sample rebuilds the snapshot once.
        countingEventPrefs.setChargeCompleteEnabled(false)
        coord.onTelemetry(obdSample(21L, soc = 50.0))
        assertTrue("a toggle forces exactly one rebuild", counting.chargeCompleteReads > readsAfterStart)
    }

    /** Delegates to a real prefs but counts reads of the charge-complete toggle, to prove caching. */
    private class CountingPrefs(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        var chargeCompleteReads = 0

        override fun getBoolean(
            key: String?,
            defValue: Boolean,
        ): Boolean {
            if (key == EventNotificationPrefs.PREF_CHARGE_COMPLETE_ENABLED) {
                chargeCompleteReads += 1
            }
            return delegate.getBoolean(key, defValue)
        }
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
