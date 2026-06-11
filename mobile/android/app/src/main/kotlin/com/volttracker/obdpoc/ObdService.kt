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
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.location.LocationManagerTracker
import com.volttracker.obdpoc.location.LocationTracker
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service that owns an OBD logging session: Android lifecycle, session start/stop,
 * foreground notification, GPS tracking, and status broadcasts to the dashboard.
 */
open class ObdService :
    Service(),
    EngineHost {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val competingAppExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override val ioLock = Any()

    override val running = AtomicBoolean(false)

    override val androidContext: Context
        get() = this

    @JvmField val sessionStateMachine = SessionStateMachine()
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

    override var locationTracker: LocationTracker? = null

    override var bluetoothObservability: BluetoothStateReporter? = null

    @JvmField var sdpProbe: SdpProbe? = null

    @JvmField var competingAppDetector: CompetingAppDetector? = null

    @JvmField var voltageProbe: VoltageProbe? = null

    override var activeName = "OBD adapter"

    override var sessionStartedAtMs = 0L
    private var lastSessionState = ""
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

    fun getLastVoltage(): Double? = lastVoltage

    fun getCompetingAppsCsv(): String? = competingAppsCsv

    override fun onCreate() {
        super.onCreate()
        localStore = ObdLocalStore(this)
        locationTracker = LocationManagerTracker(this)
        notifications = ObdNotifications(this)
        notifications.createChannel()
        val rollingAppLog = RollingAppLog(File(filesDir, "app-log"))
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

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopCurrentSession("Disconnected.")
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                foregroundServiceActive = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_APP_FOREGROUND -> {
                recordAppVisibility(true)
                if (!running.get()) stopSelf(startId)
                return if (running.get()) START_STICKY else START_NOT_STICKY
            }
            ACTION_APP_BACKGROUND -> {
                recordAppVisibility(false)
                if (!running.get()) stopSelf(startId)
                return if (running.get()) START_STICKY else START_NOT_STICKY
            }
            ACTION_CANCEL_RETRY -> {
                requestCancelRetry()
                broadcastStatus("idle", "Retry cancelled.", false)
                if (!running.get()) stopSelf(startId)
                return if (running.get()) START_STICKY else START_NOT_STICKY
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
        stopCurrentSession("Service stopped.")
        bluetoothObservability?.unregister(this)
        executor.shutdownNow()
        competingAppExecutor.shutdownNow()
        recorder.shutdown()
        localStore?.close()
        localStore = null
        super.onDestroy()
    }

    override fun maybeRunVoltageProbe(engineRef: ObdPollingEngine?) {
        if (voltageProbe == null || engineRef == null) {
            return
        }
        voltageProbe?.run(engineRef::transactOneShot)
    }

    private fun startObdSession(
        address: String?,
        scanMode: Boolean,
    ) {
        startSession(
            SessionStartRequest(
                if (scanMode) "scan" else "obd",
                address,
                (if (scanMode) "Scanning " else "Connecting to ") + activeName,
                if (scanMode) SessionStateMachine.Phase.SCANNING else SessionStateMachine.Phase.CONNECTING,
                if (scanMode) "Starting diagnostic scan." else "Connecting to adapter.",
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
                "Detail Probe ($normalizedStage) on $activeName",
                SessionStateMachine.Phase.SCANNING,
                "Starting detailed signal probe.",
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
                "Clearing codes on $activeName",
                SessionStateMachine.Phase.CLEAR_DTC,
                "Preparing to clear diagnostic codes.",
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
                "Running demo telemetry",
                SessionStateMachine.Phase.DEMO,
                "Demo telemetry starting.",
                "demo",
                resetCancelRetry = false,
                refreshCompetingApps = false,
                startLocationTracking = false,
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
            broadcastStatus(
                "blocked",
                "Android blocked foreground logging. Check notification and nearby-device permissions, then try again.",
                true,
            )
            return
        }
        sessionStartedAtMs = System.currentTimeMillis()
        sessionStateMachine.start(request.phase, request.phaseDetail)
        engine.beginSession(request.engineMode)
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
            broadcastStatus("error", "Could not start the OBD logging worker.", true)
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
        if (!hasLocationPermission()) {
            recorder.logEvent("gps_skipped", "reason", "missing_location_permission")
            return
        }
        tracker.start(recorder::persistLocation)
        recorder.logEvent("gps_started")
    }

    private fun stopLocationTracking() {
        locationTracker?.stop()
        recorder.logEvent("gps_stopped")
    }

    private fun stopCurrentSession(statusMessage: String?) {
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
        broadcast(BROADCAST_TELEMETRY, payload)
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
            notifications.build(if (appInForeground) "Logging while app is open" else "Background logging active")
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
                updateNotification(if (foreground) "Logging while app is open" else "Background logging active")
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
        private val SESSION_ACTIVE = AtomicBoolean(false)

        @JvmStatic
        fun hasActiveSession(): Boolean = SESSION_ACTIVE.get()

        @JvmStatic
        fun adapterNameFrom(intent: Intent?): String {
            val name = intent?.getStringExtra(EXTRA_NAME)
            return if (name.isNullOrBlank()) "OBD adapter" else name
        }
    }
}
