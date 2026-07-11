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
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.volttracker.obdpoc.data.ObdLocalStore
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

open class MainActivity :
    ComponentActivity(),
    DashboardHost {
    private var webView: WebView? = null
    private var dashboardPublisher: DashboardPublisher? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val publishAppStateCommand: () -> Unit = { callDashboard("setAppState", getAppStateJson()) }
    private var isActivityResumed = false
    private var postReadyDashboardRefreshPending = false
    private val postReadyDashboardRefreshRunnable =
        Runnable {
            if (!postReadyDashboardRefreshPending || !isActivityResumed || isFinishing || isDestroyed) {
                return@Runnable
            }
            postReadyDashboardRefreshPending = false
            publishPostReadyDashboardRefresh()
        }

    // System Back handling lives in DashboardBackPressCallback; see its KDoc.
    private val backCallback =
        DashboardBackPressCallback(
            { webView },
            {
                if (!moveTaskToBack(true)) finish()
            },
        )

    // Default Back when the dashboard has nothing to dismiss. evaluateJavascript is async so the
    // press is already consumed by the time we decide; onBackPressedDispatcher.onBackPressed()
    // only re-runs registered callbacks (it does NOT perform the OS finish), so we background the
    // app ourselves. moveTaskToBack keeps the live Activity/WebView and state alive (like Home);
    // finish() is the fallback if this somehow isn't the task root.

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

    // Per-trip GPX/CSV export orchestration (read route -> write cache file -> record export ->
    // share). Lazily built so it survives the test seam that skips super.onCreate(); holds the body
    // off this class surface, mirroring EventNotificationHostDelegate.
    private val tripExportController by lazy { TripExportController(applicationContext, this) }

    // App-open maintenance-overdue check (M2). Reads the maintenance log + latest odometer, runs the
    // pure evaluator, and posts via EventNotifier; the body lives on the notifier so this Activity
    // adds a single onResume() call. Lazy so it survives the test seam that skips super.onCreate(),
    // and because eventNotificationPrefs/localStore only exist after onCreate.
    private val maintenanceDueNotifier by lazy {
        val notifier = EventNotifier(applicationContext)
        MaintenanceDueNotifier(
            isEnabled = { eventNotificationPrefs?.maintenanceDueEnabled() == true },
            readMaintenanceLogJson = {
                localStore?.maintenanceLog?.getMaintenanceLogJson(MaintenanceDueNotifier.MAX_MAINTENANCE_ROWS)
            },
            readLatestOdometerKm = { localStore?.projections()?.latestOdometerKm() },
            readNotifiedSignatures = { eventNotificationPrefs?.notifiedMaintenanceSignatures() ?: emptySet() },
            persistNotifiedSignatures = { eventNotificationPrefs?.setNotifiedMaintenanceSignatures(it) },
            notify = { event -> notifier.notify(event) },
        )
    }

    private var autoConnectController: AutoConnectController? = null
    private var eventNotificationPrefs: EventNotificationPrefs? = null
    private var restoredDashboardView: String? = null
    private val dashboardExperienceHost by lazy {
        DashboardExperienceHostDelegate(
            activity = this,
            prefs = { prefs },
            loggingActive = { isLoggingActive() },
            publishAppState = publishAppStateCommand,
            hasNotificationPermission = { permissionGate?.hasNotifications() == true },
            ensureNotificationPermission = { requirePermissionGate().ensureNotifications() },
        )
    }

    // Backs the EventNotificationCommands seam (M1/M3 settings). Kept off the Activity body so the
    // six toggle handlers don't swell MainActivity's surface; reads the prefs lazily because they
    // only exist after onCreate.
    private val eventNotificationHost =
        EventNotificationHostDelegate(
            prefs = { eventNotificationPrefs },
            publishStatus = { state, detail, blocked -> publishStatus(state, detail, blocked) },
            publishAppState = publishAppStateCommand,
            notReadyMessage = { getString(R.string.status_event_prefs_not_ready) },
            hasNotificationPermission = { permissionGate?.hasNotifications() == true },
            ensureNotificationPermission = { requirePermissionGate().ensureNotifications() },
        )
    private var restoreFilePicker: ActivityResultLauncher<Intent>? = null
    private var permissionRequester: ActivityResultLauncher<Array<String>>? = null

    // Connect-attempt / runtime-permission handshake (parked connects, denial messaging). The
    // lambdas dispatch through the open methods so test subclasses keep their overrides.
    private val connectPermissionFlow =
        ConnectPermissionFlow(
            context = this,
            permissionGate = { requirePermissionGate() },
            publishStatus = { state, detail, blocked -> publishStatus(state, detail, blocked) },
            publishDeviceList = { publishDeviceList() },
            startObdService = { action, address, name, stage -> startObdService(action, address, name, stage) },
            canAskForBluetoothConnectAgain = { canAskForBluetoothConnectAgain() },
            openAppPermissionSettings = { openAppPermissionSettings() },
        )

    // Threading contract: written on the UI thread, read from the WebView JavaBridge thread
    // (exportDebugBundle/getAppStateJson/isLoggingActive). JsonSnapshot stores an isolated,
    // never-mutated copy on every publish, so readers can never observe a mid-mutation object.
    private val lastTelemetry = JsonSnapshot()

    private val lastStatus = JsonSnapshot()

    private val lastStorage = JsonSnapshot()
    private val liveDashboardStatePublisher =
        LiveDashboardStatePublisher(
            storeTelemetry = lastTelemetry::set,
            storeStatus = lastStatus::set,
            publishStatus = { callDashboard("setStatus", it.toString()) },
            publishAppState = publishAppStateCommand,
            publishTelemetry = { callDashboard("updateTelemetry", it.toString()) },
        )
    private val dashboardResumeCatchUp = DashboardResumeCatchUp { callDashboard("showToast", it) }
    private val dashboardTripDeepLink =
        DashboardTripDeepLink(
            isDashboardReady = { dashboardPublisher?.isPageReady() == true },
            publishTrip = { routeKey, receipt ->
                callDashboard(if (receipt) "openTripReceipt" else "openTrip", routeKey)
            },
        )
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val storageReader = DashboardStorageReader { localStore }

    // The OBD telemetry/status broadcast receiving + dashboard-forwarding plumbing lives in the
    // coordinator; the Activity supplies the seam callbacks that touch its snapshot state and the
    // overridable hooks (so test subclasses' overrides stay on the path). See the coordinator KDoc.
    private val broadcastCoordinator =
        DashboardBroadcastCoordinator(
            object : DashboardBroadcastCoordinator.Seam {
                override fun storeTelemetry(json: String) = lastTelemetry.setFromString(json)

                override fun storeStatus(json: String): String {
                    lastStatus.setFromString(json)
                    dashboardExperienceHost.onLoggingStateChanged()
                    return lastStatus.get().optString("state", "")
                }

                override fun callDashboard(
                    functionName: String,
                    jsonPayload: String?,
                ) = this@MainActivity.callDashboard(functionName, jsonPayload)

                override fun markStorageSummaryDirty() = this@MainActivity.markStorageSummaryDirty()

                override fun publishStorageSummary() = this@MainActivity.publishStorageSummary()

                override fun publishStorageSummaryThrottled() = this@MainActivity.publishStorageSummaryThrottled()

                override fun isDashboardReady(): Boolean = dashboardPublisher?.isPageReady() == true

                override fun publishAppState() = publishAppStateCommand()

                override fun onAdapterStatusForReadyNotify(status: JSONObject) =
                    this@MainActivity.onAdapterStatusForReadyNotify(status)

                override fun lastStatus(): JSONObject = lastStatus.get()

                override fun maybeAutoConnect(
                    trigger: String,
                    observedAddress: String?,
                ) {
                    this@MainActivity.maybeAutoConnect(trigger, observedAddress)
                }

                override fun bluetoothDeviceAddress(intent: Intent): String =
                    this@MainActivity.bluetoothDeviceAddress(intent)
            },
        )
    private val storageSummaryPublisher =
        StorageSummaryPublisher(
            submitBackground = { task -> submitBackground(task) },
            runOnUi = { task -> runOnUiThread(task) },
            readStorageJson = { getStorageSummaryJson() },
            publishStorageJson = { storage, parsed ->
                lastStorage.set(parsed)
                callDashboard("setStorage", storage)
            },
            scheduleDelayed = { delayMs, task -> mainHandler.postDelayed(task, delayMs) },
        )
    private val deviceListPublisher =
        DeviceListPublisher(
            submitBackground = { task -> submitBackground(task) },
            runOnUi = { task -> runOnUiThread(task) },
            readDeviceJson = {
                val catalog = requireDeviceCatalog()
                DeviceListPayload(
                    devicesJson = catalog.getBondedDevicesJson(),
                    historyJson = catalog.getDeviceHistoryJson(),
                )
            },
            publishDeviceJson = { payload ->
                callDashboard("setDevices", payload.devicesJson)
                callDashboard("setHistory", payload.historyJson)
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

    private fun submitBackground(task: Runnable) {
        try {
            backgroundExecutor.execute(task)
        } catch (ex: RejectedExecutionException) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "background task dropped; executor is shut down (activity tearing down)", ex)
            }
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
                    .setNegativeButton(R.string.dialog_cancel) { _, _ ->
                        publishStatus("ready", getString(R.string.status_action_cancelled), false)
                    }.setOnCancelListener {
                        publishStatus("ready", getString(R.string.status_action_cancelled), false)
                    }.show()
            } catch (ex: RuntimeException) {
                Log.w(TAG, "bridge confirmation failed", ex)
                publishStatus("blocked", getString(R.string.status_confirmation_failed), true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        StartupTrace.mark("activity_on_create_start")
        super.onCreate(savedInstanceState)
        dashboardTripDeepLink.capture(intent)
        restoreFilePicker =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult(), this::onRestoreFilePicked)
        permissionRequester =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionsResult() }
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        restoredDashboardView = savedInstanceState?.getString(STATE_DASHBOARD_VIEW)
        if (restoredDashboardView != null) {
            dashboardExperienceHost.restoreActiveView(restoredDashboardView)
        }
        val activityPrefs = checkNotNull(prefs)
        deviceCatalog = DeviceCatalog(this, activityPrefs)
        autoConnectController = AutoConnectController(activityPrefs, requireDeviceCatalog())
        eventNotificationPrefs = EventNotificationPrefs(activityPrefs)
        val backup = DataBackup(this)
        dataBackup = backup
        // Best-effort transient-cache cleanup used to run in DataBackup's constructor on this (main)
        // thread during onCreate. Sweep it on the background executor instead so cold start isn't
        // blocked on cache-dir traversals.
        backgroundExecutor.execute { backup.sweepTransientCacheFiles() }
        backupController =
            BackupController(this, requireDataBackup(), backgroundExecutor).also {
                it.restoreState(savedInstanceState)
            }
        permissionGate = PermissionGate(this, ::launchPermissionRequest)
        localStore =
            StartupTrace.measure("local_store_open_start", "local_store_open_end") {
                try {
                    createLocalStore()
                } catch (ex: RuntimeException) {
                    // A corrupt DB file or full disk must not crash startup. Every store access
                    // already tolerates null (localStore?. reads here; DashboardStorageReader
                    // answers storage_unavailable), and onDashboardReady surfaces the failure.
                    Log.e(TAG, "ObdLocalStore failed to open; continuing without local storage", ex)
                    null
                }
            }
        troubleshooter = TroubleshooterBridge(this)
        ObdNotifications.ensureChannel(this)
        submitBackground {
            try {
                val retentionDays =
                    activityPrefs.getInt("raw_retention_days", ObdLocalStore.DEFAULT_RAW_RETENTION_DAYS)
                val pruned = localStore?.dbMaintenance?.runStartupMaintenance(retentionDays) ?: 0
                if (pruned > 0) {
                    markStorageSummaryDirty()
                    Log.i(TAG, "Pruned $pruned raw rows older than $retentionDays days")
                }
            } catch (ex: RuntimeException) {
                Log.w(TAG, "Retention prune failed; continuing without it", ex)
            }
        }

        StartupTrace.mark("webview_create_start")
        val createdWebView = WebView(this)
        StartupTrace.mark("webview_create_end")
        webView = createdWebView
        createdWebView.id = R.id.dashboard_webview
        createdWebView.contentDescription = DASHBOARD_LOADING_DESCRIPTION
        createdWebView.layoutParams =
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        StartupTrace.mark("set_content_view_start")
        setContentView(createdWebView)
        StartupTrace.mark("set_content_view_end")

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
        StartupTrace.measure("webview_configure_start", "webview_configure_end") {
            WebViewBootstrap.configure(createdWebView, VoltBridge(this))
        }

        onBackPressedDispatcher.addCallback(this, backCallback)
        StartupTrace.mark("activity_on_create_end")
    }

    override fun onDashboardReady() {
        StartupTrace.mark("dashboard_ready_bridge_start")
        Log.i(TAG, "$DASHBOARD_READY_LOG: JS is live")
        val publisher = dashboardPublisher ?: return
        if (publisher.isPageReady()) {
            return
        }
        publisher.setPageReady(true)
        restoredDashboardView?.let { view ->
            callDashboard("restoreView", DashboardExperienceHostDelegate.normalizeView(view))
            restoredDashboardView = null
        }
        webView?.contentDescription = DASHBOARD_READY_DESCRIPTION
        StartupTrace.mark("dashboard_ready_content_description")
        StartupTrace.mark("dashboard_ready_post_work_scheduled")
        postReadyDashboardRefreshPending = true
        mainHandler.removeCallbacks(postReadyDashboardRefreshRunnable)
        mainHandler.postDelayed(postReadyDashboardRefreshRunnable, POST_READY_REFRESH_DELAY_MS)
        StartupTrace.mark("dashboard_ready_native_complete")
    }

    private fun publishPostReadyDashboardRefresh() {
        StartupTrace.mark("dashboard_ready_post_work_start")
        publishDeviceList()
        publishStorageSummary()
        dashboardResumeCatchUp.publish(liveDashboardStatePublisher.publish())
        if (!isLoggingActive() && localStore == null) {
            // The store failed to open in onCreate; say so instead of claiming "viewing local
            // data" with no data behind it.
            publishStatus("blocked", getString(R.string.status_local_store_unavailable), true)
        } else if (!isLoggingActive()) {
            publishStatus("ready", getString(R.string.status_viewing_local_data), false)
        }
        maybeAutoConnect(AutoConnectController.TRIGGER_DASHBOARD_READY, null)
        // Auto-show the guided setup walkthrough once for a genuinely fresh install (decision in
        // OnboardingFlow; completion is persisted there, so it shows at most once).
        setupGuideController.maybeAutoShow()
        dashboardTripDeepLink.publishPending()
        StartupTrace.mark("dashboard_ready_post_work_end")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dashboardTripDeepLink.capture(intent)
        dashboardTripDeepLink.publishPending()
    }

    // Guided first-run setup walkthrough (M7). The staged-dialog body and step cursor live in the
    // controller (mirroring tripExportController); this Activity only supplies the live-state read
    // and the native side effects. Lazy so it survives the test seam that skips super.onCreate(),
    // and because it reads permissionGate/deviceCatalog that only exist after onCreate.
    private val setupGuideController by lazy {
        SetupGuideController(
            context = this,
            flow = { prefs?.let { OnboardingFlow(it) } },
            hasPairedAdapter = { deviceCatalog?.lastAddress()?.isNotEmpty() == true },
            hasBluetoothPermission = { requirePermissionGate().hasConnectPermissions() },
            hasLocationPermission = { requirePermissionGate().hasLocation() },
            loggingActive = { isLoggingActive() },
            openBluetoothSettings = { requireTroubleshooter().openBluetoothSettings() },
            ensureBluetoothPermission = { requirePermissionGate().ensureConnectPermissions() },
            ensureLocationPermission = { requirePermissionGate().ensureGranted() },
            startDemo = { startObdService(ObdService.ACTION_DEMO, null, null) },
        )
    }

    /** Re-opens the guided setup walkthrough on demand (bridge "Setup guide" affordance). */
    override fun openSetupGuideFromBridge() {
        setupGuideController.open()
    }

    @VisibleForTesting
    internal fun isDashboardReadyForTest(): Boolean = dashboardPublisher?.isPageReady() == true

    @VisibleForTesting
    internal fun webViewForTest(): WebView? = webView

    override fun onSaveInstanceState(outState: Bundle) {
        backupController?.saveState(outState)
        outState.putString(STATE_DASHBOARD_VIEW, dashboardExperienceHost.activeViewForSave())
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        // Resume the WebView's renderer and JS timers. Without this the chromium compositor surface
        // stays suspended after a background -> foreground trip and the page repaints as a black
        // frame (the "black screen on resume" report). Paired with onPause()'s onPause() below.
        webView?.onResume()
        broadcastCoordinator.register(this)
        dashboardExperienceHost.onResume()
        if (dashboardPublisher?.isPageReady() == true) {
            publishDeviceList()
            publishStorageSummary()
            dashboardResumeCatchUp.publish(liveDashboardStatePublisher.publish())
        } else {
            StartupTrace.mark("resume_dashboard_publish_skipped_not_ready")
        }
        reportAppVisibility(true)
        maybeAutoConnect(AutoConnectController.TRIGGER_APP_RESUME, null)
        if (postReadyDashboardRefreshPending) {
            mainHandler.removeCallbacks(postReadyDashboardRefreshRunnable)
            mainHandler.postDelayed(postReadyDashboardRefreshRunnable, POST_READY_REFRESH_DELAY_MS)
        }
        // M2: on every foreground entry, fire a one-shot alert for any tracked maintenance item that
        // has newly gone overdue. Off the UI thread; failures must never crash a resume.
        submitBackground {
            try {
                maintenanceDueNotifier.checkOnAppOpen()
            } catch (ex: RuntimeException) {
                Log.w(TAG, "maintenance-due check failed", ex)
            }
        }
    }

    override fun onPause() {
        dashboardResumeCatchUp.recordPause(lastTelemetry.get())
        dashboardExperienceHost.onPause()
        reportAppVisibility(false)
        isActivityResumed = false
        mainHandler.removeCallbacks(postReadyDashboardRefreshRunnable)
        // Suspend the WebView renderer/JS timers while backgrounded so chromium releases its drawing
        // surface cleanly; onResume() above brings it back. Called before super.onPause() to mirror
        // the standard Activity<->WebView lifecycle pairing.
        webView?.onPause()
        super.onPause()
        broadcastCoordinator.unregisterAll(this)
    }

    override fun onDestroy() {
        backupController?.dispose()
        val storeToClose = localStore
        // Detach first so late bridge callbacks cannot acquire the handle while teardown drains the
        // worker. A task already using the captured store is allowed to finish before it is closed.
        localStore = null
        backgroundExecutor.shutdownNow()
        troubleshooter?.shutdown()
        ActivityStoreTeardown.closeWhenExecutorStops(backgroundExecutor, storeToClose)
        // Tear the WebView down explicitly. It holds the VoltBridge JS interface, which keeps a
        // strong reference back to this Activity; without destroy()/removeJavascriptInterface the
        // WebView (and the whole Activity graph + native chromium resources) leaks every time the
        // Activity is torn down, and the orphaned page's JS timers/console callbacks keep firing.
        webView?.let { wv ->
            try {
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.removeJavascriptInterface("VoltTrackerAndroid")
                wv.stopLoading()
                wv.destroy()
            } catch (ex: RuntimeException) {
                // Chromium can throw from stopLoading()/destroy() while tearing down its native
                // side; a leaked WebView beats crashing the whole Activity teardown.
                Log.w(TAG, "WebView teardown failed", ex)
            }
        }
        webView = null
        dashboardPublisher = null
        autoConnectController = null
        eventNotificationPrefs = null
        super.onDestroy()
    }

    /** Opens the SQLite-backed local store. Overridable in tests to simulate an open failure. */
    protected open fun createLocalStore(): ObdLocalStore = ObdLocalStore(this)

    /** Launches the runtime-permission request via the Activity Result API. Overridable in tests. */
    open fun launchPermissionRequest(permissions: Array<String>) {
        permissionRequester?.launch(permissions)
    }

    /**
     * Reacts to a runtime-permission result. While the guided walkthrough is mid-flight the result
     * advances it (re-rendering the next pending step); otherwise the decision tree lives in
     * [ConnectPermissionFlow]. Invoked by the [ActivityResultContracts.RequestMultiplePermissions]
     * callback.
     */
    open fun onPermissionsResult() {
        if (setupGuideController.isActive()) {
            publishDeviceList()
            publishAppStateCommand()
            setupGuideController.resumeAfterPermissionResult()
            return
        }
        connectPermissionFlow.onPermissionsResult()
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
        StartupTrace.mark("publish_device_list_start")
        deviceListPublisher.publish()
    }

    override fun publishStatus(
        state: String?,
        detail: String?,
        blocked: Boolean,
    ) {
        val catalog = requireDeviceCatalog()
        val payload =
            DashboardPayloadJson.status(
                state,
                detail,
                blocked,
                isBluetoothReady(),
                catalog.lastAddress(),
                catalog.lastName(),
            )
        callDashboard("setStatus", payload.toString())
        lastStatus.set(payload)
        dashboardExperienceHost.onLoggingStateChanged()
        publishAppStateCommand()
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
        operation: String?,
    ) {
        val payload =
            DashboardPayloadJson.restoreProgress(
                visible,
                busy,
                title,
                detail,
                tone,
                phase,
                bytesDone,
                bytesTotal,
                rowsDone,
                rowsTotal,
                percent,
                etaSeconds,
                operation,
            )
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
        if (ObdService.isSessionStartAction(action) && DatabaseOperationLease.isHeld()) {
            publishStatus("blocked", getString(R.string.status_database_operation_running), true)
            return
        }
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
                connectPermissionFlow.parkPendingStart(action, address, name, detailStage)
                return
            }
            val adapter = BluetoothAdapters.get(this)
            if (adapter == null) {
                publishStatus("blocked", getString(R.string.status_no_bluetooth_support), true)
                return
            }
            if (!adapter.isEnabled) {
                publishStatus("blocked", getString(R.string.status_turn_on_bluetooth), true)
                try {
                    startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (ex: RuntimeException) {
                    Log.w(TAG, "Bluetooth enable prompt failed; falling back to Bluetooth settings", ex)
                    try {
                        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    } catch (settingsEx: RuntimeException) {
                        Log.w(TAG, "Bluetooth settings fallback failed", settingsEx)
                        publishStatus("blocked", getString(R.string.status_open_bluetooth_settings_manually), true)
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
            publishStatus("blocked", getString(R.string.status_obd_start_blocked), true)
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
        publishStatus(
            "ready",
            getString(R.string.status_remembered_device, if (cleanName.isEmpty()) cleanAddress else cleanName),
            false,
        )
    }

    override fun stopObdService() {
        val service = Intent(this, ObdService::class.java)
        service.action = ObdService.ACTION_DISCONNECT
        try {
            startService(service)
        } catch (ex: RuntimeException) {
            Log.w(TAG, "stopObdService could not reach the service", ex)
            publishStatus("ready", getString(R.string.status_stop_noted), false)
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

    override fun shareDiagnosticsDigestFromBridge() {
        requireTroubleshooter().shareDiagnosticsDigest()
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
        } catch (ignored: RuntimeException) {
            // Visibility is diagnostic only.
        }
    }

    private fun callDashboard(
        functionName: String,
        jsonPayload: String?,
    ) {
        dashboardPublisher?.publish(functionName, jsonPayload)
    }

    override fun publishDashboardPayload(
        functionName: String,
        jsonPayload: String?,
    ) {
        callDashboard(functionName, jsonPayload)
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

    override fun getStorageDetailsJson(): String = storageReader.storageDetailsJson()

    override fun getTripsJson(): String = storageReader.tripsJson()

    // getTripRouteJson / getCurrentSessionRouteJson / getBatterySohHistoryJson ride the
    // SessionDataReader interface defaults (same DashboardStorageReader-backed pattern as
    // getTripsPageJson), so this Activity does not accrete a one-line forward per route read.

    override fun exportTripFromBridge(
        routeKey: String?,
        format: String?,
    ): String = tripExportController.exportAndShare(routeKey, format)

    override fun getInsightsJson(): String = storageReader.insightsJson()

    open fun launchRestoreFilePicker(intent: Intent) {
        restoreFilePicker?.launch(intent)
    }

    private fun onRestoreFilePicked(result: ActivityResult) {
        backupController?.onRestorePickerResult(result.resultCode, result.data)
    }

    override fun isLoggingActive(): Boolean =
        MainActivityUtils.isConnectedState(lastStatus.get().optString("state", "")) || ObdService.hasActiveSession()

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
            lastTelemetry.get(),
            lastStatus.get(),
            lastStorage.get(),
        )
    }

    override fun getAutoConnectStateJson(): String =
        autoConnectController?.stateJson()
            ?: MainActivityUtils.errorPayload("auto_connect_unavailable", "Auto-connect is not ready.").toString()

    override fun setAutoConnectEnabledFromBridge(enabled: Boolean) {
        val controller = autoConnectController
        if (controller == null) {
            publishStatus("blocked", getString(R.string.status_autoconnect_not_ready), true)
            return
        }
        controller.setEnabled(enabled)
        if (enabled) {
            publishStatus("ready", getString(R.string.status_autoconnect_enabled), false)
            maybeAutoConnect(AutoConnectController.TRIGGER_USER_ENABLED, null)
        } else {
            publishStatus("ready", getString(R.string.status_autoconnect_disabled), false)
        }
        publishAppStateCommand()
    }

    // The event-notification + auto-scan toggle cluster (M1/M3) is exposed as one accessor rather
    // than seven flat host overrides: the bridge calls eventNotifications().setNewDtcEnabled(...)
    // and the delegate holds every body. This is the A1/I3 fix for MainActivity's growth-by-override
    // — the file gains the cluster with a single member instead of one forward per toggle.
    override fun eventNotifications(): EventNotificationCommands = eventNotificationHost

    override fun dashboardExperience(): DashboardExperienceCommands = dashboardExperienceHost

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
                // The typed getParcelableExtra(String, Class) overload requires API 33; minSdk 23
                // still needs the deprecated overload on this branch.
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

    companion object {
        const val EXTRA_OPEN_TRIP = "com.volttracker.obdpoc.extra.OPEN_TRIP"
        const val EXTRA_OPEN_TRIP_RECEIPT = "com.volttracker.obdpoc.extra.OPEN_TRIP_RECEIPT"

        /** Shared logcat tag. Canonical home is [AppPrefs.LOG_TAG]; kept here as a compatibility alias. */
        const val TAG = AppPrefs.LOG_TAG

        /**
         * Prefix of the JS->native handshake log line emitted by [onDashboardReady]. This is the
         * entire positive signal of `scripts/emulator-smoke.sh`, which greps logcat for it to prove
         * the dashboard JS came alive. Renaming it on either side would make the smoke pass while
         * testing nothing, so `EmulatorSmokeContractTest` asserts the script references this exact
         * constant. Keep the emitted line's prefix unchanged so the live smoke still matches.
         */
        const val DASHBOARD_READY_LOG = "dashboard handshake received"

        /** Stable UiAutomator signal used by the startup Macrobenchmark. */
        const val DASHBOARD_LOADING_DESCRIPTION = "VoltTracker dashboard loading"

        /** Stable UiAutomator signal used by the startup Macrobenchmark. */
        const val DASHBOARD_READY_DESCRIPTION = "VoltTracker dashboard ready"
        private const val STATE_DASHBOARD_VIEW = "dashboard_active_view"
        private const val POST_READY_REFRESH_DELAY_MS = 650L

        /**
         * SharedPreferences file shared by the Activity, [ObdService], the widget package, and
         * [EventNotificationPrefs]. Canonical home is [AppPrefs.FILE]; kept here as a compatibility
         * alias for the existing `MainActivity.PREFS` call sites (report item A3).
         */
        const val PREFS = AppPrefs.FILE
    }
}

private fun MainActivity.appVersionName(): String =
    try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (ex: PackageManager.NameNotFoundException) {
        ""
    }
