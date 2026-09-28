package com.volttracker.obdpoc

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.service.ObdService
import com.volttracker.obdpoc.service.ObdServiceLauncher
import com.volttracker.obdpoc.ui.VoltApp
import com.volttracker.obdpoc.ui.VoltAppActions
import com.volttracker.obdpoc.ui.VoltAppUiState
import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import com.volttracker.obdpoc.ui.settings.SettingChange
import com.volttracker.obdpoc.ui.settings.SettingsCommand
import com.volttracker.obdpoc.ui.trips.TripExport
import com.volttracker.obdpoc.update.UpdateCoordinator
import com.volttracker.obdpoc.update.UpdateManager
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native Compose dashboard host — the app's launcher experience. Subscribes to
 * the same service seams the WebView dashboard uses (the package-scoped
 * telemetry/status broadcasts plus the [LiveDashboardSnapshot] resume replay)
 * and folds them into [LiveUiStateStore] for the screens to render.
 *
 * The classic WebView dashboard ([MainActivity]) stays fully functional and is
 * one tap away (Settings → "Open classic dashboard") while the native screens
 * absorb its features phase by phase.
 */
class ComposeDashboardActivity :
    ComponentActivity(),
    TroubleshooterHost,
    BackupHost,
    TripExportHost {
    private val store = LiveUiStateStore()
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val exportInFlight = AtomicBoolean(false)

    /** The classic dashboard's name for the visible screen ("charge", …), from [VoltApp]. */
    private var shownView: String? = null

    /** Whether a session is logging; a seam so tests can hold one open without a real adapter. */
    internal var loggingProbe: () -> Boolean = ObdService::hasActiveSession

    /**
     * Opened only while a backup, restore or export needs it ([ensureLocalStore]) and closed again
     * when the screen stops, so the classic dashboard never restores underneath an open handle.
     */
    @Volatile override var localStore: ObdLocalStore? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var deviceCatalog: DeviceCatalog
    private lateinit var autoConnect: AutoConnectController
    private lateinit var experience: DashboardExperienceHostDelegate
    private lateinit var settings: ComposeSettingsStore
    private lateinit var troubleshooter: TroubleshooterBridge<ComposeDashboardActivity>
    private lateinit var backups: BackupController<ComposeDashboardActivity>
    private val tripExports by lazy { TripExportController(applicationContext, this) }

    // Process-scoped: survives configuration recreation (see UpdateCoordinator).
    private lateinit var updates: UpdateCoordinator

    private val serviceReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                val json = intent.getStringExtra(ObdService.EXTRA_JSON)
                ComposeDashboardSupport.routeServiceBroadcast(intent.action, json, store)
                if (intent.action == ObdService.BROADCAST_STATUS) {
                    // Keep-screen-awake only holds while a session is logging.
                    experience.onLoggingStateChanged()
                    troubleshooter.onAdapterStatusForReadyNotify(MainActivityUtils.parseJson(json))
                    publishAdapterWait()
                }
                // The native screens only show a short status label, so a refused start (e.g. a
                // missing Nearby devices permission) would otherwise vanish without a trace.
                ComposeDashboardSupport.blockedStatusDetail(intent.action, json)?.let(::showMessage)
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) showMessage(getString(R.string.compose_notifications_denied))
        }

    private val restorePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // The store was closed when the picker covered this screen; the restore needs it back.
            ensureLocalStore()
            backups.onRestorePickerResult(result.resultCode, result.data)
        }

    private val connectPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                connectLastAdapter()
            } else {
                showMessage(getString(R.string.status_bt_denied_open_settings))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(AppPrefs.FILE, MODE_PRIVATE)
        deviceCatalog = DeviceCatalog(this, prefs)
        autoConnect = AutoConnectController(prefs, deviceCatalog)
        experience =
            DashboardExperienceHostDelegate(
                activity = this,
                prefs = { prefs },
                loggingActive = ObdService::hasActiveSession,
                publishAppState = ::refreshSettings,
                hasNotificationPermission = ::hasNotificationPermission,
                ensureNotificationPermission = ::requestNotificationPermission,
            )
        settings =
            ComposeSettingsStore(
                prefs = prefs,
                autoConnect = autoConnect,
                events =
                    EventNotificationHostDelegate(
                        prefs = { EventNotificationPrefs(prefs) },
                        publishStatus = { _, detail, _ -> showMessage(detail) },
                        publishAppState = ::refreshSettings,
                        notReadyMessage = { getString(R.string.status_obd_start_blocked) },
                        hasNotificationPermission = ::hasNotificationPermission,
                        ensureNotificationPermission = ::requestNotificationPermission,
                    ),
                experience = experience,
            )
        troubleshooter = TroubleshooterBridge(this)
        backups = BackupController(this, DataBackup(this), backgroundExecutor)
        backups.restoreState(savedInstanceState)
        updates = UpdateCoordinator.shared(this)
        store.onVersionLabel("Volt Tracker ${BuildConfig.VERSION_NAME}")
        refreshSettings()
        // A recreated Activity starts with a fresh store; the coordinator's
        // retained result (an offered build, say) must not be forgotten.
        updates.lastResult?.let(::publishUpdateResult)
        setContent {
            val state by store.state.collectAsState()
            VoltApp(
                state = state,
                actions =
                    VoltAppActions(
                        onOpenClassicDashboard = ::openClassicDashboard,
                        onConnect = ::connectLastAdapter,
                        onStartDemo = { startObdService(ObdService.ACTION_DEMO, null, null) },
                        onStopDemo = ::stopObdService,
                        onCheckForUpdate = ::checkForUpdate,
                        onInstallUpdate = ::installUpdate,
                        onSettingChange = ::changeSetting,
                        onSettingsCommand = ::runCommand,
                        onScreenShown = ::onScreenShown,
                        onSelectTrip = ::selectTrip,
                        onExportTrip = ::exportTrip,
                    ),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            serviceReceiver,
            IntentFilter().apply {
                addAction(ObdService.BROADCAST_TELEMETRY)
                addAction(ObdService.BROADCAST_STATUS)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        replayServiceSnapshot()
        loadHistoryFor(shownView)
        // The classic dashboard may have changed a shared setting while this screen was away.
        refreshSettings()
        experience.onResume()
        signalAppForeground(true)
        maybeAutoConnect()
        // One silent update check per process start — the auto half of
        // auto-update. Manual re-checks live in Settings.
        updates.autoCheckOnce(::publishUpdateResult)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        backups.saveState(outState)
    }

    override fun onStop() {
        // Hand the database back unless a backup, restore or export is still using it.
        if (!DatabaseOperationLease.isHeld() && !exportInFlight.get()) closeLocalStore()
        super.onStop()
    }

    override fun onDestroy() {
        backups.dispose()
        troubleshooter.shutdown()
        backgroundExecutor.shutdownNow()
        val storeToClose = localStore
        localStore = null
        ActivityStoreTeardown.closeWhenExecutorStops(backgroundExecutor, storeToClose)
        super.onDestroy()
    }

    override fun onPause() {
        try {
            unregisterReceiver(serviceReceiver)
        } catch (_: IllegalArgumentException) {
            // Not registered — nothing to do.
        }
        signalAppForeground(false)
        experience.onPause()
        super.onPause()
    }

    /** Rebuilds live state from the service's in-process snapshot after a pause/relaunch. */
    private fun replayServiceSnapshot() {
        ComposeDashboardSupport.replayServiceSnapshot(store)
    }

    private fun publishUpdateResult(result: UpdateManager.CheckResult) {
        val banner = ComposeDashboardSupport.updateBanner(result)
        store.onUpdateState(banner.statusLabel, banner.availableTag, null)
    }

    private fun checkForUpdate() {
        store.onUpdateState("Checking for updates…", null, null)
        // The manager's busy path still answers (with Failed), so this status
        // can never wedge on a dropped request.
        updates.check(::publishUpdateResult)
    }

    private fun installUpdate() {
        val build = updates.availableBuild() ?: return
        store.onUpdateState("${build.tag} is available", build.tag, 0)
        updates.downloadAndInstall { percent ->
            when {
                percent < 0 -> store.onUpdateState("Download failed — try again", build.tag, null)
                percent >= 100 -> store.onUpdateState("Handing to Android's installer…", build.tag, null)
                else -> store.onUpdateState("${build.tag} is available", build.tag, percent)
            }
        }
    }

    private fun changeSetting(change: SettingChange) {
        settings.apply(change)
        refreshSettings()
    }

    private fun refreshSettings() {
        store.onSettings(settings::read)
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openClassicDashboard() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun maybeAutoConnect() {
        autoConnect.maybeConnect(
            trigger = AutoConnectController.TRIGGER_APP_RESUME,
            observedAddress = null,
            bluetoothReady = hasConnectPermission() && BluetoothAdapters.get(this)?.isEnabled == true,
            loggingActive = ObdService.hasActiveSession(),
            startConnect = { address, name -> startObdService(ObdService.ACTION_CONNECT, address, name) },
            publishStatus = { state, detail, _ ->
                store.onStatus(
                    JSONObject()
                        .put("state", state)
                        .put("detail", detail)
                        .put("adapter", deviceCatalog.lastName()),
                )
            },
        )
    }

    // decideConnectAction gates the ACTION_REQUEST_ENABLE launch behind the permission
    // check; lint cannot see through it (same suppression MainActivity.startObdService uses).
    @android.annotation.SuppressLint("MissingPermission")
    private fun connectLastAdapter() {
        val address = deviceCatalog.lastAddress().trim()
        val action =
            ComposeDashboardSupport.decideConnectAction(
                lastAddress = address,
                hasConnectPermission = hasConnectPermission(),
                bluetoothEnabled = BluetoothAdapters.get(this)?.isEnabled == true,
            )
        when (action) {
            // Pairing/selection still lives in the classic dashboard.
            ConnectAction.OPEN_CLASSIC -> openClassicDashboard()
            ConnectAction.REQUEST_PERMISSION ->
                // Only reachable on S+ (below S hasConnectPermission() is always true);
                // the explicit check keeps lint's InlinedApi analysis satisfied.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    connectPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            ConnectAction.REQUEST_ENABLE_BLUETOOTH ->
                try {
                    startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (ex: RuntimeException) {
                    Log.w(AppPrefs.LOG_TAG, "Bluetooth enable prompt failed", ex)
                }
            ConnectAction.CONNECT -> startObdService(ObdService.ACTION_CONNECT, address, deviceCatalog.lastName())
        }
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    override fun startObdService(
        action: String?,
        address: String?,
        name: String?,
    ) = startObdService(action, address, name, null)

    override fun startObdService(
        action: String?,
        address: String?,
        name: String?,
        detailStage: String?,
    ) {
        if (ObdService.isSessionStartAction(action) && DatabaseOperationLease.isHeld()) {
            showMessage(getString(R.string.status_database_operation_running))
            return
        }
        troubleshooter.clearPendingTestConnectionStop()
        val service = Intent(this, ObdService::class.java)
        service.action = action
        if (address != null) service.putExtra(ObdService.EXTRA_ADDRESS, address)
        if (name != null) service.putExtra(ObdService.EXTRA_NAME, name)
        if (detailStage != null) service.putExtra(ObdService.EXTRA_DETAIL_STAGE, detailStage)
        try {
            ObdServiceLauncher.start(this, service)
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "startObd blocked", ex)
            troubleshooter.clearPendingTestConnectionStop()
            showMessage(getString(R.string.status_obd_start_blocked))
        }
    }

    private fun showMessage(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    /** Ends the running session (Settings → Demo / testing → Stop), as the classic Disconnect does. */
    override fun stopObdService() {
        val service = Intent(this, ObdService::class.java)
        service.action = ObdService.ACTION_DISCONNECT
        try {
            startService(service)
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "stopSession could not reach the service", ex)
        }
    }

    private fun signalAppForeground(foreground: Boolean) {
        val service = Intent(this, ObdService::class.java)
        service.action =
            if (foreground) ObdService.ACTION_APP_FOREGROUND else ObdService.ACTION_APP_BACKGROUND
        try {
            startService(service)
        } catch (ex: RuntimeException) {
            // Background start restrictions can reject this housekeeping signal; it is best-effort.
            Log.w(AppPrefs.LOG_TAG, "foreground signal skipped", ex)
        }
    }

    // ===== Settings tools: the same helpers the classic dashboard drives =====================

    internal fun runCommand(command: SettingsCommand) {
        when (command) {
            SettingsCommand.TestConnection -> troubleshooter.startTestConnection()
            SettingsCommand.SendDiagnostics -> troubleshooter.shareDiagnostics()
            is SettingsCommand.WaitForAdapter -> waitForAdapter(command.on, command.minutes)
            is SettingsCommand.BackUp -> backUp(command.passphrase)
            is SettingsCommand.Restore -> restore(command.passphrase)
            SettingsCommand.ExportTrips -> exportTrips()
        }
    }

    private fun waitForAdapter(
        on: Boolean,
        minutes: Int,
    ) {
        if (!on) {
            troubleshooter.cancelAdapterReadyNotify()
        } else if (deviceCatalog.lastAddress().isBlank()) {
            // Every probe would fail the same way; say so once instead of every 30 s.
            showMessage(getString(R.string.status_no_remembered_adapter_yet))
        } else {
            if (!hasNotificationPermission()) requestNotificationPermission()
            troubleshooter.scheduleAdapterReadyNotify(minutes)
        }
        store.onSettings { it.copy(adapterWaitMins = minutes) }
        publishAdapterWait()
    }

    private fun publishAdapterWait() {
        val waiting = troubleshooter.isAdapterReadyScheduled()
        store.onSettings { it.copy(waitingForAdapter = waiting) }
    }

    private fun backUp(passphrase: String?) {
        if (!ensureLocalStore()) return
        if (passphrase == null) backups.launchShare() else backups.launchEncryptedShare(passphrase)
    }

    private fun restore(passphrase: String?) {
        when (ComposeDataTools.restoreGate(isLoggingActive(), ClassicDashboardPresence.isAlive())) {
            ComposeDataTools.RestoreGate.STOP_LOGGING ->
                showMessage(getString(R.string.status_stop_logging_before_restore))
            ComposeDataTools.RestoreGate.USE_CLASSIC -> {
                showMessage(getString(R.string.compose_restore_use_classic))
                openClassicDashboard()
            }
            ComposeDataTools.RestoreGate.OK ->
                if (passphrase ==
                    null
                ) {
                    backups.launchRestorePicker()
                } else {
                    backups.launchEncryptedRestorePicker(passphrase)
                }
        }
    }

    private fun exportTrips() = runTripExport { tripExports.exportAllTrips() }

    private fun exportTrip(export: TripExport) {
        when (export) {
            TripExport.All -> exportTrips()
            is TripExport.One -> runTripExport { tripExports.exportAndShare(export.routeKey, export.format) }
        }
    }

    /** Runs a trip export off the main thread (it reads the database and writes the file), one at a time. */
    private fun runTripExport(export: () -> String) {
        if (!ensureLocalStore() || !exportInFlight.compareAndSet(false, true)) return
        store.onSettings { it.copy(dataTaskLabel = getString(R.string.compose_export_running)) }
        try {
            backgroundExecutor.execute {
                val result = MainActivityUtils.parseJson(export())
                exportInFlight.set(false)
                runOnUiThread {
                    store.onSettings { it.copy(dataTaskLabel = null) }
                    if (!result.optBoolean("ok", false)) {
                        result.optString("message", "").ifBlank { null }?.let(::showMessage)
                    }
                }
            }
        } catch (ex: RejectedExecutionException) {
            Log.w(AppPrefs.LOG_TAG, "trip export not started", ex)
            exportInFlight.set(false)
            store.onSettings { it.copy(dataTaskLabel = null) }
        }
    }

    private fun onScreenShown(view: String) {
        shownView = view
        experience.setActiveDashboardView(view)
        loadHistoryFor(view)
    }

    private fun loadHistoryFor(view: String?) {
        when (view) {
            CHARGE_VIEW -> history.loadCharges()
            TRIPS_VIEW -> history.loadTrips()
        }
    }

    private fun selectTrip(routeKey: String) {
        store.selectTrip(routeKey)
        history.loadRoute()
    }

    /** The state the screens render (tests read it back). */
    internal fun uiState(): VoltAppUiState = store.state.value

    /** Reads the Charge and Trips tabs' history (tests swap its readers). */
    internal val history by lazy {
        ComposeHistoryLoader(backgroundExecutor, store, ::runOnUiThread) { ObdLocalStore(applicationContext) }
    }

    /** Opens the database for a data tool; false (with a message) when it can't be opened. */
    private fun ensureLocalStore(): Boolean {
        if (localStore?.isOpen == true) return true
        return try {
            localStore = ObdLocalStore(this)
            true
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "local store open failed", ex)
            showMessage(getString(R.string.compose_storage_unavailable))
            false
        }
    }

    private fun closeLocalStore() {
        val open = localStore ?: return
        localStore = null
        try {
            open.close()
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "local store close failed", ex)
        }
    }

    // ===== TroubleshooterHost / BackupHost / TripExportHost =================================

    override fun requireDeviceCatalog(): DeviceCatalog = deviceCatalog

    override fun rememberDevice(
        address: String?,
        name: String?,
    ) {
        deviceCatalog.remember(address, name)
    }

    override fun isLoggingActive(): Boolean = loggingProbe()

    /** The native screens have no status line for tool results, so each one is a toast. */
    override fun publishStatus(
        state: String?,
        detail: String?,
        blocked: Boolean,
    ) {
        detail?.takeIf { it.isNotBlank() }?.let(::showMessage)
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
        val label = ComposeDataTools.progressLabel(visible, busy, title, detail, percent)
        store.onSettings { it.copy(dataTaskLabel = label) }
    }

    override fun publishDashboardPayload(
        functionName: String,
        jsonPayload: String?,
    ) {
        // The backup receipt updates the "Last backup" line; the rest only matter to the WebView.
        if (functionName == BACKUP_RECEIPT) refreshSettings()
    }

    override fun publishDeviceList() = Unit

    override fun publishStorageSummary() = Unit

    override fun getStorageSummaryJson(): String = DashboardStorageReader { localStore }.storageSummaryJson()

    override fun launchRestoreFilePicker(intent: Intent) {
        restorePicker.launch(intent)
    }

    private companion object {
        const val CHARGE_VIEW = "charge"
        const val TRIPS_VIEW = "map"
        const val BACKUP_RECEIPT = "setBackupReceipt"
    }
}
