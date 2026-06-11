package com.volttracker.obdpoc

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.volttracker.obdpoc.data.ObdLocalStore
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

open class MainActivity :
    ComponentActivity(),
    DashboardHost {
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

    // Threading contract: written on the UI thread (onCreate/onDestroy) and on the backup
    // executor during a Replace restore (BackupController.applyReplace closes the store, nulls
    // this, then reopens the same DB file — close-then-open is unavoidable because both stores
    // would target one SQLite file). @Volatile keeps JS-bridge readers from seeing a stale
    // reference; a reader that still races the brief null/closed window is covered by the
    // existing null checks and RuntimeException handling around store reads.
    @Volatile override var localStore: ObdLocalStore? = null

    @JvmField var troubleshooter: TroubleshooterBridge? = null

    private var autoConnectController: AutoConnectController? = null
    private var restoreFilePicker: ActivityResultLauncher<Intent>? = null
    private var permissionRequester: ActivityResultLauncher<Array<String>>? = null

    /** Connect/scan request parked while Android shows the Bluetooth permission prompt. */
    private data class PendingObdStart(
        val action: String?,
        val address: String?,
        val name: String?,
        val detailStage: String?,
    ) {
        fun label(): String =
            name?.takeIf { it.isNotBlank() } ?: address?.takeIf { it.isNotBlank() } ?: "the OBD adapter"
    }

    private var pendingConnectStart: PendingObdStart? = null

    // Threading contract: these payload snapshots are REPLACED wholesale on the UI thread and
    // never mutated in place. @Volatile makes each swapped-in JSONObject safely publishable to
    // the WebView JavaBridge thread (exportDebugBundle/getAppStateJson/isLoggingActive read them
    // off the UI thread). Anyone adding a `.put(...)` on these after assignment must instead
    // build a fresh object and reassign.
    @Volatile private var lastTelemetry = JSONObject()

    @Volatile private var lastStatus = JSONObject()

    @Volatile private var lastStorage = JSONObject()
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val storageReader = DashboardStorageReader { localStore }
    private val broadcastCoordinator =
        DashboardBroadcastCoordinator(
            onTelemetryJson = { json -> onTelemetryBroadcast(json) },
            onStatusJson = { json -> onStatusBroadcast(json) },
            onAutoConnectTrigger = { trigger, address -> maybeAutoConnect(trigger, address) },
            bluetoothDeviceAddress = { intent -> bluetoothDeviceAddress(intent) },
        )
    private val storageSummaryPublisher =
        StorageSummaryPublisher(
            submitBackground = { task -> submitBackground(task) },
            runOnUi = { task -> runOnUiThread(task) },
            readStorageJson = { getStorageSummaryJson() },
            publishStorageJson = { storage, parsed ->
                lastStorage = parsed
                callDashboard("setStorage", storage)
            },
        )

    override fun runOnBackground(task: Runnable) {
        submitBackground(task)
    }

    override fun requireDeviceCatalog(): DeviceCatalog = checkNotNull(deviceCatalog) { "DeviceCatalog is not ready" }

    override fun requireDataBackup(): DataBackup = checkNotNull(dataBackup) { "DataBackup is not ready" }

    override fun requireBackupController(): BackupController =
        checkNotNull(backupController) {
            "BackupController is not ready"
        }

    override fun requirePermissionGate(): PermissionGate =
        checkNotNull(permissionGate) { "PermissionGate is not ready" }

    fun requireTroubleshooter(): TroubleshooterBridge =
        checkNotNull(troubleshooter) { "TroubleshooterBridge is not ready" }

    private fun onTelemetryBroadcast(json: String) {
        lastTelemetry = MainActivityUtils.parseJson(json)
        markStorageSummaryDirty()
        callDashboard("updateTelemetry", json)
        publishAppState()
    }

    private fun onStatusBroadcast(json: String) {
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

    private fun submitBackground(task: Runnable) {
        try {
            backgroundExecutor.execute(task)
        } catch (ex: RejectedExecutionException) {
            Log.d(TAG, "background task dropped; executor is shut down (activity tearing down)", ex)
        }
    }

    override fun confirmBridgeAction(
        title: String,
        message: String,
        positiveLabel: String,
        onConfirmed: Runnable,
    ) {
        runOnUiThread {
            try {
                AlertDialog
                    .Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(positiveLabel) { _, _ -> onConfirmed.run() }
                    .setNegativeButton("Cancel") { _, _ ->
                        publishStatus("ready", "Action cancelled.", false)
                    }.setOnCancelListener {
                        publishStatus("ready", "Action cancelled.", false)
                    }.show()
            } catch (ex: RuntimeException) {
                Log.w(TAG, "bridge confirmation failed", ex)
                publishStatus("blocked", "Could not show the Android confirmation.", true)
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
        autoConnectController = AutoConnectController(activityPrefs, requireDeviceCatalog())
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

    override fun onDashboardReady() {
        Log.i(TAG, "$DASHBOARD_READY_LOG: JS is live")
        val publisher = dashboardPublisher ?: return
        if (publisher.isPageReady()) {
            return
        }
        publisher.setPageReady(true)
        publishDeviceList()
        publishStorageSummary()
        publishAppState()
        if (isLoggingActive()) {
            callDashboard("setStatus", lastStatus.toString())
        } else {
            publishStatus("ready", "Viewing local data. Connect only when you want live OBD logging.", false)
        }
        maybeAutoConnect(AutoConnectController.TRIGGER_DASHBOARD_READY, null)
    }

    fun isDashboardReadyForTest(): Boolean = dashboardPublisher?.isPageReady() == true

    override fun onResume() {
        super.onResume()
        broadcastCoordinator.register(this)
        publishDeviceList()
        publishStorageSummary()
        publishAppState()
        reportAppVisibility(true)
        maybeAutoConnect(AutoConnectController.TRIGGER_APP_RESUME, null)
    }

    override fun onPause() {
        reportAppVisibility(false)
        super.onPause()
        broadcastCoordinator.unregisterAll(this)
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
        autoConnectController = null
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
        val pending = pendingConnectStart
        pendingConnectStart = null
        if (!gate.hasBluetoothConnect()) {
            publishBluetoothPermissionDenied(wasConnecting = pending != null)
        } else if (pending != null) {
            // The user already asked for this connection and the permission was the only blocker,
            // so continue automatically instead of bouncing them back to the Connect button.
            publishStatus(
                "connecting",
                "Bluetooth permission granted. Connecting to ${pending.label()}...",
                false,
            )
            startObdService(pending.action, pending.address, pending.name, pending.detailStage)
        } else if (!gate.hasLocation()) {
            publishStatus(
                "ready",
                "Bluetooth permission granted. Location is still off, so trips may not show a route.",
                false,
            )
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

    /**
     * Explains a denied Bluetooth permission with a recovery path. Once Android suppresses the
     * prompt (repeated denials), the only fix left is the app-settings screen — open it when the
     * denial interrupted an active connect attempt so the user can finish in one round trip.
     */
    private fun publishBluetoothPermissionDenied(wasConnecting: Boolean) {
        if (canAskForBluetoothConnectAgain()) {
            publishStatus(
                "blocked",
                "Bluetooth permission was denied, so the OBD adapter can't be reached. " +
                    "Tap Connect to try again and choose Allow.",
                true,
            )
            return
        }
        val openedSettings = wasConnecting && openAppPermissionSettings()
        publishStatus(
            "blocked",
            if (openedSettings) {
                "Android is no longer showing the Bluetooth prompt. In the app settings screen that " +
                    "just opened, choose Permissions > Nearby devices > Allow, then come back and tap Connect."
            } else {
                "Bluetooth permission is off for Volt Tracker. Open Android Settings > Apps > " +
                    "Volt Tracker > Permissions and allow Nearby devices, then tap Connect again."
            },
            true,
        )
    }

    /** True while Android would still show the Bluetooth permission prompt. Overridable in tests. */
    protected open fun canAskForBluetoothConnectAgain(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)

    /** Opens this app's Android settings page so the user can re-enable "Nearby devices". */
    protected open fun openAppPermissionSettings(): Boolean =
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ),
            )
            true
        } catch (ex: RuntimeException) {
            Log.w(TAG, "Could not open app settings for the Bluetooth permission", ex)
            false
        }

    override fun publishDeviceList() {
        val catalog = requireDeviceCatalog()
        callDashboard("setDevices", catalog.getBondedDevicesJson())
        callDashboard("setHistory", catalog.getDeviceHistoryJson())
    }

    override fun publishStatus(
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
    ) {
        val payload = JSONObject()
        try {
            payload.put("visible", visible)
            payload.put("busy", busy)
            payload.put("title", title)
            payload.put("detail", detail)
            payload.put("tone", tone)
            if (!phase.isNullOrBlank()) {
                payload.put("phase", phase)
            }
            if (bytesDone >= 0L) {
                payload.put("bytesDone", bytesDone)
            }
            if (bytesTotal >= 0L) {
                payload.put("bytesTotal", bytesTotal)
            }
            if (rowsDone >= 0L) {
                payload.put("rowsDone", rowsDone)
            }
            if (rowsTotal >= 0L) {
                payload.put("rowsTotal", rowsTotal)
            }
            if (percent >= 0) {
                payload.put("percent", percent.coerceIn(0, 100))
            }
            if (etaSeconds >= 0L) {
                payload.put("etaSeconds", etaSeconds)
            }
        } catch (ignored: JSONException) {
            // Values are local literals.
        }
        callDashboard("setRestoreProgress", payload.toString())
    }

    private fun isBluetoothReady(): Boolean {
        val adapter = BluetoothAdapters.get(this)
        return adapter != null && adapter.isEnabled && requireDeviceCatalog().hasBluetoothConnectPermission()
    }

    @SuppressLint("MissingPermission")
    override fun startObdService(
        action: String?,
        address: String?,
        name: String?,
    ) {
        startObdService(action, address, name, null)
    }

    @SuppressLint("MissingPermission")
    override fun startObdService(
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
                // ensureConnectPermissions just launched the system prompt; park the request so
                // onPermissionsResult can finish the connection instead of dead-ending the tap.
                pendingConnectStart = PendingObdStart(action, address, name, detailStage)
                publishStatus(
                    "blocked",
                    "Waiting for Bluetooth permission. Allow \"Nearby devices\" in the Android " +
                        "prompt and the connection will continue.",
                    true,
                )
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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service)
            } else {
                startService(service)
            }
        } catch (ex: RuntimeException) {
            Log.w(TAG, "startObdService blocked", ex)
            troubleshooter?.clearPendingTestConnectionStop()
            publishStatus(
                "blocked",
                "Android blocked OBD logging startup. Check permissions and try again.",
                true,
            )
        }
    }

    override fun rememberDevice(
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

    override fun stopObdService() {
        val service = Intent(this, ObdService::class.java)
        service.action = ObdService.ACTION_DISCONNECT
        try {
            startService(service)
        } catch (ignored: IllegalStateException) {
            publishStatus("ready", "Stop request noted; reopen the app if logging is still active.", false)
        }
    }

    override fun forceStopPackageFromBridge(packageName: String?): Boolean =
        requireTroubleshooter().forceStopPackage(packageName)

    override fun cancelRetryFromBridge() {
        requireTroubleshooter().cancelRetry()
    }

    override fun openBluetoothSettingsFromBridge() {
        requireTroubleshooter().openBluetoothSettings()
    }

    override fun getRecentSessionsJson(n: Int): String = requireTroubleshooter().getRecentSessionsJson(n)

    override fun shareDiagnosticsFromBridge() {
        requireTroubleshooter().shareDiagnostics()
    }

    override fun startTestConnectionFromBridge() {
        requireTroubleshooter().startTestConnection()
    }

    override fun scheduleAdapterReadyNotifyFromBridge(mins: Int) {
        requireTroubleshooter().scheduleAdapterReadyNotify(mins)
    }

    override fun cancelAdapterReadyNotifyFromBridge() {
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
        storageSummaryPublisher.publishThrottled()
    }

    open fun markStorageSummaryDirty() {
        storageSummaryPublisher.markDirty()
    }

    override fun publishStorageSummary() {
        storageSummaryPublisher.publish()
    }

    override fun getStorageSummaryJson(): String = storageReader.storageSummaryJson()

    override fun getTripsJson(): String = storageReader.tripsJson()

    open fun getTripRouteJson(sessionId: Long): String = getTripRouteJson(sessionId.toString())

    override fun getTripRouteJson(routeKey: String?): String = storageReader.tripRouteJson(routeKey)

    override fun getInsightsJson(): String = storageReader.insightsJson()

    open fun launchRestoreFilePicker(intent: Intent) {
        restoreFilePicker?.launch(intent)
    }

    private fun onRestoreFilePicked(result: ActivityResult) {
        backupController?.onRestorePickerResult(result.resultCode, result.data)
    }

    override fun isLoggingActive(): Boolean =
        MainActivityUtils.isConnectedState(lastStatus.optString("state", "")) || ObdService.hasActiveSession()

    private fun publishAppState() {
        callDashboard("setAppState", getAppStateJson())
    }

    override fun getAppStateJson(): String {
        val catalog = requireDeviceCatalog()
        val gate = requirePermissionGate()
        return AppStateJson.build(
            appVersionName(),
            isBluetoothReady(),
            gate.hasBluetoothConnect(),
            BluetoothAdapters.get(this)?.isEnabled == true,
            gate.hasLocation(),
            gate.hasNotifications(),
            catalog.lastAddress(),
            catalog.lastName(),
            lastTelemetry,
            lastStatus,
            lastStorage,
        )
    }

    override fun getAutoConnectStateJson(): String =
        autoConnectController?.stateJson()
            ?: MainActivityUtils.errorPayload("auto_connect_unavailable", "Auto-connect is not ready.").toString()

    override fun setAutoConnectEnabledFromBridge(enabled: Boolean) {
        val controller = autoConnectController
        if (controller == null) {
            publishStatus("blocked", "Auto-connect is not ready yet.", true)
            return
        }
        controller.setEnabled(enabled)
        if (enabled) {
            publishStatus(
                "ready",
                "Auto-connect enabled. Volt Tracker will use the last adapter when the app sees it.",
                false,
            )
            maybeAutoConnect(AutoConnectController.TRIGGER_USER_ENABLED, null)
        } else {
            publishStatus("ready", "Auto-connect disabled.", false)
        }
        publishAppState()
    }

    private fun maybeAutoConnect(
        trigger: String,
        observedAddress: String?,
    ): Boolean {
        val controller = autoConnectController ?: return false
        return controller.maybeConnect(
            trigger,
            observedAddress,
            isBluetoothReady(),
            isLoggingActive(),
            { address, name -> startObdService(ObdService.ACTION_CONNECT, address, name) },
            { state, detail, blocked -> publishStatus(state, detail, blocked) },
        )
    }

    @SuppressLint("MissingPermission")
    private fun bluetoothDeviceAddress(intent: Intent): String {
        val device: BluetoothDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
        return try {
            device?.address ?: ""
        } catch (ex: SecurityException) {
            Log.w(TAG, "Bluetooth ACL device address read denied", ex)
            ""
        }
    }

    private fun appVersionName(): String =
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (ex: PackageManager.NameNotFoundException) {
            ""
        }

    companion object {
        const val TAG = "VoltTracker"

        /**
         * Prefix of the JS->native handshake log line emitted by [onDashboardReady]. This is the
         * entire positive signal of `scripts/emulator-smoke.sh`, which greps logcat for it to prove
         * the dashboard JS came alive. Renaming it on either side would make the smoke pass while
         * testing nothing, so `EmulatorSmokeContractTest` asserts the script references this exact
         * constant. Keep the emitted line's prefix unchanged so the live smoke still matches.
         */
        const val DASHBOARD_READY_LOG = "dashboard handshake received"
        private const val PREFS = "volt_obd_prefs"
    }
}
