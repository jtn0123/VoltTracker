package com.volttracker.obdpoc

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Looper
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.location.LocationTracker
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    private val controllers = mutableListOf<ServiceController<out ObdService>>()
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

    @Test
    fun connectWithLocationPermissionStartsForegroundWithLocationServiceType() {
        val controller = newController(intentFor(ObdService.ACTION_CONNECT, "AA:BB:CC:DD:EE:FF", "GPS ELM", null))
        val service = controller.create().get()
        shadowOf(service).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        controller.startCommand(0, 1)

        assertTrue("CONNECT marks the session running", service.running.get())
        assertTrue(
            "foreground service type should include LOCATION when GPS permission is available",
            activeForegroundServiceType(service) and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0,
        )
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

    @Test
    fun cancelRetryActionKeepsActiveSessionSticky() {
        val controller = newController(intentFor(ObdService.ACTION_CONNECT, "AA:BB:CC:DD:EE:FF", "Garage ELM", null))
        val service = controller.create().get()
        val captured = captureBroadcasts()
        controller.startCommand(0, 1)
        assertTrue("precondition: a session is running before CANCEL_RETRY", service.running.get())

        val result = service.onStartCommand(intentFor(ObdService.ACTION_CANCEL_RETRY, null, null, null), 0, 2)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("active cancel retry should keep the service sticky", Service.START_STICKY, result)
        assertTrue("CANCEL_RETRY flips the cancel-retry request flag", service.cancelRetryRequested)
        assertFalse("active cancel retry must not stop the service directly", shadowOf(service).isStoppedBySelf)
        val status = captured.lastStatus()
        assertNotNull("CANCEL_RETRY must broadcast a status", status)
        assertEquals("idle", status!!.optString("state"))
        assertEquals("Retry cancelled.", status.optString("detail"))
    }

    @Test
    fun appVisibilityActionsStopIdleServiceButKeepActiveSessionSticky() {
        val idleController = newController(null)
        val idleService = idleController.create().get()

        val idleBackground =
            idleService.onStartCommand(intentFor(ObdService.ACTION_APP_BACKGROUND, null, null, null), 0, 1)

        assertEquals(Service.START_NOT_STICKY, idleBackground)
        assertFalse("background action records the app as backgrounded", idleService.appInForeground)
        assertTrue("idle background action should stop the service", shadowOf(idleService).isStoppedBySelf)

        val activeController =
            newController(intentFor(ObdService.ACTION_CONNECT, "AA:BB:CC:DD:EE:FF", "Garage ELM", null))
        val activeService = activeController.create().get()
        activeController.startCommand(0, 1)

        val activeBackground =
            activeService.onStartCommand(intentFor(ObdService.ACTION_APP_BACKGROUND, null, null, null), 0, 2)
        val activeForeground =
            activeService.onStartCommand(intentFor(ObdService.ACTION_APP_FOREGROUND, null, null, null), 0, 3)

        assertEquals(Service.START_STICKY, activeBackground)
        assertEquals(Service.START_STICKY, activeForeground)
        assertTrue("foreground action records the app as foregrounded again", activeService.appInForeground)
        assertFalse("active visibility changes must not stop the service", shadowOf(activeService).isStoppedBySelf)
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
    fun onBindReturnsNullBecauseDashboardUsesBroadcasts() {
        val controller = newController(null)
        val service = controller.create().get()

        assertEquals(null, service.onBind(Intent()))
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
    fun broadcastStatusIncludesFailureVoltageCompetingAppsAndExtras() {
        val controller = newController(null)
        val service = controller.create().get()
        val captured = captureBroadcasts()
        service.activeName = "Helper Adapter"
        service.setLastFailureClass(FailureClass.INSTANT_DROP)
        service.setLastFailureClass(null)
        service.setLastVoltage(12.4)
        service.setCompetingApps("Torque Pro, Car Scanner")

        service.broadcastStatus(
            "connecting",
            "Retrying adapter.",
            false,
            JSONObject().put("retryAttempt", 2).put("origin", "test"),
        )
        shadowOf(Looper.getMainLooper()).idle()

        val status = captured.lastStatus()
        assertNotNull(status)
        assertEquals("connecting", status!!.optString("state"))
        assertEquals("Helper Adapter", status.optString("adapter"))
        assertEquals(FailureClass.INSTANT_DROP.wireName(), status.optString("failureClass"))
        assertEquals(12.4, status.optDouble("lastVoltage"), 0.01)
        assertEquals("Torque Pro, Car Scanner", status.optString("competingApps"))
        assertEquals(2, status.optInt("retryAttempt"))
        assertEquals("test", status.optString("origin"))

        service.clearLastFailureClass()
        service.setCompetingApps(null)
        service.broadcastStatus("idle", "Done.", false)
        shadowOf(Looper.getMainLooper()).idle()
        val cleared = captured.lastStatus()
        assertFalse(
            "clearLastFailureClass should remove the failure class from later statuses",
            cleared!!.has("failureClass"),
        )
        assertFalse("null competing-app state should be omitted from later statuses", cleared.has("competingApps"))
    }

    @Test
    fun sessionOutcomeAccumulatesIntoOneConsistentSnapshot() {
        // B5: the state/detail/failureClass/voltage/competingApps quintet is published as ONE
        // immutable SessionOutcome record, so closeSessionLog and the status payload read a
        // consistent snapshot instead of five separately-volatile fields.
        val controller = newController(null)
        val service = controller.create().get()
        // onCreate kicks an async competing-app refresh that always publishes a CSV ("" when none
        // found). Wait for it so the background write can't overwrite this test's value below.
        val refreshDeadline = System.currentTimeMillis() + 5_000L
        while (service.sessionOutcomeForTest().competingAppsCsv == null &&
            System.currentTimeMillis() < refreshDeadline
        ) {
            Thread.sleep(10L)
        }
        assertNotNull(
            "the onCreate competing-app refresh must have completed",
            service.sessionOutcomeForTest().competingAppsCsv,
        )

        service.setLastFailureClass(FailureClass.CONNECT_TIMEOUT)
        service.setLastVoltage(12.6)
        service.setCompetingApps("Torque Pro")
        service.broadcastStatus("error", "Adapter timed out.", true)
        shadowOf(Looper.getMainLooper()).idle()

        val outcome = service.sessionOutcomeForTest()
        assertEquals("error", outcome.state)
        assertEquals("Adapter timed out.", outcome.detail)
        assertEquals(FailureClass.CONNECT_TIMEOUT, outcome.failureClass)
        assertEquals(12.6, outcome.voltage!!, 0.001)
        assertEquals("Torque Pro", outcome.competingAppsCsv)

        service.clearLastFailureClass()
        val cleared = service.sessionOutcomeForTest()
        assertEquals("clearing the failure class must not disturb the sibling fields", "error", cleared.state)
        assertEquals("Adapter timed out.", cleared.detail)
        assertEquals(null, cleared.failureClass)
        assertEquals(12.6, cleared.voltage!!, 0.001)
        assertEquals("Torque Pro", cleared.competingAppsCsv)

        // A null setLastFailureClass call is a no-op by contract (the engine passes null when a
        // failure could not be classified) — it must not clear an earlier classification.
        service.setLastFailureClass(FailureClass.INSTANT_DROP)
        service.setLastFailureClass(null)
        assertEquals(FailureClass.INSTANT_DROP, service.sessionOutcomeForTest().failureClass)
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
        service.broadcastTelemetry(null as JSONObject?)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("an empty telemetry payload must not be broadcast", captured.telemetry.isEmpty())
    }

    // ---- B6: foreground refusal must not leave an orphaned started service ----------

    @Test
    fun foregroundRefusalStopsTheOrphanedServiceWithoutAWakeLock() {
        val controller =
            newController(
                ForegroundRefusedObdService::class.java,
                intentFor(ObdService.ACTION_CONNECT, "AA:BB:CC:DD:EE:FF", "Garage ELM", null),
            )
        val service = controller.create().get()
        val captured = captureBroadcasts()

        controller.startCommand(0, 1)

        assertFalse("a refused foreground start must not leave a session running", service.running.get())
        assertFalse("the foreground flag must stay down after the refusal", service.foregroundServiceActive)
        val status = captured.lastStatus()
        assertNotNull("the refusal must broadcast a blocked status", status)
        assertEquals("blocked", status!!.optString("state"))
        assertTrue(
            "a service launched via startForegroundService that never reached the foreground " +
                "must stop itself instead of lingering until a RemoteServiceException (B6)",
            shadowOf(service).isStoppedBySelf,
        )
        assertNull(
            "no session wake lock may be acquired when the foreground start is refused",
            ShadowPowerManager.getLatestWakeLock(),
        )
    }

    // ---- B4: onDestroy must not block the main thread on the persistence drain ------

    @Test
    fun destroyDoesNotBlockTheMainThreadOnThePersistenceDrain() {
        val controller = newController(null)
        val service = controller.create().get()
        val releaseWorker = CountDownLatch(1)
        val workerFinished = AtomicBoolean(false)
        // Occupy the recorder's persistence worker the way an in-flight session finalize would.
        service.recorder.runAsync {
            try {
                releaseWorker.await(20, TimeUnit.SECONDS)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            workerFinished.set(true)
        }

        val startedAtMs = System.currentTimeMillis()
        controller.destroy()
        val destroyMs = System.currentTimeMillis() - startedAtMs

        assertFalse(
            "onDestroy must return while the persistence worker is still draining (B4)",
            workerFinished.get(),
        )
        assertTrue(
            "onDestroy must not block the main thread on the drain (took ${destroyMs}ms; the " +
                "pre-B4 inline shutdown would have waited out the blocked worker)",
            destroyMs < 10_000L,
        )
        releaseWorker.countDown()
        assertTrue(
            "the persistence teardown must still run to completion off the main thread",
            service.awaitPersistenceTeardownForTest(10_000L),
        )
    }

    // ---- B3: a stale runner must not poison or stop the superseding session ---------

    @Test
    fun staleRunnerCannotPoisonOrStopAFreshlyStartedSession() {
        val controller =
            newController(
                StaleRunnerObdService::class.java,
                intentFor(ObdService.ACTION_CONNECT, "AA:BB:CC:DD:EE:FF", "First ELM", null),
            )
        val service = controller.create().get()
        val captured = captureBroadcasts()
        controller.startCommand(0, 1)
        assertTrue(
            "precondition: the first session's runner must have started",
            service.firstRunnerParked.await(5, TimeUnit.SECONDS),
        )

        // A second CONNECT supersedes the first session. startSession() interrupts the parked
        // first runner, which then fires the exact late calls a stale runner races the new
        // session with: an error broadcast, a failure classification, session teardown, stopSelf.
        service.onStartCommand(intentFor(ObdService.ACTION_CONNECT, "AA:BB:CC:DD:EE:FF", "Second ELM", null), 0, 2)
        assertTrue(
            "the stale runner must have fired its late calls",
            service.firstRunnerDone.await(5, TimeUnit.SECONDS),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("the new session must still be running", service.running.get())
        assertFalse(
            "a stale runner must never stop the service underneath the new session",
            shadowOf(service).isStoppedBySelf,
        )
        val outcome = service.sessionOutcomeForTest()
        assertFalse(
            "a stale runner's error state must not reach the new session's outcome",
            "error" == outcome.state,
        )
        assertEquals(
            "a stale runner's failure classification must be dropped",
            null,
            outcome.failureClass,
        )
        assertTrue(
            "the stale error broadcast must be suppressed",
            captured.status.none { it.optString("detail") == "stale runner poison" },
        )
        assertTrue(
            "a stale runner's telemetry must be neither published nor persisted into the new session",
            captured.telemetry.none { it.optString("source") == "stale-runner-poison" },
        )
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

    private fun newController(intent: Intent?): ServiceController<TestObdService> =
        newController(TestObdService::class.java, intent)

    private fun <T : ObdService> newController(
        serviceClass: Class<T>,
        intent: Intent?,
    ): ServiceController<T> {
        val controller =
            if (intent != null) {
                Robolectric.buildService(serviceClass, intent)
            } else {
                Robolectric.buildService(serviceClass)
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

    private fun activeForegroundServiceType(service: ObdService): Int {
        val field = ObdService::class.java.getDeclaredField("activeForegroundServiceType")
        field.isAccessible = true
        return field.getInt(service)
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
     * [TestObdService] whose foreground start is always refused, the way API 31+ blocks a
     * background-initiated FGS start with ForegroundServiceStartNotAllowedException (B6).
     */
    class ForegroundRefusedObdService : TestObdService() {
        override fun enterForeground(
            notification: Notification,
            serviceType: Int?,
        ): Unit = throw IllegalStateException("simulated ForegroundServiceStartNotAllowedException")
    }

    /**
     * [ObdService] whose engine's live-loop body is replaced with a controllable script (B3): the
     * FIRST runner parks until a superseding session interrupts it, then fires the exact late
     * calls a stale runner races the new session with; later runners return immediately so the
     * new session's flags stay untouched.
     */
    class StaleRunnerObdService : ObdService() {
        val firstRunnerParked = CountDownLatch(1)
        val firstRunnerDone = CountDownLatch(1)
        private val runnerInvocations = AtomicInteger()

        override fun createPollingEngine(): ObdPollingEngine =
            object : ObdPollingEngine(this@StaleRunnerObdService) {
                override fun runBluetoothLoop(
                    address: String?,
                    scanMode: Boolean,
                ) {
                    if (runnerInvocations.incrementAndGet() > 1) {
                        return // the superseding session's runner: inert
                    }
                    firstRunnerParked.countDown()
                    try {
                        Thread.sleep(30_000L)
                    } catch (ignored: InterruptedException) {
                        // startSession() cancelled this runner in favor of a newer session; fall
                        // through and fire the poison exactly like a stale runner would.
                    }
                    broadcastStatus("error", "stale runner poison", true)
                    broadcastTelemetry(JSONObject().put("source", "stale-runner-poison").put("speedKph", 999))
                    setLastFailureClass(FailureClass.INSTANT_DROP)
                    markSessionInactive()
                    stopSelfFromRunner()
                    firstRunnerDone.countDown()
                }
            }
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

        override fun isSessionRunnerActive(): Boolean = false

        override fun stopSelfFromRunner() = Unit

        override fun hasBluetoothConnectPermission(): Boolean = false

        override fun hasBluetoothScanPermission(): Boolean = false

        override fun setLastFailureClass(fc: FailureClass?) = Unit

        override fun clearLastFailureClass() = Unit

        override fun maybeRunVoltageProbe(engineRef: ObdPollingEngine?) = Unit

        override fun maybeRunAutoDtcScan(engineRef: ObdPollingEngine?) = Unit
    }
}
