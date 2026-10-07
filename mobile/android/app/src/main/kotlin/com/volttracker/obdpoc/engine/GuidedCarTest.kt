package com.volttracker.obdpoc.engine

import com.volttracker.obdpoc.GuidedCarTestState
import com.volttracker.obdpoc.GuidedTestStatus
import com.volttracker.obdpoc.SwcanField
import com.volttracker.obdpoc.SwcanFrame
import com.volttracker.obdpoc.SwcanFrameDecoder
import com.volttracker.obdpoc.SwcanGroup
import com.volttracker.obdpoc.SwcanReading
import java.io.IOException
import java.util.EnumMap
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val MONOTONIC_ORIGIN_NS = System.nanoTime()

/** Milliseconds since the app started, from a clock that never jumps: what the body-bus listens time with. */
internal fun monotonicNowMs(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - MONOTONIC_ORIGIN_NS)

/**
 * A spoken, step-by-step car test ([GuidedCarScript]): the phone says what to do (lock the doors,
 * open the hatch, turn the car off, drive), listens to the body bus while it is done, and logs
 * what was heard and when, so the decoders and the listening schedule can be checked against the
 * car. Debug builds only.
 *
 * It runs on the poll thread, one step per [runNext], with an HS poll cycle between steps, so the
 * session keeps its live data and is not ended for a quiet car while the test holds it
 * ([isActive]). Each step switches to SW-CAN, says its instruction once monitoring has started,
 * streams frames for as long as the step needs, and switches back exactly as [SwcanListenRunner]'s
 * windows do. The drive is one continuous listen, live HS data paused, until the car is switched
 * off and its shutdown recorded; then the session ends ([takeSessionEnd]).
 *
 * What a listen hears goes to the session log as it goes: a `guided_capture` chunk every
 * [CHUNK_MS] ([GuidedCarRecorder]: per-id counts and gaps, arrival times of the slow broadcasts, the
 * allowlisted body frames' payload changes), then a `guided_step` with the result, the monitor's
 * coverage and anything that made the capture partial. A dropped link logs the listen as
 * `interrupted` with the chunk it was in, and the adapter's state as unknown until the reconnect's
 * re-init; the test outlives the reconnect (the runner keeps it across
 * [SwcanListenRunner.resetSession]) and runs the step again.
 *
 * It first checks that the driver can hear the phone (a tap on "I can hear it") and that the body
 * bus is heard. A step that needs Park waits for Park to be read ([SwcanListenRunner.Io.isInPark]),
 * and the wheels turning during one pauses the test until it is. It ends on Stop, at the end of the
 * drive, or when the adapter keeps failing to switch buses.
 *
 * Start, Skip, Stop and the hearing check come from the service thread ([request]); progress goes to
 * the Car tab through [GuidedCarTestState].
 */
class GuidedCarTest(
    private val io: SwcanListenRunner.Io,
    private val bus: Bus,
    private val steps: List<GuidedStep> = GuidedCarScript.steps,
    private val clock: () -> Long = ::monotonicNowMs,
    private val publish: (GuidedTestStatus) -> Unit = GuidedCarTestState::publish,
) {
    /** Spoken instructions. */
    interface Voice {
        fun say(text: String)

        /** True while anything said is still being spoken, or waiting to be. */
        fun speaking(): Boolean

        /** True once the phone has shown it can't speak: no speech engine, or one that failed. */
        fun failed(): Boolean

        /** Stops the line being spoken and drops any still waiting. */
        fun interrupt()

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

        /** "I can hear it": the phone's voice reaches the driver. */
        CONFIRM,
        ;

        companion object {
            fun fromWire(name: String?): Op? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
    }

    /** One continuous listen: the step's frames, when its instruction was said and when what it waits for came. */
    private inner class Listen(
        val step: GuidedStep,
        private val say: String,
        val attempt: Int,
    ) {
        val startedAt = clock()
        private val originEpochMs = System.currentTimeMillis()
        val recorder = GuidedCarRecorder(startedAt)
        var saidAt = -1L
        var spokenAt = startedAt
        var heardAt = -1L

        /** When the step stopped listening (-1 until then); later lines are the adapter's queue. */
        var endedAt = -1L
        var runs = 0
        var restarts = 0
        var gotPrompt = true
        var restored = true
        var moved = false

        /** Why it stopped early ([STOPPED], [SKIPPED], [MOVED], [VOICE_FAILED]), or null: it ran its course. */
        var cut: String? = null

        /** What kept the capture from being whole: `no_prompt`, `monitor_stuck`, `cut_short`, `truncated`, … */
        val partial = sortedSetOf<String>()

        /** Each monitor run, as ms after [startedAt]: where the bus was heard, and so where it wasn't. */
        private val coverage = mutableListOf<Pair<Long, Long>>()
        private var flushedAt = startedAt

        /** Monitors until the step is over, picking up again whenever the adapter stops by itself. */
        @Throws(IOException::class)
        fun monitor() {
            var quickRuns = 0
            while (endedAt < 0) {
                val runStart = clock()
                val leftMs = capMs() - (runStart - startedAt)
                if (leftMs <= 0) {
                    partial += "cap_reached"
                    break
                }
                runs += 1
                val result =
                    io.monitorStream(
                        SwcanListenRunner.MONITOR_COMMAND,
                        leftMs,
                        STOP_TIMEOUT_MS,
                        ::onLine,
                        ::onDrain,
                    )
                val runEnd = clock()
                coverage += (runStart - startedAt) to ((if (endedAt >= 0) endedAt else runEnd) - startedAt)
                gotPrompt = result.gotPrompt
                if (result.capped) partial += "tail_capped"
                if (!result.gotPrompt) partial += "no_prompt"
                if (!result.gotPrompt || endedAt >= 0) break
                if (!result.endedEarly) {
                    // The listen ran out, or the session is ending.
                    partial += if (runEnd - startedAt >= capMs()) "cap_reached" else "cut_short"
                    break
                }
                // The adapter stopped monitoring by itself (its buffer full): pick up where it left
                // off, unless it keeps doing so at once.
                restarts += 1
                quickRuns = if (runEnd - runStart < QUICK_RUN_MS) quickRuns + 1 else 0
                if (quickRuns >= MAX_QUICK_RUNS) {
                    partial += "monitor_stuck"
                    break
                }
            }
            if (endedAt < 0) endedAt = clock()
        }

        /** The longest the listen may run: speech, the step's time, and the capture after what it waits for. */
        private fun capMs(): Long {
            val tail = SPEECH_MAX_MS + SHUTDOWN_CAPTURE_MS + TAIL_MS
            if (step.kind != GuidedStepKind.DRIVE || driveStartedAt < 0) return step.maxMs + tail
            return maxOf(0L, step.maxMs - (startedAt - driveStartedAt)) + tail
        }

        fun onLine(line: String): Boolean {
            val now = clock()
            if (saidAt < 0) speak(now)
            if (line.isNotEmpty()) SwcanFrameDecoder.parseLine(line)?.let { frame(it, now, drained = false) }
            if (now - flushedAt >= CHUNK_MS) flush(now)
            if (endedAt >= 0) return false
            if (now - saidAt <= SPEECH_MAX_MS && voice.get()?.speaking() == true) spokenAt = now
            if (cut == null) cut = interruption()
            val goOn = cut == null && !over(now)
            if (!goOn) endedAt = now
            return goOn
        }

        /** A line the adapter printed after the stop byte: recorded as late, never what the step waits for. */
        fun onDrain(line: String) {
            SwcanFrameDecoder.parseLine(line)?.let { frame(it, clock(), drained = true) }
        }

        /** Monitoring has started: now the instruction, so nothing is done before the bus is heard. */
        private fun speak(now: Long) {
            saidAt = now
            spokenAt = now
            if (say.isNotEmpty()) voice.get()?.say(say)
            if (step.kind == GuidedStepKind.DRIVE && driveStartedAt < 0) {
                driveStartedAt = now
                // Live HS data pauses for the drive: this trip is a diagnostic one, not an ordinary
                // drive to judge recording quality by.
                io.logEvent("session_diagnostic", "reason", "guided_drive", "maxMs", step.maxMs.toString())
            }
        }

        private fun interruption(): String? =
            when {
                stopRequested -> STOPPED
                skipRequested -> {
                    skipRequested = false
                    SKIPPED
                }
                step.kind != GuidedStepKind.DRIVE && voice.get()?.failed() != false -> VOICE_FAILED
                moved -> MOVED
                else -> null
            }?.also { if (it != VOICE_FAILED) voice.get()?.interrupt() }

        private fun frame(
            frame: SwcanFrame,
            now: Long,
            drained: Boolean,
        ) {
            recorder.add(frame, now, drained)
            val decoded = SwcanFrameDecoder.decode(frame)
            if (decoded.isEmpty()) return
            bus.record(decoded)
            val expect = step.expect
            for (reading in decoded) {
                val before = lastSeen[reading.field]
                if (!drained &&
                    heardAt < 0 &&
                    expect != null &&
                    reading.field == expect.field &&
                    expect.matches(before, reading.value)
                ) {
                    heardAt = now
                }
                if (!drained && step.phase != GuidedPhase.DRIVING && isMoving(reading)) moved = true
                lastSeen[reading.field] = reading.value
            }
        }

        private fun isMoving(reading: SwcanReading): Boolean =
            reading.field.group == SwcanGroup.WHEELS && (reading.value as? Double ?: 0.0) >= MOTION_KPH

        private fun over(now: Long): Boolean =
            when (step.kind) {
                GuidedStepKind.CONFIRM ->
                    confirmRequested && now - spokenAt >= TALK_SETTLE_MS || now - spokenAt >= step.maxMs
                GuidedStepKind.EVENT -> if (heardAt >= 0) now - heardAt >= TAIL_MS else now - spokenAt >= step.maxMs
                GuidedStepKind.TIMED -> now - spokenAt >= step.maxMs
                GuidedStepKind.POWER_OFF ->
                    if (heardAt >= 0) now - heardAt >= SHUTDOWN_CAPTURE_MS else now - spokenAt >= step.maxMs
                GuidedStepKind.DRIVE ->
                    if (heardAt >= 0) now - heardAt >= SHUTDOWN_CAPTURE_MS else now - driveStartedAt >= step.maxMs
                GuidedStepKind.BENCHMARK -> true
            }

        /** Writes what was heard since the last chunk as a `guided_capture` event. */
        fun flush(now: Long) {
            flushedAt = now
            val chunk = recorder.flush(now)
            if (chunk.truncated) partial += "truncated"
            val fields =
                listOf(
                    "step" to step.id,
                    "index" to (index + 1).toString(),
                    "attempt" to attempt.toString(),
                    "originEpochMs" to originEpochMs.toString(),
                ) + chunk.fields
            io.logEvent("guided_capture", *fields.flatMap { listOf(it.first, it.second) }.toTypedArray())
        }

        /** `0-61234,61400-120000`: each monitor run's span; between them the bus wasn't heard. */
        fun coverageText(): String = coverage.take(MAX_COVERAGE_SPANS).joinToString(",") { "${it.first}-${it.second}" }

        /** The time between monitor runs, when nothing on the bus could be heard. */
        fun gapMs(): Long = coverage.zipWithNext { a, b -> b.first - a.second }.sum()

        fun captureText(): String = if (partial.isEmpty()) "complete" else "partial:" + partial.joinToString("+")
    }

    @Volatile private var startRequested = false

    @Volatile private var skipRequested = false

    @Volatile private var stopRequested = false

    @Volatile private var confirmRequested = false

    /** A test is running: mirrors [index] for the service thread, so a second Start is ignored. */
    @Volatile private var running = false

    /** Last status sent to the Car tab; read by [abandon] on the service thread. */
    @Volatile private var shown = GuidedTestStatus()

    private val voice = AtomicReference<Voice?>(null)

    /** Guards [busThread]: the thread on the body bus, if one is, which [abandon] waits for. */
    private val busLock = ReentrantLock()
    private val offBus = busLock.newCondition()
    private var busThread: Thread? = null

    /** The step running, -1 when no test is. Poll thread only, like everything below. */
    private var index = -1
    private var attempt = 0
    private val lastSeen = EnumMap<SwcanField, Any>(SwcanField::class.java)
    private val benchmark = SwitchBenchmark(io, bus, clock)
    private var failedRestores = 0
    private var failedEnters = 0
    private var driveStartedAt = -1L
    private var parkWaitSince = -1L

    /** Set by the poll thread at the end of the drive, taken by it in [takeSessionEnd]. */
    private var sessionEnds = false

    /** Start, skip, stop or the hearing check; safe from any thread. Start is ignored while a test runs. */
    fun request(op: Op) {
        when (op) {
            Op.START -> if (!running || stopRequested) startRequested = true
            Op.SKIP -> skipRequested = true
            Op.STOP -> {
                startRequested = false
                stopRequested = true
            }
            Op.CONFIRM -> confirmRequested = true
        }
    }

    /**
     * The session is ending (Disconnect, or the service going away): stop, give the poll thread a
     * moment ([ABANDON_WAIT_MS]) to switch the adapter back to HS, and let the voice go now rather
     * than on a poll turn that may never come. Safe from any thread.
     */
    fun abandon() {
        request(Op.STOP)
        if (!awaitOffBus()) io.logEvent("guided_bus_state_unknown", "reason", "session_ended_on_the_body_bus")
        voice.getAndSet(null)?.let {
            it.interrupt()
            it.close()
        }
        if (shown.running) show(shown.copy(running = false, ended = "Stopped", confirm = false, note = ""))
    }

    /** A test is running or waiting to start, so the session must not end for a quiet car. */
    fun isActive(): Boolean = index >= 0 || startRequested

    /** True once, after the drive heard the car switch off and recorded its shutdown: end the session now. */
    fun takeSessionEnd(): Boolean {
        val ends = sessionEnds
        sessionEnds = false
        return ends
    }

    /**
     * Poll thread, after each sample: runs the next step (the drive is one). False when there was
     * nothing to run, so the runner's own windows may use the turn.
     */
    @Throws(IOException::class)
    fun runNext(): Boolean {
        if (stopRequested) return stopNow()
        if (index < 0 && !begin()) return false
        when (bus.canListen()) {
            // A reconnect: the adapter is asked what it is again before the step runs.
            null -> return true
            false -> {
                finish("This needs an OBDLink adapter", "")
                return true
            }
            true -> Unit
        }
        val step = steps[index]
        if (!mayStart(step)) return true
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
            if (shown.running) show(shown.copy(running = false, ended = "Stopped", confirm = false, note = ""))
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
        confirmRequested = false
        index = 0
        attempt = 0
        running = true
        lastSeen.clear()
        benchmark.reset()
        failedRestores = 0
        failedEnters = 0
        driveStartedAt = -1L
        parkWaitSince = -1L
        sessionEnds = false
        voice.getAndSet(io.openVoice())?.close()
        io.logEvent("guided_test_start", "steps", steps.size.toString())
        return true
    }

    /**
     * Whether [step] may start: a parked step needs Park, an off-phase one Park or the car off. While
     * it can't, the test waits (saying why, once), and gives up after [PARK_WAIT_MS].
     */
    private fun mayStart(step: GuidedStep): Boolean {
        val ok =
            when (step.phase) {
                GuidedPhase.PARKED -> io.isInPark()
                GuidedPhase.PARKED_OR_OFF -> io.isInPark() || lastSeen[SwcanField.POWER_MODE] in OFF_POWER_MODES
                GuidedPhase.DRIVING -> true
            }
        val now = clock()
        if (ok) {
            val waitedMs = now - parkWaitSince
            if (parkWaitSince >= 0) io.logEvent("guided_park_read", "step", step.id, "waitedMs", waitedMs.toString())
            parkWaitSince = -1L
            return true
        }
        if (parkWaitSince < 0) {
            waitForPark(step, PARK_PROMPT)
        } else if (now - parkWaitSince >= PARK_WAIT_MS) {
            finish("The car wasn't read in Park", "I couldn't tell the car is in Park, so I've stopped the test.")
        }
        return false
    }

    private fun waitForPark(
        step: GuidedStep,
        prompt: String,
    ) {
        parkWaitSince = clock()
        io.logEvent("guided_waiting_for_park", "step", step.id)
        voice.get()?.say(prompt)
        show(shown.copy(instruction = prompt, confirm = false, note = ""))
    }

    @Throws(IOException::class)
    private fun runStep(step: GuidedStep) {
        showStep(step)
        val listen = listen(step, step.say)
        val result = resultOf(step, listen)
        logListen(step, listen, result)
        when (result) {
            STOPPED -> stopNow()
            MOVED -> {
                // The step runs again once Park is read.
                io.noteMotion()
                waitForPark(step, MOVING_PROMPT)
            }
            NO_CONFIRM -> finish("No tap on “I can hear it”", "I didn't get a tap, so I've stopped the test.")
            NO_BUS -> finish("Not hearing the body bus", "I can't hear the car's body bus, so I've stopped the test.")
            VOICE_FAILED -> finish("The phone couldn't speak", "")
            BUS_FAILED -> again(result)
            else -> {
                ACKS[result]?.let { voice.get()?.say(it) }
                next(result)
            }
        }
    }

    @Throws(IOException::class)
    private fun runDrive(step: GuidedStep) {
        showStep(step, DRIVE_NOTE)
        // Said once: a listen picked up again after a dropped link or a stuck monitor carries on quietly.
        val listen = listen(step, if (driveStartedAt < 0) step.say else "")
        val cut = listen?.cut
        val result =
            when {
                listen == null || listen.saidAt < 0 -> BUS_FAILED
                cut != null -> cut
                listen.heardAt >= 0 -> HEARD
                driveStartedAt >= 0 && clock() - driveStartedAt >= step.maxMs -> MISSED
                else -> SEGMENT
            }
        logListen(step, listen, result)
        when (result) {
            STOPPED -> stopNow()
            HEARD -> {
                sessionEnds = true
                next(result)
            }
            MISSED, SKIPPED -> next(result)
            else -> again(result)
        }
    }

    @Throws(IOException::class)
    private fun runBenchmark(step: GuidedStep) {
        showStep(step)
        if (step.say.isNotEmpty()) voice.get()?.say(step.say)
        val batched = step.id.startsWith(GuidedCarScript.BENCH_BATCHED)
        holdBus()
        val outcome =
            try {
                benchmark.run(step.id, batched, step.cycles) { stopRequested || skipRequested }
            } finally {
                releaseBus()
            }
        if (outcome == SwitchBenchmark.RESTORE_FAILED) failedRestores += 1
        when {
            stopRequested -> stopNow()
            skipRequested -> {
                // Skip passes over the whole timing check, not one block of it.
                skipRequested = false
                voice.get()?.interrupt()
                while (index + 1 < steps.size && steps[index + 1].kind == GuidedStepKind.BENCHMARK) index += 1
                next(SKIPPED)
            }
            else -> next(outcome)
        }
    }

    /**
     * Switches to SW-CAN, says [say] once monitoring has started and streams until the step is over,
     * then switches back. Null when SW-CAN could not be set up. A link that drops meanwhile logs the
     * listen so far as `interrupted`; anything else that goes wrong still tries to switch back.
     */
    @Throws(IOException::class)
    private fun listen(
        step: GuidedStep,
        say: String,
    ): Listen? =
        io.exclusive {
            holdBus()
            attempt += 1
            val listen = Listen(step, say, attempt)
            try {
                listenOnBus(listen)
            } catch (ex: IOException) {
                // What the adapter is set to is unknown until the reconnect's re-init (ATZ) resets it.
                interrupted(listen, ex)
                throw ex
            } catch (ex: RuntimeException) {
                interrupted(listen, ex)
                try {
                    bus.leave()
                } catch (leaveFailed: IOException) {
                    io.logEvent("guided_restore_failed", "step", step.id, "error", leaveFailed.javaClass.simpleName)
                }
                throw ex
            } finally {
                releaseBus()
            }
        }

    @Throws(IOException::class)
    private fun listenOnBus(listen: Listen): Listen? {
        val failed = bus.enter()
        if (failed != null) {
            failedEnters += 1
            io.logEvent("guided_bus_failed", "step", listen.step.id, "failedCommand", failed)
            leaveBus()
            return null
        }
        listen.monitor()
        // A monitor that never printed a line never started: the instruction wasn't said either.
        if (listen.saidAt < 0) failedEnters += 1 else failedEnters = 0
        listen.flush(clock())
        if (listen.recorder.lossy) listen.partial += "truncated"
        listen.restored = leaveBus()
        return listen
    }

    private fun interrupted(
        listen: Listen,
        ex: Exception,
    ) {
        if (listen.endedAt < 0) listen.endedAt = clock()
        listen.partial += "interrupted"
        listen.flush(clock())
        logListen(listen.step, listen, INTERRUPTED, ex.javaClass.simpleName)
        io.logEvent("guided_bus_state_unknown", "step", listen.step.id, "error", ex.javaClass.simpleName)
    }

    @Throws(IOException::class)
    private fun leaveBus(): Boolean {
        if (bus.leave()) return true
        failedRestores += 1
        io.logEvent("swcan_hs_reinit", "reason", "guided_restore_failed")
        io.reinitialize()
        return false
    }

    private fun holdBus() = busLock.withLock { busThread = Thread.currentThread() }

    private fun releaseBus() =
        busLock.withLock {
            busThread = null
            offBus.signalAll()
        }

    /** True once no other thread is on the body bus, waiting up to [ABANDON_WAIT_MS] for it to leave. */
    private fun awaitOffBus(): Boolean =
        busLock.withLock {
            var leftNs = TimeUnit.MILLISECONDS.toNanos(ABANDON_WAIT_MS)
            try {
                while (onBusElsewhere() && leftNs > 0L) leftNs = offBus.awaitNanos(leftNs)
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            !onBusElsewhere()
        }

    private fun onBusElsewhere(): Boolean = busThread.let { it != null && it != Thread.currentThread() }

    private fun resultOf(
        step: GuidedStep,
        listen: Listen?,
    ): String {
        val cut = listen?.cut
        return when {
            listen == null || listen.saidAt < 0 -> BUS_FAILED
            cut != null -> cut
            step.kind == GuidedStepKind.CONFIRM ->
                when {
                    !confirmRequested -> NO_CONFIRM
                    listen.recorder.frames == 0 -> NO_BUS
                    else -> DONE
                }
            step.expect == null -> if (listen.partial.isEmpty()) RECORDED else PARTIAL
            listen.heardAt >= 0 -> HEARD
            listen.partial.isNotEmpty() -> PARTIAL
            else -> MISSED
        }
    }

    /** The step runs again next turn (the switch failed, or the drive's capture stopped early), unless the adapter keeps failing. */
    private fun again(result: String) {
        val text =
            if (result == SEGMENT && driveStartedAt >= 0) {
                "Driving: ${(clock() - driveStartedAt) / MINUTE_MS} min recorded"
            } else {
                RESULT_TEXT[result].orEmpty()
            }
        show(shown.copy(lastResult = text))
        checkTrouble()
    }

    /** Moves past the step that just ran, unless the adapter keeps failing to switch. */
    private fun next(result: String) {
        show(shown.copy(lastResult = RESULT_TEXT[result].orEmpty(), confirm = false))
        if (checkTrouble()) return
        index += 1
        attempt = 0
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
        io.logEvent(
            "guided_test_end",
            "ended",
            ended,
            "step",
            (index + 1).toString(),
            "steps",
            steps.size.toString(),
            "endsSession",
            sessionEnds.toString(),
        )
        voice.getAndSet(null)?.let {
            it.interrupt()
            it.say(say)
            it.close()
        }
        index = -1
        running = false
        show(shown.copy(running = false, ended = ended, confirm = false, note = ""))
    }

    private fun showStep(
        step: GuidedStep,
        note: String = "",
    ) {
        show(
            GuidedTestStatus(
                running = true,
                step = index + 1,
                steps = steps.size,
                instruction = step.say.ifEmpty { shown.instruction },
                lastResult = shown.lastResult,
                confirm = step.kind == GuidedStepKind.CONFIRM,
                note = note,
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
        error: String = "",
    ) {
        val fields =
            mutableListOf(
                "step" to step.id,
                "index" to (index + 1).toString(),
                "kind" to step.kind.name.lowercase(Locale.US),
                "attempt" to attempt.toString(),
                "result" to result,
                "error" to error,
                "inPark" to io.isInPark().toString(),
            )
        if (listen != null) {
            fun since(at: Long) = if (at >= 0) (at - listen.startedAt).toString() else ""
            fields +=
                listOf(
                    "listenMs" to since(listen.endedAt),
                    "saidMs" to since(listen.saidAt),
                    "spokenMs" to since(listen.spokenAt),
                    "heardMs" to since(listen.heardAt),
                    "frames" to listen.recorder.frames.toString(),
                    "drainedFrames" to listen.recorder.drained.toString(),
                    "chunks" to listen.recorder.chunks.toString(),
                    "coverage" to listen.coverageText(),
                    "gapMs" to listen.gapMs().toString(),
                    "monitorRuns" to listen.runs.toString(),
                    "restarts" to listen.restarts.toString(),
                    "capture" to listen.captureText(),
                    "gotPrompt" to listen.gotPrompt.toString(),
                    "restored" to listen.restored.toString(),
                    "moved" to listen.moved.toString(),
                )
        }
        io.logEvent("guided_step", *fields.flatMap { listOf(it.first, it.second) }.toTypedArray())
    }

    companion object {
        /** The longest a step waits for the phone to finish speaking before its own time starts. */
        private const val SPEECH_MAX_MS = 30_000L
        private const val TALK_SETTLE_MS = 500L

        /** Listening on after what a step waited for: the frames that follow it, and a pause before the next. */
        private const val TAIL_MS = 5_000L

        /** Recorded after the car is heard switching off: its shutdown broadcasts. */
        private const val SHUTDOWN_CAPTURE_MS = 30_000L

        /** How often a listen writes out what it has heard. */
        private const val CHUNK_MS = 30_000L
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Monitor runs this short, this many in a row, are an adapter that won't stay monitoring. */
        private const val QUICK_RUN_MS = 1_000L
        private const val MAX_QUICK_RUNS = 5
        private const val MAX_COVERAGE_SPANS = 200
        private const val MAX_FAILED_RESTORES = 2
        private const val MAX_FAILED_ENTERS = 3
        private const val PARK_WAIT_MS = 5 * 60_000L
        private const val ABANDON_WAIT_MS = 2_000L
        private const val MINUTE_MS = 60_000L

        /** Any wheel this fast during a parked step: the car is moving. */
        private const val MOTION_KPH = 2.0
        private const val WAITING = "Waiting for the adapter"
        private const val PARK_PROMPT = "Put the car in Park. I'll carry on once it reads Park."
        private const val MOVING_PROMPT =
            "The car is moving, so I've paused the test. Stop, put it in Park, and I'll carry on."
        private const val DRIVE_NOTE =
            "Live data is paused while the drive is recorded. It comes back when the test ends."
        private val OFF_POWER_MODES = setOf("off", "accessory")

        private const val HEARD = "heard"
        private const val MISSED = "missed"
        private const val RECORDED = "recorded"
        private const val PARTIAL = "partial"
        private const val DONE = "done"
        private const val SEGMENT = "segment"
        private const val BUS_FAILED = "bus_failed"
        private const val SKIPPED = "skipped"
        private const val STOPPED = "stopped"
        private const val MOVED = "moved"
        private const val INTERRUPTED = "interrupted"
        private const val NO_CONFIRM = "no_confirm"
        private const val NO_BUS = "no_bus"
        private const val VOICE_FAILED = "voice_failed"

        private val ACKS =
            mapOf(
                DONE to "Thanks.",
                HEARD to "Got it.",
                MISSED to "Didn't hear that one. Moving on.",
                PARTIAL to "I only heard part of that one. Moving on.",
            )

        private val RESULT_TEXT =
            mapOf(
                DONE to "Voice and body bus OK",
                HEARD to "Heard it",
                MISSED to "Didn't hear it",
                RECORDED to "Recorded",
                PARTIAL to "Recorded, with gaps",
                SKIPPED to "Skipped",
                BUS_FAILED to "Couldn't switch to the body bus",
                SwitchBenchmark.OK to "Timed",
                SwitchBenchmark.PARTIAL to "Timed, some cycles failed",
                SwitchBenchmark.UNSUPPORTED to "This adapter can't batch commands",
                SwitchBenchmark.RESTORE_FAILED to "Couldn't switch back",
                SwitchBenchmark.HS_FAILED to "The car stopped answering",
            )
    }
}
