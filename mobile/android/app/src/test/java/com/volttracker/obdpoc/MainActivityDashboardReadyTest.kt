package com.volttracker.obdpoc

import android.os.Looper
import android.webkit.WebView
import com.volttracker.obdpoc.data.ObdLocalStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Integration coverage for the [MainActivity] dashboard handshake and the OBD broadcast -> dashboard
 * publish path. [MainActivityTest] covers only the static helpers in `MainActivityUtils`; this drives
 * the live Activity through the same publish seam ([MainActivity.publishDeviceList] /
 * [MainActivity.publishStatus] / [MainActivity.publishStorageSummary]) that
 * `PermissionGateTest`/`MainActivityPermissionTest` use, capturing what the handshake actually
 * triggers instead of relying on the emulator smoke.
 *
 * The JS->native handshake ([MainActivity.onDashboardReady]) is invoked directly (in production the
 * [VoltBridge] calls it); the obdReceiver path is exercised by sending the real
 * `BROADCAST_TELEMETRY` / `BROADCAST_STATUS` intents the [ObdService] would emit and asserting they
 * route through the publish chain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityDashboardReadyTest {
    private lateinit var controller: ActivityController<RecordingActivity>
    private lateinit var activity: RecordingActivity

    @Before
    fun forceNativeSqliteLoad() {
        // Bind Robolectric's native SQLite runtime through the plain main-thread path before the
        // Activity lifecycle first opens the DB. Doing the first open here keeps this class from
        // leaving the sandbox in a state where a later SQLite test (e.g. ObdServiceIntegrationTest)
        // hits UnsatisfiedLinkError: SQLiteConnectionNatives.nativeOpen — the same effect a clean
        // store-backed test running earlier in the suite already provides.
        ObdLocalStore(RuntimeEnvironment.getApplication()).apply {
            getRecentSessions(1)
            close()
        }
    }

    /** create()-only: onCreate has run (dashboardPublisher is live) but onResume has not. */
    private fun launch() {
        controller = Robolectric.buildActivity(RecordingActivity::class.java).create()
        activity = controller.get()
    }

    /** Full create -> start -> resume: onResume registers the obdReceiver for OBD broadcasts. */
    private fun launchResumed() {
        controller = Robolectric.buildActivity(RecordingActivity::class.java).setup()
        activity = controller.get()
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) {
            controller.destroy()
        }
    }

    // ---- onDashboardReady ----------------------------------------------------------

    @Test
    fun dashboardReadyPublishesDeviceListStorageAndReadyStatus() {
        launchResumed()
        // Fresh launch: the dashboard page is not yet marked ready.
        assertFalse("precondition: page should not be ready before the handshake", activity.isDashboardReadyForTest())
        val webView = activity.findViewById<WebView>(R.id.dashboard_webview)
        assertEquals(MainActivity.DASHBOARD_LOADING_DESCRIPTION, webView.contentDescription)

        activity.onDashboardReady()
        settleMainLooper()

        assertTrue("onDashboardReady must mark the dashboard page ready", activity.isDashboardReadyForTest())
        assertEquals(MainActivity.DASHBOARD_READY_DESCRIPTION, webView.contentDescription)
        assertTrue("onDashboardReady must publish the device list", activity.deviceListPublishCount >= 1)
        assertTrue("onDashboardReady must publish the storage summary", activity.storageSummaryPublishCount >= 1)
        assertEquals("onDashboardReady must publish a 'ready' status", "ready", activity.lastStatusState)
        assertFalse("the ready status is not blocked", activity.lastStatusBlocked)
    }

    @Test
    fun dashboardReadyIsIdempotentOncePageIsAlreadyReady() {
        launchResumed()
        activity.onDashboardReady()
        settleMainLooper()
        val devicesAfterFirst = activity.deviceListPublishCount
        val statusAfterFirst = activity.statusPublishCount

        // A second handshake (e.g. a WebView reload racing the first) must short-circuit on the
        // page-ready guard and not re-publish.
        activity.onDashboardReady()
        settleMainLooper()

        assertEquals(
            "a repeat handshake must not re-publish the device list",
            devicesAfterFirst,
            activity.deviceListPublishCount,
        )
        assertEquals(
            "a repeat handshake must not re-publish the status",
            statusAfterFirst,
            activity.statusPublishCount,
        )
    }

    @Test
    fun dashboardReadyPostWorkWaitsUntilActivityResumes() {
        launch()

        activity.onDashboardReady()
        settleMainLooper()

        assertTrue("dashboardReady still marks the page ready", activity.isDashboardReadyForTest())
        assertEquals("paused post-ready work must not publish devices", 0, activity.deviceListPublishCount)
        assertEquals("paused post-ready work must not publish storage", 0, activity.storageSummaryPublishCount)

        controller.start().resume()
        settleMainLooper()

        assertTrue("resume must drain the deferred device publish", activity.deviceListPublishCount >= 1)
        assertTrue("resume must drain the deferred storage publish", activity.storageSummaryPublishCount >= 1)
    }

    @Test
    fun initialResumeDefersDashboardSnapshotUntilJsReady() {
        launchResumed()

        assertFalse("precondition: setup should not mark the dashboard ready", activity.isDashboardReadyForTest())
        assertEquals("initial onResume should not publish a pre-ready device list", 0, activity.deviceListPublishCount)
        assertEquals(
            "initial onResume should not read storage before the dashboard can receive it",
            0,
            activity.storageSummaryPublishCount,
        )

        activity.onDashboardReady()
        settleMainLooper()

        assertTrue("dashboardReady marks the page ready", activity.isDashboardReadyForTest())
        assertTrue("ready handshake publishes the device list", activity.deviceListPublishCount >= 1)
        assertTrue("ready handshake publishes storage", activity.storageSummaryPublishCount >= 1)
    }

    // ---- obdReceiver: BROADCAST_STATUS / BROADCAST_TELEMETRY -> dashboard -----------

    @Test
    fun statusBroadcastRoutesThroughTheReceiverToTheDashboard() {
        launchResumed() // onResume registers obdReceiver for the OBD broadcasts.
        activity.onDashboardReady()
        settleMainLooper()
        val baseStorage = activity.storageSummaryPublishCount

        val status = JSONObject()
        status.put("state", "idle")
        status.put("detail", "Disconnected.")
        status.put("blocked", false)
        sendObdBroadcast(ObdService.BROADCAST_STATUS, status)

        // An "idle" status publishes the storage summary (un-throttled), and routes the ready-notify
        // hook. The receiver swallowing the broadcast (rather than crashing) is the contract.
        assertTrue(
            "an idle status broadcast must refresh the storage summary",
            activity.storageSummaryPublishCount > baseStorage,
        )
        assertTrue("the ready-notify hook must observe the status", activity.readyNotifyStatusCount >= 1)
    }

    @Test
    fun preReadyStatusBroadcastDoesNotReadStorageThatCannotBePublished() {
        launchResumed()

        val status = JSONObject()
        status.put("state", "idle")
        status.put("detail", "Disconnected.")
        status.put("blocked", false)
        sendObdBroadcast(ObdService.BROADCAST_STATUS, status)

        assertEquals(
            "pre-ready status broadcast should not force a storage read",
            0,
            activity.storageSummaryPublishCount,
        )
        assertTrue("ready-notify hook still observes the status", activity.readyNotifyStatusCount >= 1)

        activity.onDashboardReady()
        settleMainLooper()

        assertTrue(
            "ready handshake publishes storage once the page can receive it",
            activity.storageSummaryPublishCount >= 1,
        )
    }

    @Test
    fun telemetryBroadcastRoutesThroughTheReceiverAndMarksStorageDirty() {
        launchResumed()
        val baseDirty = activity.markStorageDirtyCount

        val telemetry = JSONObject()
        telemetry.put("source", "obd")
        telemetry.put("connected", true)
        telemetry.put("speedKph", 42)
        sendObdBroadcast(ObdService.BROADCAST_TELEMETRY, telemetry)

        // The telemetry branch of obdReceiver marks the storage summary dirty before pushing the
        // sample to the dashboard; observing that increment proves the broadcast reached the live
        // receiver and routed through the telemetry path (not the status path).
        assertTrue(
            "a telemetry broadcast must mark the storage summary dirty",
            activity.markStorageDirtyCount > baseDirty,
        )
    }

    @Test
    fun resumePublishesTheNewestServiceTelemetryInsteadOfThePreBackgroundSample() {
        val serviceController = Robolectric.buildService(ObdService::class.java).create()
        val service = serviceController.get()
        try {
            launchResumed()
            activity.onDashboardReady()
            settleMainLooper()
            val now = System.currentTimeMillis()

            service.broadcastStatus("connected", "Polling live OBD data.", false)
            service.broadcastTelemetry(
                JSONObject()
                    .put("source", "obd")
                    .put("connected", true)
                    .put("updatedAt", now - 300_000L)
                    .put("speedKph", 112),
            )
            settleMainLooper()
            assertEquals("precondition: the foreground dashboard saw the highway sample", 112, latestSpeedKph())

            controller.pause()
            service.broadcastTelemetry(
                JSONObject()
                    .put("source", "obd")
                    .put("connected", true)
                    .put("updatedAt", now)
                    .put("speedKph", 32),
            )
            service.broadcastStatus("scanning", "Refreshing live vehicle state.", false)
            settleMainLooper()
            assertEquals("the paused Activity snapshot remains the last visible sample", 112, latestSpeedKph())
            assertEquals("the paused Activity status remains the last visible state", "connected", latestSessionState())

            controller.resume()
            settleMainLooper()

            assertEquals(
                "resume must hydrate the latest sample collected by the foreground service",
                32,
                latestSpeedKph(),
            )
            assertEquals(
                "resume must hydrate status transitions that happened in the background too",
                "scanning",
                latestSessionState(),
            )
            val resumedScript = shadowOf(checkNotNull(activity.webViewForTest())).getLastEvaluatedJavascript()
            assertTrue(
                "a fresh resumed sample must advance every live dashboard surface through updateTelemetry; " +
                    "last script=$resumedScript",
                resumedScript?.contains("window.VoltTrackerNative.updateTelemetry(") == true &&
                    resumedScript.contains("\\\"speedKph\\\":32"),
            )
        } finally {
            serviceController.destroy()
        }
    }

    private fun latestSpeedKph(): Int =
        JSONObject(activity.getAppStateJson())
            .getJSONObject("latestTelemetry")
            .getInt("speedKph")

    private fun latestSessionState(): String =
        JSONObject(activity.getAppStateJson())
            .getJSONObject("session")
            .getString("state")

    private fun sendObdBroadcast(
        action: String,
        payload: JSONObject,
    ) {
        val intent = android.content.Intent(action)
        intent.setPackage(activity.packageName)
        intent.putExtra(ObdService.EXTRA_JSON, payload.toString())
        activity.sendBroadcast(intent)
        // Robolectric's main looper is paused: sendBroadcast posts delivery to the registered
        // obdReceiver onto the queue, so drain it before asserting the receiver's effect.
        settleMainLooper()
    }

    private fun settleMainLooper() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
    }

    /**
     * Captures the dashboard-publish calls the handshake and broadcast paths trigger. Mirrors the
     * harness style in `PermissionGateTest`, but keeps the real `onCreate` (so `dashboardPublisher`
     * is live and [MainActivity.onDashboardReady] does not short-circuit on a null publisher).
     */
    class RecordingActivity : MainActivity() {
        var deviceListPublishCount = 0
        var storageSummaryPublishCount = 0
        var statusPublishCount = 0
        var lastStatusState: String? = null
        var lastStatusBlocked = false
        var readyNotifyStatusCount = 0
        var markStorageDirtyCount = 0

        override fun publishDeviceList() {
            deviceListPublishCount += 1
        }

        override fun markStorageSummaryDirty() {
            markStorageDirtyCount += 1
        }

        override fun publishStorageSummary() {
            storageSummaryPublishCount += 1
        }

        override fun publishStorageSummaryThrottled() {
            storageSummaryPublishCount += 1
        }

        override fun publishStatus(
            state: String?,
            detail: String?,
            blocked: Boolean,
        ) {
            statusPublishCount += 1
            lastStatusState = state
            lastStatusBlocked = blocked
        }

        override fun onAdapterStatusForReadyNotify(status: JSONObject?) {
            readyNotifyStatusCount += 1
            // Do not delegate to the real TroubleshooterBridge; the count is the signal under test.
        }
    }
}
