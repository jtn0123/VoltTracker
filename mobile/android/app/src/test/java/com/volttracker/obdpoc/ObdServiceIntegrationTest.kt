package com.volttracker.obdpoc

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.location.LocationTracker
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Integration coverage for the [ObdService] action-dispatch orchestration that the pure-logic
 * [ObdServiceTest] explicitly leaves out ("The connection/polling/threading code is integration-level
 * and not covered here"). End-to-end this wiring was only exercised by the emulator smoke.
 *
 * The real challenge is that a live `ACTION_CONNECT`/`ACTION_DEMO`/etc. submits an
 * [ObdPollingEngine] IO loop to a background executor, and that loop opens a Bluetooth RFCOMM socket
 * that cannot run under Robolectric. The minimal production seam this test relies on is
 * [ObdService.createPollingEngine] (an `open fun` factory, behavior-identical to the inline
 * `ObdPollingEngine(this)` it replaced). [TestObdService] overrides it to build an engine bound to a
 * [NeutralizedHost] — an [EngineHost] that reports `running == false` and no-ops every side-effecting
 * callback — so whichever loop the runner invokes terminates immediately and observably does nothing
 * (no socket, no broadcasts, no recorder writes from the worker thread). That makes the *service's
 * own* synchronous orchestration — recorder session mode, foreground notification, active-adapter
 * name, session/foreground flags — the stable, race-free signal of how each `ACTION_*` routed.
 *
 * The [EngineHost] broadcast helpers ([ObdService.broadcastStatus] / [ObdService.broadcastTelemetry])
 * are asserted by invoking them directly on the test thread and capturing the resulting
 * `BROADCAST_STATUS` / `BROADCAST_TELEMETRY` intents via a registered receiver, so the
 * payload/extras contract is checked without any background-thread timing.
 *
 * Not feasibly covered here: the post-connect polling/reconnect loop body and the live status
 * broadcasts it emits. Those run on the worker thread and require a real adapter socket; they are
 * driven instead by [ObdPollingEngineTest] (which scripts a fake [ElmConnection]). Driving them
 * through the service would reintroduce the exact Bluetooth/threading dependency this seam exists to
 * avoid, so this file asserts the dispatch -> orchestration boundary and the broadcast helpers only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObdServiceIntegrationTest {
    private val controllers = mutableListOf<ServiceController<TestObdService>>()
    private val receivers = mutableListOf<BroadcastReceiver>()

    @After
    fun tearDown() {
        for (receiver in receivers) {
            try {
                RuntimeEnvironment.getApplication().unregisterReceiver(receiver)
            } catch (ignored: IllegalArgumentException) {
                // Already unregistered.
            }
        }
        for (controller in controllers) {
            try {
                controller.get().running.set(false)
                controller.destroy()
            } catch (ignored: RuntimeException) {
                // destroy() stops a foreground service that may never have started; safe.
            }
        }
    }

    // ---- per-action dispatch -> synchronous orchestration --------------------------

    @Test
    fun connectActionRoutesToAnObdSessionUnderTheAdapterName() {
        val service = dispatch(ObdService.ACTION_CONNECT, address = "AA:BB:CC:DD:EE:FF", name = "Garage ELM")

        assertEquals("CONNECT opens an 'obd'-mode session", ObdLocalStore.MODE_OBD, service.recorder.activeMode())
        assertEquals("CONNECT adopts the adapter name from the intent", "Garage ELM", service.activeName)
        assertTrue("CONNECT marks the session running", service.running.get())
        assertTrue("CONNECT brings up the foreground service", service.foregroundServiceActive)
        assertTrue("CONNECT stamps the session start time", service.sessionStartedAtMs > 0)
        assertTrue("a session is globally active after CONNECT", ObdService.hasActiveSession())
    }

    @Test
    fun scanActionRoutesToAScanSession() {
        val service = dispatch(ObdService.ACTION_SCAN, address = "AA:BB:CC:DD:EE:FF", name = "Scanner")

        assertEquals("SCAN opens a 'scan'-mode session", ObdLocalStore.MODE_SCAN, service.recorder.activeMode())
        assertEquals("Scanner", service.activeName)
        assertTrue("SCAN marks the session running", service.running.get())
    }

    @Test
    fun demoActionRoutesToADemoSessionWithoutRequiringAnAddress() {
        val service = dispatch(ObdService.ACTION_DEMO, address = null, name = null)

        assertEquals("DEMO opens a 'demo'-mode session", ObdLocalStore.MODE_DEMO, service.recorder.activeMode())
        assertEquals("DEMO labels the adapter as the synthetic stream", "Demo stream", service.activeName)
        assertTrue("DEMO marks the session running", service.running.get())
        assertTrue("DEMO brings up the foreground service", service.foregroundServiceActive)
    }

    @Test
    fun clearDtcActionRoutesToAClearDtcSession() {
        val service = dispatch(ObdService.ACTION_CLEAR_DTC, address = "AA:BB:CC:DD:EE:FF", name = "DTC tool")

        assertEquals("CLEAR_DTC opens a 'clear-dtc'-mode session", "clear-dtc", service.recorder.activeMode())
        assertEquals("DTC tool", service.activeName)
        assertTrue("CLEAR_DTC marks the session running", service.running.get())
    }

    @Test
    fun tpmsScanActionRoutesToATpmsScanSession() {
        val service =
            dispatch(
                ObdService.ACTION_TPMS_SCAN,
                address = "AA:BB:CC:DD:EE:FF",
                name = "TPMS probe",
                detailStage = "tires",
            )

        assertEquals("TPMS_SCAN opens a 'tpms-scan'-mode session", "tpms-scan", service.recorder.activeMode())
        assertEquals("TPMS probe", service.activeName)
        assertTrue("TPMS_SCAN marks the session running", service.running.get())
    }

    @Test
    fun connectWithoutNameFallsBackToTheDefaultAdapterLabel() {
        val service = dispatch(ObdService.ACTION_CONNECT, address = "AA:BB:CC:DD:EE:FF", name = null)

        assertEquals("a nameless CONNECT uses the default adapter label", "OBD adapter", service.activeName)
    }

    // ---- DISCONNECT / teardown -----------------------------------------------------

    @Test
    fun disconnectActionTearsDownTheSessionAndBroadcastsIdle() {
        // First bring a session up, then DISCONNECT the same service instance.
        val controller = newController(intentFor(ObdService.ACTION_CONNECT, "AA:BB:CC:DD:EE:FF", "Garage ELM", null))
        val service = controller.get()
        val captured = captureBroadcasts()
        controller.create().startCommand(0, 1)
        assertTrue("precondition: a session is running before DISCONNECT", service.running.get())

        controller.withIntent(intentFor(ObdService.ACTION_DISCONNECT, null, null, null)).startCommand(0, 2)

        assertFalse("DISCONNECT stops the running session", service.running.get())
        assertFalse("DISCONNECT clears the foreground-service flag", service.foregroundServiceActive)
        assertFalse("DISCONNECT clears the global active-session flag", ObdService.hasActiveSession())
        val idle = captured.lastStatus()
        assertNotNull("DISCONNECT must broadcast a status", idle)
        assertEquals("DISCONNECT broadcasts the idle state", "idle", idle!!.optString("state"))
        assertEquals("Disconnected.", idle.optString("detail"))
        assertFalse("the idle teardown status is not blocked", idle.optBoolean("blocked"))
    }

    @Test
    fun cancelRetryActionRequestsCancellationAndBroadcastsIdle() {
        val controller = newController(intentFor(ObdService.ACTION_CANCEL_RETRY, null, null, null))
        val service = controller.get()
        val captured = captureBroadcasts()

        controller.create().startCommand(0, 1)

        assertTrue("CANCEL_RETRY flips the cancel-retry request flag", service.cancelRetryRequested)
        val status = captured.lastStatus()
        assertNotNull("CANCEL_RETRY must broadcast a status", status)
        assertEquals("idle", status!!.optString("state"))
        assertEquals("Retry cancelled.", status.optString("detail"))
    }

    // ---- null / unrecognized start commands ------------------------------------------

    @Test
    fun nullIntentRestartStopsTheIdleService() {
        // A START_STICKY restart after process death redelivers a null intent. With no session
        // to resume, the service must stop itself instead of lingering as an invisible orphan
        // (onCreate already opened the store, registered receivers, and started executors).
        val controller = newController(null)
        val service = controller.create().get()

        val result = service.onStartCommand(null, 0, 1)

        assertEquals("a null-intent restart must not re-stick", Service.START_NOT_STICKY, result)
        assertTrue("an idle service must stop itself on a null-intent restart", shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun unrecognizedActionStopsTheIdleService() {
        val controller = newController(null)
        val service = controller.create().get()
        val bogus = Intent(RuntimeEnvironment.getApplication(), TestObdService::class.java)
        bogus.action = "com.volttracker.obdpoc.action.BOGUS"

        val result = service.onStartCommand(bogus, 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue("an idle service must stop itself on an unknown action", shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun nullIntentRestartDoesNotStopAnActiveSession() {
        val controller = newController(intentFor(ObdService.ACTION_CONNECT, "AA:BB:CC:DD:EE:FF", "Garage ELM", null))
        val service = controller.create().get()
        controller.startCommand(0, 1)
        assertTrue("precondition: a session is running", service.running.get())

        service.onStartCommand(null, 0, 2)

        assertTrue("a live session must survive a null-intent dispatch", service.running.get())
        assertFalse("the service must not stop itself while a session is active", shadowOf(service).isStoppedBySelf)
    }

    // ---- EngineHost broadcast helpers ----------------------------------------------

    @Test
    fun broadcastStatusEmitsStatusIntentWithStatusPayloadExtras() {
        val controller = newController(null)
        val service = controller.create().get()
        service.activeName = "Helper Adapter"
        val captured = captureBroadcasts()

        service.broadcastStatus("connected", "Polling live OBD data.", false)
        shadowOf(Looper.getMainLooper()).idle()

        val status = captured.lastStatus()
        assertNotNull("broadcastStatus must emit a BROADCAST_STATUS intent", status)
        assertEquals("connected", status!!.optString("state"))
        assertEquals("Polling live OBD data.", status.optString("detail"))
        assertFalse(status.optBoolean("blocked"))
        assertEquals("the status carries the active adapter name", "Helper Adapter", status.optString("adapter"))
        assertTrue("the status payload stamps an update time", status.optLong("updatedAt") > 0)
        assertTrue("no telemetry intent should be emitted by broadcastStatus", captured.telemetry.isEmpty())
    }

    @Test
    fun broadcastStatusForwardsBlockedFlag() {
        val controller = newController(null)
        val service = controller.create().get()
        val captured = captureBroadcasts()

        service.broadcastStatus("blocked", "Android blocked foreground logging.", true)
        shadowOf(Looper.getMainLooper()).idle()

        val status = captured.lastStatus()
        assertNotNull(status)
        assertEquals("blocked", status!!.optString("state"))
        assertTrue("the blocked flag must round-trip through the payload", status.optBoolean("blocked"))
    }

    @Test
    fun broadcastTelemetryEmitsTelemetryIntentWithTypedFields() {
        val controller = newController(null)
        val service = controller.create().get()
        val captured = captureBroadcasts()

        val payload = JSONObject()
        payload.put("source", "obd")
        payload.put("connected", true)
        payload.put("speedKph", 54)
        payload.put("rpm", 1280)
        service.broadcastTelemetry(payload)
        shadowOf(Looper.getMainLooper()).idle()

        val telemetry = captured.lastTelemetry()
        assertNotNull("broadcastTelemetry must emit a BROADCAST_TELEMETRY intent", telemetry)
        assertEquals("obd", telemetry!!.optString("source"))
        assertTrue(telemetry.optBoolean("connected"))
        assertEquals(54, telemetry.optInt("speedKph"))
        assertEquals(1280, telemetry.optInt("rpm"))
        assertTrue("no status intent should be emitted by broadcastTelemetry", captured.status.isEmpty())
    }

    @Test
    fun broadcastTelemetryCoalescesWidgetSideEffectsWithoutDroppingTelemetryBroadcasts() {
        val controller = newController(null)
        val service = controller.create().get()
        val captured = captureBroadcasts()

        service.broadcastTelemetry(JSONObject().put("source", "obd").put("soc", 50).put("connected", true))
        service.broadcastTelemetry(JSONObject().put("source", "obd").put("soc", 51).put("connected", true))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("live telemetry broadcasts stay per-sample", 2, captured.telemetry.size)
        assertTrue(
            "second sample should coalesce into the pending widget update",
            service.drainCoalescedWidgetTelemetryCountForTest() >= 1L,
        )
    }

    @Test
    fun broadcastTelemetryDropsEmptyPayloads() {
        val controller = newController(null)
        val service = controller.create().get()
        val captured = captureBroadcasts()

        service.broadcastTelemetry(JSONObject())
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("an empty telemetry payload must not be broadcast", captured.telemetry.isEmpty())
    }

    // ---- harness -------------------------------------------------------------------

    /** Builds the service, runs onCreate + onStartCommand for [action], returns the live service. */
    private fun dispatch(
        action: String,
        address: String?,
        name: String?,
        detailStage: String? = null,
    ): TestObdService {
        val controller = newController(intentFor(action, address, name, detailStage))
        controller.create().startCommand(0, 1)
        return controller.get()
    }

    private fun newController(intent: Intent?): ServiceController<TestObdService> {
        val controller =
            if (intent != null) {
                Robolectric.buildService(TestObdService::class.java, intent)
            } else {
                Robolectric.buildService(TestObdService::class.java)
            }
        controllers += controller
        return controller
    }

    private fun intentFor(
        action: String,
        address: String?,
        name: String?,
        detailStage: String?,
    ): Intent {
        val intent = Intent(RuntimeEnvironment.getApplication(), TestObdService::class.java)
        intent.action = action
        if (address != null) intent.putExtra(ObdService.EXTRA_ADDRESS, address)
        if (name != null) intent.putExtra(ObdService.EXTRA_NAME, name)
        if (detailStage != null) intent.putExtra(ObdService.EXTRA_DETAIL_STAGE, detailStage)
        return intent
    }

    private fun captureBroadcasts(): CapturedBroadcasts {
        val captured = CapturedBroadcasts()
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    val json = intent.getStringExtra(ObdService.EXTRA_JSON) ?: return
                    val parsed = JSONObject(json)
                    when (intent.action) {
                        ObdService.BROADCAST_STATUS -> captured.status += parsed
                        ObdService.BROADCAST_TELEMETRY -> captured.telemetry += parsed
                    }
                }
            }
        val filter = IntentFilter()
        filter.addAction(ObdService.BROADCAST_STATUS)
        filter.addAction(ObdService.BROADCAST_TELEMETRY)
        RuntimeEnvironment.getApplication().registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        receivers += receiver
        return captured
    }

    private class CapturedBroadcasts {
        val status: MutableList<JSONObject> = Collections.synchronizedList(ArrayList())
        val telemetry: MutableList<JSONObject> = Collections.synchronizedList(ArrayList())

        fun lastStatus(): JSONObject? = status.lastOrNull()

        fun lastTelemetry(): JSONObject? = telemetry.lastOrNull()
    }

    /**
     * [ObdService] subclass whose polling engine is bound to a [NeutralizedHost] so the worker-thread
     * runner submitted by `onStartCommand` is inert: it sees `running == false`, so every loop body
     * exits immediately, and its callbacks are no-ops, so it produces no broadcasts, recorder writes,
     * or notification changes that could race the synchronous orchestration under test.
     */
    open class TestObdService : ObdService() {
        override fun createPollingEngine(): ObdPollingEngine = ObdPollingEngine(NeutralizedHost(this))
    }

    /**
     * An [EngineHost] that delegates read-only adapter/context state to the real service but reports
     * the session as not running and swallows every mutating callback the engine might make.
     */
    private class NeutralizedHost(
        private val service: ObdService,
    ) : EngineHost {
        override val recorder: SessionRecorder get() = service.recorder
        override val running = AtomicBoolean(false)
        override val ioLock = Any()
        override val localStore: ObdLocalStore? get() = service.localStore
        override val locationTracker: LocationTracker? get() = null
        override val bluetoothObservability: BluetoothStateReporter? get() = null
        override var activeName: String
            get() = service.activeName
            set(_) {}
        override var sessionStartedAtMs: Long
            get() = service.sessionStartedAtMs
            set(_) {}
        override var appInForeground: Boolean = true
        override var foregroundServiceActive: Boolean = false
        override var cancelRetryRequested: Boolean = false
        override val androidContext: Context get() = service

        override fun broadcastStatus(
            state: String?,
            detail: String?,
            blocked: Boolean,
        ) = Unit

        override fun broadcastTelemetry(payload: JSONObject?) = Unit

        override fun updateNotification(text: String?) = Unit

        override fun closeSessionLog() = Unit

        override fun markSessionInactive() = Unit

        override fun stopSelf() = Unit

        override fun hasBluetoothConnectPermission(): Boolean = false

        override fun hasBluetoothScanPermission(): Boolean = false

        override fun setLastFailureClass(fc: FailureClass?) = Unit

        override fun clearLastFailureClass() = Unit

        override fun maybeRunVoltageProbe(engineRef: ObdPollingEngine?) = Unit

        override fun maybeRunAutoDtcScan(engineRef: ObdPollingEngine?) = Unit
    }
}
