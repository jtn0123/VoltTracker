package com.volttracker.obdpoc

import android.content.Context
import android.content.Intent
import com.volttracker.obdpoc.data.ObdLocalStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Dashboard-level workflow simulations for the JavaScript bridge. These call the same
 * @JavascriptInterface methods button handlers use, backed by a real SQLite store, then read the
 * same dashboard JSON projections the WebView consumes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardBridgeWorkflowSimulationTest {
    private lateinit var context: Context
    private lateinit var store: ObdLocalStore
    private lateinit var host: SimulatedDashboardHost
    private lateinit var bridge: VoltBridge

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        store = ObdLocalStore(context)
        store.clearAllData()
        host = SimulatedDashboardHost(context, store)
        bridge = VoltBridge(host)
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun tripManagementButtonsUpdateRealTripProjections() {
        val routeKey = seedDrive()

        bridge.setTripLabel("  $routeKey  ", "  Morning commute  ")
        bridge.setTripFavorite(routeKey, true)

        var trips = JSONArray(bridge.getTrips())
        assertEquals(1, trips.length())
        var trip = trips.getJSONObject(0)
        assertEquals("Morning commute", trip.optString("label"))
        assertTrue("favorite should round-trip through the trip list", trip.optBoolean("favorite"))
        assertEquals("ready", host.lastStatusState)
        assertTrue("trip changes should refresh storage", host.storageSummaryPublishes >= 2)

        val route = JSONObject(bridge.getTripRoute(routeKey))
        assertEquals(routeKey, route.getJSONObject("session").optString("id"))
        assertTrue("route still exposes GPS geometry", route.optInt("pointCount") >= 3)

        bridge.markTripNotTrip(routeKey)

        trips = JSONArray(bridge.getTrips())
        assertEquals("hidden trip should disappear from dashboard trips", 0, trips.length())
        assertEquals("ready", host.lastStatusState)
        assertEquals("Mark as not a trip?", host.lastConfirmationTitle)
        assertTrue("confirmation path should have run", host.confirmationCount >= 1)
        trip = JSONObject(bridge.getTripRoute(routeKey))
        assertEquals("hidden route should no longer be readable", 0, trip.length())
    }

    @Test
    fun notificationSetpointWorkflowPersistsClampedDashboardState() {
        bridge.setChargeCompleteNotify(false)
        bridge.setNewDtcNotify(false)
        bridge.setLowSocNotify(true, 500.0)
        bridge.setHighPackTempNotify(true, 0.0)
        bridge.setChargeTargetSoc(10.0)
        bridge.setAutoScanOnConnect(true)
        bridge.setMaintenanceDueNotify(true)

        val state = JSONObject(bridge.getEventNotificationState())

        assertFalse(state.getBoolean("chargeComplete"))
        assertFalse(state.getBoolean("newDtc"))
        assertTrue(state.getBoolean("lowSoc"))
        assertEquals(
            "low-SOC threshold should clamp to the safe upper bound",
            99.0,
            state.getDouble("lowSocThresholdPct"),
            0.001,
        )
        assertTrue(state.getBoolean("highPackTemp"))
        assertEquals(
            "high-temp threshold should clamp to the safe lower bound",
            20.0,
            state.getDouble("highPackTempThresholdC"),
            0.001,
        )
        assertEquals("target SOC should clamp to the safe lower bound", 50.0, state.getDouble("targetSocPct"), 0.001)
        assertTrue(state.getBoolean("autoScanOnConnect"))
        assertTrue(state.getBoolean("maintenanceDue"))
        assertTrue("each setting change should publish app state", host.appStatePublishes >= 7)
    }

    @Test
    fun connectionAndDiagnosticButtonsDispatchNativeActionsAndGuardBadAdapters() {
        bridge.connect("not-a-mac", "Bad adapter")

        assertEquals("blocked", host.lastStatusState)
        assertEquals("Choose a valid Bluetooth adapter.", host.lastStatusDetail)
        assertTrue(host.lastStatusBlocked)
        assertEquals("invalid adapter must not start native service", 0, host.startedServices.size)

        bridge.connect("  $ADAPTER_ADDRESS  ", "  Primary ELM  ")
        bridge.scan(ADAPTER_ADDRESS, "Primary ELM")
        bridge.quickScan(ADAPTER_ADDRESS, "Primary ELM")
        bridge.tpmsScan(ADAPTER_ADDRESS, "Primary ELM")
        bridge.detailProbe(ADAPTER_ADDRESS, "Primary ELM", EnhancedPidProfiles.STAGE_CELLS)
        bridge.clearVehicleDtcCodes()
        bridge.demo()
        bridge.disconnect()
        bridge.cancelRetry()
        bridge.openBluetoothSettings()

        val actions = host.startedServices.map { it.action }
        assertEquals(
            listOf(
                ObdService.ACTION_CONNECT,
                ObdService.ACTION_SCAN,
                ObdService.ACTION_SCAN,
                ObdService.ACTION_TPMS_SCAN,
                ObdService.ACTION_TPMS_SCAN,
                ObdService.ACTION_CLEAR_DTC,
                ObdService.ACTION_DEMO,
            ),
            actions,
        )
        assertEquals(ADAPTER_ADDRESS, host.startedServices[0].address)
        assertEquals("Primary ELM", host.startedServices[0].name)
        assertEquals(DiagnosticScanProfile.QUICK.wireName, host.startedServices[2].detailStage)
        assertEquals(EnhancedPidProfiles.STAGE_TIRES, host.startedServices[3].detailStage)
        assertEquals(EnhancedPidProfiles.STAGE_CELLS, host.startedServices[4].detailStage)
        assertEquals("Clear vehicle codes?", host.lastConfirmationTitle)
        assertEquals(1, host.stopServiceCalls)
        assertEquals(1, host.cancelRetryCalls)
        assertEquals(1, host.openBluetoothSettingsCalls)
        assertTrue("valid actions should keep the adapter remembered", host.rememberedDevices.size >= 6)
    }

    @Test
    fun clearStoredDataButtonConfirmsAndClearsRealDashboardProjections() {
        seedDrive()
        assertEquals(1, JSONArray(bridge.getTrips()).length())
        assertEquals(1, JSONObject(bridge.getStorageSummary()).optInt("sessionCount"))

        bridge.clearStoredData()

        assertEquals("Clear stored data?", host.lastConfirmationTitle)
        assertEquals("ready", host.lastStatusState)
        assertFalse(host.lastStatusBlocked)
        assertEquals("clear should remove trip rows from the real store", 0, JSONArray(bridge.getTrips()).length())
        assertEquals(0, JSONObject(bridge.getStorageSummary()).optInt("sessionCount"))
        assertTrue("clear should publish a storage refresh", host.storageSummaryPublishes >= 1)
    }

    @Test
    fun diagnosticUtilityButtonsForwardCallbacksAndClampHostileInputs() {
        val recent = JSONArray(bridge.getRecentSessions(Int.MAX_VALUE))
        bridge.openExternalSearch("  P0AA6  ")
        bridge.shareDiagnostics()
        bridge.shareDiagnosticsDigest()
        bridge.startTestConnection()
        bridge.scheduleAdapterReadyNotify(0)
        bridge.scheduleAdapterReadyNotify(999)
        bridge.cancelAdapterReadyNotify()

        assertEquals(1, recent.length())
        assertEquals(100, host.recentSessionRequests.single())
        assertEquals(1, host.startedActivities.size)
        val lookup = host.startedActivities.single()
        assertEquals(Intent.ACTION_VIEW, lookup.action)
        val uri = lookup.dataString ?: ""
        assertTrue(uri.contains("P0AA6"))
        assertTrue(uri.contains("Chevy+Volt+DTC"))
        assertEquals(1, host.shareDiagnosticsCalls)
        assertEquals(1, host.shareDiagnosticsDigestCalls)
        assertEquals(1, host.startTestConnectionCalls)
        assertEquals(listOf(1, 30), host.adapterReadyNotifyMinutes)
        assertEquals(1, host.cancelAdapterReadyNotifyCalls)
    }

    @Test
    fun maintenanceButtonsPersistListAndDeletionThroughBridge() {
        bridge.addMaintenanceEntry(
            JSONObject()
                .put("type", "Coolant service")
                .put("note", "Flush inverter loop")
                .put("odometerKm", 12_345.6)
                .put("date", 5_000L)
                .put("intervalKm", 8_000.0)
                .put("intervalMonths", 12)
                .toString(),
        )

        var log = JSONArray(bridge.getMaintenanceLog())
        assertEquals(1, log.length())
        val entry = log.getJSONObject(0)
        assertEquals("Coolant service", entry.optString("type"))
        assertEquals("Flush inverter loop", entry.optString("note"))
        assertEquals(12_345.6, entry.optDouble("odometerKm"), 0.001)
        assertEquals(8_000.0, entry.optDouble("intervalKm"), 0.001)
        assertEquals(12, entry.optInt("intervalMonths"))
        assertEquals("ready", host.lastStatusState)

        bridge.deleteMaintenanceEntry(entry.optLong("id").toString())

        log = JSONArray(bridge.getMaintenanceLog())
        assertEquals("deleted maintenance row should disappear", 0, log.length())
        assertEquals("ready", host.lastStatusState)
        assertTrue("maintenance changes should refresh storage", host.storageSummaryPublishes >= 2)
    }

    @Test
    fun asyncStorageRequestsPublishDashboardCallbacksFromRealStore() {
        val routeKey = seedDrive()

        assertTrue(bridge.requestStorageSummary())
        val summary = JSONObject(host.lastPayload("setStorage"))
        assertEquals(1, summary.optInt("sessionCount"))
        assertTrue(summary.optLong("sampleCount") >= 4L)
        assertTrue(summary.has("recentSessions"))
        assertTrue(summary.has("lastSessionId"))

        assertTrue(bridge.requestStorageDetails())
        val details = JSONObject(host.lastPayload("setStorage"))
        assertTrue(details.optBoolean("storageDetails"))
        assertTrue(details.getJSONObject("latestRoute").optInt("pointCount") >= 3)

        assertTrue(bridge.requestTrips())
        val trips = JSONArray(host.lastPayload("setTrips"))
        assertEquals(routeKey, trips.getJSONObject(0).optString("id"))

        assertTrue(bridge.requestTripRoute(routeKey))
        val wrappedRoute = JSONObject(host.lastPayload("setTripRoute"))
        assertEquals(routeKey, wrappedRoute.optString("routeKey"))
        assertTrue(wrappedRoute.getJSONObject("payload").optInt("pointCount") >= 3)
    }

    @Test
    fun dashboardPanelRefreshButtonsPublishInsightsCurrentRouteAndBatteryHealth() {
        seedDrive()
        val activeSessionId = seedActiveDriveWithBatteryHealth()

        assertTrue(bridge.requestInsights())
        val insights = JSONObject(host.lastPayload("setInsights"))
        assertTrue("insights should include the completed drive", insights.optInt("tripCount") >= 1)
        assertTrue("insights should count active battery samples too", insights.optLong("sampleCount") >= 7L)
        assertTrue("insights should expose real GPS rows", insights.optLong("locationSampleCount") >= 3L)

        assertTrue(bridge.requestCurrentSessionRoute())
        val currentRoute = JSONObject(host.lastPayload("setCurrentSessionRoute"))
        assertEquals(activeSessionId, currentRoute.getJSONObject("session").optLong("id"))
        assertEquals(ObdLocalStore.STATUS_ACTIVE, currentRoute.getJSONObject("session").optString("status"))
        assertTrue("active route should rehydrate map geometry", currentRoute.optInt("pointCount") >= 3)

        assertTrue(bridge.requestBatterySohHistory())
        val sohHistory = JSONArray(host.lastPayload("setBatterySohHistory"))
        assertEquals(3, sohHistory.length())
        assertEquals("SOH history should be oldest-first", 94.8, sohHistory.getJSONObject(0).optDouble("sohPct"), 0.001)
        assertEquals(45.0, sohHistory.getJSONObject(2).optDouble("capacityAh"), 0.001)
    }

    private fun seedDrive(): String {
        val startedAtMs = 10_000L
        val sessionId = store.startSession(ObdLocalStore.MODE_OBD, ADAPTER_ADDRESS, "Workflow ELM", startedAtMs)
        val syntheticLat = 10.0
        val syntheticLng = 20.0
        store.recordTelemetry(sessionId, sample(42, 65.0, syntheticLat, syntheticLng, startedAtMs))
        store.recordTelemetry(
            sessionId,
            sample(
                48,
                64.5,
                syntheticLat + 0.001,
                syntheticLng + 0.001,
                startedAtMs + 1_000L,
            ),
        )
        store.recordTelemetry(
            sessionId,
            sample(
                52,
                64.0,
                syntheticLat + 0.002,
                syntheticLng + 0.002,
                startedAtMs + 2_000L,
            ),
        )
        store.recordTelemetry(
            sessionId,
            sample(
                45,
                63.5,
                syntheticLat + 0.003,
                syntheticLng + 0.003,
                startedAtMs + 3_000L,
            ),
        )
        store.finalizeSession(
            sessionId,
            ObdLocalStore.STATUS_COMPLETE,
            startedAtMs + 3_000L,
            "4100FFFFFFFF",
            ADAPTER_ADDRESS,
            "Workflow ELM",
            ObdLocalStore.MODE_OBD,
            4,
            "",
        )
        val trips = store.getTripsJson(10)
        assertEquals("seeded drive should appear as one dashboard trip", 1, trips.length())
        return trips.getJSONObject(0).getString("id")
    }

    private fun seedActiveDriveWithBatteryHealth(): Long {
        val startedAtMs = 30_000L
        val sessionId = store.startSession(ObdLocalStore.MODE_OBD, ADAPTER_ADDRESS, "Active Workflow ELM", startedAtMs)
        val syntheticLat = 11.0
        val syntheticLng = 21.0
        val samples =
            listOf(
                batterySample(35, 66.0, 94.8, 45.6, syntheticLat, syntheticLng, startedAtMs),
                batterySample(
                    38,
                    65.5,
                    94.2,
                    45.3,
                    syntheticLat + 0.0007,
                    syntheticLng + 0.0007,
                    startedAtMs + 10_000L,
                ),
                batterySample(
                    41,
                    65.0,
                    93.6,
                    45.0,
                    syntheticLat + 0.0014,
                    syntheticLng + 0.0014,
                    startedAtMs + 20_000L,
                ),
            )
        for (sample in samples) {
            val atMs = sample.optLong("updatedAt")
            store.recordTelemetry(sessionId, sample, atMs)
            store.recordLocationSample(
                sessionId,
                atMs,
                "workflow",
                sample.optDouble("latitude"),
                sample.optDouble("longitude"),
                sample.optDouble("accuracyM"),
                null,
                sample.optDouble("gpsSpeedMps"),
                null,
                0L,
                null,
            )
        }
        return sessionId
    }

    private fun sample(
        speedKph: Int,
        soc: Double,
        lat: Double,
        lng: Double,
        atMs: Long,
    ): JSONObject =
        JSONObject()
            .put("source", "obd")
            .put("speedKph", speedKph)
            .put("rpm", 1500)
            .put("soc", soc)
            .put("latitude", lat)
            .put("longitude", lng)
            .put("updatedAt", atMs)

    private fun batterySample(
        speedKph: Int,
        soc: Double,
        sohPct: Double,
        capacityAh: Double,
        lat: Double,
        lng: Double,
        atMs: Long,
    ): JSONObject =
        sample(speedKph, soc, lat, lng, atMs)
            .put("packVoltage", 355.0)
            .put("packCurrentA", 5.0)
            .put("capacityAh", capacityAh)
            .put("capacityAhStaleMs", 0L)
            .put("sohPct", sohPct)
            .put("accuracyM", 7.0)
            .put("gpsSpeedMps", speedKph / 3.6)

    private class SimulatedDashboardHost(
        private val context: Context,
        private val store: ObdLocalStore,
    ) : DashboardHost {
        private val reader = DashboardStorageReader { store.takeIf { it.isOpen } }
        private val prefs =
            EventNotificationPrefs(
                context.getSharedPreferences("dashboard-workflow-simulation-prefs", Context.MODE_PRIVATE).also {
                    it.edit().clear().commit()
                },
            )
        private val deviceCatalog =
            DeviceCatalog(
                context,
                context.getSharedPreferences("dashboard-workflow-device-prefs", Context.MODE_PRIVATE).also {
                    it.edit().clear().commit()
                },
            )
        private val publishedPayloads = LinkedHashMap<String, String?>()
        private val eventNotifications =
            EventNotificationHostDelegate(
                prefs = { prefs },
                publishStatus = { state, detail, blocked -> publishStatus(state, detail, blocked) },
                publishAppState = {
                    appStatePublishes += 1
                    publishDashboardPayload("setAppState", getAppStateJson())
                },
                notReadyMessage = { "Notification settings are not ready." },
            )

        var lastStatusState: String? = null
        var lastStatusDetail: String? = null
        var lastStatusBlocked: Boolean = false
        var lastConfirmationTitle: String? = null
        var confirmationCount: Int = 0
        var storageSummaryPublishes: Int = 0
        var appStatePublishes: Int = 0
        val rememberedDevices = mutableListOf<RememberedDevice>()
        val startedServices = mutableListOf<StartedService>()
        var stopServiceCalls: Int = 0
        var cancelRetryCalls: Int = 0
        var openBluetoothSettingsCalls: Int = 0
        var shareDiagnosticsCalls: Int = 0
        var shareDiagnosticsDigestCalls: Int = 0
        var startTestConnectionCalls: Int = 0
        var cancelAdapterReadyNotifyCalls: Int = 0
        val adapterReadyNotifyMinutes = mutableListOf<Int>()
        val startedActivities = mutableListOf<Intent>()
        val recentSessionRequests = mutableListOf<Int>()

        override val localStore: ObdLocalStore?
            get() = store

        override fun runOnUiThread(action: Runnable) {
            action.run()
        }

        override fun runOnBackground(task: Runnable) {
            task.run()
        }

        override fun confirmBridgeAction(
            title: String,
            message: String,
            positiveLabel: String,
            onConfirmed: Runnable,
        ) {
            lastConfirmationTitle = title
            confirmationCount += 1
            onConfirmed.run()
        }

        override fun eventNotifications(): EventNotificationCommands = eventNotifications

        override fun publishDashboardPayload(
            functionName: String,
            jsonPayload: String?,
        ) {
            publishedPayloads[functionName] = jsonPayload
        }

        fun lastPayload(functionName: String): String =
            publishedPayloads[functionName] ?: error("No dashboard payload was published for $functionName")

        override fun publishStatus(
            state: String?,
            detail: String?,
            blocked: Boolean,
        ) {
            lastStatusState = state
            lastStatusDetail = detail
            lastStatusBlocked = blocked
        }

        override fun publishStorageSummary() {
            storageSummaryPublishes += 1
            publishDashboardPayload("setStorage", getStorageSummaryJson())
        }

        override fun getStorageSummaryJson(): String = reader.storageSummaryJson()

        override fun getStorageDetailsJson(): String = reader.storageDetailsJson()

        override fun getTripsJson(): String = reader.tripsJson()

        override fun getInsightsJson(): String = reader.insightsJson()

        override fun getTripRouteJson(routeKey: String?): String = reader.tripRouteJson(routeKey)

        override fun getCurrentSessionRouteJson(): String = reader.currentSessionRouteJson()

        override fun getBatterySohHistoryJson(): String = reader.batterySohHistoryJson()

        override fun getAppStateJson(): String =
            JSONObject()
                .put("eventNotifications", JSONObject(eventNotifications.getEventNotificationStateJson()))
                .toString()

        override fun getAutoConnectStateJson(): String = JSONObject().put("enabled", false).toString()

        override fun setAutoConnectEnabledFromBridge(enabled: Boolean) = Unit

        override fun exportTripFromBridge(
            routeKey: String?,
            format: String?,
        ): String = MainActivityUtils.errorPayload("unsupported", "Export is not part of this simulation.").toString()

        override fun isLoggingActive(): Boolean = false

        override fun startActivity(intent: Intent?) {
            if (intent != null) {
                startedActivities += intent
            }
        }

        override fun startObdService(
            action: String?,
            address: String?,
            name: String?,
        ) {
            startedServices += StartedService(action, address, name, null)
        }

        override fun startObdService(
            action: String?,
            address: String?,
            name: String?,
            detailStage: String?,
        ) {
            startedServices += StartedService(action, address, name, detailStage)
        }

        override fun stopObdService() {
            stopServiceCalls += 1
        }

        override fun rememberDevice(
            address: String?,
            name: String?,
        ) {
            rememberedDevices += RememberedDevice(address, name)
            deviceCatalog.remember(address, name)
        }

        override fun requireDeviceCatalog(): DeviceCatalog = deviceCatalog

        override fun requirePermissionGate(): PermissionGate = unsupported("permission gate")

        override fun requireDataBackup(): DataBackup = unsupported("data backup")

        override fun requireBackupController(): BackupController = unsupported("backup controller")

        override fun onDashboardReady() = Unit

        override fun publishDeviceList() = Unit

        override fun publishRestoreProgress(
            visible: Boolean,
            busy: Boolean,
            title: String?,
            detail: String?,
            tone: String?,
            phase: String?,
            bytesDone: Long,
            bytesTotal: Long,
            rowsDone: Long,
            rowsTotal: Long,
            percent: Int,
            etaSeconds: Long,
            operation: String?,
        ) = Unit

        override fun forceStopPackageFromBridge(packageName: String?): Boolean = false

        override fun cancelRetryFromBridge() {
            cancelRetryCalls += 1
        }

        override fun openBluetoothSettingsFromBridge() {
            openBluetoothSettingsCalls += 1
        }

        override fun getRecentSessionsJson(n: Int): String {
            recentSessionRequests += n
            return JSONArray()
                .put(JSONObject().put("id", 1L).put("status", "complete"))
                .toString()
        }

        override fun shareDiagnosticsFromBridge() {
            shareDiagnosticsCalls += 1
        }

        override fun shareDiagnosticsDigestFromBridge() {
            shareDiagnosticsDigestCalls += 1
        }

        override fun startTestConnectionFromBridge() {
            startTestConnectionCalls += 1
        }

        override fun scheduleAdapterReadyNotifyFromBridge(mins: Int) {
            adapterReadyNotifyMinutes += mins
        }

        override fun cancelAdapterReadyNotifyFromBridge() {
            cancelAdapterReadyNotifyCalls += 1
        }

        override fun openSetupGuideFromBridge() = Unit

        private fun <T> unsupported(label: String): T = throw UnsupportedOperationException(label)
    }

    private companion object {
        private const val ADAPTER_ADDRESS = "AA:BB:CC:DD:EE:FF"
    }

    private data class StartedService(
        val action: String?,
        val address: String?,
        val name: String?,
        val detailStage: String?,
    )

    private data class RememberedDevice(
        val address: String?,
        val name: String?,
    )
}
