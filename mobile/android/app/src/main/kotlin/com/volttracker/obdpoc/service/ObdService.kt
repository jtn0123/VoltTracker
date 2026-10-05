package com.volttracker.obdpoc.service

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.ServiceCompat
import com.volttracker.obdpoc.AppPrefs
import com.volttracker.obdpoc.AutoScanController
import com.volttracker.obdpoc.BluetoothStateReporter
import com.volttracker.obdpoc.BuildConfig
import com.volttracker.obdpoc.CarCommand
import com.volttracker.obdpoc.CompetingAppDetector
import com.volttracker.obdpoc.DatabaseOperationLease
import com.volttracker.obdpoc.EnhancedPidProfiles
import com.volttracker.obdpoc.EventNotificationCoordinator
import com.volttracker.obdpoc.EventNotificationPrefs
import com.volttracker.obdpoc.EventNotifier
import com.volttracker.obdpoc.FailureClass
import com.volttracker.obdpoc.LiveDashboardSnapshot
import com.volttracker.obdpoc.OBDLog
import com.volttracker.obdpoc.ObdSessionLog
import com.volttracker.obdpoc.R
import com.volttracker.obdpoc.RollingAppLog
import com.volttracker.obdpoc.SdpProbe
import com.volttracker.obdpoc.SessionOutcome
import com.volttracker.obdpoc.SessionStateMachine
import com.volttracker.obdpoc.SessionSummaryStore
import com.volttracker.obdpoc.StartupTrace
import com.volttracker.obdpoc.StatusPayload
import com.volttracker.obdpoc.SystemSnapshot
import com.volttracker.obdpoc.TelemetryPayload
import com.volttracker.obdpoc.TripSummaryNotifier
import com.volttracker.obdpoc.VoltageProbe
import com.volttracker.obdpoc.WidgetTelemetryCoalescer
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.data.ObdSessionRecovery
import com.volttracker.obdpoc.engine.EngineHost
import com.volttracker.obdpoc.engine.ObdPollingEngine
import com.volttracker.obdpoc.engine.SwcanListenRunner
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
    private var tripSummaryNotifier: TripSummaryNotifier? = null

    // Coalesces per-sample widget updates down to one delivery per window on the telemetry
    // side-effect executor. The collaborator owns the last-wins scheduling state that used to be
    // three loose atomics on this class (B5).
    private val widgetTelemetryCoalescer =
        WidgetTelemetryCoalescer(
            WIDGET_TELEMETRY_COALESCE_MS,
            { task, delayMs -> telemetrySideEffectExecutor.schedule(task, delayMs, TimeUnit.MILLISECONDS) },
            ::maybeUpdateWidgetTelemetry,
            { ex -> Log.w(AppPrefs.LOG_TAG, "widget telemetry enqueue failed", ex) },
        )

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

    // Adapter address the current session was started for (null for demo / no session).
    @Volatile
    private var sessionAddress: String? = null

    // The session-outcome record (state/detail/failureClass/voltage/competingApps) is written by
    // broadcastStatus + the probe/detector setters on the poll/IO, side-effect, and main threads,
    // and read back as ONE consistent snapshot by closeSessionLog and every status broadcast.
    // A single AtomicReference with copy-on-write updates replaces the five separately-@Volatile
    // fields this class used to carry, closing the torn-read window between them (B5). The
    // AtomicReference provides the same happens-before publication each @Volatile did.
    private val sessionOutcome = AtomicReference(SessionOutcome())

    // AtomicReference.updateAndGet needs API 24 (minSdk is 23) — same CAS loop, done by hand.
    private fun updateSessionOutcome(transform: (SessionOutcome) -> SessionOutcome): SessionOutcome {
        while (true) {
            val current = sessionOutcome.get()
            val next = transform(current)
            if (sessionOutcome.compareAndSet(current, next)) return next
        }
    }

    // Flipped by AppVisibility on the main thread and read on the poll/IO thread
    // (background-sample accounting) — @Volatile for the cross-thread visibility edge. It
    // is deliberately NOT part of sessionOutcome: it is never read together with the outcome
    // fields, so folding it in would only add contention on the visibility flags.
    @Volatile
    override var appInForeground = true

    // The activities report resume/pause through this in-process listener instead of a
    // startService round trip: a start command makes ActivityThread wait for every pending
    // SharedPreferences apply() on the main thread, which froze the app when a share sheet paused it.
    private val appVisibilityListener = AppVisibility.Listener { recordAppVisibility(it) }

    // Written on the main thread (foreground start/stop) and read on the poll/IO thread;
    // @Volatile for the same independent-flag reasoning as appInForeground.
    @Volatile
    override var foregroundServiceActive = false

    // Set by the user's cancel action on the main thread, consumed (and cleared) by the retry
    // loop on the poll/IO thread; @Volatile for the same independent-flag reasoning as above.
    @Volatile
    override var cancelRetryRequested = false
    private var activeForegroundServiceType = 0

    // Acquired on the main thread (startSession) but released from BOTH the main thread
    // (stopCurrentSession) and the poll/IO thread (markSessionInactive at the end of a one-shot
    // runner, B1) — an AtomicReference so the two release paths can't double-release or leak.
    private val sessionWakeLock = AtomicReference<PowerManager.WakeLock?>()

    private val persistenceTeardown = ServicePersistenceTeardown()
    private var persistenceOwner: DatabaseOperationLease.PersistenceOwner? = null
    private var recoveredSessions = false

    @Volatile private var sessionInitialization = java.util.concurrent.CountDownLatch(0)

    @VisibleForTesting
    fun awaitSessionInitializationForTest(timeoutMs: Long): Boolean =
        sessionInitialization.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

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
        // Also wake a pending extended-tier reconnect wait: the engine consumes the cancel flag
        // right after the wait returns, so the cancel takes effect immediately instead of after
        // up to a full extended-retry interval (B3).
        if (::engine.isInitialized) {
            engine.requestImmediateRetry()
        }
    }

    override fun markSessionInactive() {
        if (!canCurrentThreadCleanupSession()) {
            return
        }
        running.set(false)
        SESSION_ACTIVE.set(false)
        sessionStateMachine.stop("inactive")
        // One-shot runners (diagnostic scan / clear-DTC / TPMS / cell probe) and pre-connect
        // aborts end here on the poll thread without ever passing through stopCurrentSession —
        // release the session wake lock so it can't leak until its 12h ceiling (B1).
        releaseSessionWakeLock()
    }

    override fun isSessionRunnerActive(): Boolean = running.get() && canCurrentThreadCleanupSession()

    override fun stopSelfFromRunner() {
        // A stale runner superseded by a newer session must never stop the service (and with it
        // the new session's foreground state) out from under that session (B3).
        if (!canCurrentThreadCleanupSession()) {
            return
        }
        stopSelf()
    }

    fun setLastVoltage(volts: Double) {
        updateSessionOutcome { it.copy(voltage = volts) }
    }

    fun setCompetingApps(csv: String?) {
        updateSessionOutcome { it.copy(competingAppsCsv = csv) }
    }

    /** Test-only: the current consolidated session-outcome snapshot. */
    @VisibleForTesting
    fun sessionOutcomeForTest(): SessionOutcome = sessionOutcome.get()

    override fun onCreate() {
        super.onCreate()
        // A service instance owns one live session stream. Clear any process-local snapshot left by
        // a previous stopped instance before this one starts publishing authoritative values.
        LiveDashboardSnapshot.reset()
        persistenceOwner = DatabaseOperationLease.tryRegisterPersistence()
        // The wrapper is lazy; recovery and the first SQLite/file opens happen on the runner.
        localStore = if (persistenceOwner != null) ObdLocalStore(this) else null
        locationTracker = LocationManagerTracker(this)
        notifications = ObdNotifications(this)
        notifications.createChannel()
        eventCoordinator = createEventCoordinator()
        val sharedPrefs = getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        val eventPrefs = EventNotificationPrefs(sharedPrefs)
        tripSummaryNotifier = TripSummaryNotifier(this) { eventPrefs.tripSummaryEnabled() }
        widgetUpdater = createWidgetUpdater()
        rollingAppLog = RollingAppLog(File(filesDir, RollingAppLog.DIR_NAME))
        OBDLog.mirror(rollingAppLog)
        val summaryStore = SessionSummaryStore.getInstance(filesDir)
        recorder =
            SessionRecorder(
                ioLock,
                ObdSessionLog(File(filesDir, "obd-logs")),
                localStore,
                summaryStore,
                { SystemSnapshot.collect(this, summaryStore) },
                { store, sessionId -> tripSummaryNotifier?.notifyMaterializedTrip(store, sessionId) },
            )
        recorder.onRecordingWarning = { warning -> publishRecordingWarning(warning) }
        engine = createPollingEngine()
        // Start from the current screen state (a session can start while the app is backgrounded),
        // then follow every later resume/pause. Registered after the recorder exists because the
        // listener hands visibility changes to it.
        appInForeground = AppVisibility.isForeground
        AppVisibility.addListener(appVisibilityListener)
        sdpProbe = SdpProbe(this)
        // The ACL hook keeps mid-drive recovery working while the Activity is gone (B3): when
        // the OS reports the active adapter's link is back, wake the engine's extended
        // reconnect wait so it retries immediately instead of waiting out its interval.
        bluetoothObservability =
            BluetoothStateReporter(this, sdpProbe) {
                if (running.get()) {
                    engine.requestImmediateRetry()
                }
            }
        voltageProbe = VoltageProbe(this)
        competingAppDetector = CompetingAppDetector(packageManager, this, recorder, packageName)
        if (hasBluetoothConnectPermission()) {
            bluetoothObservability?.register(this)
        } else {
            recorder.logEvent("bluetooth_reporter_skipped", "reason", "missing_bluetooth_connect")
        }
        refreshCompetingAppsAsync()
    }

    /** Seam for [ObdSessionRecovery.recoverSafely]; `open` so a test can simulate a failing database. */
    open fun recoverInterruptedSessions(): Int = ObdSessionRecovery.recover(this)

    /**
     * Factory for the polling engine, created once in [onCreate]. It exists so a test subclass can
     * substitute an engine whose IO loops are neutralized (the real loops open a Bluetooth RFCOMM
     * socket that cannot run under Robolectric), letting the action-dispatch orchestration be driven
     * directly. Debug builds also log raw SW-CAN windows.
     */
    open fun createPollingEngine(): ObdPollingEngine =
        ObdPollingEngine(this, swcanPolicy = SwcanListenRunner.Policy(logRawWindows = BuildConfig.DEBUG))

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
        if (isSessionStartAction(intent?.action) && DatabaseOperationLease.isHeld()) {
            broadcastStatus("blocked", getString(R.string.status_database_operation_running), true)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopCurrentSession(getString(R.string.status_disconnected))
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                foregroundServiceActive = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CANCEL_RETRY -> {
                requestCancelRetry()
                broadcastStatus("idle", getString(R.string.status_retry_cancelled), false)
                val active = running.get()
                if (!active) stopSelf(startId)
                return if (active) START_STICKY else START_NOT_STICKY
            }
            ACTION_CAR_CONTROL -> {
                // Never starts a session: only a live one can run a car command, and the engine
                // refuses (recording why) when none is polling. Unknown names are ignored.
                CarCommand.fromWireName(intent.getStringExtra(EXTRA_CAR_COMMAND))?.let(engine::requestCarControl)
                val active = running.get()
                if (!active) stopSelf(startId)
                return if (active) START_STICKY else START_NOT_STICKY
            }
            ACTION_BODY_TEST -> {
                // Like a car command, only meaningful on a live session; never starts one.
                if (running.get()) engine.requestBodyTest(BODY_TEST_MS)
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
                // A repeat CONNECT (double tap, auto-connect racing a manual one) while this same
                // adapter is connected and polling must not tear down the working live session:
                // field logs showed 5 healthy sessions discarded 6-35 s in exactly this way.
                if (running.get() &&
                    recorder.activeMode() == ObdLocalStore.MODE_OBD &&
                    SessionStateMachine.isRedundantConnect(sessionStateMachine.phase(), sessionAddress, address)
                ) {
                    recorder.logEvent("duplicate_connect_ignored")
                    val outcome = sessionOutcome.get()
                    broadcastStatus(outcome.state, outcome.detail, false)
                    return START_STICKY
                }
                activeName = adapterNameFrom(intent)
                startObdSession(address, false)
                return START_STICKY
            }
            ACTION_SCAN -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                activeName = adapterNameFrom(intent)
                // The scan depth profile (full/quick) rides the shared detail-stage extra.
                startObdSession(address, true, intent.getStringExtra(EXTRA_DETAIL_STAGE))
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
        // there is nothing to do, but onCreate already created the store wrapper, registered
        // receivers, and started executors. Without a session, stop instead of lingering as
        // an invisible orphaned service.
        if (!running.get()) {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AppVisibility.removeListener(appVisibilityListener)
        stopCurrentSession(getString(R.string.status_service_stopped))
        // Drop the foreground state right away: the persistence drain below runs off the main
        // thread, and the dying service must not keep its notification alive meanwhile (B4).
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundServiceActive = false
        bluetoothObservability?.unregister(this)
        executor.shutdown()
        competingAppExecutor.shutdownNow()
        telemetrySideEffectExecutor.shutdownNow()
        persistenceTeardown.start(
            recorder,
            localStore,
            persistenceOwner,
            listOf(executor, telemetrySideEffectExecutor, competingAppExecutor),
        )
        localStore = null
        // Detach the app-log mirror before releasing its handle so any late OBDLog call becomes a
        // no-op rather than lazily reopening the writer we're about to close.
        OBDLog.mirror(null)
        rollingAppLog?.close()
        rollingAppLog = null
        super.onDestroy()
    }

    /** Test-only: waits for this service's background drain without releasing a failed restore guard. */
    @VisibleForTesting
    fun awaitPersistenceTeardownForTest(timeoutMs: Long): Boolean = persistenceTeardown.await(timeoutMs)

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
            Log.w(AppPrefs.LOG_TAG, "auto DTC scan failed", ex)
        }
    }

    private fun maybePostEventNotifications(payload: JSONObject) {
        try {
            eventCoordinator?.onTelemetry(payload)
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "event notification dispatch failed", ex)
        }
    }

    private fun startObdSession(
        address: String?,
        scanMode: Boolean,
        scanProfile: String? = null,
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
                if (scanMode) {
                    engine.runScanLoop(address, scanProfile)
                } else {
                    engine.runBluetoothLoop(address, scanMode)
                }
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
                    Log.w(AppPrefs.LOG_TAG, "competing-app refresh failed", ex)
                }
            }
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "competing-app refresh rejected", ex)
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
        val blockedDetail = startForegroundSession(request)
        if (blockedDetail != null) {
            broadcastStatus("blocked", blockedDetail, true)
            // The service was launched via startForegroundService but never reached the
            // foreground: without a session to own, it must stop itself or Android eventually
            // kills the process with a RemoteServiceException for the missing startForeground
            // call (B6). No wake lock is held here — acquisition only happens after this gate,
            // and stopCurrentSession above released any lock a previous session held.
            stopSelf()
            return
        }
        if (request.holdWakeLock) {
            acquireSessionWakeLock(request.mode)
        }
        sessionStartedAtMs = System.currentTimeMillis()
        sessionAddress = request.address
        // Anchor for the connect→first-sample latency spans (debug builds only; see StartupTrace).
        StartupTrace.mark("${StartupTrace.OBD_CONNECT_REQUEST}:${request.mode}")
        sessionStateMachine.start(request.phase, request.phaseDetail)
        // Clear any voltage carried over from a prior session: if this connect's 0142 probe
        // doesn't run, broadcastStatus must not re-emit the previous drive's reading into the
        // low-voltage hint / adapter-ready check.
        updateSessionOutcome { it.copy(voltage = null) }
        engine.beginSession(request.engineMode)
        try {
            eventCoordinator?.onSessionStart()
        } catch (ex: RuntimeException) {
            // A notification session-start hook failure must never abort session startup.
            Log.w(AppPrefs.LOG_TAG, "event notification session-start hook failed", ex)
        }
        if (request.startLocationTracking) {
            startLocationTracking()
        }
        running.set(true)
        SESSION_ACTIVE.set(true)
        val initialized = java.util.concurrent.CountDownLatch(1)
        sessionInitialization = initialized
        try {
            activeTask =
                executor.submit {
                    runSessionTask(token) { initializeAndRunSession(request, initialized) }
                }
        } catch (ex: RuntimeException) {
            initialized.countDown()
            Log.w(AppPrefs.LOG_TAG, "session task submit failed", ex)
            broadcastStatus("error", getString(R.string.status_worker_start_failed), true)
            stopCurrentSession(null)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundServiceActive = false
            stopSelf()
        }
    }

    private fun initializeAndRunSession(
        request: SessionStartRequest,
        initialized: java.util.concurrent.CountDownLatch,
    ) {
        try {
            if (!recoveredSessions) {
                ObdSessionRecovery.recoverSafely(::recoverInterruptedSessions)
                recoveredSessions = true
            }
            if (!isSessionRunnerActive()) return
            openSessionLog(request.mode, request.address)
        } finally {
            initialized.countDown()
        }
        if (isSessionRunnerActive()) request.runner.run()
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
            recorder.runAsync { recorder.logEvent("gps_skipped", "reason", "missing_location_permission") }
            return
        }
        recorder.runAsync { recorder.logEvent("gps_started") }
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
        recorder.runAsync { recorder.logEvent("gps_stopped") }
    }

    /**
     * Holds the CPU awake while a real adapter session is recording. The foreground service
     * keeps the process alive, but on some OEM builds Doze can still suspend the CPU with the
     * screen off mid-drive, stalling the Bluetooth polling thread and punching gaps into the
     * session. Callers opt in via [SessionStartRequest.holdWakeLock]; [mode] is only logged.
     */
    private fun acquireSessionWakeLock(mode: String) {
        if (sessionWakeLock.get()?.isHeld == true) {
            return
        }
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            lock.setReferenceCounted(false)
            // Timeout is a leak ceiling, not the session length: stopCurrentSession() and
            // markSessionInactive() release on every session end; the ceiling only catches a
            // missed release.
            lock.acquire(SESSION_WAKE_LOCK_TIMEOUT_MS)
            sessionWakeLock.set(lock)
            recorder.runAsync { recorder.logEvent("wake_lock_acquired", "mode", mode) }
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "session wake lock acquire failed", ex)
        }
    }

    private fun releaseSessionWakeLock() {
        val lock = sessionWakeLock.getAndSet(null) ?: return
        try {
            if (lock.isHeld) {
                lock.release()
            }
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "session wake lock release failed", ex)
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
        // Queue behind any runner still leaving its SQLite/file call; teardown drains this queue.
        val outcome = sessionOutcome.get()
        val sampleCount = if (::engine.isInitialized) engine.sampleCount() else 0
        val supportedPids = if (::engine.isInitialized) engine.supportedPidsSummary() else ""
        try {
            executor.execute {
                recorder.closeSession(outcome.state, outcome.detail, supportedPids, sampleCount, outcome.failureClass)
            }
        } catch (ex: java.util.concurrent.RejectedExecutionException) {
            Log.w(AppPrefs.LOG_TAG, "session close already handed to persistence teardown", ex)
        }
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
        // Same stale-runner gate as broadcastStatus (B3): a runner superseded between its loop's
        // isSessionRunnerActive() check and this call must not persist or publish its sample into
        // the NEW session. Non-runner threads carry no token and always pass.
        if (!canCurrentThreadCleanupSession()) {
            return
        }
        val payload = telemetry.toJson()
        recorder.logJson("telemetry", payload)
        recorder.persistTelemetry(payload)
        enqueueEventNotifications(payload)
        enqueueWidgetTelemetry(payload)
        // Record before broadcasting: the Activity deliberately stops receiving broadcasts while
        // backgrounded, but can pull this last value synchronously as soon as it resumes.
        LiveDashboardSnapshot.recordTelemetry(payload)
        broadcast(BROADCAST_TELEMETRY, payload)
    }

    private fun enqueueEventNotifications(payload: JSONObject) {
        try {
            telemetrySideEffectExecutor.execute {
                maybePostEventNotifications(payload)
            }
        } catch (ex: RejectedExecutionException) {
            Log.w(AppPrefs.LOG_TAG, "event notification enqueue failed", ex)
        }
    }

    private fun enqueueWidgetTelemetry(payload: JSONObject) {
        widgetTelemetryCoalescer.submit(payload)
    }

    @VisibleForTesting
    fun drainCoalescedWidgetTelemetryCountForTest(): Long = widgetTelemetryCoalescer.drainCoalescedCountForTest()

    private fun maybeUpdateWidgetTelemetry(payload: JSONObject) {
        try {
            widgetUpdater?.onTelemetry(payload)
        } catch (ex: RuntimeException) {
            // The widget snapshot is best-effort and must never break the live telemetry path.
            Log.w(AppPrefs.LOG_TAG, "widget telemetry hook failed", ex)
        }
    }

    private fun maybeUpdateWidgetStatus(state: String?) {
        try {
            widgetUpdater?.onStatus(state)
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "widget status hook failed", ex)
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
        // A stale runner superseded by a newer session must not write its terminal state into
        // the NEW session's outcome record and broadcast stream (B3). Non-runner threads (main,
        // side-effect executors) carry no token and always pass.
        if (!canCurrentThreadCleanupSession()) {
            return
        }
        // Fold the new state/detail into the outcome record and read the rest of the payload
        // fields from the SAME snapshot, so the broadcast can't mix a fresh state with a
        // concurrently-cleared failure class or voltage.
        val outcome = updateSessionOutcome { it.copy(state = state ?: "", detail = detail ?: "") }
        val status =
            StatusPayload(
                state,
                detail,
                blocked,
                activeName,
                System.currentTimeMillis(),
                recorder.logFileName(),
                outcome.failureClass,
                outcome.voltage,
                outcome.competingAppsCsv,
                extras,
            )
        val payload = status.toJson()
        payload.put("recordingWarning", recorder.recordingWarning() ?: "")
        sessionStateMachine.observeStatus(state, detail, blocked)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            recorder.runAsync {
                recorder.logJson("status", payload)
                recorder.persistStatus(state, detail, blocked, payload)
            }
        } else {
            recorder.logJson("status", payload)
            recorder.persistStatus(state, detail, blocked, payload)
        }
        maybeUpdateWidgetStatus(state)
        LiveDashboardSnapshot.recordStatus(payload)
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

    /** Never persist this status: reporting a storage failure must not recurse into that failure. */
    private fun publishRecordingWarning(warning: String?) {
        val outcome = sessionOutcome.get()
        val payload =
            LiveDashboardSnapshot.latestStatus().takeIf { it.length() > 0 }
                ?: JSONObject().put("state", outcome.state).put("detail", outcome.detail).put("adapter", activeName)
        payload.put("recordingWarning", warning ?: "")
        LiveDashboardSnapshot.recordStatus(payload)
        broadcast(BROADCAST_STATUS, payload)
        if (foregroundServiceActive) {
            notifications.post(
                notifications.loggingText(appInForeground, recorder.recordingWarning()),
            )
        }
    }

    /**
     * Puts the service in the state [request]'s session needs (see [ForegroundServicePolicy]).
     * Returns null on success, or the user-facing reason the session cannot start.
     */
    private fun startForegroundSession(request: SessionStartRequest): String? {
        val plan =
            ForegroundServicePolicy.plan(
                request.mode,
                Build.VERSION.SDK_INT,
                hasBluetoothConnectPermission() || hasBluetoothScanPermission(),
                hasLocationPermission(),
            )
        val serviceType =
            when (plan) {
                ForegroundPlan.Background -> {
                    // The demo runs as a plain started service: drop any foreground state (and its
                    // notification) a previous real session left behind.
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    foregroundServiceActive = false
                    activeForegroundServiceType = 0
                    return null
                }
                ForegroundPlan.MissingNearbyDevicesPermission -> {
                    recorder.logEvent("foreground_skipped", "reason", "missing_nearby_devices_permission")
                    return getString(R.string.status_foreground_needs_nearby_devices)
                }
                is ForegroundPlan.Foreground -> plan.serviceType
            }
        return try {
            enterForeground(notifications.build(request.foregroundText), serviceType)
            activeForegroundServiceType = serviceType ?: 0
            foregroundServiceActive = true
            null
        } catch (ex: SecurityException) {
            onStartForegroundRefused("startForegroundSession", ex)
            getString(R.string.status_foreground_blocked)
        } catch (ex: IllegalStateException) {
            // API 31+ throws ForegroundServiceStartNotAllowedException (an IllegalStateException
            // subclass) instead of SecurityException when background FGS starts are blocked;
            // route it to the same "blocked" fallback instead of crashing the process.
            onStartForegroundRefused("startForegroundSession", ex)
            getString(R.string.status_foreground_blocked)
        }
    }

    /**
     * Performs the actual startForeground call ([serviceType] is null below Android Q).
     * Behavior-identical to the inlined calls it replaces; `open` only so a test can simulate
     * the OS refusing the foreground start (B6) — Robolectric never throws here on its own.
     */
    @VisibleForTesting
    open fun enterForeground(
        notification: Notification,
        serviceType: Int?,
    ) {
        // The SDK_INT check re-proves what the caller already guarantees (serviceType is only
        // non-null on Q+) so lint can verify the 3-arg overload's API-29 requirement locally.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && serviceType != null) {
            startForeground(ObdNotifications.NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(ObdNotifications.NOTIFICATION_ID, notification)
        }
    }

    private fun onStartForegroundRefused(
        where: String,
        ex: RuntimeException,
    ) {
        Log.w(AppPrefs.LOG_TAG, "$where refused", ex)
        foregroundServiceActive = false
        activeForegroundServiceType = 0
    }

    private fun reevaluateForegroundServiceType() {
        if (!running.get() || !foregroundServiceActive) {
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        val desired = ForegroundServicePolicy.serviceType(hasLocationPermission())
        if (desired == activeForegroundServiceType) {
            return
        }
        val notification: Notification =
            notifications.build(notifications.loggingText(appInForeground, recorder.recordingWarning()))
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
            Log.w(AppPrefs.LOG_TAG, "reevaluateForegroundServiceType refused", ex)
        } catch (ex: IllegalStateException) {
            // See startForegroundSession: API 31+ ForegroundServiceStartNotAllowedException.
            // The session keeps its existing foreground type; only the upgrade is skipped.
            Log.w(AppPrefs.LOG_TAG, "reevaluateForegroundServiceType refused", ex)
        }
    }

    override fun updateNotification(text: String?) {
        recorder.runAsync { recorder.logEvent("notification", "text", text) }
        notifications.post(recorder.recordingWarning()?.let { "Recording incomplete · $it" } ?: text ?: "")
    }

    override fun setLastFailureClass(fc: FailureClass?) {
        // The token gate keeps a stale runner's late failure classification out of the NEW
        // session's outcome (B3).
        if (fc == null || !canCurrentThreadCleanupSession()) {
            return
        }
        updateSessionOutcome { it.copy(failureClass = fc) }
    }

    override fun clearLastFailureClass() {
        if (!canCurrentThreadCleanupSession()) {
            return
        }
        updateSessionOutcome { it.copy(failureClass = null) }
    }

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
            // A background (demo) session owns no notification, so there is nothing to refresh.
            if (running.get() && foregroundServiceActive) {
                updateNotification(notifications.loggingText(appInForeground, recorder.recordingWarning()))
                reevaluateForegroundServiceType()
            }
        }
    }

    private fun openSessionLog(
        mode: String,
        address: String?,
    ) {
        synchronized(ioLock) {
            updateSessionOutcome { it.copy(state = "active", detail = "") }
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
            // One atomic read: the session row is finalized from a single consistent
            // state/detail/failureClass snapshot instead of three separate volatile reads (B5).
            val outcome = sessionOutcome.get()
            recorder.closeSession(
                outcome.state,
                outcome.detail,
                engine.supportedPidsSummary(),
                engine.sampleCount(),
                outcome.failureClass,
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
        const val ACTION_CANCEL_RETRY = "com.volttracker.obdpoc.action.CANCEL_RETRY"
        const val ACTION_CAR_CONTROL = "com.volttracker.obdpoc.action.CAR_CONTROL"
        const val ACTION_BODY_TEST = "com.volttracker.obdpoc.action.BODY_TEST"

        /** How long a body test listens: long enough to walk round the car opening things. */
        const val BODY_TEST_MS = 60_000L
        const val EXTRA_CAR_COMMAND = "car_command"
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
        fun isSessionStartAction(action: String?): Boolean =
            action == ACTION_CONNECT ||
                action == ACTION_SCAN ||
                action == ACTION_TPMS_SCAN ||
                action == ACTION_CLEAR_DTC ||
                action == ACTION_DEMO

        @JvmStatic
        fun adapterNameFrom(intent: Intent?): String {
            val name = intent?.getStringExtra(EXTRA_NAME)
            return if (name.isNullOrBlank()) DEFAULT_ADAPTER_NAME else name
        }
    }
}
