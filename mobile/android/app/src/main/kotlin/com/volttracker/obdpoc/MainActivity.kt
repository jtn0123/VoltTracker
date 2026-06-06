package com.volttracker.obdpoc

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.volttracker.obdpoc.data.ObdLocalStore
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

open class MainActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var dashboardPublisher: DashboardPublisher? = null

    // System Back: give the dashboard SPA first crack at it (close a modal, exit fullscreen, or
    // return to the Drive home tab) before the OS backgrounds/exits the app. evaluateJavascript is
    // async, so we always consume the press, then re-dispatch the default Back only if the
    // dashboard reports it had nothing to dismiss.
    private val backCallback =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (wv == null) {
                    exitToBackground()
                    return
                }
                wv.evaluateJavascript(
                    "(window.VoltDashboard && typeof VoltDashboard.handleAndroidBack === 'function')" +
                        " ? !!VoltDashboard.handleAndroidBack() : false",
                ) { result ->
                    if (result != "true") {
                        exitToBackground()
                    }
                }
            }
        }

    // Default Back when the dashboard has nothing to dismiss. evaluateJavascript is async so the
    // press is already consumed by the time we decide; onBackPressedDispatcher.onBackPressed()
    // only re-runs registered callbacks (it does NOT perform the OS finish), so we background the
    // app ourselves. moveTaskToBack keeps the live Activity/WebView and state alive (like Home);
    // finish() is the fallback if this somehow isn't the task root.
    private fun exitToBackground() {
        if (!moveTaskToBack(true)) {
            finish()
        }
    }

    private var prefs: SharedPreferences? = null

    @JvmField var deviceCatalog: DeviceCatalog? = null

    @JvmField var dataBackup: DataBackup? = null

    @JvmField var backupController: BackupController? = null

    @JvmField var permissionGate: PermissionGate? = null

    @JvmField var localStore: ObdLocalStore? = null

    @JvmField var troubleshooter: TroubleshooterBridge? = null

    private var restoreFilePicker: ActivityResultLauncher<Intent>? = null
    private var permissionRequester: ActivityResultLauncher<Array<String>>? = null
    private var lastTelemetry = JSONObject()
    private var lastStatus = JSONObject()
    private var lastStorage = JSONObject()
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val broadcastReceivers = BroadcastReceiverGroup()
    private val storageSummaryInFlight = AtomicBoolean(false)
    private val storageSummaryQueued = AtomicBoolean(false)
    private val lastStorageSummaryAtMs = AtomicLong(0L)
    private val storageSummaryDirty = AtomicBoolean(true)

    open fun runOnBackground(task: Runnable) {
        submitBackground(task)
    }

    fun requireDeviceCatalog(): DeviceCatalog = checkNotNull(deviceCatalog) { "DeviceCatalog is not ready" }

    fun requireDataBackup(): DataBackup = checkNotNull(dataBackup) { "DataBackup is not ready" }

    fun requireBackupController(): BackupController = checkNotNull(backupController) { "BackupController is not ready" }

    fun requirePermissionGate(): PermissionGate = checkNotNull(permissionGate) { "PermissionGate is not ready" }

    fun requireTroubleshooter(): TroubleshooterBridge = checkNotNull(troubleshooter) { "TroubleshooterBridge is not ready" }

    private fun submitBackground(task: Runnable) {
        try {
            backgroundExecutor.execute(task)
        } catch (ex: RejectedExecutionException) {
            Log.d(TAG, "background task dropped; executor is shut down (activity tearing down)", ex)
        }
    }

    private val obdReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                val action = intent.action
                val json = intent.getStringExtra(ObdService.EXTRA_JSON) ?: "{}"
                if (ObdService.BROADCAST_TELEMETRY == action) {
                    lastTelemetry = MainActivityUtils.parseJson(json)
                    markStorageSummaryDirty()
                    callDashboard("updateTelemetry", json)
                    publishAppState()
                } else if (ObdService.BROADCAST_STATUS == action) {
                    lastStatus = MainActivityUtils.parseJson(json)
                    callDashboard("setStatus", json)
                    if ("idle" == lastStatus.optString("state", "")) {
                        publishStorageSummary()
                    } else {
                        publishStorageSummaryThrottled()
                    }
                    publishAppState()
                    onAdapterStatusForReadyNotify(lastStatus)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreFilePicker =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult(), this::onRestoreFilePicked)
        permissionRequester =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionsResult() }
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val activityPrefs = checkNotNull(prefs)
        deviceCatalog = DeviceCatalog(this, activityPrefs)
        dataBackup = DataBackup(this)
        backupController = BackupController(this, requireDataBackup(), backgroundExecutor)
        permissionGate = PermissionGate(this, ::launchPermissionRequest)
        localStore = ObdLocalStore(this)
        troubleshooter = TroubleshooterBridge(this)
        ObdNotifications.ensureChannel(this)
        submitBackground {
            try {
                val retentionDays =
                    activityPrefs.getInt("raw_retention_days", ObdLocalStore.DEFAULT_RAW_RETENTION_DAYS)
                val pruned = localStore?.pruneRawDataOlderThan(retentionDays) ?: 0
                if (pruned > 0) {
                    markStorageSummaryDirty()
                    Log.i(TAG, "Pruned $pruned raw rows older than $retentionDays days")
                }
            } catch (ex: RuntimeException) {
                Log.w(TAG, "Retention prune failed; continuing without it", ex)
            }
        }

        val createdWebView = WebView(this)
        webView = createdWebView
        createdWebView.layoutParams =
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(createdWebView)

        ViewCompat.setOnApplyWindowInsetsListener(createdWebView) { view, windowInsets ->
            val bars =
                windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            windowInsets
        }
        ViewCompat.requestApplyInsets(createdWebView)

        dashboardPublisher =
            DashboardPublisher(createdWebView, { !isFinishing && !isDestroyed }) { command ->
                runOnUiThread(command)
            }
        WebViewBootstrap.configure(createdWebView, VoltBridge(this))

        onBackPressedDispatcher.addCallback(this, backCallback)
    }

    open fun onDashboardReady() {
        Log.i(TAG, "dashboard handshake received: JS is live")
        val publisher = dashboardPublisher ?: return
        if (publisher.isPageReady()) {
            return
        }
        publisher.setPageReady(true)
        publishDeviceList()
        publishStorageSummary()
        publishAppState()
        publishStatus("ready", "Pick a paired OBD adapter to start logging.", false)
    }

    fun isDashboardReadyForTest(): Boolean = dashboardPublisher?.isPageReady() == true

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter()
        filter.addAction(ObdService.BROADCAST_TELEMETRY)
        filter.addAction(ObdService.BROADCAST_STATUS)
        broadcastReceivers.register(this, obdReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        publishDeviceList()
        publishStorageSummary()
        publishAppState()
        reportAppVisibility(true)
    }

    override fun onPause() {
        reportAppVisibility(false)
        super.onPause()
        broadcastReceivers.unregisterAll(this)
    }

    override fun onDestroy() {
        backgroundExecutor.shutdownNow()
        troubleshooter?.shutdown()
        localStore?.close()
        localStore = null
        // Tear the WebView down explicitly. It holds the VoltBridge JS interface, which keeps a
        // strong reference back to this Activity; without destroy()/removeJavascriptInterface the
        // WebView (and the whole Activity graph + native chromium resources) leaks every time the
        // Activity is torn down, and the orphaned page's JS timers/console callbacks keep firing.
        webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.removeJavascriptInterface("VoltTrackerAndroid")
            wv.stopLoading()
            wv.destroy()
        }
        webView = null
        dashboardPublisher = null
        super.onDestroy()
    }

    /** Launches the runtime-permission request via the Activity Result API. Overridable in tests. */
    open fun launchPermissionRequest(permissions: Array<String>) {
        permissionRequester?.launch(permissions)
    }

    /**
     * Reacts to a runtime-permission result. The decision re-reads the live grant state through
     * [PermissionGate] (not the result map), so it stays correct regardless of how the request was
     * delivered. Invoked by the [ActivityResultContracts.RequestMultiplePermissions] callback.
     */
    open fun onPermissionsResult() {
        publishDeviceList()
        val gate = requirePermissionGate()
        if (!gate.hasBluetoothConnect()) {
            publishStatus("blocked", "Bluetooth permission is required to talk to the OBD adapter.", true)
        } else if (!gate.hasLocation()) {
            publishStatus("ready", "Bluetooth permission granted. Location is still off, so trips may not show a route.", false)
        } else if (!gate.hasNotifications()) {
            publishStatus(
                "ready",
                "Bluetooth permission granted. Notifications are still off, so background logging may be quieter.",
                false,
            )
        } else {
            publishStatus("ready", "Bluetooth permission granted. Pick a paired adapter.", false)
        }
    }

    open fun publishDeviceList() {
        val catalog = requireDeviceCatalog()
        callDashboard("setDevices", catalog.getBondedDevicesJson())
        callDashboard("setHistory", catalog.getDeviceHistoryJson())
    }

    open fun publishStatus(
        state: String?,
        detail: String?,
        blocked: Boolean,
    ) {
        val catalog = requireDeviceCatalog()
        val payload = JSONObject()
        try {
            payload.put("state", state)
            payload.put("detail", detail)
            payload.put("blocked", blocked)
            payload.put("bluetoothReady", isBluetoothReady())
            payload.put("lastAddress", catalog.lastAddress())
            payload.put("lastName", catalog.lastName())
        } catch (ignored: JSONException) {
            // Values are local literals.
        }
        callDashboard("setStatus", payload.toString())
        lastStatus = payload
        publishAppState()
    }

    private fun isBluetoothReady(): Boolean {
        val adapter = BluetoothAdapters.get(this)
        return adapter != null && adapter.isEnabled && requireDeviceCatalog().hasBluetoothConnectPermission()
    }

    @SuppressLint("MissingPermission")
    open fun startObdService(
        action: String?,
        address: String?,
        name: String?,
    ) {
        startObdService(action, address, name, null)
    }

    @SuppressLint("MissingPermission")
    open fun startObdService(
        action: String?,
        address: String?,
        name: String?,
        detailStage: String?,
    ) {
        // The demo session is a synthetic telemetry loop (ObdService.startDemoSession runs with a
        // null address and no Bluetooth socket), so it must NOT be gated behind Bluetooth
        // permission/adapter/enabled checks. Gating it forced a fresh user who taps "Demo" to
        // preview the app into a Bluetooth permission prompt or a "Turn on Bluetooth" block for a
        // feature that never touches Bluetooth.
        val isDemo = action == ObdService.ACTION_DEMO
        if (!isDemo) {
            if (!requirePermissionGate().ensureConnectPermissions()) {
                publishStatus("blocked", "Grant Bluetooth permission, then connect again.", true)
                return
            }
            val adapter = BluetoothAdapters.get(this)
            if (adapter == null) {
                publishStatus("blocked", "This phone does not report Bluetooth support.", true)
                return
            }
            if (!adapter.isEnabled) {
                publishStatus("blocked", "Turn on Bluetooth to connect to the OBD adapter.", true)
                try {
                    startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (ignored: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    } catch (settingsIgnored: Exception) {
                        publishStatus("blocked", "Open Android Bluetooth settings, then try again.", true)
                    }
                }
                return
            }
        }

        troubleshooter?.clearPendingTestConnectionStop()

        val service = Intent(this, ObdService::class.java)
        service.action = action
        if (address != null) {
            service.putExtra(ObdService.EXTRA_ADDRESS, address)
        }
        if (name != null) {
            service.putExtra(ObdService.EXTRA_NAME, name)
        }
        if (detailStage != null) {
            service.putExtra(ObdService.EXTRA_DETAIL_STAGE, detailStage)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service)
        } else {
            startService(service)
        }
    }

    open fun rememberDevice(
        address: String?,
        name: String?,
    ) {
        val catalog = requireDeviceCatalog()
        val cleanAddress = catalog.remember(address, name)
        if (cleanAddress.isEmpty()) {
            return
        }
        val cleanName = name?.trim() ?: ""
        try {
            localStore?.recordAdapterSummary(
                cleanAddress,
                cleanName,
                ObdLocalStore.MODE_OBD,
                0L,
                "remembered",
                0,
                "",
                "Remembered adapter",
            )
        } catch (ex: RuntimeException) {
            Log.w(TAG, "recordAdapterSummary failed for ${MainActivityUtils.redactAddress(cleanAddress)}", ex)
        }
        publishDeviceList()
        publishStorageSummary()
        publishStatus("ready", "Remembered ${if (cleanName.isEmpty()) cleanAddress else cleanName}.", false)
    }

    open fun stopObdService() {
        val service = Intent(this, ObdService::class.java)
        service.action = ObdService.ACTION_DISCONNECT
        try {
            startService(service)
        } catch (ignored: IllegalStateException) {
            publishStatus("ready", "Stop request noted; reopen the app if logging is still active.", false)
        }
    }

    open fun forceStopPackageFromBridge(packageName: String?): Boolean = requireTroubleshooter().forceStopPackage(packageName)

    open fun cancelRetryFromBridge() {
        requireTroubleshooter().cancelRetry()
    }

    open fun openBluetoothSettingsFromBridge() {
        requireTroubleshooter().openBluetoothSettings()
    }

    open fun getRecentSessionsJson(n: Int): String = requireTroubleshooter().getRecentSessionsJson(n)

    open fun shareDiagnosticsFromBridge() {
        requireTroubleshooter().shareDiagnostics()
    }

    open fun startTestConnectionFromBridge() {
        requireTroubleshooter().startTestConnection()
    }

    open fun scheduleAdapterReadyNotifyFromBridge(mins: Int) {
        requireTroubleshooter().scheduleAdapterReadyNotify(mins)
    }

    open fun cancelAdapterReadyNotifyFromBridge() {
        requireTroubleshooter().cancelAdapterReadyNotify()
    }

    open fun onAdapterStatusForReadyNotify(status: JSONObject?) {
        requireTroubleshooter().onAdapterStatusForReadyNotify(status)
    }

    private fun reportAppVisibility(foreground: Boolean) {
        val service = Intent(this, ObdService::class.java)
        service.action = if (foreground) ObdService.ACTION_APP_FOREGROUND else ObdService.ACTION_APP_BACKGROUND
        try {
            startService(service)
        } catch (ignored: IllegalStateException) {
            // Visibility is diagnostic only.
        }
    }

    private fun callDashboard(
        functionName: String,
        jsonPayload: String?,
    ) {
        dashboardPublisher?.publish(functionName, jsonPayload)
    }

    open fun publishStorageSummaryThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastStorageSummaryAtMs.get() < STORAGE_SUMMARY_MIN_INTERVAL_MS) {
            return
        }
        if (!storageSummaryDirty.get()) {
            return
        }
        publishStorageSummary()
    }

    open fun markStorageSummaryDirty() {
        storageSummaryDirty.set(true)
    }

    open fun publishStorageSummary() {
        if (!storageSummaryInFlight.compareAndSet(false, true)) {
            storageSummaryQueued.set(true)
            return
        }
        runStorageSummaryRefresh()
    }

    private fun runStorageSummaryRefresh() {
        submitBackground {
            storageSummaryDirty.set(false)
            val storage = getStorageSummaryJson()
            if (!MainActivityUtils.parseJson(storage).optBoolean("ok", true)) {
                storageSummaryDirty.set(true)
            }
            lastStorageSummaryAtMs.set(System.currentTimeMillis())
            runOnUiThread {
                lastStorage = MainActivityUtils.parseJson(storage)
                callDashboard("setStorage", storage)
            }
            storageSummaryInFlight.set(false)
            if (storageSummaryQueued.getAndSet(false)) {
                publishStorageSummary()
            }
        }
    }

    open fun getStorageSummaryJson(): String {
        val store = localStore ?: return MainActivityUtils.errorPayload("storage_unavailable", "Local storage is not ready yet.").toString()
        return try {
            StorageSummaryJson.build(store.getStorageSummaryRecord()).toString()
        } catch (ex: RuntimeException) {
            Log.w(TAG, "getStorageSummary failed", ex)
            MainActivityUtils.errorPayload("storage_summary_failed", "Could not read local storage summary.").toString()
        }
    }

    open fun getTripsJson(): String {
        val store = localStore ?: return MainActivityUtils.errorPayload("storage_unavailable", "Local storage is not ready yet.").toString()
        return try {
            store.getTripsJson(40).toString()
        } catch (ex: RuntimeException) {
            Log.w(TAG, "getTripsJson failed", ex)
            MainActivityUtils.errorPayload("trips_read_failed", "Could not read logged trips.").toString()
        }
    }

    open fun getTripRouteJson(sessionId: Long): String = getTripRouteJson(sessionId.toString())

    open fun getTripRouteJson(routeKey: String?): String {
        val store = localStore ?: return MainActivityUtils.errorPayload("storage_unavailable", "Local storage is not ready yet.").toString()
        return try {
            store.getTripRouteJson(routeKey).toString()
        } catch (ex: RuntimeException) {
            Log.w(TAG, "getTripRouteJson failed", ex)
            MainActivityUtils.errorPayload("trip_route_read_failed", "Could not read the trip route.").toString()
        }
    }

    open fun getInsightsJson(): String {
        val store = localStore ?: return MainActivityUtils.errorPayload("storage_unavailable", "Local storage is not ready yet.").toString()
        return try {
            store.getInsightsJson().toString()
        } catch (ex: RuntimeException) {
            Log.w(TAG, "getInsightsJson failed", ex)
            MainActivityUtils.errorPayload("insights_read_failed", "Could not read vehicle insights.").toString()
        }
    }

    open fun launchRestoreFilePicker(intent: Intent) {
        restoreFilePicker?.launch(intent)
    }

    private fun onRestoreFilePicked(result: ActivityResult) {
        backupController?.onRestorePickerResult(result.resultCode, result.data)
    }

    open fun isLoggingActive(): Boolean =
        MainActivityUtils.isConnectedState(lastStatus.optString("state", "")) || ObdService.hasActiveSession()

    private fun publishAppState() {
        callDashboard("setAppState", getAppStateJson())
    }

    open fun getAppStateJson(): String {
        val catalog = requireDeviceCatalog()
        val gate = requirePermissionGate()
        return AppStateJson.build(
            appVersionName(),
            isBluetoothReady(),
            gate.hasLocation(),
            gate.hasNotifications(),
            catalog.lastAddress(),
            catalog.lastName(),
            lastTelemetry,
            lastStatus,
            lastStorage,
        )
    }

    private fun appVersionName(): String =
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (ex: PackageManager.NameNotFoundException) {
            ""
        }

    companion object {
        const val TAG = "VoltTracker"
        private const val PREFS = "volt_obd_prefs"
        private const val STORAGE_SUMMARY_MIN_INTERVAL_MS = 10_000L
    }
}
