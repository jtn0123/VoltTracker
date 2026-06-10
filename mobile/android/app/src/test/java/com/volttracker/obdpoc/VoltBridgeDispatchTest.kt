package com.volttracker.obdpoc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import com.volttracker.obdpoc.data.ObdLocalStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/**
 * Exercises the actual dispatch behavior of the [VoltBridge] `@JavascriptInterface` surface — the
 * effect each call has on its collaborators — rather than just the ABI pinned by [VoltBridgeTest].
 *
 * Most bridge methods clean their inputs, post to the UI/background thread, and forward to a seam on
 * [MainActivity]: a service action via [MainActivity.startObdService], a blocked-status broadcast via
 * [MainActivity.publishStatus], a store mutation via [MainActivity.localStore], or a troubleshooter
 * hop via one of the `*FromBridge` helpers. [RecordingActivity] overrides those open seams so each
 * call's observable outcome can be asserted directly, without spinning up the real WebView, Bluetooth
 * stack, or foreground service.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoltBridgeDispatchTest {
    private var controller: ActivityController<RecordingActivity>? = null
    private lateinit var activity: RecordingActivity
    private lateinit var bridge: VoltBridge

    @Before
    fun setUp() {
        val controller = Robolectric.buildActivity(RecordingActivity::class.java).create()
        this.controller = controller
        activity = controller.get()
        bridge = VoltBridge(activity)
    }

    @After
    fun tearDown() {
        activity.store.close()
        try {
            controller?.destroy()
        } catch (ignored: RuntimeException) {
            // Activity teardown can race in Robolectric; it must not fail the assertions above.
        }
    }

    /** Drains the main looper so the `runOnUiThread` body the bridge posts actually executes. */
    private fun drain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    // ---- service dispatch with an explicit address -------------------------------------

    @Test
    fun scanStartsScanServiceWithCleanedAddressAndName() {
        bridge.scan(VALID_ADDRESS, "  Veepeak  ")
        drain()

        assertEquals(ObdService.ACTION_SCAN, activity.lastServiceAction)
        assertEquals(VALID_ADDRESS, activity.lastServiceAddress)
        assertEquals("Veepeak", activity.lastServiceName)
        assertNull("scan should not carry a detail stage", activity.lastServiceStage)
        // The adapter is remembered before the service is started.
        assertEquals(VALID_ADDRESS, activity.lastRememberedAddress)
    }

    @Test
    fun scanBlocksAndStartsNothingForInvalidAddress() {
        bridge.scan("not-a-mac", "Bogus")
        drain()

        assertNull("invalid address must not start a service", activity.lastServiceAction)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    @Test
    fun connectStartsConnectService() {
        bridge.connect(VALID_ADDRESS, "ELM327")
        drain()

        assertEquals(ObdService.ACTION_CONNECT, activity.lastServiceAction)
        assertEquals(VALID_ADDRESS, activity.lastServiceAddress)
        assertEquals("ELM327", activity.lastServiceName)
    }

    @Test
    fun detailProbeStartsTpmsScanWithNormalizedStage() {
        bridge.detailProbe(VALID_ADDRESS, "OBDLink", "  Passive  ")
        drain()

        assertEquals(ObdService.ACTION_TPMS_SCAN, activity.lastServiceAction)
        assertEquals(VALID_ADDRESS, activity.lastServiceAddress)
        assertEquals(EnhancedPidProfiles.STAGE_PASSIVE, activity.lastServiceStage)
    }

    @Test
    fun detailProbeFallsBackToTiresStageForUnknownInput() {
        // normalizeStage() maps anything outside the known set onto STAGE_TIRES.
        bridge.detailProbe(VALID_ADDRESS, "OBDLink", "nonsense")
        drain()

        assertEquals(ObdService.ACTION_TPMS_SCAN, activity.lastServiceAction)
        assertEquals(EnhancedPidProfiles.STAGE_TIRES, activity.lastServiceStage)
    }

    @Test
    fun tpmsScanDelegatesToDetailProbeWithTiresStage() {
        bridge.tpmsScan(VALID_ADDRESS, "OBDLink")
        drain()

        assertEquals(ObdService.ACTION_TPMS_SCAN, activity.lastServiceAction)
        assertEquals(EnhancedPidProfiles.STAGE_TIRES, activity.lastServiceStage)
    }

    // ---- service dispatch against the remembered/last adapter --------------------------

    @Test
    fun connectLastStartsConnectAgainstRememberedAdapter() {
        rememberLastDevice()

        bridge.connectLast()
        drain()

        assertEquals(ObdService.ACTION_CONNECT, activity.lastServiceAction)
        assertEquals(VALID_ADDRESS, activity.lastServiceAddress)
        assertEquals("Saved adapter", activity.lastServiceName)
    }

    @Test
    fun connectLastBlocksWhenNoAdapterRemembered() {
        bridge.connectLast()
        drain()

        assertNull(activity.lastServiceAction)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
        assertEquals(
            "No remembered adapter yet. Connect once to save it.",
            activity.lastStatusDetail,
        )
    }

    @Test
    fun scanLastStartsScanAgainstRememberedAdapter() {
        rememberLastDevice()

        bridge.scanLast()
        drain()

        assertEquals(ObdService.ACTION_SCAN, activity.lastServiceAction)
        assertEquals(VALID_ADDRESS, activity.lastServiceAddress)
    }

    @Test
    fun scanLastBlocksWhenNoAdapterRemembered() {
        bridge.scanLast()
        drain()

        assertNull(activity.lastServiceAction)
        assertEquals("blocked", activity.lastStatusState)
    }

    @Test
    fun tryReconnectNowStartsConnectAgainstRememberedAdapter() {
        rememberLastDevice()

        bridge.tryReconnectNow()
        drain()

        assertEquals(ObdService.ACTION_CONNECT, activity.lastServiceAction)
        assertEquals(VALID_ADDRESS, activity.lastServiceAddress)
    }

    @Test
    fun tryReconnectNowBlocksWhenNoAdapterRemembered() {
        bridge.tryReconnectNow()
        drain()

        assertNull(activity.lastServiceAction)
        assertEquals("blocked", activity.lastStatusState)
    }

    @Test
    fun clearVehicleDtcCodesStartsClearDtcAgainstRememberedAdapter() {
        rememberLastDevice()

        bridge.clearVehicleDtcCodes()
        drain()

        assertEquals("Clear vehicle codes?", activity.lastConfirmationTitle)
        assertEquals(ObdService.ACTION_CLEAR_DTC, activity.lastServiceAction)
        assertEquals(VALID_ADDRESS, activity.lastServiceAddress)
    }

    @Test
    fun clearVehicleDtcCodesBlocksWhenNoAdapterRemembered() {
        bridge.clearVehicleDtcCodes()
        drain()

        assertNull(activity.lastServiceAction)
        assertEquals("blocked", activity.lastStatusState)
    }

    @Test
    fun detailProbeLastStartsTpmsScanWithNormalizedStage() {
        rememberLastDevice()

        bridge.detailProbeLast("experimental")
        drain()

        assertEquals(ObdService.ACTION_TPMS_SCAN, activity.lastServiceAction)
        assertEquals(VALID_ADDRESS, activity.lastServiceAddress)
        assertEquals(EnhancedPidProfiles.STAGE_EXPERIMENTAL, activity.lastServiceStage)
    }

    @Test
    fun detailProbeLastBlocksWhenNoAdapterRemembered() {
        bridge.detailProbeLast(EnhancedPidProfiles.STAGE_TIRES)
        drain()

        assertNull(activity.lastServiceAction)
        assertEquals("blocked", activity.lastStatusState)
    }

    @Test
    fun tpmsScanLastDelegatesToDetailProbeLastWithTiresStage() {
        rememberLastDevice()

        bridge.tpmsScanLast()
        drain()

        assertEquals(ObdService.ACTION_TPMS_SCAN, activity.lastServiceAction)
        assertEquals(EnhancedPidProfiles.STAGE_TIRES, activity.lastServiceStage)
    }

    // ---- demo / disconnect -------------------------------------------------------------

    @Test
    fun demoStartsDemoServiceWithoutAnAddress() {
        bridge.demo()
        drain()

        assertEquals(ObdService.ACTION_DEMO, activity.lastServiceAction)
        assertNull("demo runs without a Bluetooth address", activity.lastServiceAddress)
        assertEquals("Demo stream", activity.lastServiceName)
    }

    @Test
    fun disconnectStopsTheService() {
        bridge.disconnect()
        drain()

        assertTrue("disconnect must stop the OBD service", activity.stopServiceCalls > 0)
        assertNull("disconnect does not start a service", activity.lastServiceAction)
    }

    // ---- rememberDevice ----------------------------------------------------------------

    @Test
    fun rememberDeviceForwardsCleanedAddressAndName() {
        bridge.rememberDevice("  $VALID_ADDRESS  ", "  My Adapter  ")
        drain()

        assertEquals(VALID_ADDRESS, activity.lastRememberedAddress)
        assertEquals("My Adapter", activity.lastRememberedName)
        assertNull("rememberDevice must not start a service", activity.lastServiceAction)
    }

    // ---- clearStoredData ---------------------------------------------------------------

    @Test
    fun clearStoredDataClearsStoreAndReportsReadyWhenIdle() {
        activity.loggingActive = false

        bridge.clearStoredData()
        drain()

        assertEquals("Clear stored data?", activity.lastConfirmationTitle)
        assertEquals(1, activity.store.clearAllDataCalls)
        assertEquals("ready", activity.lastStatusState)
        assertFalse(activity.lastStatusBlocked)
        assertTrue("storage summary should be refreshed after a clear", activity.storageSummaryCalls > 0)
    }

    @Test
    fun clearStoredDataBlocksWithoutTouchingStoreWhileLogging() {
        activity.loggingActive = true

        bridge.clearStoredData()
        drain()

        assertEquals(0, activity.store.clearAllDataCalls)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    // ---- deleteDetailedSignalLog -------------------------------------------------------

    @Test
    fun deleteDetailedSignalLogRemovesRowAndReportsReadyOnSuccess() {
        activity.store.deleteReturn = 1

        bridge.deleteDetailedSignalLog("42")
        drain()

        assertEquals(42L, activity.store.lastDeletedId)
        assertEquals("ready", activity.lastStatusState)
        assertFalse(activity.lastStatusBlocked)
    }

    @Test
    fun deleteDetailedSignalLogReportsBlockedWhenNothingWasRemoved() {
        activity.store.deleteReturn = 0

        bridge.deleteDetailedSignalLog("42")
        drain()

        assertEquals(42L, activity.store.lastDeletedId)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    @Test
    fun deleteDetailedSignalLogRejectsInvalidIdWithoutTouchingStore() {
        bridge.deleteDetailedSignalLog("0")
        drain()

        // A non-positive id short-circuits before runOnBackground, so the store is never asked
        // to delete anything — its capture stays at the untouched sentinel.
        assertEquals(Long.MIN_VALUE, activity.store.lastDeletedId)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    // ---- export detailed signal logs ---------------------------------------------------

    @Test
    fun exportDetailedSignalLogReturnsStoreExportForValidId() {
        activity.store.singleExport = JSONObject().put("ok", true).put("id", 7)

        val payload = JSONObject(bridge.exportDetailedSignalLog("7"))

        assertEquals(7L, activity.store.lastSingleExportId)
        assertTrue(payload.optBoolean("ok"))
        assertEquals(7, payload.optInt("id"))
    }

    @Test
    fun exportDetailedSignalLogRejectsInvalidId() {
        val payload = JSONObject(bridge.exportDetailedSignalLog("-5"))

        // A non-positive id is rejected before the store is consulted.
        assertEquals(Long.MIN_VALUE, activity.store.lastSingleExportId)
        assertFalse(payload.optBoolean("ok", true))
        assertEquals("invalid_id", payload.optString("error"))
    }

    @Test
    fun exportDetailedSignalLogsReturnsBoundedStoreExport() {
        activity.store.bulkExport = JSONObject().put("ok", true).put("count", 3)

        val payload = JSONObject(bridge.exportDetailedSignalLogs())

        // The bridge always asks the store for the most recent 250 capability rows.
        assertEquals(250, activity.store.lastBulkExportLimit)
        assertTrue(payload.optBoolean("ok"))
    }

    @Test
    fun exportDetailedSignalLogsReturnsValidJsonWhenStorageUnavailable() {
        activity.localStore = null

        val payload = JSONObject(bridge.exportDetailedSignalLogs())

        assertFalse(payload.optBoolean("ok", true))
        assertEquals("storage_unavailable", payload.optString("error"))
        assertEquals("Local storage is not ready.", payload.optString("message"))
    }

    // ---- openExternalSearch ------------------------------------------------------------

    @Test
    fun openExternalSearchLaunchesADtcLookupViewIntent() {
        bridge.openExternalSearch("P0AA6")
        drain()

        val launched = activity.lastStartedActivity
        assertEquals(Intent.ACTION_VIEW, launched!!.action)
        val url = launched.data.toString()
        assertTrue(
            "URL should point at a Google search: $url",
            url.startsWith("https://www.google.com/search?q="),
        )
        assertTrue("encoded query should include the DTC code: $url", url.contains("P0AA6"))
        assertTrue("encoded query should mention the Volt: $url", url.contains("Volt"))
    }

    @Test
    fun openExternalSearchIgnoresBlankCode() {
        bridge.openExternalSearch("   ")
        drain()

        assertNull("a blank DTC must not open the browser", activity.lastStartedActivity)
    }

    // ---- troubleshooter forwarding -----------------------------------------------------

    @Test
    fun forceStopPackageForwardsNonEmptyPackageAndReturnsResult() {
        activity.forceStopReturn = true

        val result = bridge.forceStopPackage("  com.example.obd  ")
        drain()

        assertTrue(result)
        assertEquals("Force-stop OBD app?", activity.lastConfirmationTitle)
        assertEquals("com.example.obd", activity.lastForceStopPackage)
    }

    @Test
    fun forceStopPackageShortCircuitsEmptyPackageWithoutForwarding() {
        val result = bridge.forceStopPackage("   ")

        assertFalse(result)
        assertNull("empty package must not reach the troubleshooter", activity.lastForceStopPackage)
    }

    @Test
    fun cancelRetryForwardsToActivity() {
        bridge.cancelRetry()
        drain()

        assertEquals(1, activity.cancelRetryCalls)
    }

    @Test
    fun openBluetoothSettingsForwardsToActivity() {
        bridge.openBluetoothSettings()
        drain()

        assertEquals(1, activity.openBluetoothSettingsCalls)
    }

    @Test
    fun getRecentSessionsForwardsCountAndReturnsActivityJson() {
        activity.recentSessionsJson = "[{\"id\":1}]"

        val json = bridge.getRecentSessions(5)

        assertEquals(5, activity.lastRecentSessionsCount)
        assertEquals("[{\"id\":1}]", json)
    }

    @Test
    fun shareDiagnosticsForwardsToActivity() {
        bridge.shareDiagnostics()
        drain()

        assertEquals(1, activity.shareDiagnosticsCalls)
    }

    @Test
    fun startTestConnectionForwardsToActivity() {
        bridge.startTestConnection()
        drain()

        assertEquals(1, activity.startTestConnectionCalls)
    }

    @Test
    fun scheduleAdapterReadyNotifyClampsMinutesIntoOneToThirty() {
        bridge.scheduleAdapterReadyNotify(0)
        drain()
        assertEquals(1, activity.lastScheduleMinutes)

        bridge.scheduleAdapterReadyNotify(999)
        drain()
        assertEquals(30, activity.lastScheduleMinutes)

        bridge.scheduleAdapterReadyNotify(12)
        drain()
        assertEquals(12, activity.lastScheduleMinutes)
    }

    @Test
    fun cancelAdapterReadyNotifyForwardsToActivity() {
        bridge.cancelAdapterReadyNotify()
        drain()

        assertEquals(1, activity.cancelAdapterReadyNotifyCalls)
    }

    // ---- read-through getter -----------------------------------------------------------

    @Test
    fun getTripRouteForwardsCleanedSessionIdToActivity() {
        activity.tripRouteJson = "{\"ok\":true}"

        val json = bridge.getTripRoute("  session-123  ")

        assertEquals("session-123", activity.lastTripRouteKey)
        assertEquals("{\"ok\":true}", json)
    }

    /** Seeds the device catalog with a valid remembered adapter for the `*Last` dispatch paths. */
    private fun rememberLastDevice() {
        activity.requireDeviceCatalog().remember(VALID_ADDRESS, "Saved adapter")
    }

    /**
     * A [MainActivity] whose heavy collaborators (WebView, foreground service, Bluetooth gating) are
     * replaced by recording overrides. Only the seams the bridge actually reaches are wired up: a
     * real [DeviceCatalog] over scratch prefs and a [RecordingStore], plus captured `startObdService`
     * / `publishStatus` / `rememberDevice` / troubleshooter calls.
     */
    class RecordingActivity : MainActivity() {
        lateinit var store: RecordingStore

        var lastServiceAction: String? = null
        var lastServiceAddress: String? = null
        var lastServiceName: String? = null
        var lastServiceStage: String? = null
        var stopServiceCalls = 0

        var lastStatusState: String? = null
        var lastStatusDetail: String? = null
        var lastStatusBlocked = false

        var lastRememberedAddress: String? = null
        var lastRememberedName: String? = null

        var loggingActive = false
        var storageSummaryCalls = 0

        var confirmationCalls = 0
        var lastConfirmationTitle: String? = null
        var lastConfirmationMessage: String? = null
        var lastConfirmationPositiveLabel: String? = null

        var forceStopReturn = false
        var lastForceStopPackage: String? = null
        var cancelRetryCalls = 0
        var openBluetoothSettingsCalls = 0
        var recentSessionsJson = "[]"
        var lastRecentSessionsCount = Int.MIN_VALUE
        var shareDiagnosticsCalls = 0
        var startTestConnectionCalls = 0
        var lastScheduleMinutes = Int.MIN_VALUE
        var cancelAdapterReadyNotifyCalls = 0

        var tripRouteJson = "{}"
        var lastTripRouteKey: String? = null

        var lastStartedActivity: Intent? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            // Deliberately skip super.onCreate(): it builds a WebView and a real ObdLocalStore. We
            // only need the catalog + recording store the bridge dispatches against. The store is
            // built here (not as a field initializer) so the activity's base context is attached.
            store = RecordingStore(this)
            getSharedPreferences("volt-bridge-dispatch-test", 0).edit().clear().commit()
            deviceCatalog = DeviceCatalog(this, getSharedPreferences("volt-bridge-dispatch-test", 0))
            localStore = store
        }

        // runOnBackground hops to a single-thread executor in production; run inline so the
        // background body completes before the test asserts.
        override fun runOnBackground(task: Runnable) {
            task.run()
        }

        override fun startObdService(
            action: String?,
            address: String?,
            name: String?,
        ) {
            recordService(action, address, name, null)
        }

        override fun startObdService(
            action: String?,
            address: String?,
            name: String?,
            detailStage: String?,
        ) {
            recordService(action, address, name, detailStage)
        }

        private fun recordService(
            action: String?,
            address: String?,
            name: String?,
            stage: String?,
        ) {
            lastServiceAction = action
            lastServiceAddress = address
            lastServiceName = name
            lastServiceStage = stage
        }

        override fun stopObdService() {
            stopServiceCalls += 1
        }

        override fun publishStatus(
            state: String?,
            detail: String?,
            blocked: Boolean,
        ) {
            lastStatusState = state
            lastStatusDetail = detail
            lastStatusBlocked = blocked
        }

        override fun rememberDevice(
            address: String?,
            name: String?,
        ) {
            lastRememberedAddress = address
            lastRememberedName = name
            // Keep the catalog in sync so the `*Last` reads see the freshly remembered adapter.
            requireDeviceCatalog().remember(address, name)
        }

        override fun isLoggingActive(): Boolean = loggingActive

        override fun confirmBridgeAction(
            title: String,
            message: String,
            positiveLabel: String,
            onConfirmed: Runnable,
        ) {
            confirmationCalls += 1
            lastConfirmationTitle = title
            lastConfirmationMessage = message
            lastConfirmationPositiveLabel = positiveLabel
            onConfirmed.run()
        }

        override fun publishStorageSummary() {
            storageSummaryCalls += 1
        }

        override fun forceStopPackageFromBridge(packageName: String?): Boolean {
            lastForceStopPackage = packageName
            return forceStopReturn
        }

        override fun cancelRetryFromBridge() {
            cancelRetryCalls += 1
        }

        override fun openBluetoothSettingsFromBridge() {
            openBluetoothSettingsCalls += 1
        }

        override fun getRecentSessionsJson(n: Int): String {
            lastRecentSessionsCount = n
            return recentSessionsJson
        }

        override fun shareDiagnosticsFromBridge() {
            shareDiagnosticsCalls += 1
        }

        override fun startTestConnectionFromBridge() {
            startTestConnectionCalls += 1
        }

        override fun scheduleAdapterReadyNotifyFromBridge(mins: Int) {
            lastScheduleMinutes = mins
        }

        override fun cancelAdapterReadyNotifyFromBridge() {
            cancelAdapterReadyNotifyCalls += 1
        }

        override fun getTripRouteJson(routeKey: String?): String {
            lastTripRouteKey = routeKey
            return tripRouteJson
        }

        // openExternalSearch's only observable effect is the browser Intent it fires; capture it
        // here instead of leaning on the shadow so the assertion does not depend on the skipped
        // super.onCreate() leaving the activity's start-activity plumbing intact.
        override fun startActivity(intent: Intent?) {
            lastStartedActivity = intent
        }
    }

    /** Records the store mutations the bridge drives and serves canned export payloads. */
    class RecordingStore(
        context: Context,
    ) : ObdLocalStore(context) {
        var clearAllDataCalls = 0

        var deleteReturn = 0
        var lastDeletedId = Long.MIN_VALUE

        var singleExport = JSONObject()
        var lastSingleExportId = Long.MIN_VALUE

        var bulkExport = JSONObject()
        var lastBulkExportLimit = Int.MIN_VALUE

        override fun clearAllData() {
            clearAllDataCalls += 1
        }

        override fun deleteEnhancedCapability(id: Long): Int {
            lastDeletedId = id
            return deleteReturn
        }

        override fun getEnhancedCapabilityExportJson(id: Long): JSONObject {
            lastSingleExportId = id
            return singleExport
        }

        override fun getEnhancedCapabilitiesExportJson(limit: Int): JSONObject {
            lastBulkExportLimit = limit
            return bulkExport
        }
    }

    private companion object {
        /** A MAC that passes [android.bluetooth.BluetoothAdapter.checkBluetoothAddress]. */
        private const val VALID_ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
