package com.volttracker.obdpoc.engine

import com.volttracker.obdpoc.GuidedCarTestState
import com.volttracker.obdpoc.GuidedTestStatus
import com.volttracker.obdpoc.SwcanField
import com.volttracker.obdpoc.SwcanFrame
import com.volttracker.obdpoc.SwcanFrameDecoder
import com.volttracker.obdpoc.SwcanReading
import java.io.IOException
import java.util.EnumMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * A spoken, step-by-step car test ([GuidedCarScript]): the phone says what to do (lock the doors,
 * open the hatch, turn the car off, drive), listens to the body bus while it is done, and logs
 * what was heard and when, so the decoders and the listening schedule can be checked against the
 * car. Debug builds only.
 *
 * It runs on the poll thread, one step per [runNext], with an HS poll cycle between steps, so the
 * session keeps its live data and is not ended for a quiet car while the test holds it
 * ([isActive]). Each step switches to SW-CAN, streams frames for as long as the step needs,
 * switches back exactly as [SwcanListenRunner]'s windows do, and logs a `guided_step` built by
 * [GuidedCarRecorder]: per-id counts and gaps, arrival times of the slow broadcasts, and the body
 * frames' payload changes, never a sensitive frame. A step's time starts once the phone has
 * finished speaking. The test outlives a reconnect (the runner keeps it across
 * [SwcanListenRunner.resetSession]) and ends on Stop, at the end of the drive, or after a second
 * failed switch back.
 *
 * Start, Skip and Stop come from the service thread ([request]); progress goes to the Car tab
 * through [GuidedCarTestState].
 */
class GuidedCarTest(
    private val io: SwcanListenRunner.Io,
    private val bus: Bus,
    private val steps: List<GuidedStep> = GuidedCarScript.steps,
    private val clock: () -> Long = System::currentTimeMillis,
    private val publish: (GuidedTestStatus) -> Unit = GuidedCarTestState::publish,
) {
    /** Spoken instructions. */
    interface Voice {
        fun say(text: String)

        /** True while anything said is still being spoken, or waiting to be. */
        fun speaking(): Boolean

        /** Lets what is being said finish, then releases the speech engine. */
        fun close()
    }

    /** The SW-CAN switch, done as [SwcanListenRunner] does it for its own windows. */
    interface Bus {
        /** Null until the adapter has said what it is; then whether it can listen to SW-CAN. */
        fun canListen(): Boolean?

        /** Checks the HS protocol and sets SW-CAN up; returns the command that failed, or null. */
        @Throws(IOException::class)
        fun enter(): String?

        /** Puts HS-CAN back as polling left it; false when it would not come back. */
        @Throws(IOException::class)
        fun leave(): Boolean

        /** What a step decoded, for the Car tab's live readings. */
        fun record(decoded: List<SwcanReading>)
    }

    enum class Op {
        START,
        SKIP,
        STOP,
        ;

        companion object {
            fun fromWire(name: String?): Op? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
    }

    /** One continuous listen: the step's frames, and when what it waits for came. */
    private inner class Listen(
        val step: GuidedStep,
        val startedAt: Long,
    ) {
        val recorder = GuidedCarRecorder(startedAt)
        var spokenAt = startedAt
        var heardAt = -1L
        var lastFrameAt = startedAt

        /** When the step stopped listening (-1 until then); later lines are the adapter's queue. */
        var endedAt = -1L
        var runs = 0
        var gotPrompt = true
        var restored = true

        /** Why it stopped early: [STOPPED], [SKIPPED], or null (it ran its course). */
        var cut: String? = null

        fun onLine(line: String): Boolean {
            val now = clock()
            if (line.isNotEmpty()) SwcanFrameDecoder.parseLine(line)?.let { frame(it, now) }
            if (endedAt >= 0) return false
            if (now - startedAt <= SPEECH_MAX_MS && voice.get()?.speaking() == true) spokenAt = now
            when {
                stopRequested -> cut = STOPPED
                skipRequested -> {
                    skipRequested = false
                    cut = SKIPPED
                }
            }
            val goOn = cut == null && !over(now)
            if (!goOn) endedAt = now
            return goOn
        }

        private fun frame(
            frame: SwcanFrame,
            now: Long,
        ) {
            recorder.add(frame, now)
            lastFrameAt = now
            val decoded = SwcanFrameDecoder.decode(frame)
            if (decoded.isEmpty()) return
            bus.record(decoded)
            val expect = step.expect
            for (reading in decoded) {
                val before = lastSeen[reading.field]
                if (heardAt < 0 &&
                    expect != null &&
                    reading.field == expect.field &&
                    expect.matches(before, reading.value)
                ) {
                    heardAt = now
                }
                lastSeen[reading.field] = reading.value
            }
        }

        private fun over(now: Long): Boolean =
            when (step.kind) {
                GuidedStepKind.TALK -> now - spokenAt >= TALK_SETTLE_MS && now - startedAt >= TALK_MIN_MS
                GuidedStepKind.EVENT -> if (heardAt >= 0) now - heardAt >= TAIL_MS else now - spokenAt >= step.maxMs
                GuidedStepKind.TIMED, GuidedStepKind.BENCHMARK -> now - spokenAt >= step.maxMs
                GuidedStepKind.POWER_OFF -> if (heardAt >= 0) shutdownOver(now) else now - spokenAt >= step.maxMs
                GuidedStepKind.DRIVE -> if (heardAt >= 0) shutdownOver(now) else now - startedAt >= DRIVE_SEGMENT_MS
            }

        /** The shutdown burst is over: the bus went silent, or the capture hit its cap. */
        private fun shutdownOver(now: Long): Boolean =
            now - lastFrameAt >= SHUTDOWN_SILENCE_MS || now - heardAt >= SHUTDOWN_MAX_MS
    }

    @Volatile private var startRequested = false

    @Volatile private var skipRequested = false

    @Volatile private var stopRequested = false

    /** Last status sent to the Car tab; read by [abandon] on the service thread. */
    @Volatile private var shown = GuidedTestStatus()

    private val voice = AtomicReference<Voice?>(null)

    /** The step running, -1 when no test is. Poll thread only, like everything below. */
    private var index = -1
    private val lastSeen = EnumMap<SwcanField, Any>(SwcanField::class.java)
    private val benchmark = SwitchBenchmark(io, bus, clock)
    private var failedRestores = 0
    private var failedEnters = 0
    private var driveStartedAtMs = 0L
    private var driveSegments = 0

    /** Start, skip or stop; safe from any thread. Start is ignored while a test runs. */
    fun request(op: Op) {
        when (op) {
            Op.START -> startRequested = true
            Op.SKIP -> skipRequested = true
            Op.STOP -> {
                startRequested = false
                stopRequested = true
            }
        }
    }

    /**
     * The session is ending (Disconnect, or the service going away): stop, and let the voice go
     * now rather than on a poll turn that may never come. Safe from any thread.
     */
    fun abandon() {
        request(Op.STOP)
        voice.getAndSet(null)?.close()
        if (shown.running) show(shown.copy(running = false, ended = "Stopped"))
    }

    /** A test is running or waiting to start, so the session must not end for a quiet car. */
    fun isActive(): Boolean = index >= 0 || startRequested

    /**
     * Poll thread, after each sample: runs the next step (or one drive segment). False when there
     * was nothing to run, so the runner's own windows may use the turn.
     */
    @Throws(IOException::class)
    fun runNext(): Boolean {
        if (stopRequested) return stopNow()
        if (index < 0 && !begin()) return false
        val step = steps[index]
        when (step.kind) {
            GuidedStepKind.BENCHMARK -> runBenchmark(step)
            GuidedStepKind.DRIVE -> runDrive(step)
            else -> runStep(step)
        }
        return true
    }

    /** Ends the test on Stop. A Start that came after the Stop still stands, for the next turn. */
    private fun stopNow(): Boolean {
        stopRequested = false
        skipRequested = false
        if (index < 0) {
            if (shown.running) show(shown.copy(running = false, ended = "Stopped"))
            return false
        }
        finish("Stopped", "Test stopped.")
        return true
    }

    private fun begin(): Boolean {
        if (!startRequested) return false
        when (bus.canListen()) {
            null -> {
                val waiting = GuidedTestStatus(running = true, steps = steps.size, instruction = WAITING)
                if (shown != waiting) show(waiting)
                return false
            }
            false -> {
                startRequested = false
                io.logEvent("guided_test_unavailable", "reason", "not_stn")
                show(GuidedTestStatus(ended = "This needs an OBDLink adapter"))
                return false
            }
            true -> Unit
        }
        startRequested = false
        skipRequested = false
        index = 0
        lastSeen.clear()
        benchmark.reset()
        failedRestores = 0
        failedEnters = 0
        driveSegments = 0
        voice.getAndSet(io.openVoice())?.close()
        io.logEvent("guided_test_start", "steps", steps.size.toString())
        return true
    }

    @Throws(IOException::class)
    private fun runStep(step: GuidedStep) {
        showStep(step)
        val listen = listen(step, step.say)
        val result = resultOf(step, listen)
        logListen(step, listen, result)
        if (result == STOPPED) {
            stopNow()
            return
        }
        when (result) {
            HEARD -> voice.get()?.say("Got it.")
            MISSED -> voice.get()?.say("Didn't hear that one. Moving on.")
        }
        next(result)
    }

    @Throws(IOException::class)
    private fun runDrive(step: GuidedStep) {
        val first = driveSegments == 0
        if (first) {
            driveStartedAtMs = clock()
            showStep(step)
        }
        driveSegments += 1
        val listen = listen(step, if (first) step.say else "")
        val carOff = listen != null && listen.heardAt >= 0
        val timedOut = clock() - driveStartedAtMs >= step.maxMs
        val cut = listen?.cut
        val result =
            when {
                cut != null -> cut
                carOff -> HEARD
                timedOut -> MISSED
                else -> SEGMENT
            }
        logListen(step, listen, result, driveSegments)
        if (result == STOPPED) {
            stopNow()
        } else if (result == SEGMENT) {
            val minutes = (clock() - driveStartedAtMs) / MINUTE_MS
            show(shown.copy(lastResult = "Driving: $minutes min recorded"))
            checkTrouble()
        } else {
            next(result)
        }
    }

    @Throws(IOException::class)
    private fun runBenchmark(step: GuidedStep) {
        showStep(step)
        if (step.say.isNotEmpty()) voice.get()?.say(step.say)
        val batched = step.id.startsWith(GuidedCarScript.BENCH_BATCHED)
        val outcome = benchmark.run(step.id, batched, step.maxMs.toInt()) { stopRequested || skipRequested }
        if (outcome == SwitchBenchmark.RESTORE_FAILED) failedRestores += 1
        when {
            stopRequested -> stopNow()
            skipRequested -> {
                // Skip passes over the whole timing check, not one block of it.
                skipRequested = false
                while (index + 1 < steps.size && steps[index + 1].kind == GuidedStepKind.BENCHMARK) index += 1
                next(SKIPPED)
            }
            else -> next(outcome)
        }
    }

    /**
     * Switches to SW-CAN, says [say] and streams until the step is over, then switches back. Null
     * when SW-CAN could not be set up.
     */
    @Throws(IOException::class)
    private fun listen(
        step: GuidedStep,
        say: String,
    ): Listen? =
        io.exclusive {
            val failed = bus.enter()
            if (failed != null) {
                failedEnters += 1
                io.logEvent("guided_bus_failed", "step", step.id, "failedCommand", failed)
                leaveBus()
                return@exclusive null
            }
            failedEnters = 0
            val listen = Listen(step, clock())
            if (say.isNotEmpty()) voice.get()?.say(say)
            val capMs =
                if (step.kind == GuidedStepKind.DRIVE) {
                    DRIVE_SEGMENT_MS + SHUTDOWN_MAX_MS + SPEECH_MAX_MS
                } else {
                    SPEECH_MAX_MS + step.maxMs + SHUTDOWN_MAX_MS
                }
            var result: ElmConnection.MonitorResult
            var leftMs: Long
            do {
                listen.runs += 1
                leftMs = capMs - (clock() - listen.startedAt)
                result = io.monitorStream(SwcanListenRunner.MONITOR_COMMAND, leftMs, STOP_TIMEOUT_MS, listen::onLine)
                // The adapter stops monitoring by itself when its buffer fills: pick up where it left off.
            } while (result.endedEarly &&
                result.gotPrompt &&
                listen.endedAt < 0 &&
                listen.runs < MAX_MONITOR_RUNS &&
                leftMs > 0
            )
            if (listen.endedAt < 0) listen.endedAt = clock()
            listen.gotPrompt = result.gotPrompt
            listen.restored = leaveBus()
            listen
        }

    @Throws(IOException::class)
    private fun leaveBus(): Boolean {
        if (bus.leave()) return true
        failedRestores += 1
        io.logEvent("swcan_hs_reinit", "reason", "guided_restore_failed")
        io.reinitialize()
        return false
    }

    private fun resultOf(
        step: GuidedStep,
        listen: Listen?,
    ): String {
        val cut = listen?.cut
        return when {
            listen == null -> BUS_FAILED
            cut != null -> cut
            step.kind == GuidedStepKind.TALK -> DONE
            step.expect == null -> RECORDED
            listen.heardAt >= 0 -> HEARD
            else -> MISSED
        }
    }

    /** Moves past the step that just ran, unless the adapter keeps failing to switch. */
    private fun next(result: String) {
        show(shown.copy(lastResult = RESULT_TEXT[result].orEmpty()))
        if (checkTrouble()) return
        index += 1
        if (index >= steps.size) finish("Finished", "That's the whole test. Thank you.")
    }

    private fun checkTrouble(): Boolean {
        if (failedRestores < MAX_FAILED_RESTORES && failedEnters < MAX_FAILED_ENTERS) return false
        finish(
            "The adapter didn't switch buses cleanly",
            "The adapter isn't switching buses cleanly, so I've stopped the test.",
        )
        return true
    }

    private fun finish(
        ended: String,
        say: String,
    ) {
        io.logEvent("guided_test_end", "ended", ended, "step", (index + 1).toString(), "steps", steps.size.toString())
        voice.getAndSet(null)?.let {
            it.say(say)
            it.close()
        }
        index = -1
        show(shown.copy(running = false, ended = ended))
    }

    private fun showStep(step: GuidedStep) {
        show(
            GuidedTestStatus(
                running = true,
                step = index + 1,
                steps = steps.size,
                instruction = step.say.ifEmpty { shown.instruction },
                lastResult = shown.lastResult,
            ),
        )
    }

    private fun show(status: GuidedTestStatus) {
        shown = status
        publish(status)
    }

    private fun logListen(
        step: GuidedStep,
        listen: Listen?,
        result: String,
        segment: Int = 0,
    ) {
        val recorder = listen?.recorder
        io.logEvent(
            "guided_step",
            "step",
            step.id,
            "index",
            (index + 1).toString(),
            "kind",
            step.kind.name.lowercase(Locale.US),
            "result",
            result,
            "segment",
            segment.toString(),
            "listenMs",
            listen?.let { it.endedAt - it.startedAt }?.toString().orEmpty(),
            "spokenMs",
            listen?.let { it.spokenAt - it.startedAt }?.toString().orEmpty(),
            "heardMs",
            listen?.let { if (it.heardAt >= 0) (it.heardAt - it.startedAt).toString() else "" }.orEmpty(),
            "frames",
            recorder?.frames?.toString().orEmpty(),
            "ids",
            recorder?.idSummary().orEmpty().take(MAX_FIELD_CHARS),
            "arrivals",
            recorder?.arrivalSummary().orEmpty().take(MAX_FIELD_CHARS),
            "changes",
            recorder
                ?.changes
                .orEmpty()
                .joinToString(" | ") { "%d %08X %s".format(Locale.US, it.atMs, it.id, it.data) }
                .take(MAX_FIELD_CHARS),
            "droppedChanges",
            recorder?.droppedChanges?.toString().orEmpty(),
            "monitorRuns",
            listen?.runs?.toString().orEmpty(),
            "gotPrompt",
            listen?.gotPrompt?.toString().orEmpty(),
            "restored",
            listen?.restored?.toString().orEmpty(),
            "parked",
            io.isStationary().toString(),
        )
    }

    companion object {
        /** The longest a step waits for the phone to finish speaking before its own time starts. */
        private const val SPEECH_MAX_MS = 30_000L
        private const val TALK_MIN_MS = 3_000L
        private const val TALK_SETTLE_MS = 500L

        /** Listening on after what a step waited for, for the frames that follow it. */
        private const val TAIL_MS = 3_000L
        private const val SHUTDOWN_SILENCE_MS = 5_000L
        private const val SHUTDOWN_MAX_MS = 60_000L
        private const val DRIVE_SEGMENT_MS = 60_000L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val MAX_MONITOR_RUNS = 20
        private const val MAX_FAILED_RESTORES = 2
        private const val MAX_FAILED_ENTERS = 3
        private const val MAX_FIELD_CHARS = 16_000
        private const val MINUTE_MS = 60_000L
        private const val WAITING = "Waiting for the adapter"

        private const val HEARD = "heard"
        private const val MISSED = "missed"
        private const val RECORDED = "recorded"
        private const val DONE = "done"
        private const val SEGMENT = "segment"
        private const val BUS_FAILED = "bus_failed"
        private const val SKIPPED = "skipped"
        private const val STOPPED = "stopped"

        private val RESULT_TEXT =
            mapOf(
                HEARD to "Heard it",
                MISSED to "Didn't hear it",
                RECORDED to "Recorded",
                SKIPPED to "Skipped",
                BUS_FAILED to "Couldn't switch to the body bus",
                SwitchBenchmark.OK to "Timed",
                SwitchBenchmark.UNSUPPORTED to "This adapter can't batch commands",
                SwitchBenchmark.RESTORE_FAILED to "Couldn't switch back",
                SwitchBenchmark.HS_FAILED to "The car stopped answering",
            )
    }
}
