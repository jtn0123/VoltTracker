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
        launch()
        // Fresh launch: the dashboard page is not yet marked ready.
        assertFalse("precondition: page should not be ready before the handshake", activity.isDashboardReadyForTest())
        val webView = activity.findViewById<WebView>(R.id.dashboard_webview)
        assertEquals(MainActivity.DASHBOARD_LOADING_DESCRIPTION, webView.contentDescription)

        activity.onDashboardReady()

        assertTrue("onDashboardReady must mark the dashboard page ready", activity.isDashboardReadyForTest())
        assertEquals(MainActivity.DASHBOARD_READY_DESCRIPTION, webView.contentDescription)
        assertTrue("onDashboardReady must publish the device list", activity.deviceListPublishCount >= 1)
        assertTrue("onDashboardReady must publish the storage summary", activity.storageSummaryPublishCount >= 1)
        assertEquals("onDashboardReady must publish a 'ready' status", "ready", activity.lastStatusState)
        assertFalse("the ready status is not blocked", activity.lastStatusBlocked)
    }

    @Test
    fun dashboardReadyIsIdempotentOncePageIsAlreadyReady() {
        launch()
        activity.onDashboardReady()
        val devicesAfterFirst = activity.deviceListPublishCount
        val statusAfterFirst = activity.statusPublishCount

        // A second handshake (e.g. a WebView reload racing the first) must short-circuit on the
        // page-ready guard and not re-publish.
        activity.onDashboardReady()

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

    // ---- obdReceiver: BROADCAST_STATUS / BROADCAST_TELEMETRY -> dashboard -----------

    @Test
    fun statusBroadcastRoutesThroughTheReceiverToTheDashboard() {
        launchResumed() // onResume registers obdReceiver for the OBD broadcasts.
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
        shadowOf(Looper.getMainLooper()).idle()
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
