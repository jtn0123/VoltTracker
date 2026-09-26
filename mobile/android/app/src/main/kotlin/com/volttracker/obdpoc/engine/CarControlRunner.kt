package com.volttracker.obdpoc.engine

import com.volttracker.obdpoc.CarCommand
import com.volttracker.obdpoc.CarControlFrames
import com.volttracker.obdpoc.CarControlGate
import com.volttracker.obdpoc.ControlBus
import com.volttracker.obdpoc.ControlFrame
import com.volttracker.obdpoc.ControlReadback
import com.volttracker.obdpoc.ControlStep
import com.volttracker.obdpoc.ObdProtocol
import com.volttracker.obdpoc.SwcanField
import com.volttracker.obdpoc.SwcanFrameDecoder
import com.volttracker.obdpoc.SwcanReading
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Executes one user-confirmed car command (lock, unlock, lights, remote start, windows) on an
 * OBDLink adapter, inside the live polling session, then puts HS-CAN polling back.
 *
 * One command at a time, only when asked: [request] fills a single slot (a second request while one
 * is pending or running is refused, never queued), and the poll loop drains it in [afterSample].
 * Nothing here repeats, schedules or runs in the background.
 *
 * Before transmitting, on the poll thread, the command must pass — in this order — the settings
 * opt-in, a fresh one-shot native confirmation ([Io.consumeConfirmation]), and [CarControlGate]
 * (STN adapter that has heard SW-CAN, parked and not moving, rate limit).
 *
 * Transmission: the ONLY way a frame reaches the adapter is [CarControlFrames.stpxFor], which
 * refuses anything outside the allowlist. Around it the runner only sends adapter-local
 * configuration (protocol presets, filters, formatting, SW-CAN transceiver mode) and `STM` to read
 * the car's broadcasts back. The read-only listen path ([SwcanListenRunner]) is untouched and still
 * never transmits.
 *
 * Afterwards the adapter always gets [SwcanListenRunner.RESTORE_COMMANDS] (plus `ATV0`), verified
 * with `ATDPN`; a failed restore, or no live HS data on the next poll cycle, forces a full re-init.
 */
class CarControlRunner(
    private val io: Io,
    private val policy: Policy = Policy(),
    private val gate: CarControlGate = CarControlGate(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The engine operations the runner needs; implemented by [ObdPollingEngine]. */
    interface Io {
        @Throws(IOException::class)
        fun send(
            command: String,
            timeoutMs: Long,
        ): String

        @Throws(IOException::class)
        fun monitor(
            command: String,
            listenMs: Long,
            stopTimeoutMs: Long,
        ): ElmConnection.MonitorResult

        /** Waits [ms]; false when the session is stopping. */
        fun pause(ms: Long): Boolean

        @Throws(IOException::class)
        fun reinitialize()

        fun liveCycleCount(): Long

        fun <T> exclusive(block: () -> T): T

        fun logEvent(
            event: String,
            vararg pairs: String,
        )

        /** The Settings opt-in (and a stored PIN). */
        fun controlsEnabled(): Boolean

        fun adapterCapability(): CarControlGate.Adapter

        /** Consumes the one-shot native confirmation for [command]; false if there is none. */
        fun consumeConfirmation(command: CarCommand): Boolean

        /** Hands the decoded read-back frames to the live SW-CAN readings. */
        fun recordReadback(
            readings: List<SwcanReading>,
            atMs: Long,
        )
    }

    class Policy(
        val readbackMs: Long = 2_500L,
        val windowReadbackMs: Long = 4_000L,
        val stopTimeoutMs: Long = 1_500L,
        /** A request the poll loop could not reach within this long is dropped, not run late. */
        val maxRequestAgeMs: Long = 15_000L,
    )

    /** The last command's outcome, surfaced on every live sample. */
    class Result(
        val command: CarCommand,
        /** `confirmed`, `sent_unconfirmed`, `failed` or `refused`. */
        val outcome: String,
        val detail: String,
        val atMs: Long,
    )

    private class Pending(
        val command: CarCommand,
        val atMs: Long,
    )

    /** Mutable state of one transmission. */
    private class Run(
        val command: CarCommand,
    ) {
        var bus: ControlBus? = null
        var framesSent = 0
        var releasePending = false
        val monitorText = StringBuilder()
    }

    private val pending = AtomicReference<Pending?>(null)

    @Volatile private var running = false

    @Volatile private var lastResult: Result? = null
    private var lastCommandAtMs = NEVER
    private var healthCheckPending = false
    private var liveCyclesAtEnd = 0L

    fun resetSession() {
        pending.set(null)
        gate.reset()
        lastCommandAtMs = NEVER
        healthCheckPending = false
    }

    fun lastResult(): Result? = lastResult

    /**
     * Queues [command] for the poll loop. Returns null when accepted, otherwise the refusal (also
     * recorded as the last result). Safe from any thread.
     */
    fun request(command: CarCommand): String? {
        val now = clock()
        val refusal =
            when {
                !io.controlsEnabled() -> CarControlGate.Block.DISABLED
                running || !pending.compareAndSet(null, Pending(command, now)) -> CarControlGate.Block.BUSY
                else -> null
            }
        if (refusal != null) {
            refuse(command, refusal.wire, refusal.message, now)
            return refusal.wire
        }
        io.logEvent("car_control_request", "command", command.wireName)
        return null
    }

    /** Refuses [command] without it ever reaching the slot (e.g. no live session). */
    fun refuseOutsideSession(
        command: CarCommand,
        reason: String,
        message: String,
    ) {
        refuse(command, reason, message, clock())
    }

    /** Feeds the gate with a broadcast live sample. */
    fun observe(
        sample: JSONObject,
        now: Long,
    ) {
        gate.observe(sample, now)
    }

    /** Adds the gate state and the last result to a live sample, when controls are enabled. */
    @Throws(JSONException::class)
    fun appendTo(
        sample: JSONObject,
        now: Long,
    ) {
        if (!io.controlsEnabled()) return
        val busy = running || pending.get() != null
        val block = if (busy) CarControlGate.Block.BUSY else gate.evaluate(now, io.adapterCapability(), lastCommandAtMs)
        sample.put("carControlGate", block?.wire ?: "ready")
        sample.put("carControlGateDetail", block?.message ?: "")
        sample.put("carControlBusy", busy)
        val result = lastResult ?: return
        sample.put("carControlLastCommand", result.command.wireName)
        sample.put("carControlLastOutcome", result.outcome)
        sample.put("carControlLastDetail", result.detail)
        sample.put("carControlLastAtMs", result.atMs)
    }

    /** Called by the poll loop after each broadcast sample: health check, then the pending command. */
    @Throws(IOException::class)
    fun afterSample() {
        if (healthCheckPending) {
            healthCheckPending = false
            if (io.liveCycleCount() == liveCyclesAtEnd) {
                io.logEvent("car_control_hs_reinit", "reason", "no_live_data_after_command")
                io.reinitialize()
                return
            }
        }
        val next = pending.get() ?: return
        running = true
        pending.set(null)
        try {
            execute(next)
        } finally {
            running = false
        }
    }

    @Throws(IOException::class)
    private fun execute(request: Pending) {
        val now = clock()
        val command = request.command
        // Consumed first and unconditionally, so a refused command's confirmation can't be reused.
        val confirmed = io.consumeConfirmation(command)
        val block = gate.evaluate(now, io.adapterCapability(), lastCommandAtMs)
        when {
            now - request.atMs > policy.maxRequestAgeMs ->
                refuse(command, "expired", "The request waited too long and was dropped. Try again.", now)
            !io.controlsEnabled() ->
                refuse(command, CarControlGate.Block.DISABLED.wire, CarControlGate.Block.DISABLED.message, now)
            !confirmed -> refuse(command, "not_confirmed", "The command was not confirmed with the PIN dialog.", now)
            block != null -> refuse(command, block.wire, block.message, now)
            else -> {
                lastCommandAtMs = now
                io.exclusive { transmit(command, now) }
            }
        }
    }

    @Throws(IOException::class)
    private fun transmit(
        command: CarCommand,
        startedAt: Long,
    ) {
        val protocol = currentProtocol()
        if (protocol != HS_PROTOCOL) {
            refuse(command, "hs_protocol_$protocol", "The adapter is not on the expected HS-CAN protocol.", startedAt)
            return
        }
        val run = Run(command)
        var failure = SETUP_COMMANDS.firstOrNull { !sendOk(it) }?.let { "setup: $it" }
        if (failure == null) failure = runSteps(run)
        if (failure != null && run.releasePending) sendRelease(run)
        if (failure == null) failure = readBack(run)
        val restored = restoreHs()
        val frames = SwcanFrameDecoder.parseMonitorOutput(run.monitorText.toString())
        val decoded = SwcanFrameDecoder.decodeAll(frames)
        io.recordReadback(decoded, clock())
        val confirmation = readbackConfirmation(command.readback, decoded)
        val (outcome, detail) =
            when {
                failure != null && run.framesSent == 0 -> "failed" to "Nothing was sent: $failure."
                failure != null -> "failed" to "Stopped part-way ($failure) after ${run.framesSent} frame(s)."
                confirmation != null -> "confirmed" to "Car reported $confirmation."
                command.readback == ControlReadback.NONE ->
                    "sent_unconfirmed" to
                        "Sent. This command has no readable confirmation."
                else -> "sent_unconfirmed" to "Sent, but the car did not report the change while listening."
            }
        record(command, outcome, detail, clock())
        io.logEvent(
            "car_control",
            "command",
            command.wireName,
            "outcome",
            outcome,
            "detail",
            detail,
            "framesSent",
            run.framesSent.toString(),
            "framesHeard",
            frames.size.toString(),
            "readback",
            readbackSummary(decoded),
            "restored",
            restored.toString(),
            "durationMs",
            maxOf(0L, clock() - startedAt).toString(),
        )
        if (!restored) {
            io.logEvent("car_control_hs_reinit", "reason", "restore_failed")
            io.reinitialize()
        } else {
            healthCheckPending = true
            liveCyclesAtEnd = io.liveCycleCount()
        }
    }

    /** Runs the command's steps; returns a failure description, or null when every step went out. */
    private fun runSteps(run: Run): String? {
        for (step in run.command.steps) {
            val failure =
                when (step) {
                    is ControlStep.Send -> sendFrame(run, step.frame)
                    is ControlStep.Pause -> pause(run, step)
                    is ControlStep.HighVoltageWakeup ->
                        when {
                            !selectBus(run, ControlBus.SWCAN_11BIT) -> "protocol ${ControlBus.SWCAN_11BIT.stnProtocol}"
                            !sendOk(OPEN_PROTOCOL) -> OPEN_PROTOCOL
                            !sendOk(HIGH_VOLTAGE_WAKEUP) -> HIGH_VOLTAGE_WAKEUP
                            else -> null
                        }
                    is ControlStep.NormalTransceiver -> if (sendOk(NORMAL_TRANSCEIVER)) null else NORMAL_TRANSCEIVER
                }
            if (failure != null) return failure
        }
        return null
    }

    private fun sendFrame(
        run: Run,
        frame: ControlFrame,
    ): String? {
        if (!selectBus(run, frame.bus)) return "protocol ${frame.bus.stnProtocol}"
        val response = io.send(CarControlFrames.stpxFor(frame), TRANSMIT_TIMEOUT_MS)
        if (!transmitOk(response)) return "transmit %X: %s".format(Locale.US, frame.id, ObdProtocol.summarize(response))
        run.framesSent += 1
        if (frame.id == CarControlFrames.TELEMATICS_ID) {
            run.releasePending = frame != CarControlFrames.TELEMATICS_RELEASE
        }
        return null
    }

    private fun pause(
        run: Run,
        step: ControlStep.Pause,
    ): String? {
        val onSwcan = run.bus == ControlBus.SWCAN_11BIT || run.bus == ControlBus.SWCAN_29BIT
        if (step.listen && onSwcan) {
            val result = io.monitor(SwcanListenRunner.MONITOR_COMMAND, step.ms, policy.stopTimeoutMs)
            run.monitorText.append(result.text).append('\r')
            return if (result.gotPrompt) null else "monitor did not stop"
        }
        return if (io.pause(step.ms)) null else "session stopped"
    }

    /** A telematics request must never be left without its release frame, even on a failure. */
    private fun sendRelease(run: Run) {
        val frame = CarControlFrames.TELEMATICS_RELEASE
        if (selectBus(run, frame.bus) && transmitOk(io.send(CarControlFrames.stpxFor(frame), TRANSMIT_TIMEOUT_MS))) {
            run.framesSent += 1
            run.releasePending = false
        }
    }

    /** Listens on SW-CAN for the car's reaction; returns a failure description or null. */
    private fun readBack(run: Run): String? {
        if (run.bus != ControlBus.SWCAN_11BIT && run.bus != ControlBus.SWCAN_29BIT) {
            if (!selectBus(run, ControlBus.SWCAN_11BIT)) return "protocol ${ControlBus.SWCAN_11BIT.stnProtocol}"
        }
        val listenMs =
            if (run.command.readback == ControlReadback.WINDOWS_OPENING ||
                run.command.readback == ControlReadback.WINDOWS_CLOSING
            ) {
                policy.windowReadbackMs
            } else {
                policy.readbackMs
            }
        val result = io.monitor(SwcanListenRunner.MONITOR_COMMAND, listenMs, policy.stopTimeoutMs)
        run.monitorText.append(result.text).append('\r')
        return if (result.gotPrompt) null else "readback monitor did not stop"
    }

    /** Switches the adapter to [bus]'s raw preset (and, on SW-CAN, a pass-all receive filter). */
    private fun selectBus(
        run: Run,
        bus: ControlBus,
    ): Boolean {
        if (run.bus == bus) return true
        if (!sendOk("STP ${bus.stnProtocol}")) return false
        if (bus != ControlBus.HSCAN_11BIT && !(sendOk(CLEAR_FILTERS) && sendOk(PASS_ALL_FILTER))) return false
        run.bus = bus
        return true
    }

    private fun restoreHs(): Boolean {
        var ok = true
        for (command in RESTORE_COMMANDS) {
            ok = sendOk(command) && ok
        }
        return ok && currentProtocol() == HS_PROTOCOL
    }

    private fun currentProtocol(): String =
        ObdProtocol
            .summarize(io.send("ATDPN", COMMAND_TIMEOUT_MS))
            .uppercase(Locale.US)
            .split(Regex("\\s+"))
            .last()
            .removePrefix("A")

    private fun sendOk(command: String): Boolean =
        io.send(command, COMMAND_TIMEOUT_MS).uppercase(Locale.US).contains("OK")

    private fun refuse(
        command: CarCommand,
        reason: String,
        message: String,
        now: Long,
    ) {
        record(command, "refused", message, now)
        io.logEvent(
            "car_control",
            "command",
            command.wireName,
            "outcome",
            "refused",
            "reason",
            reason,
            "detail",
            message,
        )
    }

    private fun record(
        command: CarCommand,
        outcome: String,
        detail: String,
        atMs: Long,
    ) {
        lastResult = Result(command, outcome, detail, atMs)
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE / 2
        private const val HS_PROTOCOL = "6"
        private const val COMMAND_TIMEOUT_MS = 1_000L
        private const val TRANSMIT_TIMEOUT_MS = 1_000L
        const val OPEN_PROTOCOL = "STPO"
        const val HIGH_VOLTAGE_WAKEUP = "STCSWM 2"
        const val NORMAL_TRANSCEIVER = "STCSWM 3"
        const val CLEAR_FILTERS = "STFPC"
        const val PASS_ALL_FILTER = "STFPA 00,00"

        /** Adapter-local setup before any protocol switch: silent monitoring, raw frames, headers shown. */
        @JvmField
        val SETUP_COMMANDS =
            listOf(
                "STCMM 0", // read-back monitoring never ACKs
                "ATCAF0", // raw CAN: no PCI byte added to STPX data
                "ATH1",
                "ATS1",
                "ATV1", // variable DLC: a 3-byte frame goes out as DLC 3, not zero-padded to 8
            )

        /** The listen runner's HS restore, plus variable DLC back off. */
        @JvmField
        val RESTORE_COMMANDS = listOf("ATV0") + SwcanListenRunner.RESTORE_COMMANDS

        /** Every non-transmit command the runner can send; CarControlRunnerTest pins it. */
        @JvmField
        val CONFIG_COMMANDS: Set<String> =
            (
                SETUP_COMMANDS +
                    RESTORE_COMMANDS +
                    ControlBus.entries.map { "STP ${it.stnProtocol}" } +
                    listOf(
                        "ATDPN",
                        OPEN_PROTOCOL,
                        HIGH_VOLTAGE_WAKEUP,
                        NORMAL_TRANSCEIVER,
                        CLEAR_FILTERS,
                        PASS_ALL_FILTER,
                        SwcanListenRunner.MONITOR_COMMAND,
                    )
            ).toSet()

        private val TRANSMIT_ERRORS =
            listOf(
                "?",
                "ERROR",
                "BUS",
                "UNABLE",
                "FB ",
                "ACT ALERT",
                "LV RESET",
                "BUFFER FULL",
                "OUT OF MEMORY",
                "STOPPED",
            )

        /** `STPX` with `r:0` prints nothing (or `OK`) on success; any error token means not sent. */
        @JvmStatic
        fun transmitOk(response: String?): Boolean {
            val text = ObdProtocol.summarize(response).uppercase(Locale.US)
            return TRANSMIT_ERRORS.none { text.contains(it) }
        }

        /**
         * What the car broadcast that proves [readback], or null. Readings are in bus order, so the
         * last value of a field is the car's current state.
         */
        @JvmStatic
        fun readbackConfirmation(
            readback: ControlReadback,
            readings: List<SwcanReading>,
        ): String? {
            fun last(field: SwcanField): Any? = readings.lastOrNull { it.field == field }?.value
            return when (readback) {
                ControlReadback.LOCKED -> if (last(SwcanField.LOCK_STATE) == "locked") "doors locked" else null
                ControlReadback.UNLOCKED -> if (last(SwcanField.LOCK_STATE) == "unlocked") "doors unlocked" else null
                ControlReadback.REMOTE_START_ON ->
                    when {
                        last(SwcanField.REMOTE_START) == "on" -> "remote start on"
                        ((last(SwcanField.BLOWER) as? Double) ?: 0.0) > 0.0 -> "cabin blower running"
                        else -> null
                    }
                ControlReadback.REMOTE_START_OFF ->
                    if (last(SwcanField.REMOTE_START) ==
                        "off"
                    ) {
                        "remote start off"
                    } else {
                        null
                    }
                ControlReadback.WINDOWS_OPENING ->
                    if (windowValues(
                            readings,
                        ).any { it > 0.0 }
                    ) {
                        "windows opening"
                    } else {
                        null
                    }
                ControlReadback.WINDOWS_CLOSING -> if (windowsClosing(readings)) "windows closing" else null
                ControlReadback.NONE -> null
            }
        }

        private val WINDOW_FIELDS =
            setOf(SwcanField.WINDOW_FL, SwcanField.WINDOW_FR, SwcanField.WINDOW_RL, SwcanField.WINDOW_RR)

        private fun windowValues(readings: List<SwcanReading>): List<Double> =
            readings.filter { it.field in WINDOW_FIELDS }.mapNotNull { it.value as? Double }

        /** Some window travelled down (a later position below an earlier one), or a passenger window reads shut. */
        private fun windowsClosing(readings: List<SwcanReading>): Boolean {
            val byField = readings.filter { it.field in WINDOW_FIELDS }.groupBy({ it.field }, { it.value as? Double })
            return byField.any { (field, values) ->
                val known = values.filterNotNull()
                known.zipWithNext().any { (a, b) -> b < a } ||
                    (field != SwcanField.WINDOW_FL && known.any { it == 0.0 })
            }
        }

        private val READBACK_FIELDS =
            listOf(SwcanField.LOCK_STATE, SwcanField.LOCK_SOURCE, SwcanField.REMOTE_START, SwcanField.BLOWER) +
                WINDOW_FIELDS

        private fun readbackSummary(readings: List<SwcanReading>): String =
            READBACK_FIELDS
                .mapNotNull { field ->
                    readings.lastOrNull { it.field == field }?.let { "${field.name.lowercase(Locale.US)}=${it.value}" }
                }.joinToString(" ")
    }
}
