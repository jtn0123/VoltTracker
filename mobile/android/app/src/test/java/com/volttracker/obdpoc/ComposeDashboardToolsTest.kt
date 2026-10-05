package com.volttracker.obdpoc

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.core.content.edit
import com.volttracker.obdpoc.service.ObdService
import com.volttracker.obdpoc.ui.insights.InsightsPeriod
import com.volttracker.obdpoc.ui.insights.SpeedEfficiency
import com.volttracker.obdpoc.ui.settings.SettingsCommand
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.atomic.AtomicInteger

/** The native Settings tools run through the shared helpers, with the restore guards in front. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeDashboardToolsTest {
    private lateinit var controller: ActivityController<ComposeDashboardActivity>
    private lateinit var activity: ComposeDashboardActivity

    @Before
    fun setUp() {
        while (ClassicDashboardPresence.isAlive()) ClassicDashboardPresence.onDestroyed()
        controller = Robolectric.buildActivity(ComposeDashboardActivity::class.java).create()
        activity = controller.get()
        activity.loggingProbe = { false }
    }

    @After
    fun tearDown() {
        while (ClassicDashboardPresence.isAlive()) ClassicDashboardPresence.onDestroyed()
        controller.destroy()
    }

    @Test
    fun editingADriveOpensItsClassicReceipt() {
        activity.openClassicTrip("route-42")
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started.component?.className)
        assertEquals("route-42", started.getStringExtra(MainActivity.EXTRA_OPEN_TRIP))
        assertEquals(true, started.getBooleanExtra(MainActivity.EXTRA_OPEN_TRIP_RECEIPT, false))
    }

    @Test
    fun theMaintenanceLinkOpensTheClassicInsightsTab() {
        activity.openClassicView("insights")
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started.component?.className)
        assertEquals("insights", started.getStringExtra(MainActivity.EXTRA_OPEN_VIEW))
    }

    @Test
    fun theTireTestNeedsARememberedAdapter() {
        activity.startTireTest()
        assertEquals("Pick your adapter in Settings first.", ShadowToast.getTextOfLatestToast())
        assertNull(shadowOf(activity).nextStartedService)
    }

    @Test
    fun theBodyTestDispatchesTheOneMinuteListenAction() {
        activity.startBodyTest()
        assertEquals(ObdService.ACTION_BODY_TEST, shadowOf(activity).nextStartedService.action)
        assertTrue(ShadowToast.getTextOfLatestToast().toString().contains("Listening for 1 minute"))
    }

    @Test
    fun healthTroubleshootingOpensTheClassicTroubleshooter() {
        activity.openTroubleshooter()
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra(MainActivity.EXTRA_OPEN_TROUBLESHOOTER, false))
    }

    private fun readyBluetooth() {
        org.robolectric.Shadows
            .shadowOf(org.robolectric.RuntimeEnvironment.getApplication())
            .grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        @Suppress("DEPRECATION")
        val radio = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        shadowOf(radio).setEnabled(true)
    }

    @Test
    fun pickingAnAdapterRemembersItAndStartsItsConnection() {
        readyBluetooth()
        activity.runCommand(SettingsCommand.PickAdapter("AA:BB:CC:DD:EE:FF", "OBDLink MX+"))
        val prefs = activity.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        assertEquals("AA:BB:CC:DD:EE:FF", prefs.getString(DeviceCatalog.PREF_LAST_ADDRESS, ""))
        assertEquals("OBDLink MX+", prefs.getString(DeviceCatalog.PREF_LAST_NAME, ""))
        val intent = shadowOf(activity).nextStartedService
        assertEquals(ObdService.ACTION_CONNECT, intent.action)
        assertEquals("AA:BB:CC:DD:EE:FF", intent.getStringExtra(ObdService.EXTRA_ADDRESS))
    }

    @Test
    fun resumeAutoConnectHonorsLoggingPermissionAndCooldownGuards() {
        val prefs = activity.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        DeviceCatalog(activity, prefs).remember("AA:BB:CC:DD:EE:FF", "OBDLink MX+")
        prefs.edit { putBoolean(AutoConnectController.PREF_AUTO_CONNECT_ENABLED, true) }
        activity.loggingProbe = { true }
        readyBluetooth()
        controller.start().resume()
        assertNull(shadowOf(activity).nextStartedService)
        controller.pause()
        activity.loggingProbe = { false }
        shadowOf(
            org.robolectric.RuntimeEnvironment.getApplication(),
        ).denyPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        controller.resume()
        assertNull(shadowOf(activity).nextStartedService)
        controller.pause()
        readyBluetooth()
        controller.resume()
        val started = shadowOf(activity).nextStartedService
        assertEquals(ObdService.ACTION_CONNECT, started.action)
        assertEquals("AA:BB:CC:DD:EE:FF", started.getStringExtra(ObdService.EXTRA_ADDRESS))
        controller.pause().resume()
        assertNull("repeat resume stays in cooldown", shadowOf(activity).nextStartedService)
    }

    @Test
    fun refreshStaysActiveUntilASlowReadCompletesAndStopsOnFailure() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        activity.history.chargeReader = {
            entered.countDown()
            assertTrue("history reader was released", release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            throw IllegalStateException("database locked")
        }
        activity.history.loadCharges()
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            Thread.sleep(750L)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("read still running after the old 700ms timer", activity.uiState().historyRefreshing)
        } finally {
            release.countDown()
        }
        waitFor { !activity.uiState().historyRefreshing }
        assertTrue(!activity.uiState().historyRefreshing)
    }

    @Test
    fun theTireTestRunsTheTireProbeOnTheRememberedAdapter() {
        activity.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE).edit {
            putString(DeviceCatalog.PREF_LAST_ADDRESS, "AA:BB:CC:DD:EE:FF")
            putString(DeviceCatalog.PREF_LAST_NAME, "OBDLink MX+")
        }
        activity.startTireTest()
        val started = shadowOf(activity).nextStartedService
        assertEquals(ObdService.ACTION_TPMS_SCAN, started.action)
        assertEquals("AA:BB:CC:DD:EE:FF", started.getStringExtra(ObdService.EXTRA_ADDRESS))
        assertEquals(EnhancedPidProfiles.STAGE_TIRES, started.getStringExtra(ObdService.EXTRA_DETAIL_STAGE))
    }

    @Test
    fun restoreIsRefusedWhileASessionIsLogging() {
        activity.loggingProbe = { true }
        activity.runCommand(SettingsCommand.Restore(null))
        assertEquals(
            activity.getString(R.string.status_stop_logging_before_restore),
            ShadowToast.getTextOfLatestToast(),
        )
        assertNull("no file picker opens", shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun restoreIsHandedToTheClassicDashboardWhileItIsOpen() {
        ClassicDashboardPresence.onCreated()
        activity.runCommand(SettingsCommand.Restore(null))
        assertEquals(activity.getString(R.string.compose_restore_use_classic), ShadowToast.getTextOfLatestToast())
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started.component?.className)
    }

    @Test
    fun restoreOpensTheFilePickerWhenNothingElseHoldsTheDatabase() {
        activity.runCommand(SettingsCommand.Restore(null))
        val started = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.action)
    }

    @Test
    fun backUpOpensTheDatabaseAndTheDisclosureThenStoppingHandsTheDatabaseBack() {
        activity.runCommand(SettingsCommand.BackUp(null))
        assertNotNull("the privacy disclosure shows first", ShadowAlertDialog.getLatestAlertDialog())
        assertNotNull(activity.localStore)
        controller
            .start()
            .resume()
            .pause()
            .stop()
        assertNull("closed once the screen stops", activity.localStore)
    }

    @Test
    fun aShortEncryptionPassphraseIsRefused() {
        activity.runCommand(SettingsCommand.BackUp("short"))
        assertNotNull(ShadowToast.getTextOfLatestToast())
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test
    fun testConnectionAndWaitingNeedARememberedAdapter() {
        activity.runCommand(SettingsCommand.TestConnection)
        val noAdapter = activity.getString(R.string.status_no_remembered_adapter_yet)
        assertEquals(noAdapter, ShadowToast.getTextOfLatestToast())
        ShadowToast.reset()
        activity.runCommand(SettingsCommand.WaitForAdapter(on = true, minutes = 5))
        assertEquals(noAdapter, ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun waitingArmsAndCancelsWithARememberedAdapter() {
        activity
            .getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
            .edit { clear() }
        DeviceCatalog(activity, activity.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE))
            .remember("00:11:22:33:44:55", "OBDLink MX+")
        ShadowToast.reset()
        activity.runCommand(SettingsCommand.WaitForAdapter(on = true, minutes = 10))
        assertNull("no complaint when an adapter is remembered", ShadowToast.getTextOfLatestToast())
        activity.runCommand(SettingsCommand.WaitForAdapter(on = false, minutes = 10))
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun exportingWithNoTripsSaysSo() {
        activity.runCommand(SettingsCommand.ExportTrips)
        val expected = activity.getString(R.string.status_all_trips_export_empty)
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (ShadowToast.getTextOfLatestToast() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(expected, ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun progressAndTheBackupReceiptAreAcceptedWithoutAWebView() {
        activity.publishRestoreProgress(true, true, "Preparing backup", null, "ok", null, 0, 0, 0, 0, 40, -1, "backup")
        activity.publishDashboardPayload("setBackupReceipt", "{}")
        activity.publishDashboardPayload("somethingElse", null)
        activity.publishDeviceList()
        activity.publishStorageSummary()
        assertNotNull(activity.getStorageSummaryJson())
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val POLL_MS = 20L
    }

    @Test
    fun showingTheChargeTabReadsTheLoggedChargesIntoTheScreen() {
        activity.history.chargeReader = {
            JSONArray().put(
                JSONObject()
                    .put("startedAtMs", 1_000L)
                    .put("endedAtMs", 2_000L)
                    .put("chargerType", "level2")
                    .put("startSoc", 30)
                    .put("endSoc", 90)
                    .put("energyKwh", 8.4),
            )
        }
        activity.history.loadCharges()
        waitFor {
            activity
                .uiState()
                .charge.sessions
                .isNotEmpty()
        }
        val sessions = activity.uiState().charge.sessions
        assertEquals(1, sessions.size)
        assertEquals("L2", sessions[0].level)
        assertEquals(8.4, sessions[0].energyKwh ?: Double.NaN, 1e-9)
    }

    @Test
    fun aFailedChargeReadLeavesTheListAloneAndCanBeRetried() {
        val reads = AtomicInteger()
        activity.history.chargeReader = {
            reads.incrementAndGet()
            throw IllegalStateException("database locked")
        }
        activity.history.loadCharges()
        waitFor { reads.get() == 1 }
        assertTrue(
            activity
                .uiState()
                .charge.sessions
                .isEmpty(),
        )
        // Once the failed read has finished, the next visit reads again.
        waitFor {
            activity.history.loadCharges()
            reads.get() >= 2
        }
        assertTrue(reads.get() >= 2)
    }

    @Test
    fun theChargeReadWaitsOutABackupOrRestore() {
        val reads = AtomicInteger()
        activity.history.chargeReader = {
            reads.incrementAndGet()
            JSONArray()
        }
        val lease = DatabaseOperationLease.tryAcquire("test") ?: error("lease expected")
        try {
            activity.history.loadCharges()
            Thread.sleep(WAIT_MS / 50)
            assertEquals(0, reads.get())
        } finally {
            lease.close()
        }
    }

    @Test
    fun showingTheTripsTabReadsTheDrivesThenTheNewestRoute() {
        val routesRead = mutableListOf<String>()
        activity.history.tripsReader = {
            JSONArray()
                .put(tripRow("1:100:200", 100L))
                .put(tripRow("1:300:400", 300L))
        }
        activity.history.routeReader = { key ->
            synchronized(routesRead) { routesRead += key }
            val points =
                JSONArray()
                    .put(JSONObject().put("lat", 0.0).put("lng", 0.0).put("atMs", 300L))
                    .put(JSONObject().put("lat", 0.0).put("lng", 0.01).put("atMs", 400L))
            JSONObject().put("points", points) to JSONArray().put(JSONObject().put("atMs", 350L).put("gas", true))
        }
        activity.history.loadTrips()
        waitFor { activity.uiState().trips.selectedRoute != null }
        val trips = activity.uiState().trips
        assertEquals(listOf("1:300:400", "1:100:200"), trips.trips.map { it.routeKey })
        assertEquals(listOf(true, true), trips.selectedRoute?.points?.map { it.gas })
        assertEquals(listOf("1:300:400"), synchronized(routesRead) { routesRead.toList() })
    }

    @Test
    fun aFailedRouteReadShowsTheDriveWithoutARoute() {
        activity.history.tripsReader = { JSONArray().put(tripRow("1:100:200", 100L)) }
        activity.history.routeReader = { throw IllegalStateException("database locked") }
        activity.history.loadTrips()
        waitFor { activity.uiState().trips.selectedRoute != null }
        assertTrue(
            activity
                .uiState()
                .trips.selectedRoute
                ?.points
                ?.isEmpty() == true,
        )
    }

    @Test
    fun showingInsightsReadsTheDrivesSpeedsAndCellsForTheShownPeriod() {
        val windows = mutableListOf<Pair<Long, Long>>()
        activity.history.insightsReader = { since, until ->
            synchronized(windows) { windows += since to until }
            Triple(
                JSONArray().put(tripRow("1:100:200", 100L)),
                JSONArray().put(JSONObject().put("mph", 40).put("miPerKwh", 4.7)),
                JSONObject()
                    .put("cell", 47)
                    .put("belowMeanMv", 18)
                    .put("driftMv", 12)
                    .put("days", 14),
            )
        }
        activity.history.loadInsights(nowMs = 1_777_585_320_000L)
        waitFor { activity.uiState().insights.cellDrift != null }
        val insights = activity.uiState().insights
        assertEquals(listOf("1:100:200"), insights.trips.map { it.routeKey })
        assertEquals(listOf(SpeedEfficiency(40, 4.7)), insights.speedEfficiency)
        // The month on show, in local time: it starts before and ends after the given instant.
        val (since, until) = synchronized(windows) { windows.single() }
        assertTrue(since < 1_777_585_320_000L && until > 1_777_585_320_000L)

        // Picking another period reads again, for that period.
        activity.selectInsightsPeriod(InsightsPeriod.ALL)
        waitFor { synchronized(windows) { windows.size } == 2 }
        assertEquals(0L to Long.MAX_VALUE, synchronized(windows) { windows[1] })
    }

    @Test
    fun healthReadsTheBatteryHealthHistoryWithTheCodes() {
        val codes =
            JSONObject().put(
                "latestDiagnosticCodes",
                JSONArray().put(JSONObject().put("dtc", "P0420").put("lastSeenMs", 5_000L)),
            )
        activity.history.healthReader = { codes }
        activity.history.sohReader = {
            JSONArray()
                .put(JSONObject().put("capturedAtMs", 1_000L).put("sohPct", 95.0))
                .put(JSONObject().put("capturedAtMs", 2_000L).put("capacityAh", 47.0))
        }
        activity.history.loadHealth()
        waitFor {
            activity
                .uiState()
                .diag.sohHistory
                .isNotEmpty()
        }
        val diag = activity.uiState().diag
        assertEquals(listOf(95.0, 47.0 / 52.0 * 100.0), diag.sohHistory.map { it.pct })
        assertEquals(listOf("P0420"), diag.codes.orEmpty().map { it.code })
    }

    @Test
    fun aFailedBatteryHistoryReadStillShowsTheCodes() {
        val codes =
            JSONObject().put(
                "latestDiagnosticCodes",
                JSONArray().put(JSONObject().put("dtc", "P0011").put("lastSeenMs", 5_000L)),
            )
        activity.history.healthReader = { codes }
        activity.history.sohReader = { throw IllegalStateException("no battery table") }
        activity.history.loadHealth()
        waitFor {
            activity
                .uiState()
                .diag.codes
                ?.isNotEmpty() == true
        }
        assertEquals(
            listOf("P0011"),
            activity
                .uiState()
                .diag.codes
                .orEmpty()
                .map { it.code },
        )
        assertTrue(
            activity
                .uiState()
                .diag.sohHistory
                .isEmpty(),
        )
    }

    @Test
    fun aReceiptIsSharedAsPlainTextThroughTheShareSheet() {
        activity.shareText("Evening drive", "Distance: 12.0 mi")
        val chooser = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertEquals("text/plain", send?.type)
        assertEquals("Evening drive", send?.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("Distance: 12.0 mi", send?.getStringExtra(Intent.EXTRA_TEXT))
    }

    private fun tripRow(
        key: String,
        startedAtMs: Long,
    ) = JSONObject()
        .put("id", key)
        .put("startedAtMs", startedAtMs)
        .put("endedAtMs", startedAtMs + 100L)
        .put("distanceMeters", 5_000.0)
        .put("evShare", 1.0)

    /** Polls [done] (running main-thread posts between tries) until it holds or [WAIT_MS] passes. */
    private fun waitFor(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!done() && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
