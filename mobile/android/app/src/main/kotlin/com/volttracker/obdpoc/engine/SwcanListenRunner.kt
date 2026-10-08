package com.volttracker.obdpoc.engine

import com.volttracker.obdpoc.CarControlGate
import com.volttracker.obdpoc.ObdProtocol
import com.volttracker.obdpoc.SwcanField
import com.volttracker.obdpoc.SwcanFrame
import com.volttracker.obdpoc.SwcanFrameDecoder
import com.volttracker.obdpoc.SwcanGroup
import com.volttracker.obdpoc.SwcanPrivacy
import com.volttracker.obdpoc.SwcanReading
import com.volttracker.obdpoc.SwcanReadings
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/**
 * Listen-only reader for the Volt's single-wire CAN (GMLAN, OBD pin 1, 33.3 kbit/s) broadcasts.
 *
 * An OBDLink has one CAN controller, so hearing SW-CAN means leaving HS-CAN for a moment. Every
 * [Policy.intervalMs] (while the HS bus is answering) the runner pauses polling, switches the
 * adapter to SW-CAN in receive-only mode, monitors for [Policy.listenMs], then puts HS-CAN back
 * exactly as [ObdPollingEngine] set it up and checks the next poll cycle still gets live data.
 *
 * While the Car tab is on screen ([requestBodyFocus]) windows run back to back instead, with one HS
 * poll cycle between them, so a window moving or a door opening shows up within seconds.
 *
 * READ ONLY, by construction. Every command sent here is adapter configuration or monitoring:
 * - `STCMM 0` — monitor without acknowledging frames (the adapter stays electrically silent);
 * - `STP 61` — ISO 11898 raw CAN on the SW-CAN transceiver, so no ISO-TP flow-control frame is
 *   ever generated in response to a first frame;
 * - `STFPC` / `STFPA` — receive filters; `ATH1`/`ATS1`/`ATCAF0` — output formatting;
 * - `STM` — monitor; stopped by a single byte the adapter swallows.
 * No request, wake-up (`STCSWM 2`), periodic message (`STPPMA`) or raw transmit (`STPX`) is ever
 * issued (SwcanListenRunnerTest pins the full command set).
 *
 * When the HS bus goes quiet (the car turning off, usually) one bounded quiet capture listens for
 * the states the body modules send as they shut down ([runQuietCapture]).
 *
 * Only runs on an STN-based adapter (answers `STI` with `STN…`) whose HS protocol is ISO 15765
 * 11-bit/500k (`ATDPN` 6). A setup failure, [Policy.maxConsecutiveEmpty] windows that hear nothing,
 * or [Policy.maxTroubledWindows] windows whose stop or restore went wrong disable it for the rest of
 * the session. A failed restore always forces a full adapter re-init so HS polling is never left
 * half-configured. A quiet HS bus after a window is only trouble when the car's power mode does not
 * explain it: a car that has just been switched off answers nothing, and that is not a fault.
 */
class SwcanListenRunner(
    private val io: Io,
    private val policy: Policy = Policy(),
    /** The guided test's clock: one that never jumps, since that test measures intervals. */
    guidedClock: () -> Long = ::monotonicNowMs,
    /** Wall time: readings are stamped with it and aged against each live sample's `updatedAt`. */
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

        /**
         * [monitor], each output line handed over as it arrives ([ElmConnection.monitorStream]):
         * to [onLine] while listening, to [onDrain] once the stop byte has gone. This default runs
         * [monitor] and replays its lines, enough for fakes that time nothing.
         */
        @Throws(IOException::class)
        fun monitorStream(
            command: String,
            listenMs: Long,
            stopTimeoutMs: Long,
            onLine: (String) -> Boolean,
            onDrain: (String) -> Unit,
        ): ElmConnection.MonitorResult {
            val result = monitor(command, listenMs, stopTimeoutMs)
            var listening = true
            for (line in result.text.split('\r', '\n', '>')) {
                when {
                    line.isEmpty() -> Unit
                    listening -> listening = onLine(line)
                    else -> onDrain(line)
                }
            }
            return result
        }

        /** The guided car test's spoken instructions; none by default. */
        fun openVoice(): GuidedCarTest.Voice? = null

        /** Full adapter reset + HS init (ATZ …), used when HS state cannot be trusted. */
        @Throws(IOException::class)
        fun reinitialize()

        /** Count of poll cycles that returned live data; advances whenever HS-CAN answers. */
        fun liveCycleCount(): Long

        fun msSinceLiveData(): Long

        /** Runs [block] holding the adapter IO lock, so no other runner interleaves commands. */
        fun <T> exclusive(block: () -> T): T

        /**
         * True while the car is parked ([ParkedDetector]: in Park, not just stopped at a light).
         * Parked, a longer and more frequent window costs nothing the driver sees and catches the
         * event-only body frames (locks, doors, windows).
         */
        fun isStationary(): Boolean = false

        /**
         * The guided test's Park check ([ParkedDetector.isInPark]): Park read in the last few seconds,
         * at a standstill, and nothing has shown the car moving since.
         */
        fun isInPark(): Boolean = false

        /** Asks the next HS poll cycle to read the gear, so [isInPark] can be answered from a fresh read. */
        fun requestGearRead() = Unit

        /** How many times anything has shown the car moving ([ParkedDetector.motionCount]); a change means it has. */
        fun motionCount(): Long = 0L

        /** The body bus showed the car moving: Park has to be read again before [isInPark]. */
        fun noteMotion() = Unit

        /** Session JSONL event, like every other engine event. */
        fun logEvent(
            event: String,
            vararg pairs: String,
        )
    }

    class Policy(
        /** Delay after the adapter is identified before the first window. */
        val firstWindowDelayMs: Long = 20_000L,
        val intervalMs: Long = 45_000L,
        val listenMs: Long = 1_200L,
        /**
         * How long to wait for the prompt after the stop byte. On the car the adapter has kept
         * printing queued frames for about 4 s after it (drives of 2026-10-03 and 10-05); it
         * returns as soon as the prompt arrives, so the usual stop costs nothing extra.
         */
        val stopTimeoutMs: Long = 5_000L,
        /** Only listen while HS-CAN answered within this long — a sleeping car has no SW-CAN either. */
        val maxLiveDataAgeMs: Long = 5_000L,
        val maxConsecutiveEmpty: Int = 3,
        /**
         * Windows whose stop prompt never came or whose restore failed (even after a retry) before
         * the listener gives up for the session. The first is recovered by a full re-init. On the
         * car it struck once per drive (2026-10-03 in window 66, 10-05 in window 3), and giving up
         * there lost SW-CAN for the rest of the drive.
         */
        val maxTroubledWindows: Int = 2,
        /** Stationary cadence: on the car a 1.2 s window every 45 s heard ~3% of the time. */
        val parkedIntervalMs: Long = 15_000L,
        val parkedListenMs: Long = 3_000L,
        /**
         * A body test, and any longer window, listens in chunks this long so no single monitor
         * reply nears the 64 KB cap.
         */
        val bodyTestChunkMs: Long = 5_000L,
        val bodyTestMaxMs: Long = 90_000L,
        /**
         * Tire hunt: the tire frame was never heard parked (sensors stay quiet until the wheels
         * roll), and a 1.2 s window every 45 s rarely lands on it. While moving with no tire value
         * yet this session, listen longer and more often, for a bounded number of windows. On a
         * drive (2026-10-07) the car sent them about once a minute, 8 to 94 s apart, so the hunt
         * listens often rather than long: a window pauses live data for its listen plus 2.5-5 s of
         * bus switching, which has to stay under the live trip's 10 s step. 32 windows cover 10-12 min.
         */
        val tireHuntListenMs: Long = 4_000L,
        val tireHuntIntervalMs: Long = 15_000L,
        val tireHuntMaxWindows: Int = 32,
        /**
         * The first window after connect. The one time a window caught the tire pressures at the
         * start it was 0.5 min in (2026-10-04); while driving they come about once a minute.
         */
        val startupListenMs: Long = 10_000L,
        /** Car tab open and parked: one long window after every HS poll cycle. */
        val focusParkedListenMs: Long = 10_000L,
        /**
         * Car tab open while moving: shorter windows, so a sample still lands every ~6 s and the
         * trip's distance and energy stay continuous (the live trip drops steps over 10 s).
         */
        val focusMovingListenMs: Long = 4_000L,
        /** The longest one focus request holds; the Car tab renews it while it stays open. */
        val focusMaxLeaseMs: Long = 60_000L,
        /**
         * The quiet capture's cap. On 2026-10-06 the modules sent their doors, windows and tyres in
         * a burst as the car shut down; the capture ends sooner at a chunk that hears nothing.
         */
        val quietCaptureMaxMs: Long = 60_000L,
        /**
         * Silence older than this is a car already asleep, not one turning off: no quiet capture,
         * and a power mode heard longer ago than this no longer explains a quiet HS bus.
         */
        val quietCaptureWithinMs: Long = 60_000L,
        /**
         * Also log every regular window's raw monitor text, the allowlisted body broadcasts' payload
         * bytes included ([SwcanPrivacy.PAYLOAD_PIDS]), the way a body test does. Debug builds only:
         * it is how frames heard on a drive (tires, doors) get decoded.
         */
        val logRawWindows: Boolean = false,
    )

    private enum class Identity { UNKNOWN, STN, NOT_STN }

    /** Why a window runs, which sets how long it listens. Logged on every `swcan_window`. */
    private enum class WindowMode { STARTUP, FOCUS, PARKED, TIRE_HUNT, REGULAR, QUIET }

    /**
     * One window's monitor output: every chunk's frames, whether the last one stopped cleanly, and
     * whether any chunk was cut short (the reply hit its cap, or the adapter stopped monitoring by
     * itself), which leaves a gap in what was heard.
     */
    private class Heard(
        val chunks: List<List<SwcanFrame>>,
        val gotPrompt: Boolean,
        val partial: Boolean,
    ) {
        val frames: List<SwcanFrame> get() = chunks.flatten()

        /** The last chunk heard nothing: the bus had gone quiet. */
        val endedSilent: Boolean get() = chunks.lastOrNull().isNullOrEmpty()
    }

    val readings = SwcanReadings()
    private var identity = Identity.UNKNOWN
    private var disabledReason: String? = null
    private var nextWindowAtMs = Long.MAX_VALUE
    private var consecutiveEmpty = 0
    private var windowCount = 0
    private var okWindows = 0
    private var troubledWindows = 0
    private var healthCheckPending = false
    private var liveCyclesAtWindowEnd = 0L
    private var tiresHeard = false
    private var tireHuntWindows = 0

    /** The car's last SW-CAN power mode (`off`, `accessory`, `run`, `crank`) and when it was heard. */
    private var powerMode: String? = null
    private var powerModeAtMs = 0L

    /** This stretch of HS silence has had its quiet capture; cleared once HS answers again. */
    private var quietCaptured = false

    /**
     * HS went quiet after a window without a power mode to explain it. Not counted as trouble yet:
     * the quiet capture that follows says whether the car was simply switched off, and HS answering
     * again (past [quietAtLiveCycles]) says the window's restore really did leave it broken.
     */
    private var unexplainedQuiet = false
    private var quietAtLiveCycles = 0L

    /** A requested body-test length, set from the service thread and taken by the poll loop. */
    @Volatile private var bodyTestRequestMs = 0L

    /** Until when the Car tab wants near-continuous listening; set from the service thread. */
    @Volatile private var focusUntilMs = 0L

    /** The guided car test. It outlives [resetSession], so a reconnect picks it up at the same step. */
    val guidedTest = GuidedCarTest(io, GuidedBus(), clock = guidedClock)

    /** The guided test's bus switch: the same checks, setup and restore as a window. */
    private inner class GuidedBus : GuidedCarTest.Bus {
        override fun canListen(): Boolean? = if (identity == Identity.UNKNOWN) null else identity == Identity.STN

        override fun enter(): String? =
            if (currentProtocol().removePrefix("A") !=
                HS_PROTOCOL
            ) {
                "ATDPN"
            } else {
                SETUP_COMMANDS.firstOrNull { !sendOk(it) }
            }

        override fun leave(): Boolean = restoreHs()

        override fun record(decoded: List<SwcanReading>) = this@SwcanListenRunner.record(decoded)
    }

    /** A guided test is running or about to: the session must not end for a quiet car meanwhile. */
    fun holdsSession(): Boolean = guidedTest.isActive()

    /**
     * The guided test heard the car switched off after its drive and has finished its capture: end
     * the session now, as for a car gone to sleep. True once.
     */
    fun endsSession(): Boolean = guidedTest.takeSessionEnd()

    fun resetSession() {
        readings.clear()
        identity = Identity.UNKNOWN
        disabledReason = null
        nextWindowAtMs = Long.MAX_VALUE
        consecutiveEmpty = 0
        windowCount = 0
        okWindows = 0
        troubledWindows = 0
        healthCheckPending = false
        bodyTestRequestMs = 0L
        tiresHeard = false
        tireHuntWindows = 0
        powerMode = null
        powerModeAtMs = 0L
        quietCaptured = false
        unexplainedQuiet = false
        quietAtLiveCycles = 0L
    }

    /**
     * Asks for one continuous listen of [durationMs] (capped at [Policy.bodyTestMaxMs]) at the next
     * sample, logging every frame with its payload. Live HS data pauses meanwhile. It is how a
     * decoder gets verified on the car: open a door, lock, move a window while it runs.
     */
    fun requestBodyTest(durationMs: Long) {
        bodyTestRequestMs = durationMs.coerceIn(policy.bodyTestChunkMs, policy.bodyTestMaxMs)
    }

    /**
     * Asks for windows back to back for the next [durationMs] (capped at [Policy.focusMaxLeaseMs]);
     * 0 or less ends it now. The Car tab renews it while on screen, so a closed or killed screen
     * lets it lapse on its own. Live HS data then updates once a window instead of every second.
     */
    fun requestBodyFocus(durationMs: Long) {
        focusUntilMs = if (durationMs > 0L) clock() + minOf(durationMs, policy.focusMaxLeaseMs) else 0L
    }

    private fun isFocused(): Boolean = clock() < focusUntilMs

    fun isEnabled(): Boolean = identity == Identity.STN && disabledReason == null

    fun disabledReason(): String? = disabledReason

    /**
     * What car controls may assume about this adapter: only an STN adapter that has actually heard
     * this car's SW-CAN traffic, and has not failed to switch buses cleanly, is [CarControlGate.Adapter.READY].
     * A listener that went quiet because the car stopped broadcasting ("no_frames") keeps READY. One
     * troubled window is enough to lose it, even though listening carries on, and so is a quiet HS
     * bus after a window until it is explained.
     */
    fun controlCapability(): CarControlGate.Adapter =
        when {
            identity == Identity.UNKNOWN -> CarControlGate.Adapter.UNKNOWN
            identity == Identity.NOT_STN -> CarControlGate.Adapter.NOT_STN
            okWindows > 0 &&
                troubledWindows == 0 &&
                !unexplainedQuiet &&
                (disabledReason == null || disabledReason == "no_frames") -> CarControlGate.Adapter.READY
            else -> CarControlGate.Adapter.STN_UNVERIFIED
        }

    /**
     * Asks the adapter who it is (`STI`, adapter-local, nothing reaches the car). Runs once per
     * connection with the other deferred init probes; a plain ELM327 answers `?` and is skipped.
     */
    fun probeAdapter() {
        if (identity != Identity.UNKNOWN) return
        val response =
            try {
                io.send("STI", PROBE_TIMEOUT_MS)
            } catch (ex: IOException) {
                io.logEvent("swcan_adapter_probe_failed", "error", ex.message ?: ex.javaClass.simpleName)
                return
            }
        val summary = ObdProtocol.summarize(response)
        identity = if (summary.uppercase(Locale.US).contains("STN")) Identity.STN else Identity.NOT_STN
        nextWindowAtMs = clock() + policy.firstWindowDelayMs
        io.logEvent("swcan_adapter_probe", "stn", (identity == Identity.STN).toString(), "identity", summary.take(64))
    }

    /** Adds the held SW-CAN values to a live sample. */
    @Throws(JSONException::class)
    fun appendTo(
        sample: JSONObject,
        now: Long,
    ) {
        readings.appendTo(sample, now)
    }

    /**
     * Called by the poll loop after each broadcast sample. Verifies the previous window left HS
     * polling working, then runs a window if one is due.
     */
    @Throws(IOException::class)
    fun afterSample() {
        if (healthCheckPending) {
            healthCheckPending = false
            if (io.liveCycleCount() == liveCyclesAtWindowEnd) {
                onHsQuietAfterWindow()
                return
            }
        }
        if (unexplainedQuiet && io.liveCycleCount() > quietAtLiveCycles) {
            // HS came back after the re-init, so the car was on: the window's restore had broken it.
            unexplainedQuiet = false
            countTrouble("hs_not_restored")
        }
        if (guidedTest.runNext()) return
        val bodyTestMs = bodyTestRequestMs
        if (bodyTestMs > 0L) {
            bodyTestRequestMs = 0L
            if (isEnabled()) {
                runBodyTest(bodyTestMs)
            } else {
                io.logEvent("body_test_unavailable", "reason", disabledReason ?: identity.name.lowercase(Locale.US))
            }
            return
        }
        if (!isEnabled()) return
        if (io.msSinceLiveData() > policy.maxLiveDataAgeMs) {
            if (quietCaptureDue()) runQuietCapture()
            return
        }
        quietCaptured = false
        // Focus runs a window after every poll cycle, but only once the startup window has run.
        val focusNext = isFocused() && windowCount > 0
        if (!focusNext && clock() < nextWindowAtMs) return
        runWindow()
    }

    @Throws(IOException::class)
    private fun runWindow() {
        io.exclusive {
            val startedAt = clock()
            val mode = windowMode()
            windowCount += 1
            if (mode == WindowMode.TIRE_HUNT) tireHuntWindows += 1
            val protocol = currentProtocol()
            if (protocol.removePrefix("A") != HS_PROTOCOL) {
                disable("hs_protocol_$protocol")
                logWindow("skipped", startedAt, 0, 0, emptySet(), mode = mode)
                return@exclusive
            }
            val failedSetup = SETUP_COMMANDS.firstOrNull { !sendOk(it) }
            val heard = if (failedSetup == null) listen(listenMs(mode)) else null
            val restored = restoreHs()
            if (heard != null) logRawWindow(mode, heard)
            val frames = heard?.frames.orEmpty()
            val decoded = SwcanFrameDecoder.decodeAll(frames)
            val ids = frames.filter { it.extended }.map { it.id }.toSortedSet()
            val outcome = outcomeOf(restored, failedSetup, heard)
            logWindow(outcome, startedAt, frames.size, decoded.size, ids, failedSetup, mode)
            scheduleAfter(outcome)
            if (!restored) {
                io.logEvent("swcan_hs_reinit", "reason", "restore_failed")
                io.reinitialize()
            } else {
                healthCheckPending = true
                liveCyclesAtWindowEnd = io.liveCycleCount()
            }
        }
    }

    private fun outcomeOf(
        restored: Boolean,
        failedSetup: String?,
        heard: Heard?,
    ): String =
        when {
            !restored -> "restore_failed"
            failedSetup != null -> "setup_failed"
            heard?.gotPrompt != true -> "no_stop_prompt"
            heard.frames.isEmpty() -> "empty"
            heard.partial -> "partial"
            else -> "ok"
        }

    /**
     * Monitors for [totalMs] in equal chunks no longer than [Policy.bodyTestChunkMs], stopping
     * early if a chunk's stop prompt never comes (the adapter is then not ready for another).
     */
    @Throws(IOException::class)
    private fun listen(totalMs: Long): Heard {
        val chunks = ((totalMs + policy.bodyTestChunkMs - 1) / policy.bodyTestChunkMs).coerceAtLeast(1L)
        return listenInChunks(chunks.toInt(), totalMs / chunks) { true }
    }

    /**
     * Up to [maxChunks] monitor chunks of [chunkMs], recording what each one decodes as it comes in.
     * Stops at a chunk with no stop prompt, or when [goOn] (asked after each chunk with that chunk's
     * frames) says so.
     */
    @Throws(IOException::class)
    private fun listenInChunks(
        maxChunks: Int,
        chunkMs: Long,
        goOn: (List<SwcanFrame>) -> Boolean,
    ): Heard {
        val chunks = mutableListOf<List<SwcanFrame>>()
        var gotPrompt = true
        var partial = false
        while (chunks.size < maxChunks) {
            val result = io.monitor(MONITOR_COMMAND, chunkMs, policy.stopTimeoutMs)
            val frames = SwcanFrameDecoder.parseMonitorOutput(result.text)
            chunks += frames
            record(SwcanFrameDecoder.decodeAll(frames))
            gotPrompt = result.gotPrompt
            partial = partial || result.capped || result.endedEarly
            if (!gotPrompt || !goOn(frames)) break
        }
        return Heard(chunks, gotPrompt, partial)
    }

    private fun record(decoded: List<SwcanReading>) {
        val now = clock()
        readings.record(decoded, now)
        noteTires(decoded)
        decoded.lastOrNull { it.field == SwcanField.POWER_MODE }?.let {
            powerMode = it.value as? String
            powerModeAtMs = now
        }
    }

    private fun windowMode(): WindowMode =
        when {
            windowCount == 0 -> WindowMode.STARTUP
            isFocused() -> WindowMode.FOCUS
            io.isStationary() -> WindowMode.PARKED
            isTireHunting() -> WindowMode.TIRE_HUNT
            else -> WindowMode.REGULAR
        }

    private fun listenMs(mode: WindowMode): Long =
        when (mode) {
            WindowMode.STARTUP -> policy.startupListenMs
            WindowMode.FOCUS -> if (io.isStationary()) policy.focusParkedListenMs else policy.focusMovingListenMs
            WindowMode.PARKED -> policy.parkedListenMs
            WindowMode.TIRE_HUNT -> policy.tireHuntListenMs
            WindowMode.REGULAR -> policy.listenMs
            WindowMode.QUIET -> policy.quietCaptureMaxMs
        }

    private fun intervalMs(): Long =
        when {
            io.isStationary() -> policy.parkedIntervalMs
            isTireHunting() -> policy.tireHuntIntervalMs
            else -> policy.intervalMs
        }

    /** Moving, not all four tyres heard yet this session, and hunt windows left. */
    private fun isTireHunting(): Boolean =
        !tiresHeard && !io.isStationary() && tireHuntWindows < policy.tireHuntMaxWindows

    /**
     * Debug builds log a window's frames, only the allowlisted ones ([SwcanPrivacy]). Focus windows
     * run back to back for as long as the Car tab is open, so theirs keep only the body frames
     * ([BODY_LOG_PIDS]): the ones a car test of doors, windows and locks needs, at a few hundred
     * bytes a window instead of ~60 KB.
     */
    private fun logRawWindow(
        mode: WindowMode,
        heard: Heard,
    ) {
        if (!policy.logRawWindows) return
        val label =
            when (mode) {
                WindowMode.FOCUS -> "focus"
                WindowMode.QUIET -> "quiet"
                else -> "window"
            }
        heard.chunks.forEachIndexed { index, chunk ->
            val kept = if (mode == WindowMode.FOCUS) chunk.filter { it.gmlanPid in BODY_LOG_PIDS } else chunk
            val text = SwcanPrivacy.loggable(kept)
            if (text.isNotEmpty()) logRaw(label, windowCount, text, index + 1)
        }
    }

    /** The hunt only ends once all four tyres have a valid pressure, not at the first frame. */
    private fun noteTires(decoded: List<SwcanReading>) {
        if (tiresHeard || decoded.none { it.field.group == SwcanGroup.TIRES } || !readings.hasAllTires()) return
        tiresHeard = true
        io.logEvent("swcan_tires_heard", "huntWindows", tireHuntWindows.toString())
    }

    @Throws(IOException::class)
    private fun runBodyTest(durationMs: Long) {
        io.exclusive {
            val startedAt = clock()
            io.logEvent("body_test_start", "durationMs", durationMs.toString())
            val failedSetup = SETUP_COMMANDS.firstOrNull { !sendOk(it) }
            var frameCount = 0
            var decodedCount = 0
            var chunk = 0
            val ids = sortedSetOf<Int>()
            // Bounded by chunk count too, so a clock that stalls can't hold the adapter forever.
            val maxChunks = (durationMs / policy.bodyTestChunkMs).toInt() + 1
            while (failedSetup == null && chunk < maxChunks && clock() - startedAt < durationMs) {
                chunk += 1
                val result = io.monitor(MONITOR_COMMAND, policy.bodyTestChunkMs, policy.stopTimeoutMs)
                val frames = SwcanFrameDecoder.parseMonitorOutput(result.text)
                val decoded = SwcanFrameDecoder.decodeAll(frames)
                record(decoded)
                frameCount += frames.size
                decodedCount += decoded.size
                frames.filter { it.extended }.mapTo(ids) { it.id }
                logRaw("body_test", windowCount, SwcanPrivacy.loggable(frames), chunk)
                if (!result.gotPrompt) break
            }
            val restored = restoreHs()
            io.logEvent(
                "body_test_done",
                "outcome",
                when {
                    failedSetup != null -> "setup_failed"
                    !restored -> "restore_failed"
                    else -> "ok"
                },
                "durationMs",
                (clock() - startedAt).toString(),
                "frames",
                frameCount.toString(),
                "decoded",
                decodedCount.toString(),
                "distinctIds",
                ids.size.toString(),
                "failedCommand",
                failedSetup ?: "",
            )
            nextWindowAtMs = clock() + intervalMs()
            if (!restored) {
                disable("restore_failed")
                io.logEvent("swcan_hs_reinit", "reason", "restore_failed")
                io.reinitialize()
            } else {
                healthCheckPending = true
                liveCyclesAtWindowEnd = io.liveCycleCount()
            }
        }
    }

    private fun scheduleAfter(outcome: String) {
        nextWindowAtMs = clock() + intervalMs()
        when (outcome) {
            // A partial window still heard the car; the gap is in its log.
            "ok", "partial" -> {
                consecutiveEmpty = 0
                okWindows += 1
            }
            "empty" -> {
                consecutiveEmpty += 1
                if (consecutiveEmpty >= policy.maxConsecutiveEmpty) disable("no_frames")
            }
            "no_stop_prompt", "restore_failed" -> countTrouble(outcome)
            else -> disable(outcome)
        }
    }

    private fun countTrouble(reason: String) {
        troubledWindows += 1
        if (troubledWindows >= policy.maxTroubledWindows) disable(reason)
    }

    /**
     * HS polling got nothing in the cycle after a window. If the car's power mode last said off or
     * accessory it was simply switched off. Otherwise the restore may have left HS broken, so the
     * adapter is re-initialised, but the window is not counted as trouble yet: a car switched off
     * just before its power mode was heard looks the same. The quiet capture that follows decides
     * (car off: nothing counted; car on: counted), or HS answering again does.
     */
    private fun onHsQuietAfterWindow() {
        if (carSwitchedOff()) {
            io.logEvent("swcan_hs_quiet", "reason", "car_off", "powerMode", powerMode.orEmpty())
            return
        }
        unexplainedQuiet = true
        io.logEvent("swcan_hs_reinit", "reason", "no_live_data_after_window")
        io.reinitialize()
        quietAtLiveCycles = io.liveCycleCount()
    }

    private fun carSwitchedOff(): Boolean =
        powerMode in OFF_POWER_MODES && clock() - powerModeAtMs <= policy.quietCaptureWithinMs

    /** HS has gone quiet since a window ran, recently enough that the car may be turning off. */
    private fun quietCaptureDue(): Boolean =
        !quietCaptured && windowCount > 0 && io.msSinceLiveData() <= policy.quietCaptureWithinMs

    /**
     * One bounded listen once the HS bus goes quiet, for the door, window and tyre states the body
     * modules send as the car shuts down. Listens in [Policy.bodyTestChunkMs] chunks up to
     * [Policy.quietCaptureMaxMs], and stops early at a chunk that hears nothing (the bus asleep) or,
     * after [QUIET_PROBE_CHUNKS] chunks, unless the power mode says the car is off: a quiet HS bus
     * with the car still on is not a shutdown, so HS gets the adapter straight back. It never
     * counts toward giving up for an empty bus, and HS is not health-checked afterwards (the car is
     * quiet, so there is nothing to check against).
     */
    @Throws(IOException::class)
    private fun runQuietCapture() {
        quietCaptured = true
        io.exclusive {
            val startedAt = clock()
            windowCount += 1
            val failedSetup = SETUP_COMMANDS.firstOrNull { !sendOk(it) }
            val maxChunks = (policy.quietCaptureMaxMs / policy.bodyTestChunkMs).toInt().coerceAtLeast(1)
            var chunks = 0
            val heard =
                if (failedSetup != null) {
                    null
                } else {
                    listenInChunks(maxChunks, policy.bodyTestChunkMs) { chunk ->
                        chunks += 1
                        chunk.isNotEmpty() && (chunks < QUIET_PROBE_CHUNKS || carSwitchedOff())
                    }
                }
            val restored = restoreHs()
            if (heard != null) logRawWindow(WindowMode.QUIET, heard)
            val frames = heard?.frames.orEmpty()
            val decodedCount = SwcanFrameDecoder.decodeAll(frames).size
            val ids = frames.filter { it.extended }.map { it.id }.toSortedSet()
            val outcome = outcomeOf(restored, failedSetup, heard)
            logWindow(outcome, startedAt, frames.size, decodedCount, ids, failedSetup, WindowMode.QUIET)
            val carOff = carSwitchedOff() || heard?.endedSilent == true
            io.logEvent(
                "swcan_hs_quiet",
                "reason",
                if (carOff) "car_off" else "car_on",
                "powerMode",
                powerMode.orEmpty(),
            )
            if (unexplainedQuiet && !carOff) countTrouble("hs_not_restored")
            unexplainedQuiet = false
            nextWindowAtMs = clock() + intervalMs()
            if (!restored) {
                countTrouble("restore_failed")
                io.logEvent("swcan_hs_reinit", "reason", "restore_failed")
                io.reinitialize()
            } else if (failedSetup != null || heard?.gotPrompt != true) {
                countTrouble(outcome)
            }
        }
    }

    /**
     * Puts the adapter back to the HS state [ObdPollingEngine.initializeElm327] leaves it in:
     * headers/spaces off, CAN formatting on, ISO 15765 11-bit 500k, automatic receive filtering,
     * broadcast header. Every step runs even if an earlier one fails, and a failed pass is run once
     * more: after a slow stop the first commands are answered with the monitor's leftover frames,
     * and by the time they have been the adapter is back at its prompt.
     */
    private fun restoreHs(): Boolean {
        if (restorePass()) return true
        io.logEvent("swcan_restore_retry", "window", windowCount.toString())
        return restorePass()
    }

    private fun restorePass(): Boolean {
        var ok = true
        for (command in RESTORE_COMMANDS) {
            ok = sendOk(command) && ok
        }
        return ok && currentProtocol().removePrefix("A") == HS_PROTOCOL
    }

    /** `ATDPN` answer, e.g. `6` or `A6` (auto-detected 6); the last token survives a stray echo. */
    private fun currentProtocol(): String =
        ObdProtocol
            .summarize(io.send("ATDPN", COMMAND_TIMEOUT_MS))
            .uppercase(Locale.US)
            .split(Regex("\\s+"))
            .last()

    private fun sendOk(command: String): Boolean =
        io.send(command, COMMAND_TIMEOUT_MS).uppercase(Locale.US).contains("OK")

    private fun disable(reason: String) {
        if (disabledReason != null) return
        disabledReason = reason
        io.logEvent("swcan_disabled", "reason", reason, "windows", windowCount.toString())
    }

    private fun logRaw(
        mode: String,
        window: Int,
        text: String,
        chunk: Int = window,
    ) {
        io.logEvent(
            "swcan_raw",
            "mode",
            mode,
            "window",
            window.toString(),
            "chunk",
            chunk.toString(),
            "text",
            text.take(MAX_RAW_CHARS),
        )
    }

    private fun logWindow(
        outcome: String,
        startedAt: Long,
        frameCount: Int,
        decodedCount: Int,
        ids: Set<Int>,
        failedCommand: String? = null,
        mode: WindowMode,
    ) {
        io.logEvent(
            "swcan_window",
            "outcome",
            outcome,
            "durationMs",
            maxOf(0L, clock() - startedAt).toString(),
            "frames",
            frameCount.toString(),
            "decoded",
            decodedCount.toString(),
            "distinctIds",
            ids.size.toString(),
            // Arbitration ids only (no payload bytes): the raw material for decoding more frames later.
            "ids",
            ids.take(MAX_LOGGED_IDS).joinToString(" ") { "%08X".format(Locale.US, it) },
            "failedCommand",
            failedCommand ?: "",
            "window",
            windowCount.toString(),
            "tireHunt",
            (mode == WindowMode.TIRE_HUNT).toString(),
            "mode",
            mode.name.lowercase(Locale.US),
        )
    }

    companion object {
        private const val PROBE_TIMEOUT_MS = 1_200L

        /** The car sends its power mode about every 5 s, so two 5 s chunks hear it. */
        private const val QUIET_PROBE_CHUNKS = 2

        private val OFF_POWER_MODES = setOf("off", "accessory")
        private const val COMMAND_TIMEOUT_MS = 1_000L
        private const val MAX_LOGGED_IDS = 48

        /** One window or body-test chunk's raw text in the session log (~5 s of frames is ~30 KB). */
        private const val MAX_RAW_CHARS = 48_000

        /** ISO 15765-4 CAN, 11-bit, 500 kbit/s — the Volt's HS diagnostic bus. */
        private const val HS_PROTOCOL = "6"

        const val MONITOR_COMMAND = "STM"

        /**
         * Body broadcasts a focus window logs raw: windows, door lock, the four doors, hood, hatch,
         * tires, washer fluid, bulbs and the window-normalized flags. Never the sensitive ones.
         */
        private val BODY_LOG_PIDS =
            setOf(
                0x325,
                0x20A,
                0x318,
                0x17B,
                0x17C,
                0x17D,
                0x355,
                0x394,
                0x1EA,
                0x1DE,
                0x319,
                0x323,
                // Not decoded yet, for the next car test: seat heat (front, rear), fuel door and
                // refuel state, charge-port door, charge cord, power mode, hatch release.
                0x391,
                0x392,
                0x393,
                0x3B4,
                0x3B6,
                0x3B8,
                0x3B2,
                0x112,
                0x176,
                0x121,
                0x35A,
            )

        @JvmField
        val SETUP_COMMANDS =
            listOf(
                "STCMM 0", // receive only: never ACK a frame
                "STP 61", // SW-CAN, ISO 11898 raw frames (no ISO-TP flow control), 33.3 kbit/s
                "STFPC", // drop the automatic HS receive filter
                "STFPA 00,00", // pass every frame; decoding filters in software
                "ATCAF0",
                "ATH1",
                "ATS1",
            )

        @JvmField
        val RESTORE_COMMANDS =
            listOf(
                "ATH0",
                "ATS0",
                "ATCAF1",
                "ATSP6",
                "STFAC",
                "STFA", // automatic filtering back on; regenerated from the header below
                "ATSH7DF",
                "ATAR", // drop any leftover ATCRA receive filter from a motor-node read
            )
    }
}
