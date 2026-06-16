package com.volttracker.obdpoc

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.util.Log
import com.volttracker.obdpoc.classify.VehicleState
import com.volttracker.obdpoc.location.FilteredLocation
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * Runs the OBD adapter IO on the [ObdService] worker thread: connect/reconnect, ELM327 init,
 * and live-data polling.
 */
private fun defaultPollingSleep(millis: Long): Boolean =
    try {
        Thread.sleep(millis)
        true
    } catch (ex: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

open class ObdPollingEngine(
    private val service: EngineHost,
    private val sleeper: LoopSleeper = LoopSleeper { millis -> defaultPollingSleep(millis) },
) : LiveSampleReader.SampleContext {
    fun interface LoopSleeper {
        fun sleep(millis: Long): Boolean
    }

    private var connection = ElmConnection()
    private val speedFilter = SpeedPlausibilityFilter()
    private val demoLoop: DemoPollingLoop
    private val scanRunner: DiagnosticScanRunner
    private val tpmsDiscoveryRunner: TpmsDiscoveryRunner
    private val clearDtcRunner: ClearDtcRunner
    private val sessionHealth: SessionHealthTracker
    private val pidPolling: PidPollingState
    private val liveSampleReader: LiveSampleReader
    private var sampleCount = 0
    private var supportedPidsSummary = ""
    private var redactedVin = ""

    init {
        sessionHealth = SessionHealthTracker(service)
        pidPolling = PidPollingState(service, this)
        liveSampleReader = LiveSampleReader(service, speedFilter, pidPolling)
        demoLoop = DemoPollingLoop(service, this, sleeper)
        scanRunner = DiagnosticScanRunner(service, this)
        tpmsDiscoveryRunner = TpmsDiscoveryRunner(service, this)
        clearDtcRunner = ClearDtcRunner(service, this)
    }

    fun beginSession(supportedPidsSeed: String?) {
        sampleCount = 0
        resetSessionHealth()
        speedFilter.reset()
        supportedPidsSummary = supportedPidsSeed ?: ""
        redactedVin = ""
        pidPolling.reset()
    }

    fun sampleCount(): Int = sampleCount

    override fun supportedPidsSummary(): String = supportedPidsSummary

    override fun redactedVin(): String = redactedVin

    fun backgroundSampleCount(): Int = sessionHealth.backgroundSampleCount()

    fun sampleGapCount(): Int = sessionHealth.sampleGapCount()

    fun closeSocket() {
        connection.close()
    }

    fun setConnectionForTest(replacement: ElmConnection) {
        connection = replacement
    }

    @SuppressLint("MissingPermission")
    fun runBluetoothLoop(
        address: String?,
        scanMode: Boolean,
    ) {
        runBluetoothLoop(address, scanMode, false)
    }

    @SuppressLint("MissingPermission")
    fun runBluetoothLoop(
        address: String?,
        scanMode: Boolean,
        clearDtcMode: Boolean,
    ) {
        runBluetoothLoop(address, scanMode, clearDtcMode, false, "")
    }

    @SuppressLint("MissingPermission")
    private fun runBluetoothLoop(
        address: String?,
        scanMode: Boolean,
        clearDtcMode: Boolean,
        tpmsScanMode: Boolean,
        detailProbeStage: String?,
    ) {
        if (address.isNullOrBlank()) {
            abortBeforeConnect("No adapter selected.")
            return
        }
        if (!service.hasBluetoothConnectPermission()) {
            abortBeforeConnect("Bluetooth permission is missing.")
            return
        }
        if (!isBluetoothReady()) {
            abortBeforeConnect("Bluetooth is off or unavailable.")
            return
        }

        val retry = ConnectionRetryCoordinator()
        try {
            while (service.running.get()) {
                if (consumeCancelRetry(retry.attempt, retry.everConnected, "pre_connect")) {
                    return
                }
                val attemptStart = System.currentTimeMillis()
                try {
                    connectAndInitialize(address)
                    retry.noteConnected()
                    service.clearLastFailureClass()
                    service.cancelRetryRequested = false
                    OBDLog.event("ObdPollingEngine", "connect", mapOf("name" to service.activeName))
                    // May throw IOException from the live-poll loop, which re-enters the
                    // reconnect path below exactly like a failed connect attempt.
                    runConnectedSession(address, scanMode, clearDtcMode, tpmsScanMode, detailProbeStage, retry)
                    return
                } catch (ex: IOException) {
                    if (!handleAttemptFailure(retry, ex, attemptStart)) {
                        return
                    }
                }
            }
        } catch (ex: RuntimeException) {
            Log.w(MainActivity.TAG, "OBD loop runtime failure for ${service.activeName}", ex)
            service.recorder.logError("connection_failure", ex)
            service.broadcastStatus("error", ObdElmDecode.friendlyConnectionMessage(ex), true)
            service.stopSelf()
        } finally {
            service.recorder.logEvent("socket_closing")
            closeSocket()
            service.closeSessionLog()
            service.markSessionInactive()
        }
    }

    /** Ends a session that failed one of the preflight gates, before any socket was opened. */
    private fun abortBeforeConnect(message: String) {
        service.broadcastStatus("error", message, true)
        service.closeSessionLog()
        service.markSessionInactive()
    }

    /**
     * Consumes a pending user cancel: logs it, broadcasts idle, and stops the service.
     * Returns true when the loop must exit. [phase] is logged only for the pre-connect check.
     */
    private fun consumeCancelRetry(
        attempt: Int,
        everConnected: Boolean,
        phase: String?,
    ): Boolean {
        if (!service.cancelRetryRequested) {
            return false
        }
        service.cancelRetryRequested = false
        if (phase != null) {
            service.recorder.logEvent(
                "retry_cancelled_by_user",
                "attempt",
                attempt.toString(),
                "everConnected",
                everConnected.toString(),
                "phase",
                phase,
            )
        } else {
            service.recorder.logEvent(
                "retry_cancelled_by_user",
                "attempt",
                attempt.toString(),
                "everConnected",
                everConnected.toString(),
            )
        }
        // Keep this literal in sync with R.string.status_retry_cancelled (the value ObdService
        // broadcasts). The engine has no Context, so it can't resolve the resource itself.
        service.broadcastStatus("idle", "Retry cancelled.", false)
        service.stopSelf()
        return true
    }

    /**
     * Runs whatever the connected session is for: a one-shot runner (clear-DTC / diagnostic scan /
     * TPMS / detail probe) or the default live-poll loop. One-shot runners complete the session;
     * the live poll returns normally on stop and throws [IOException] on a broken link.
     */
    @Throws(IOException::class)
    private fun runConnectedSession(
        address: String,
        scanMode: Boolean,
        clearDtcMode: Boolean,
        tpmsScanMode: Boolean,
        detailProbeStage: String?,
        retry: ConnectionRetryCoordinator,
    ) {
        if (clearDtcMode) {
            clearDtcRunner.run()
            return
        }
        if (scanMode) {
            scanRunner.run()
            return
        }
        if (tpmsScanMode) {
            tpmsDiscoveryRunner.run(address, EnhancedPidProfiles.STAGE_TIRES)
            return
        }
        if (!detailProbeStage.isNullOrBlank()) {
            tpmsDiscoveryRunner.run(address, detailProbeStage)
            return
        }
        retry.resetAttemptBudget()
        service.broadcastStatus(
            "connected",
            "Polling live OBD data from ${service.activeName}.",
            false,
        )
        service.updateNotification("Connected to ${service.activeName}")
        service.maybeRunAutoDtcScan(this)
        pollUntilStoppedOrBroken()
    }

    /**
     * Classifies and records one failed connect/poll attempt, then either schedules the next
     * retry (returns true) or ends the session — budget exhausted, session stopped, sleep
     * interrupted, or user cancel (returns false).
     */
    private fun handleAttemptFailure(
        retry: ConnectionRetryCoordinator,
        ex: IOException,
        attemptStartMs: Long,
    ): Boolean {
        val attemptDurationMs = maxOf(0L, System.currentTimeMillis() - attemptStartMs)
        closeSocket()
        if (retry.everConnected) {
            OBDLog.event("ObdPollingEngine", "disconnect", mapOf("reason" to ObdElmDecode.safeMessage(ex)))
        }
        if (!service.running.get()) {
            return false
        }
        var phase = connection.lastErrorPhase
        if (phase.isNullOrEmpty()) {
            phase = "post_connect"
        }
        val watchdogFired = connection.watchdogFired
        val failureClass =
            ConnectionFailureClassifier.classify(
                phase,
                attemptDurationMs,
                ex,
                isBluetoothReady(),
            )
        service.setLastFailureClass(failureClass)
        val decision = retry.recordFailure(failureClass, attemptDurationMs)
        if (decision.exhausted) {
            reportReconnectExhausted(decision, failureClass, watchdogFired, ex)
            return false
        }
        if (decision.wedgedModeStarted) {
            service.recorder.logEvent(
                "wedged_suspected",
                "consecutiveInstantDrops",
                decision.consecutiveInstantDrops.toString(),
                "lastAttemptDurationMs",
                attemptDurationMs.toString(),
                "failureClass",
                failureClass.wireName(),
                "watchdogFired",
                watchdogFired.toString(),
            )
        }
        logReconnectAttempt(decision, failureClass, watchdogFired, ex, attemptDurationMs)
        service.broadcastStatus(
            "connecting",
            if (decision.everConnected) {
                "Adapter link dropped - reconnecting (${decision.attempt}/${ObdProbes.MAX_RECONNECT_ATTEMPTS})..."
            } else {
                "Couldn't reach ${service.activeName} - retrying (${decision.attempt}/${ObdProbes.MAX_RECONNECT_ATTEMPTS})..."
            },
            false,
        )
        if (!sleeper.sleep(decision.backoffMs)) {
            return false
        }
        if (consumeCancelRetry(decision.attempt, decision.everConnected, null)) {
            return false
        }
        return true
    }

    private fun reportReconnectExhausted(
        decision: ConnectionRetryCoordinator.RetryDecision,
        failureClass: FailureClass,
        watchdogFired: Boolean,
        ex: IOException,
    ) {
        Log.w(
            MainActivity.TAG,
            "OBD reconnect exhausted for ${service.activeName} after ${ObdProbes.MAX_RECONNECT_ATTEMPTS} attempts",
            ex,
        )
        service.recorder.logError("reconnect_exhausted", ex)
        service.recorder.logEvent(
            "reconnect_exhausted",
            "attempts",
            ObdProbes.MAX_RECONNECT_ATTEMPTS.toString(),
            "failureClass",
            failureClass.wireName(),
            "watchdogFired",
            watchdogFired.toString(),
            "exceptionClass",
            exceptionClassName(ex),
            "stackHead",
            stackHead(ex),
        )
        service.broadcastStatus(
            "error",
            if (decision.everConnected) {
                "Lost the adapter link and could not reconnect after ${ObdProbes.MAX_RECONNECT_ATTEMPTS} tries."
            } else {
                "Could not reach ${service.activeName} after ${ObdProbes.MAX_RECONNECT_ATTEMPTS} tries. Make sure the car is awake and the adapter is plugged in."
            },
            true,
        )
        service.stopSelf()
    }

    private fun logReconnectAttempt(
        decision: ConnectionRetryCoordinator.RetryDecision,
        failureClass: FailureClass,
        watchdogFired: Boolean,
        ex: IOException,
        attemptDurationMs: Long,
    ) {
        OBDLog.event("ObdPollingEngine", "reconnect_attempt", mapOf("attempt" to decision.attempt))
        service.recorder.logEvent(
            "reconnect",
            "attempt",
            decision.attempt.toString(),
            "backoffMs",
            decision.backoffMs.toString(),
            "reason",
            ObdElmDecode.safeMessage(ex),
            "everConnected",
            decision.everConnected.toString(),
            "failureClass",
            failureClass.wireName(),
            "watchdogFired",
            watchdogFired.toString(),
            "exceptionClass",
            exceptionClassName(ex),
            "stackHead",
            stackHead(ex),
            "attemptDurationMs",
            attemptDurationMs.toString(),
            "wedgedMode",
            decision.wedgedMode.toString(),
        )
    }

    fun runTpmsScanLoop(address: String?) {
        runBluetoothLoop(address, false, false, true, "")
    }

    fun runDetailProbeLoop(
        address: String?,
        stage: String?,
    ) {
        runBluetoothLoop(address, false, false, false, stage)
    }

    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    private fun connectAndInitialize(address: String) {
        service.broadcastStatus("connecting", "Opening serial connection to ${service.activeName}...", false)
        service.bluetoothObservability?.onPreConnect(address)
        service.recorder.logEvent(
            "socket_open_attempt",
            "address",
            address,
            "uuid",
            ObdProbes.ELM327_SPP_UUID.toString(),
        )
        val openStart = System.currentTimeMillis()
        var openOk = false
        var openErrorPhase = "post_connect"
        try {
            openBluetoothSocket(address)
            openOk = true
        } catch (ex: IOException) {
            val phase = connection.lastErrorPhase
            openErrorPhase = if (phase.isNullOrEmpty()) "post_connect" else phase
            logSocketOpenResult(false, openStart, openErrorPhase)
            throw ex
        }
        try {
            val nudge = connection.wakeNudge(200L)
            service.recorder.logEvent(
                "wake_nudge",
                "durationMs",
                nudge.durationMs.toString(),
                "gotResponse",
                nudge.gotResponse.toString(),
            )
        } catch (ex: IOException) {
            openOk = false
            openErrorPhase = "first_read"
            service.recorder.logEvent(
                "wake_nudge",
                "durationMs",
                connection.firstReadMs.toString(),
                "gotResponse",
                "false",
                "error",
                ObdElmDecode.safeMessage(ex),
            )
            logSocketOpenResult(false, openStart, openErrorPhase)
            throw ex
        }
        logSocketOpenResult(openOk, openStart, openErrorPhase)
        service.broadcastStatus("initializing", "Connected. Initializing ELM327 adapter...", false)
        initializeElm327()
    }

    private fun logSocketOpenResult(
        ok: Boolean,
        openStart: Long,
        errorPhase: String?,
    ) {
        val totalMs = maxOf(0L, System.currentTimeMillis() - openStart)
        service.recorder.logEvent(
            "socket_open_result",
            "durationMs",
            totalMs.toString(),
            "ok",
            ok.toString(),
            "errorPhase",
            if (ok) "" else errorPhase ?: "post_connect",
            "rfcommConnectMs",
            connection.rfcommConnectMs.toString(),
            "getStreamsMs",
            connection.getStreamsMs.toString(),
            "firstReadMs",
            connection.firstReadMs.toString(),
        )
    }

    @SuppressLint("MissingPermission")
    open fun isBluetoothReady(): Boolean {
        val adapter = BluetoothAdapters.get(service.androidContext)
        return adapter != null && adapter.isEnabled
    }

    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    open fun openBluetoothSocket(address: String?) {
        val adapter =
            BluetoothAdapters.get(service.androidContext) ?: throw IOException("Bluetooth adapter unavailable")
        if (service.hasBluetoothScanPermission()) {
            adapter.cancelDiscovery()
        } else {
            service.recorder.logEvent("cancel_discovery_skipped", "reason", "missing BLUETOOTH_SCAN")
        }
        val device: BluetoothDevice =
            try {
                adapter.getRemoteDevice(address)
            } catch (ex: IllegalArgumentException) {
                throw IOException("Invalid Bluetooth adapter address", ex)
            }
        connection.open(device, ObdProbes.ELM327_SPP_UUID, ObdProbes.CONNECT_TIMEOUT_MS)
    }

    @Throws(IOException::class)
    private fun pollUntilStoppedOrBroken() {
        while (service.running.get()) {
            val sample = liveSampleReader.read(this)
            if (sample.length() == 0) {
                service.recorder.logEvent("empty_sample_skipped")
                continue
            }
            service.broadcastTelemetry(sample)
            // Adapt the cadence to the vehicle state: a parked/charging car changes slowly, so we
            // poll less often to save adapter round-trips and battery; a moving car keeps the
            // responsive ~850 ms cadence. Derived from the sample the engine just read/broadcast.
            if (!sleeper.sleep(pollIntervalMs(sample))) {
                return
            }
        }
    }

    fun runDemoLoop() {
        demoLoop.run()
    }

    override fun incrementSampleCount(): Int {
        sampleCount += 1
        return sampleCount
    }

    @Throws(IOException::class)
    private fun initializeElm327() {
        sendCommand("ATZ", 3200)
        sendCommand("ATE0", 1400)
        sendCommand("ATL0", 1400)
        sendCommand("ATS0", 1400)
        sendCommand("ATH0", 1400)
        sendCommand("ATAT1", 1400)
        sendCommand("ATST64", 1400)
        sendCommand("ATSP0", 1800)
        var supportedPids = sendCommand("0100", 9000)
        if (ObdElmDecode.hasElmPrompt(supportedPids)) {
            supportedPidsSummary = ObdProtocol.cleanSupportedPids(supportedPids)
            service.recorder.logEvent("protocol_probe_success", "command", "0100", "response", supportedPidsSummary)
        }
        if (!ObdElmDecode.hasElmPrompt(supportedPids)) {
            service.recorder.logEvent(
                "protocol_probe_no_prompt",
                "command",
                "0100",
                "response",
                ObdProtocol.summarize(supportedPids),
            )
            sendEscape(600)
            sendCommand("ATPC", 1400)
            sendCommand("ATSP6", 1400)
            supportedPids = sendCommand("0100", 9000)
            if (ObdElmDecode.hasElmPrompt(supportedPids)) {
                supportedPidsSummary = ObdProtocol.cleanSupportedPids(supportedPids)
                service.recorder.logEvent(
                    "protocol_probe_success",
                    "command",
                    "0100_after_ATSP6",
                    "response",
                    supportedPidsSummary,
                )
            }
            if (!ObdElmDecode.hasElmPrompt(supportedPids)) {
                service.recorder.logEvent(
                    "protocol_probe_no_prompt",
                    "command",
                    "0100_after_ATSP6",
                    "response",
                    ObdProtocol.summarize(supportedPids),
                )
                sendEscape(600)
                sendCommand("ATPC", 1400)
                sendCommand("ATSP0", 1400)
                throw IOException("Adapter did not answer the standard OBD PID probe.")
            }
        }
        OBDLog.event("ObdPollingEngine", "protocol_init", mapOf("ok" to true))
        probeAndPersistVin()
        probeMode01Batch()
        service.maybeRunVoltageProbe(this)
    }

    private fun probeAndPersistVin() {
        val storedRedactedVin = storedRedactedVin()
        if (storedRedactedVin.isNotEmpty()) {
            redactedVin = storedRedactedVin
            service.recorder.logEvent("vin_probe", "skipped", "stored_vehicle")
            return
        }
        try {
            val response = sendCommand("0902", 6000)
            val vin = ObdProtocol.parseVin(response)
            if (vin == null) {
                service.recorder.logEvent(
                    "vin_probe",
                    "parsed",
                    "false",
                    "responseLength",
                    response.length.toString(),
                )
                return
            }
            redactedVin = redactedVin(vin)
            service.recorder.logEvent("vin_probe", "parsed", "true", "vin", redactedVin)
            val store = service.localStore
            if (store != null) {
                try {
                    store.upsertVehicleFromVin(vin)
                } catch (ex: RuntimeException) {
                    service.recorder.logError("vin_persist_failed", ex)
                }
            }
        } catch (ex: IOException) {
            service.recorder.logEvent("vin_probe_failed", "error", ex.message ?: ex.javaClass.simpleName)
        }
    }

    private fun storedRedactedVin(): String {
        val store = service.localStore ?: return ""
        return try {
            val latestVehicle = store.getStorageSummaryRecord().latestVehicle
            latestVehicle?.optString("vin", "") ?: ""
        } catch (ex: RuntimeException) {
            service.recorder.logError("vin_cache_read_failed", ex)
            ""
        }
    }

    private fun probeMode01Batch() {
        try {
            val probeResponse = sendCommand("010D0C", 1500)
            val ok = ObdProtocol.responseContainsAllMode01Pids(probeResponse, listOf("0D", "0C"))
            pidPolling.setMode01BatchSupported(ok)
            service.recorder.logEvent(
                "mode01_batch_probe",
                "supported",
                ok.toString(),
                "response",
                ObdProtocol.summarize(probeResponse),
            )
        } catch (ex: IOException) {
            pidPolling.setMode01BatchSupported(false)
            service.recorder.logEvent("mode01_batch_probe_failed", "error", ex.message ?: ex.javaClass.simpleName)
        }
    }

    @Throws(IOException::class)
    fun transactOneShot(
        command: String?,
        timeoutMs: Long,
    ): String = sendCommand(command, timeoutMs)

    @Throws(JSONException::class)
    override fun appendLocation(sample: JSONObject) {
        val location: FilteredLocation = service.locationTracker?.getLastLocation() ?: return
        sample.put("latitude", location.latitude)
        sample.put("longitude", location.longitude)
        location.accuracyM?.let { sample.put("accuracyM", it) }
        location.speedMps?.let { sample.put("gpsSpeedMps", it) }
        location.bearingDeg?.let { sample.put("bearingDeg", it) }
        location.provider?.let {
            sample.put("provider", it)
            sample.put("locationProvider", it)
        }
        sample.put("locationAgeMs", maxOf(0L, System.currentTimeMillis() - location.fixTimeMs))
    }

    private fun resetSessionHealth() {
        sessionHealth.reset()
    }

    @Throws(JSONException::class)
    override fun appendSessionHealth(sample: JSONObject) {
        sessionHealth.append(sample)
    }

    @Throws(IOException::class)
    open fun sendRecoverableCommand(
        command: String?,
        timeoutMs: Long,
    ): String {
        val response = sendCommand(command, timeoutMs)
        if (!ObdElmDecode.hasElmPrompt(response)) {
            service.recorder.logEvent(
                "command_no_prompt_recovery",
                "command",
                command,
                "response",
                ObdProtocol.summarize(response),
            )
            sendEscape(700)
        }
        return response
    }

    @Throws(IOException::class)
    open fun sendCommand(
        command: String?,
        timeoutMs: Long,
    ): String =
        synchronized(service.ioLock) {
            val startedAt = System.currentTimeMillis()
            val safeCommand = command ?: ""
            val rawResponse = connection.transact(safeCommand, timeoutMs, service.running::get)
            service.recorder.logCommand(
                safeCommand,
                timeoutMs,
                System.currentTimeMillis() - startedAt,
                rawResponse,
                connection.lastTransactTruncated,
            )
            rawResponse
        }

    @Throws(IOException::class)
    private fun sendEscape(settleMs: Long) {
        synchronized(service.ioLock) {
            connection.sendEscape(settleMs)
            service.recorder.logEvent("elm_escape_sent", "settleMs", settleMs.toString())
        }
    }

    companion object {
        @JvmField val LONG_BACKOFFS_MS = ConnectionRetryCoordinator.LONG_BACKOFFS_MS
        private const val STACK_HEAD_MAX_CHARS = 1000
        private const val STACK_HEAD_FRAMES = 5
        const val RAW_TRANSCRIPT_MAX_CHARS = 4000

        /** Responsive cadence while driving — the value the loop used unconditionally before G3. */
        const val DRIVE_POLL_INTERVAL_MS = 850L

        /**
         * Relaxed cadence for slow-changing states (parked / charging / plugged). A parked or
         * charging pack's SOC, temperature, and power move slowly, so a multi-second cadence keeps
         * the data fresh enough while cutting adapter round-trips and battery use. Kept conservative.
         */
        const val IDLE_POLL_INTERVAL_MS = 2500L

        /** Speed (km/h) above which we treat the car as moving even if the state string is absent. */
        private const val MOVING_SPEED_KPH = 5.0

        /**
         * Pure cadence decision (extracted for testability): pick the poll interval for the next
         * loop iteration from the just-read [sample]. Returns [DRIVE_POLL_INTERVAL_MS] when the car
         * is driving (or moving by speed, or the state is unknown — stay responsive by default) and
         * the relaxed [IDLE_POLL_INTERVAL_MS] for the slow-changing parked/charging/plugged states.
         */
        @JvmStatic
        fun pollIntervalMs(sample: JSONObject): Long {
            // A measurable road speed always means "stay responsive", regardless of the state label.
            val speed = sample.optDouble("speedKph", Double.NaN)
            if (!speed.isNaN() && speed > MOVING_SPEED_KPH) {
                return DRIVE_POLL_INTERVAL_MS
            }
            return when (sample.optString("vehicleState", "")) {
                VehicleState.PARKED.asPayloadKey(),
                VehicleState.CHARGING.asPayloadKey(),
                VehicleState.PLUGGED.asPayloadKey(),
                -> IDLE_POLL_INTERVAL_MS
                // READY (engine on, stationary — e.g. stopped at a light) stays responsive because
                // the car is about to move; driving_ev / driving_gas / unknown / absent likewise.
                else -> DRIVE_POLL_INTERVAL_MS
            }
        }

        @JvmStatic
        fun boundedRawTranscript(rawThisCycle: StringBuilder?): String =
            PidPollingState.boundedRawTranscript(rawThisCycle)

        @JvmStatic
        fun computeBackoffMs(
            attempt: Int,
            everConnected: Boolean,
            wedgedMode: Boolean,
        ): Long = ConnectionRetryCoordinator.computeBackoffMs(attempt, everConnected, wedgedMode)

        @JvmStatic
        fun exceptionClassName(ex: Throwable?): String {
            if (ex == null) {
                return ""
            }
            val name = ex.javaClass.name
            return name.ifEmpty { ex.javaClass.simpleName }
        }

        @JvmStatic
        fun stackHead(ex: Throwable?): String {
            if (ex == null) {
                return ""
            }
            val frames = ex.stackTrace
            if (frames == null || frames.isEmpty()) {
                return ""
            }
            val sb = StringBuilder()
            val frameLimit = minOf(STACK_HEAD_FRAMES, frames.size)
            for (i in 0 until frameLimit) {
                if (sb.isNotEmpty()) {
                    sb.append(" | ")
                }
                sb.append(frames[i].toString())
                if (sb.length >= STACK_HEAD_MAX_CHARS) {
                    break
                }
            }
            if (sb.length > STACK_HEAD_MAX_CHARS) {
                sb.setLength(STACK_HEAD_MAX_CHARS)
            }
            return sb.toString()
        }

        private fun redactedVin(vin: String?): String {
            if (vin == null || vin.length < 4) {
                return ""
            }
            return "…" + vin.substring(vin.length - 4)
        }
    }
}
