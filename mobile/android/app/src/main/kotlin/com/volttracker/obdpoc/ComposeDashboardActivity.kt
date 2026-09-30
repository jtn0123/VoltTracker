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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.map.StadiaTileLoader
import com.volttracker.obdpoc.service.AppVisibility
import com.volttracker.obdpoc.service.ObdService
import com.volttracker.obdpoc.service.ObdServiceLauncher
import com.volttracker.obdpoc.ui.VoltApp
import com.volttracker.obdpoc.ui.VoltAppActions
import com.volttracker.obdpoc.ui.VoltAppUiState
import com.volttracker.obdpoc.ui.diag.DtcCatalog
import com.volttracker.obdpoc.ui.insights.InsightsPeriod
import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import com.volttracker.obdpoc.ui.settings.AdapterListState
import com.volttracker.obdpoc.ui.settings.SettingChange
import com.volttracker.obdpoc.ui.settings.SettingsCommand
import com.volttracker.obdpoc.ui.theme.VoltPalette
import com.volttracker.obdpoc.ui.theme.resolvesDark
import com.volttracker.obdpoc.ui.theme.voltPalette
import com.volttracker.obdpoc.ui.trips.LocalMapTileLoader
import com.volttracker.obdpoc.ui.trips.TripExport
import com.volttracker.obdpoc.update.UpdateCoordinator
import com.volttracker.obdpoc.update.UpdateManager
import org.json.JSONObject
import java.io.IOException
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
    TripExportHost,
    DialogPaletteHost {
    override var dialogPalette: VoltPalette? = null
        private set
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
    private val carControls by lazy { ComposeCarControls(this, { prefs }, store, ::showMessage) }
    private val dtc: ComposeDtcActions by lazy {
        ComposeDtcActions(this, this, { prefs }, store, ::showMessage) { history.loadHealth() }
    }

    /** Health's trouble-code names and severities, read from the APK once, off the main thread. */
    private val dtcCatalog: DtcCatalog by lazy { readDtcCatalog() }

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
                if (intent.action == ObdService.BROADCAST_TELEMETRY) dtc.onTelemetry(MainActivityUtils.parseJson(json))
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
            refreshAdapters()
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
        // Null without a Stadia key: the Trips map then draws its plain ground, no tiles.
        val tileLoader = StadiaTileLoader.create()
        val launch =
            if (BuildConfig.DEBUG) {
                DebugLaunch.from(
                    intent.getStringExtra(DebugLaunch.EXTRA_TAB),
                    intent.getStringExtra(DebugLaunch.EXTRA_ROUTE),
                    intent.getBooleanExtra(DebugLaunch.EXTRA_DEMO, false),
                )
            } else {
                DebugLaunch()
            }
        if (launch.startDemo && savedInstanceState == null) startObdService(ObdService.ACTION_DEMO, null, null)
        setContent {
            val state by store.state.collectAsState()
            val dark = state.settings.appearance.resolvesDark(isSystemInDarkTheme())
            val palette =
                voltPalette(dark, state.settings.darkStyle, state.settings.accent, state.settings.highContrast)
            SideEffect {
                dialogPalette = palette
                theme.applyStyle(if (dark) R.style.VoltDialogs_Dark else R.style.VoltDialogs_Light, true)
            }
            CompositionLocalProvider(LocalMapTileLoader provides tileLoader) {
                VoltApp(
                    state = state,
                    initialTab = launch.tab,
                    initialRoutes = launch.routes,
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
                            onInsightsPeriod = ::selectInsightsPeriod,
                            onCarControl = { carControls.request(it) },
                            onCarControlsEnabled = { carControls.setEnabled(it) },
                            onBodyTest = ::startBodyTest,
                            onScanCodes = { dtc.scan() },
                            onClearCodes = { dtc.clear() },
                            onShareHealthReport = { dtc.share(it) },
                        ),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from Android's Bluetooth settings (or the permission prompt) with a new pairing.
        refreshAdapters()
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
        carControls.refresh()
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
    /** False when no adapter is chosen yet: the caller opens Settings → Adapter to pick one. */
    private fun connectLastAdapter(): Boolean {
        val address = deviceCatalog.lastAddress().trim()
        val action =
            ComposeDashboardSupport.decideConnectAction(
                lastAddress = address,
                hasConnectPermission = hasConnectPermission(),
                bluetoothEnabled = BluetoothAdapters.get(this)?.isEnabled == true,
            )
        when (action) {
            ConnectAction.CHOOSE_ADAPTER -> {
                refreshAdapters()
                return false
            }
            ConnectAction.REQUEST_PERMISSION, ConnectAction.REQUEST_ENABLE_BLUETOOTH -> allowBluetooth()
            ConnectAction.CONNECT -> startObdService(ObdService.ACTION_CONNECT, address, deviceCatalog.lastName())
        }
        return true
    }

    /** Asks the live session for a one-minute body-bus listen (see SwcanListenRunner). */
    private fun startBodyTest() {
        try {
            startService(Intent(this, ObdService::class.java).setAction(ObdService.ACTION_BODY_TEST))
            showMessage("Listening for 1 minute. Open and close doors, lock and unlock, move a window.")
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "body test dispatch failed", ex)
        }
    }

    /** Reloads the Settings → Adapter picker from Android's paired-device list. */
    private fun refreshAdapters() {
        val radio = BluetoothAdapters.get(this)
        val listState =
            PairedAdapterReader.listState(
                hasBluetooth = radio != null,
                hasPermission = hasConnectPermission(),
                bluetoothEnabled = radio?.isEnabled == true,
            )
        val paired =
            if (listState == AdapterListState.READY) {
                PairedAdapterReader.parse(deviceCatalog.getBondedDevicesJson())
            } else {
                emptyList()
            }
        val selected = deviceCatalog.lastAddress().trim()
        store.onSettings {
            it.copy(
                pairedAdapters = paired,
                adapterList = listState,
                selectedAdapterAddress = selected,
                setupNeeded = selected.isEmpty(),
            )
        }
    }

    private fun pickAdapter(
        address: String,
        name: String,
    ) {
        if (deviceCatalog.remember(address, name).isBlank()) return
        refreshAdapters()
        connectLastAdapter()
    }

    /** The picker's "Allow" / "Turn on Bluetooth" button. */
    private fun allowBluetooth() {
        when {
            !hasConnectPermission() -> connectPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            BluetoothAdapters.get(this)?.isEnabled != true ->
                try {
                    startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (ex: SecurityException) {
                    Log.w(AppPrefs.LOG_TAG, "Bluetooth enable prompt refused", ex)
                } catch (ex: RuntimeException) {
                    Log.w(AppPrefs.LOG_TAG, "Bluetooth enable prompt failed", ex)
                }
        }
    }

    private fun openBluetoothSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "Bluetooth settings unavailable", ex)
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

    // In-process, not startService: a start command waits on pending prefs writes (ANR on pause).
    private fun signalAppForeground(foreground: Boolean) = AppVisibility.report(foreground)

    // ===== Settings tools: the same helpers the classic dashboard drives =====================

    internal fun runCommand(command: SettingsCommand) {
        when (command) {
            SettingsCommand.TestConnection -> troubleshooter.startTestConnection()
            SettingsCommand.SendDiagnostics -> troubleshooter.shareDiagnostics()
            is SettingsCommand.WaitForAdapter -> waitForAdapter(command.on, command.minutes)
            is SettingsCommand.BackUp -> backUp(command.passphrase)
            is SettingsCommand.Restore -> restore(command.passphrase)
            SettingsCommand.ExportTrips -> exportTrips()
            is SettingsCommand.PickAdapter -> pickAdapter(command.address, command.name)
            SettingsCommand.OpenBluetoothSettings -> openBluetoothSettings()
            SettingsCommand.AllowBluetooth -> allowBluetooth()
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
            INSIGHTS_VIEW -> history.loadInsights()
            HEALTH_VIEW -> history.loadHealth()
        }
    }

    internal fun selectInsightsPeriod(period: InsightsPeriod) {
        store.selectInsightsPeriod(period)
        history.loadInsights()
    }

    private fun selectTrip(routeKey: String) {
        store.selectTrip(routeKey)
        history.loadRoute()
    }

    /** The state the screens render (tests read it back). */
    internal fun uiState(): VoltAppUiState = store.state.value

    /** Reads the Charge, Trips and Insights tabs' history (tests swap its readers). */
    internal val history: ComposeHistoryLoader by lazy {
        ComposeHistoryLoader(
            backgroundExecutor,
            store,
            ::runOnUiThread,
            openStore = { ObdLocalStore(applicationContext) },
            dtcCatalog = { dtcCatalog },
            dtcChecks = { dtc.lastScanAtMs() to dtc.lastClearAtMs() },
        )
    }

    private fun readDtcCatalog(): DtcCatalog =
        try {
            assets.open(DtcCatalog.ASSET_PATH).bufferedReader().use { DtcCatalog.parse(it.readText()) }
        } catch (ex: IOException) {
            Log.w(AppPrefs.LOG_TAG, "DTC table unavailable", ex)
            DtcCatalog.EMPTY
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
        const val INSIGHTS_VIEW = "insights"

        /** The Car tab and Health both report "diagnostics"; both show the trouble codes. */
        const val HEALTH_VIEW = "diagnostics"
        const val BACKUP_RECEIPT = "setBackupReceipt"
    }
}
