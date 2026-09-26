package com.volttracker.obdpoc.engine

import com.volttracker.obdpoc.CarCommand
import com.volttracker.obdpoc.CarControlAuth
import com.volttracker.obdpoc.CarControlGate
import com.volttracker.obdpoc.CarControlSettings
import com.volttracker.obdpoc.SwcanReading
import org.json.JSONException
import org.json.JSONObject

/**
 * The polling engine's side of [CarControlRunner.Io]. Adapter IO, locking, re-init and session
 * logging go through the same [SwcanListenRunner.Io] the listen window uses (so every command still
 * reaches the adapter through the engine's serialized `sendCommand`); the opt-in comes from
 * [CarControlSettings], the one-shot confirmation from [CarControlAuth], and the adapter capability
 * and read-back readings from the SW-CAN [listener].
 */
internal class CarControlEngineIo(
    private val adapter: SwcanListenRunner.Io,
    private val listener: SwcanListenRunner,
    private val settings: CarControlSettings,
    private val sleep: (Long) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : CarControlRunner.Io {
    override fun send(
        command: String,
        timeoutMs: Long,
    ): String = adapter.send(command, timeoutMs)

    override fun monitor(
        command: String,
        listenMs: Long,
        stopTimeoutMs: Long,
    ): ElmConnection.MonitorResult = adapter.monitor(command, listenMs, stopTimeoutMs)

    override fun pause(ms: Long): Boolean = sleep(ms)

    override fun reinitialize() = adapter.reinitialize()

    override fun liveCycleCount(): Long = adapter.liveCycleCount()

    override fun <T> exclusive(block: () -> T): T = adapter.exclusive(block)

    override fun logEvent(
        event: String,
        vararg pairs: String,
    ) = adapter.logEvent(event, *pairs)

    override fun controlsEnabled(): Boolean = settings.isEnabled()

    override fun adapterCapability(): CarControlGate.Adapter = listener.controlCapability()

    override fun consumeConfirmation(command: CarCommand): Boolean = CarControlAuth.consume(command, clock())

    override fun recordReadback(
        readings: List<SwcanReading>,
        atMs: Long,
    ) = listener.readings.record(readings, atMs)
}

/**
 * The polling engine's whole car-control surface, kept out of [ObdPollingEngine] so the engine only
 * makes one-line calls: it tracks whether a live HS-CAN session is polling (the only time a command
 * may run), refuses requests outside one, stamps gate/outcome state onto each sample, and runs a
 * queued command after the sample (see [CarControlRunner]).
 */
internal class CarControlSession(
    private val runner: CarControlRunner,
    private val logError: (String, Exception) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // True only while [whileLive] runs, i.e. a live session that can execute a car command. Written on
    // the poll thread, read on whatever thread calls [request].
    @Volatile private var live = false

    /** Queues a user-confirmed command; refused (and recorded as the last result) when not live. */
    fun request(command: CarCommand) {
        if (!live) {
            runner.refuseOutsideSession(command, "no_live_session", "No live connection to the car. Connect first.")
            return
        }
        runner.request(command)
    }

    fun <T> whileLive(block: () -> T): T {
        live = true
        try {
            return block()
        } finally {
            live = false
        }
    }

    fun resetSession() = runner.resetSession()

    fun afterSample() = runner.afterSample()

    fun appendTo(sample: JSONObject) {
        val now = clock()
        runner.observe(sample, now)
        try {
            runner.appendTo(sample, now)
        } catch (ex: JSONException) {
            logError("car_control_sample_encoding_error", ex)
        }
    }
}
