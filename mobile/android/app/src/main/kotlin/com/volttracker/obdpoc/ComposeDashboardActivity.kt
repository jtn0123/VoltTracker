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
import com.volttracker.obdpoc.service.ObdService
import com.volttracker.obdpoc.service.ObdServiceLauncher
import com.volttracker.obdpoc.ui.VoltApp
import com.volttracker.obdpoc.ui.VoltAppActions
import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import com.volttracker.obdpoc.ui.theme.AppearanceMode
import com.volttracker.obdpoc.ui.theme.AppearancePrefs
import com.volttracker.obdpoc.update.UpdateCoordinator
import com.volttracker.obdpoc.update.UpdateManager
import org.json.JSONObject

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
class ComposeDashboardActivity : ComponentActivity() {
    private val store = LiveUiStateStore()
    private lateinit var prefs: SharedPreferences
    private lateinit var deviceCatalog: DeviceCatalog
    private lateinit var autoConnect: AutoConnectController

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
                // The native screens only show a short status label, so a refused start (e.g. a
                // missing Nearby devices permission) would otherwise vanish without a trace.
                ComposeDashboardSupport.blockedStatusDetail(intent.action, json)?.let(::showMessage)
            }
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
        updates = UpdateCoordinator.shared(this)
        store.onVersionLabel("Volt Tracker ${BuildConfig.VERSION_NAME}")
        store.onAppearance(AppearancePrefs.read(prefs))
        store.onDrivePrefs(AppearancePrefs.readDriveDetailed(prefs), AppearancePrefs.readDriveEnergyFlow(prefs))
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
                        onStartDemo = { startObd(ObdService.ACTION_DEMO, null, null) },
                        onStopDemo = ::stopSession,
                        onCheckForUpdate = ::checkForUpdate,
                        onInstallUpdate = ::installUpdate,
                        onSetAppearance = ::setAppearance,
                        onSetDriveDetailed = ::setDriveDetailed,
                        onSetDriveEnergyFlow = ::setDriveEnergyFlow,
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
        signalAppForeground(true)
        maybeAutoConnect()
        // One silent update check per process start — the auto half of
        // auto-update. Manual re-checks live in Settings.
        updates.autoCheckOnce(::publishUpdateResult)
    }

    override fun onPause() {
        try {
            unregisterReceiver(serviceReceiver)
        } catch (_: IllegalArgumentException) {
            // Not registered — nothing to do.
        }
        signalAppForeground(false)
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

    private fun setAppearance(mode: AppearanceMode) {
        AppearancePrefs.write(prefs, mode)
        store.onAppearance(mode)
    }

    private fun setDriveDetailed(detailed: Boolean) {
        AppearancePrefs.writeDriveDetailed(prefs, detailed)
        store.onDrivePrefs(detailed, AppearancePrefs.readDriveEnergyFlow(prefs))
    }

    private fun setDriveEnergyFlow(show: Boolean) {
        AppearancePrefs.writeDriveEnergyFlow(prefs, show)
        store.onDrivePrefs(AppearancePrefs.readDriveDetailed(prefs), show)
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
            startConnect = { address, name -> startObd(ObdService.ACTION_CONNECT, address, name) },
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
            ConnectAction.CONNECT -> startObd(ObdService.ACTION_CONNECT, address, deviceCatalog.lastName())
        }
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun startObd(
        action: String,
        address: String?,
        name: String?,
    ) {
        if (ObdService.isSessionStartAction(action) && DatabaseOperationLease.isHeld()) {
            return
        }
        val service = Intent(this, ObdService::class.java)
        service.action = action
        if (address != null) service.putExtra(ObdService.EXTRA_ADDRESS, address)
        if (name != null) service.putExtra(ObdService.EXTRA_NAME, name)
        try {
            ObdServiceLauncher.start(this, service)
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "startObd blocked", ex)
            showMessage(getString(R.string.status_obd_start_blocked))
        }
    }

    private fun showMessage(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    /** Ends the running session (Settings → Demo / testing → Stop), as the classic Disconnect does. */
    private fun stopSession() {
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
}
