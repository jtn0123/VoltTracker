package com.volttracker.obdpoc

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.location.LocationManagerTracker
import com.volttracker.obdpoc.location.LocationTracker
import com.volttracker.obdpoc.widget.WidgetUpdater
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground service that owns an OBD logging session: Android lifecycle, session start/stop,
 * foreground notification, GPS tracking, and status broadcasts to the dashboard.
 */
open class ObdService :
    Service(),
    EngineHost {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val competingAppExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val telemetrySideEffectExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    override val ioLock = Any()

    override val running = AtomicBoolean(false)

    override val androidContext: Context
        get() = this

    private val sessionStateMachine = SessionStateMachine()
    private val sessionToken = AtomicLong()
    private val runnerSessionToken = ThreadLocal<Long>()
    private var activeTask: Future<*>? = null

    override var localStore: ObdLocalStore? = null

    override var recorder: SessionRecorder =
        SessionRecorder(
            Any(),
            ObdSessionLog(
                File(System.getProperty("java.io.tmpdir"), "volttracker-service-recorder-${System.nanoTime()}"),
            ),
            null,
        )
    private lateinit var engine: ObdPollingEngine
    private lateinit var notifications: ObdNotifications

    // Created in onCreate, hooks called from both the main thread (onSessionStart) and the poll/IO
    // thread (broadcastTelemetry → onTelemetry), so the reference is @Volatile. (Report item B1.)
    @Volatile
    private var eventCoordinator: EventNotificationCoordinator? = null

    // Persists a compact widget snapshot and nudges the home-screen widget when state changes.
    // Nullable + guarded: created in onCreate so test subclasses that drive broadcast* directly
    // without onCreate (or before it) simply skip the widget hook instead of crashing.
    private var widgetUpdater: WidgetUpdater? = null
    private val latestWidgetTelemetry = AtomicReference<JSONObject?>()
    private val widgetTelemetryScheduled = AtomicBoolean(false)
    private val coalescedWidgetTelemetryCount = AtomicLong()

    // The process-wide app-log mirror installed in onCreate. Held so onDestroy can detach it from
    // OBDLog and release the long-lived buffered-writer file handle (G1) instead of leaking it for
    // the rest of the process lifetime.
    private var rollingAppLog: RollingAppLog? = null

    override var locationTracker: LocationTracker? = null

    override var bluetoothObservability: BluetoothStateReporter? = null

    @JvmField var sdpProbe: SdpProbe? = null

    @JvmField var competingAppDetector: CompetingAppDetector? = null

    @JvmField var voltageProbe: VoltageProbe? = null

    override var activeName = DEFAULT_ADAPTER_NAME

    override var sessionStartedAtMs = 0L

    // Written by broadcastStatus on the poll/IO thread (NOT under ioLock) and read under ioLock in
    // closeSessionLog to finalize the session row, so they must be @Volatile — otherwise a session
    // that errored at the very end could be persisted with a stale state/detail. (The sibling
    // cross-thread flags appInForeground / lastFailureClass are already @Volatile.)
    @Volatile
    private var lastSessionState = ""

    @Volatile
    private var lastSessionDetail = ""

    @Volatile
    override var appInForeground = true

    @Volatile
    override var foregroundServiceActive = false

    @Volatile
    override var cancelRetryRequested = false

    @Volatile private var lastFailureClass: FailureClass? = null

    @Volatile private var lastVoltage: Double? = null

    @Volatile private var competingAppsCsv: String? = null
    private var activeForegroundServiceType = 0
    private var sessionWakeLock: PowerManager.WakeLock? = null

    private class SessionStartRequest(
        val mode: String,
        val address: String?,
        val foregroundText: String,
        val phase: SessionStateMachine.Phase,
        val phaseDetail: String,
        val engineMode: String,
        val resetCancelRetry: Boolean,
        val refreshCompetingApps: Boolean,
        val startLocationTracking: Boolean,
        // True for sessions doing real adapter IO whose recording must survive screen-off
        // (see acquireSessionWakeLock); the demo preview deliberately opts out.
        val holdWakeLock: Boolean = true,
        val runner: Runnable,
    )

    fun requestCancelRetry() {
        cancelRetryRequested = true
    }

    override fun markSessionInactive() {
        if (!canCurrentThreadCleanupSession()) {
            return
        }
        running.set(false)
        SESSION_ACTIVE.set(false)
        sessionStateMachine.stop("inactive")
    }

    fun setLastVoltage(volts: Double) {
        lastVoltage = volts
    }

    fun setCompetingApps(csv: String?) {
        competingAppsCsv = csv
    }

    override fun onCreate() {
        super.onCreate()
        localStore = ObdLocalStore(this)
        locationTracker = LocationManagerTracker(this)
        notifications = ObdNotifications(this)
        notifications.createChannel()
        eventCoordinator = createEventCoordinator()
        widgetUpdater = createWidgetUpdater()
        rollingAppLog = RollingAppLog(File(filesDir, "app-log"))
        OBDLog.mirror(rollingAppLog)
        val summaryStore = SessionSummaryStore.getInstance(filesDir)
        recorder =
            SessionRecorder(
                ioLock,
                ObdSessionLog(File(filesDir, "obd-logs")),
                localStore,
                summaryStore,
            ) {
                SystemSnapshot.collect(this, summaryStore)
            }
        engine = createPollingEngine()
        sdpProbe = SdpProbe(this)
        bluetoothObservability = BluetoothStateReporter(this, sdpProbe)
        voltageProbe = VoltageProbe(this)
        competingAppDetector = CompetingAppDetector(packageManager, this, recorder, packageName)
        if (hasBluetoothConnectPermission()) {
            bluetoothObservability?.register(this)
        } else {
            recorder.logEvent("bluetooth_reporter_skipped", "reason", "missing_bluetooth_connect")
        }
        refreshCompetingAppsAsync()
    }

    /**
     * Factory for the polling engine, created once in [onCreate]. Behavior-identical to the inline
     * `ObdPollingEngine(this)` it replaces; it exists only so a test subclass can substitute an
     * engine whose IO loops are neutralized (the real loops open a Bluetooth RFCOMM socket that
     * cannot run under Robolectric), letting the action-dispatch orchestration be driven directly.
     */
    open fun createPollingEngine(): ObdPollingEngine = ObdPollingEngine(this)

    /**
     * Factory for the event-notification coordinator (M1 + M3). Reads the native-owned settings from
     * the shared prefs file the dashboard writes via the bridge, posts alerts through [EventNotifier]
     * on its own "alerts" channel, and gates the on-connect auto-scan via [AutoScanController].
     * `open` so a test subclass can substitute a fake.
     */
    open fun createEventCoordinator(): EventNotificationCoordinator {
        val sharedPrefs = getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        val eventPrefs = EventNotificationPrefs(sharedPrefs)
        val notifier = EventNotifier(this)
        notifier.createChannel()
        return EventNotificationCoordinator(eventPrefs, notifier, AutoScanController(eventPrefs))
    }

    /**
     * Factory for the home-screen-widget updater (M10a). It persists a compact snapshot to the
     * shared-prefs file and nudges [com.volttracker.obdpoc.widget.VoltWidgetProvider] when the
     * displayed state changes. `open` so a test subclass can substitute a fake or a no-op.
     */
    open fun createWidgetUpdater(): WidgetUpdater = WidgetUpdater(this)

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopCurrentSession(getString(R.string.status_disconnected))
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                foregroundServiceActive = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_APP_FOREGROUND -> {
                recordAppVisibility(true)
                val active = running.get()
                if (!active) stopSelf(startId)
                return if (active) START_STICKY else START_NOT_STICKY
            }
            ACTION_APP_BACKGROUND -> {
                recordAppVisibility(false)
                val active = running.get()
                if (!active) stopSelf(startId)
                return if (active) START_STICKY else START_NOT_STICKY
            }
            ACTION_CANCEL_RETRY -> {
                requestCancelRetry()
                broadcastStatus("idle", getString(R.string.status_retry_cancelled), false)
                val active = running.get()
                if (!active) stopSelf(startId)
                return if (active) START_STICKY else START_NOT_STICKY
            }
            ACTION_DEMO -> {
                activeName = "Demo stream"
                startDemoSession()
                return START_STICKY
            }
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                activeName = adapterNameFrom(intent)
                startObdSession(address, false)
                return START_STICKY
            }
            ACTION_SCAN -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                activeName = adapterNameFrom(intent)
                startObdSession(address, true)
                return START_STICKY
            }
            ACTION_TPMS_SCAN -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                activeName = adapterNameFrom(intent)
                startTpmsScanSession(address, intent.getStringExtra(EXTRA_DETAIL_STAGE))
                return START_STICKY
            }
            ACTION_CLEAR_DTC -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                activeName = adapterNameFrom(intent)
                startClearDtcSession(address)
                return START_STICKY
            }
        }
        // Null intent (START_STICKY restart after process death) or an unrecognized action:
        // there is nothing to do, but onCreate already opened the SQLite store, registered
        // receivers, and started executors. Without a session, stop instead of lingering as
        // an invisible orphaned service.
        if (!running.get()) {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCurrentSession(getString(R.string.status_service_stopped))
        bluetoothObservability?.unregister(this)
        executor.shutdownNow()
        competingAppExecutor.shutdownNow()
        telemetrySideEffectExecutor.shutdownNow()
        recorder.shutdown()
        localStore?.close()
        localStore = null
        // Detach the app-log mirror before releasing its handle so any late OBDLog call becomes a
        // no-op rather than lazily reopening the writer we're about to close.
        OBDLog.mirror(null)
        rollingAppLog?.close()
        rollingAppLog = null
        super.onDestroy()
    }

    override fun maybeRunVoltageProbe(engineRef: ObdPollingEngine?) {
        if (voltageProbe == null || engineRef == null) {
            return
        }
        voltageProbe?.run(engineRef::transactOneShot)
    }

    override fun maybeRunAutoDtcScan(engineRef: ObdPollingEngine?) {
        try {
            eventCoordinator?.maybeRunAutoDtcScan(engineRef)
        } catch (ex: RuntimeException) {
            // A notification/auto-scan failure must never break the live session.
            Log.w(MainActivity.TAG, "auto DTC scan failed", ex)
        }
    }

    private fun maybePostEventNotifications(payload: JSONObject) {
        try {
            eventCoordinator?.onTelemetry(payload)
        } catch (ex: RuntimeException) {
            Log.w(MainActivity.TAG, "event notification dispatch failed", ex)
        }
    }

    private fun startObdSession(
        address: String?,
        scanMode: Boolean,
    ) {
        startSession(
            SessionStartRequest(
                if (scanMode) "scan" else "obd",
                address,
                getString(if (scanMode) R.string.foreground_scanning else R.string.foreground_connecting, activeName),
                if (scanMode) SessionStateMachine.Phase.SCANNING else SessionStateMachine.Phase.CONNECTING,
                getString(if (scanMode) R.string.status_scan_starting else R.string.status_connecting_adapter),
                "",
                resetCancelRetry = true,
                refreshCompetingApps = true,
                startLocationTracking = true,
            ) {
                engine.runBluetoothLoop(address, scanMode)
            },
        )
    }

    private fun startTpmsScanSession(
        address: String?,
        stage: String?,
    ) {
        val normalizedStage = EnhancedPidProfiles.normalizeStage(stage)
        startSession(
            SessionStartRequest(
                "tpms-scan",
                address,
                getString(R.string.foreground_detail_probe, normalizedStage, activeName),
                SessionStateMachine.Phase.SCANNING,
                getString(R.string.status_detail_probe_starting),
                "detail-probe:$normalizedStage",
                resetCancelRetry = true,
                refreshCompetingApps = true,
                startLocationTracking = false,
            ) {
                engine.runDetailProbeLoop(address, normalizedStage)
            },
        )
    }

    private fun startClearDtcSession(address: String?) {
        startSession(
            SessionStartRequest(
                "clear-dtc",
                address,
                getString(R.string.foreground_clearing_codes, activeName),
                SessionStateMachine.Phase.CLEAR_DTC,
                getString(R.string.status_clear_dtc_preparing),
                "",
                resetCancelRetry = true,
                refreshCompetingApps = true,
                startLocationTracking = true,
            ) {
                engine.runBluetoothLoop(address, false, true)
            },
        )
    }

    private fun refreshCompetingAppsAsync() {
        val detectorRef = competingAppDetector ?: return
        try {
            competingAppExecutor.execute {
                try {
                    detectorRef.refresh()
                } catch (ex: RuntimeException) {
                    Log.w(MainActivity.TAG, "competing-app refresh failed", ex)
                }
            }
        } catch (ex: RuntimeException) {
            Log.w(MainActivity.TAG, "competing-app refresh rejected", ex)
        }
    }

    private fun startDemoSession() {
        startSession(
            SessionStartRequest(
                "demo",
                null,
                getString(R.string.foreground_demo),
                SessionStateMachine.Phase.DEMO,
                getString(R.string.status_demo_starting),
                "demo",
                resetCancelRetry = false,
                refreshCompetingApps = false,
                startLocationTracking = false,
                holdWakeLock = false,
                runner = engine::runDemoLoop,
            ),
        )
    }

    private fun startSession(request: SessionStartRequest) {
        // Invalidate the previous runner's token BEFORE interrupting it: a stale runner that
        // races canCurrentThreadCleanupSession() after the interrupt must never match the
        // current token, or it could tear down the NEW session's running flag / session log.
        val token = sessionToken.incrementAndGet()
        stopCurrentSession(null)
        if (request.resetCancelRetry) {
            cancelRetryRequested = false
        }
        if (request.refreshCompetingApps) {
            refreshCompetingAppsAsync()
        }
        if (!startForegroundSession(request.foregroundText)) {
            broadcastStatus("blocked", getString(R.string.status_foreground_blocked), true)
            return
        }
        if (request.holdWakeLock) {
            acquireSessionWakeLock(request.mode)
        }
        sessionStartedAtMs = System.currentTimeMillis()
        sessionStateMachine.start(request.phase, request.phaseDetail)
        // Clear any voltage carried over from a prior session: if this connect's 0142 probe
        // doesn't run, broadcastStatus must not re-emit the previous drive's reading into the
        // low-voltage hint / adapter-ready check.
        lastVoltage = null
        engine.beginSession(request.engineMode)
        try {
            eventCoordinator?.onSessionStart()
        } catch (ex: RuntimeException) {
            // A notification session-start hook failure must never abort session startup.
            Log.w(MainActivity.TAG, "event notification session-start hook failed", ex)
        }
        openSessionLog(request.mode, request.address)
        if (request.startLocationTracking) {
            startLocationTracking()
        }
        running.set(true)
        SESSION_ACTIVE.set(true)
        try {
            activeTask = executor.submit { runSessionTask(token, request.runner) }
        } catch (ex: RuntimeException) {
            Log.w(MainActivity.TAG, "session task submit failed", ex)
            broadcastStatus("error", getString(R.string.status_worker_start_failed), true)
            stopCurrentSession(null)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundServiceActive = false
            stopSelf()
        }
    }

    private fun runSessionTask(
        token: Long,
        runner: Runnable,
    ) {
        runnerSessionToken.set(token)
        try {
            runner.run()
        } finally {
            runnerSessionToken.remove()
        }
    }

    private fun canCurrentThreadCleanupSession(): Boolean {
        val token = runnerSessionToken.get()
        return token == null || token == sessionToken.get()
    }

    private fun startLocationTracking() {
        val tracker = locationTracker ?: return
        // Start the tracker even without permission: it parks the listener so a mid-session
        // grant can be resumed via resumeLocationTrackingIfPermitted() on the next foreground.
        tracker.start(recorder::persistLocation)
        if (!hasLocationPermission()) {
            recorder.logEvent("gps_skipped", "reason", "missing_location_permission")
            return
        }
        recorder.logEvent("gps_started")
    }

    /**
     * Begins GPS updates for a session that started without location permission once the user has
     * granted it (the grant flow foregrounds the app, which lands here on the main thread).
     */
    private fun resumeLocationTrackingIfPermitted() {
        if (!running.get()) {
            return
        }
        if (locationTracker?.resumeUpdatesIfPermitted() == true) {
            recorder.logEvent("gps_started", "reason", "permission_granted_mid_session")
            // The session may have entered the foreground state without the location service
            // type (no permission at start). Upgrade it now that GPS is delivering; the call
            // self-guards on running/foreground-active/SDK like the visibility path does.
            reevaluateForegroundServiceType()
        }
    }

    private fun stopLocationTracking() {
        locationTracker?.stop()
        recorder.logEvent("gps_stopped")
    }

    /**
     * Holds the CPU awake while a real adapter session is recording. The foreground service
     * keeps the process alive, but on some OEM builds Doze can still suspend the CPU with the
     * screen off mid-drive, stalling the Bluetooth polling thread and punching gaps into the
     * session. Callers opt in via [SessionStartRequest.holdWakeLock]; [mode] is only logged.
     */
    private fun acquireSessionWakeLock(mode: String) {
        if (sessionWakeLock?.isHeld == true) {
            return
        }
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            lock.setReferenceCounted(false)
            // Timeout is a leak ceiling, not the session length: stopCurrentSession() releases
            // on every session end; the ceiling only catches a missed release.
            lock.acquire(SESSION_WAKE_LOCK_TIMEOUT_MS)
            sessionWakeLock = lock
            recorder.logEvent("wake_lock_acquired", "mode", mode)
        } catch (ex: RuntimeException) {
            Log.w(MainActivity.TAG, "session wake lock acquire failed", ex)
        }
    }

    private fun releaseSessionWakeLock() {
        val lock = sessionWakeLock ?: return
        sessionWakeLock = null
        try {
            if (lock.isHeld) {
                lock.release()
            }
        } catch (ex: RuntimeException) {
            Log.w(MainActivity.TAG, "session wake lock release failed", ex)
        }
    }

    private fun stopCurrentSession(statusMessage: String?) {
        releaseSessionWakeLock()
        running.set(false)
        SESSION_ACTIVE.set(false)
        sessionStateMachine.stop(statusMessage)
        activeTask?.cancel(true)
        activeTask = null
        stopLocationTracking()
        if (::engine.isInitialized) {
            engine.closeSocket()
        }
        if (statusMessage != null) {
            broadcastStatus("idle", statusMessage, false)
        }
        closeSessionLog()
    }

    override fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    override fun hasBluetoothScanPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun broadcastTelemetry(payload: JSONObject?) {
        broadcastTelemetry(TelemetryPayload.fromJson(payload))
    }

    open fun broadcastTelemetry(telemetry: TelemetryPayload?) {
        if (telemetry == null || telemetry.isEmpty()) {
            return
        }
        val payload = telemetry.toJson()
        recorder.logJson("telemetry", payload)
        recorder.persistTelemetry(payload)
        enqueueEventNotifications(payload)
        enqueueWidgetTelemetry(payload)
        broadcast(BROADCAST_TELEMETRY, payload)
    }

    private fun enqueueEventNotifications(payload: JSONObject) {
        try {
            telemetrySideEffectExecutor.execute {
                maybePostEventNotifications(payload)
            }
        } catch (ex: RejectedExecutionException) {
            Log.w(MainActivity.TAG, "event notification enqueue failed", ex)
        }
    }

    private fun enqueueWidgetTelemetry(payload: JSONObject) {
        latestWidgetTelemetry.set(payload)
        if (!widgetTelemetryScheduled.compareAndSet(false, true)) {
            coalescedWidgetTelemetryCount.incrementAndGet()
            return
        }
        try {
            telemetrySideEffectExecutor.schedule(
                {
                    val latest = latestWidgetTelemetry.getAndSet(null)
                    if (latest != null) {
                        maybeUpdateWidgetTelemetry(latest)
                    }
                    widgetTelemetryScheduled.set(false)
                    if (latestWidgetTelemetry.get() != null) {
                        enqueueWidgetTelemetry(latestWidgetTelemetry.get()!!)
                    }
                },
                WIDGET_TELEMETRY_COALESCE_MS,
                TimeUnit.MILLISECONDS,
            )
        } catch (ex: RejectedExecutionException) {
            widgetTelemetryScheduled.set(false)
            Log.w(MainActivity.TAG, "widget telemetry enqueue failed", ex)
        }
    }

    fun drainCoalescedWidgetTelemetryCountForTest(): Long = coalescedWidgetTelemetryCount.getAndSet(0L)

    private fun maybeUpdateWidgetTelemetry(payload: JSONObject) {
        try {
            widgetUpdater?.onTelemetry(payload)
        } catch (ex: RuntimeException) {
            // The widget snapshot is best-effort and must never break the live telemetry path.
            Log.w(MainActivity.TAG, "widget telemetry hook failed", ex)
        }
    }

    private fun maybeUpdateWidgetStatus(state: String?) {
        try {
            widgetUpdater?.onStatus(state)
        } catch (ex: RuntimeException) {
            Log.w(MainActivity.TAG, "widget status hook failed", ex)
        }
    }

    override fun broadcastStatus(
        state: String?,
        detail: String?,
        blocked: Boolean,
    ) {
        broadcastStatus(state, detail, blocked, null)
    }

    open fun broadcastStatus(
        state: String?,
        detail: String?,
        blocked: Boolean,
        extras: JSONObject?,
    ) {
        val status =
            StatusPayload(
                state,
                detail,
                blocked,
                activeName,
                System.currentTimeMillis(),
                recorder.logFileName(),
                lastFailureClass,
                lastVoltage,
                competingAppsCsv,
                extras,
            )
        val payload = status.toJson()
        lastSessionState = state ?: ""
        lastSessionDetail = detail ?: ""
        sessionStateMachine.observeStatus(state, detail, blocked)
        recorder.logJson("status", payload)
        recorder.persistStatus(state, detail, blocked, payload)
        maybeUpdateWidgetStatus(state)
        broadcast(BROADCAST_STATUS, payload)
    }

    private fun broadcast(
        action: String,
        payload: JSONObject,
    ) {
        val intent = Intent(action)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_JSON, payload.toString())
        sendBroadcast(intent)
    }

    private fun startForegroundSession(text: String): Boolean {
        val notification = notifications.build(text)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceType = currentForegroundServiceType()
                startForeground(ObdNotifications.NOTIFICATION_ID, notification, serviceType)
                activeForegroundServiceType = serviceType
                foregroundServiceActive = true
                true
            } else {
                startForeground(ObdNotifications.NOTIFICATION_ID, notification)
                foregroundServiceActive = true
                true
            }
        } catch (ex: SecurityException) {
            onStartForegroundRefused("startForegroundSession", ex)
            false
        } catch (ex: IllegalStateException) {
            // API 31+ throws ForegroundServiceStartNotAllowedException (an IllegalStateException
            // subclass) instead of SecurityException when background FGS starts are blocked;
            // route it to the same "blocked" fallback instead of crashing the process.
            onStartForegroundRefused("startForegroundSession", ex)
            false
        }
    }

    private fun onStartForegroundRefused(
        where: String,
        ex: RuntimeException,
    ) {
        Log.w(MainActivity.TAG, "$where refused", ex)
        foregroundServiceActive = false
        activeForegroundServiceType = 0
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun currentForegroundServiceType(): Int {
        var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (hasLocationPermission()) {
            serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return serviceType
    }

    private fun reevaluateForegroundServiceType() {
        if (!running.get() || !foregroundServiceActive) {
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        val desired = currentForegroundServiceType()
        if (desired == activeForegroundServiceType) {
            return
        }
        val notification: Notification =
            notifications.build(foregroundNotificationText())
        try {
            startForeground(ObdNotifications.NOTIFICATION_ID, notification, desired)
            activeForegroundServiceType = desired
            recorder.logEvent(
                "foreground_service_type_changed",
                "type",
                Integer.toHexString(desired),
                "hasLocation",
                hasLocationPermission().toString(),
            )
        } catch (ex: SecurityException) {
            Log.w(MainActivity.TAG, "reevaluateForegroundServiceType refused", ex)
        } catch (ex: IllegalStateException) {
            // See startForegroundSession: API 31+ ForegroundServiceStartNotAllowedException.
            // The session keeps its existing foreground type; only the upgrade is skipped.
            Log.w(MainActivity.TAG, "reevaluateForegroundServiceType refused", ex)
        }
    }

    private fun foregroundNotificationText(): String =
        getString(
            if (appInForeground) R.string.notification_logging_foreground else R.string.notification_logging_background,
        )

    override fun updateNotification(text: String?) {
        recorder.logEvent("notification", "text", text)
        notifications.post(text ?: "")
    }

    override fun setLastFailureClass(fc: FailureClass?) {
        if (fc == null) {
            return
        }
        lastFailureClass = fc
    }

    override fun clearLastFailureClass() {
        lastFailureClass = null
    }

    fun lastFailureClass(): FailureClass? = lastFailureClass

    private fun recordAppVisibility(foreground: Boolean) {
        if (foreground) {
            // Runs before the unchanged-visibility check: a permission grant doesn't always
            // bounce visibility, but every grant flow ends with the app reported foreground.
            resumeLocationTrackingIfPermitted()
        }
        if (appInForeground == foreground) {
            return
        }
        appInForeground = foreground
        recorder.runAsync { applyAppVisibility(foreground) }
    }

    private fun applyAppVisibility(foreground: Boolean) {
        synchronized(ioLock) {
            recorder.logEvent(
                if (foreground) "app_foregrounded" else "app_backgrounded",
                "backgroundSampleCount",
                engine.backgroundSampleCount().toString(),
                "sampleGapCount",
                engine.sampleGapCount().toString(),
            )
            if (running.get()) {
                updateNotification(foregroundNotificationText())
                reevaluateForegroundServiceType()
            }
        }
    }

    private fun openSessionLog(
        mode: String,
        address: String?,
    ) {
        synchronized(ioLock) {
            lastSessionState = "active"
            lastSessionDetail = ""
            recorder.openSession(
                mode,
                address,
                activeName,
                if (sessionStartedAtMs > 0) sessionStartedAtMs else System.currentTimeMillis(),
            )
        }
    }

    override fun closeSessionLog() {
        if (!canCurrentThreadCleanupSession()) {
            return
        }
        synchronized(ioLock) {
            if (!::engine.isInitialized) {
                return
            }
            recorder.closeSession(
                lastSessionState,
                lastSessionDetail,
                engine.supportedPidsSummary(),
                engine.sampleCount(),
                lastFailureClass,
            )
            clearLastFailureClass()
            foregroundServiceActive = false
        }
    }

    companion object {
        const val ACTION_CONNECT = "com.volttracker.obdpoc.action.CONNECT"
        const val ACTION_SCAN = "com.volttracker.obdpoc.action.SCAN"
        const val ACTION_TPMS_SCAN = "com.volttracker.obdpoc.action.TPMS_SCAN"
        const val ACTION_CLEAR_DTC = "com.volttracker.obdpoc.action.CLEAR_DTC"
        const val ACTION_DEMO = "com.volttracker.obdpoc.action.DEMO"
        const val ACTION_DISCONNECT = "com.volttracker.obdpoc.action.DISCONNECT"
        const val ACTION_APP_FOREGROUND = "com.volttracker.obdpoc.action.APP_FOREGROUND"
        const val ACTION_APP_BACKGROUND = "com.volttracker.obdpoc.action.APP_BACKGROUND"
        const val ACTION_CANCEL_RETRY = "com.volttracker.obdpoc.action.CANCEL_RETRY"
        const val BROADCAST_TELEMETRY = "com.volttracker.obdpoc.broadcast.TELEMETRY"
        const val BROADCAST_STATUS = "com.volttracker.obdpoc.broadcast.STATUS"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NAME = "name"
        const val EXTRA_JSON = "json"
        const val EXTRA_DETAIL_STAGE = "detail_stage"
        private const val DEFAULT_ADAPTER_NAME = "OBD adapter"
        private const val WAKE_LOCK_TAG = "VoltTracker:ObdSession"
        private const val WIDGET_TELEMETRY_COALESCE_MS = 500L

        // Leak ceiling only (see acquireSessionWakeLock); generously above any realistic drive.
        private const val SESSION_WAKE_LOCK_TIMEOUT_MS = 12L * 60L * 60L * 1000L
        private val SESSION_ACTIVE = AtomicBoolean(false)

        @JvmStatic
        fun hasActiveSession(): Boolean = SESSION_ACTIVE.get()

        @JvmStatic
        fun adapterNameFrom(intent: Intent?): String {
            val name = intent?.getStringExtra(EXTRA_NAME)
            return if (name.isNullOrBlank()) DEFAULT_ADAPTER_NAME else name
        }
    }
}
