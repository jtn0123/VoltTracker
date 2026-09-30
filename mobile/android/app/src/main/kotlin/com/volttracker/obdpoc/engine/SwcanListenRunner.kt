package com.volttracker.obdpoc.engine

import com.volttracker.obdpoc.CarControlGate
import com.volttracker.obdpoc.ObdProtocol
import com.volttracker.obdpoc.SwcanFrameDecoder
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
 * READ ONLY, by construction. Every command sent here is adapter configuration or monitoring:
 * - `STCMM 0` — monitor without acknowledging frames (the adapter stays electrically silent);
 * - `STP 61` — ISO 11898 raw CAN on the SW-CAN transceiver, so no ISO-TP flow-control frame is
 *   ever generated in response to a first frame;
 * - `STFPC` / `STFPA` — receive filters; `ATH1`/`ATS1`/`ATCAF0` — output formatting;
 * - `STM` — monitor; stopped by a single byte the adapter swallows.
 * No request, wake-up (`STCSWM 2`), periodic message (`STPPMA`) or raw transmit (`STPX`) is ever
 * issued (SwcanListenRunnerTest pins the full command set).
 *
 * Only runs on an STN-based adapter (answers `STI` with `STN…`) whose HS protocol is ISO 15765
 * 11-bit/500k (`ATDPN` 6). Any setup or restore failure, or [Policy.maxConsecutiveEmpty] windows
 * that hear nothing, disables it for the rest of the session. A restore failure also forces a full
 * adapter re-init so HS polling is never left half-configured.
 */
class SwcanListenRunner(
    private val io: Io,
    private val policy: Policy = Policy(),
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

        /** Full adapter reset + HS init (ATZ …), used when HS state cannot be trusted. */
        @Throws(IOException::class)
        fun reinitialize()

        /** Count of poll cycles that returned live data; advances whenever HS-CAN answers. */
        fun liveCycleCount(): Long

        fun msSinceLiveData(): Long

        /** Runs [block] holding the adapter IO lock, so no other runner interleaves commands. */
        fun <T> exclusive(block: () -> T): T

        /**
         * True while the car is standing still. Parked, a longer and more frequent window costs
         * nothing the driver sees and catches the event-only body frames (locks, doors, windows).
         */
        fun isStationary(): Boolean = false

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
        val stopTimeoutMs: Long = 1_500L,
        /** Only listen while HS-CAN answered within this long — a sleeping car has no SW-CAN either. */
        val maxLiveDataAgeMs: Long = 5_000L,
        val maxConsecutiveEmpty: Int = 3,
        /** Stationary cadence: on the car a 1.2 s window every 45 s heard ~3% of the time. */
        val parkedIntervalMs: Long = 15_000L,
        val parkedListenMs: Long = 3_000L,
        /** A body test listens in chunks this long so no single monitor reply nears the 64 KB cap. */
        val bodyTestChunkMs: Long = 5_000L,
        val bodyTestMaxMs: Long = 90_000L,
    )

    private enum class Identity { UNKNOWN, STN, NOT_STN }

    val readings = SwcanReadings()
    private var identity = Identity.UNKNOWN
    private var disabledReason: String? = null
    private var nextWindowAtMs = Long.MAX_VALUE
    private var consecutiveEmpty = 0
    private var windowCount = 0
    private var okWindows = 0
    private var healthCheckPending = false
    private var liveCyclesAtWindowEnd = 0L

    /** A requested body-test length, set from the service thread and taken by the poll loop. */
    @Volatile private var bodyTestRequestMs = 0L

    fun resetSession() {
        readings.clear()
        identity = Identity.UNKNOWN
        disabledReason = null
        nextWindowAtMs = Long.MAX_VALUE
        consecutiveEmpty = 0
        windowCount = 0
        okWindows = 0
        healthCheckPending = false
        bodyTestRequestMs = 0L
    }

    /**
     * Asks for one continuous listen of [durationMs] (capped at [Policy.bodyTestMaxMs]) at the next
     * sample, logging every frame with its payload. Live HS data pauses meanwhile. It is how a
     * decoder gets verified on the car: open a door, lock, move a window while it runs.
     */
    fun requestBodyTest(durationMs: Long) {
        bodyTestRequestMs = durationMs.coerceIn(policy.bodyTestChunkMs, policy.bodyTestMaxMs)
    }

    fun isEnabled(): Boolean = identity == Identity.STN && disabledReason == null

    fun disabledReason(): String? = disabledReason

    /**
     * What car controls may assume about this adapter: only an STN adapter that has actually heard
     * this car's SW-CAN traffic, and has not failed to switch buses cleanly, is [CarControlGate.Adapter.READY].
     * A listener that went quiet because the car stopped broadcasting ("no_frames") keeps READY.
     */
    fun controlCapability(): CarControlGate.Adapter =
        when {
            identity == Identity.UNKNOWN -> CarControlGate.Adapter.UNKNOWN
            identity == Identity.NOT_STN -> CarControlGate.Adapter.NOT_STN
            okWindows > 0 && (disabledReason == null || disabledReason == "no_frames") -> CarControlGate.Adapter.READY
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
                disable("hs_not_restored")
                io.logEvent("swcan_hs_reinit", "reason", "no_live_data_after_window")
                io.reinitialize()
                return
            }
        }
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
        if (!isEnabled() || clock() < nextWindowAtMs || io.msSinceLiveData() > policy.maxLiveDataAgeMs) return
        runWindow()
    }

    @Throws(IOException::class)
    private fun runWindow() {
        io.exclusive {
            val startedAt = clock()
            windowCount += 1
            val protocol = currentProtocol()
            if (protocol.removePrefix("A") != HS_PROTOCOL) {
                disable("hs_protocol_$protocol")
                logWindow("skipped", startedAt, 0, 0, emptySet())
                return@exclusive
            }
            val failedSetup = SETUP_COMMANDS.firstOrNull { !sendOk(it) }
            val result =
                if (failedSetup == null) {
                    io.monitor(MONITOR_COMMAND, listenMs(), policy.stopTimeoutMs)
                } else {
                    null
                }
            val restored = restoreHs()
            val frames = SwcanFrameDecoder.parseMonitorOutput(result?.text)
            val decoded = SwcanFrameDecoder.decodeAll(frames)
            readings.record(decoded, clock())
            val ids = frames.filter { it.extended }.map { it.id }.toSortedSet()
            val outcome =
                when {
                    !restored -> "restore_failed"
                    failedSetup != null -> "setup_failed"
                    result?.gotPrompt != true -> "no_stop_prompt"
                    frames.isEmpty() -> "empty"
                    else -> "ok"
                }
            logWindow(outcome, startedAt, frames.size, decoded.size, ids, failedSetup)
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

    private fun listenMs(): Long = if (io.isStationary()) policy.parkedListenMs else policy.listenMs

    private fun intervalMs(): Long = if (io.isStationary()) policy.parkedIntervalMs else policy.intervalMs

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
                readings.record(decoded, clock())
                frameCount += frames.size
                decodedCount += decoded.size
                frames.filter { it.extended }.mapTo(ids) { it.id }
                io.logEvent(
                    "swcan_raw",
                    "mode",
                    "body_test",
                    "chunk",
                    chunk.toString(),
                    "text",
                    result.text.take(MAX_RAW_CHARS),
                )
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
            "ok" -> {
                consecutiveEmpty = 0
                okWindows += 1
            }
            "empty" -> {
                consecutiveEmpty += 1
                if (consecutiveEmpty >= policy.maxConsecutiveEmpty) disable("no_frames")
            }
            else -> disable(outcome)
        }
    }

    /**
     * Puts the adapter back to the HS state [ObdPollingEngine.initializeElm327] leaves it in:
     * headers/spaces off, CAN formatting on, ISO 15765 11-bit 500k, automatic receive filtering,
     * broadcast header. Every step runs even if an earlier one fails.
     */
    private fun restoreHs(): Boolean {
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

    private fun logWindow(
        outcome: String,
        startedAt: Long,
        frameCount: Int,
        decodedCount: Int,
        ids: Set<Int>,
        failedCommand: String? = null,
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
        )
    }

    companion object {
        private const val PROBE_TIMEOUT_MS = 1_200L
        private const val COMMAND_TIMEOUT_MS = 1_000L
        private const val MAX_LOGGED_IDS = 48

        /** One body-test chunk's raw text in the session log (~5 s of frames is ~30 KB). */
        private const val MAX_RAW_CHARS = 48_000

        /** ISO 15765-4 CAN, 11-bit, 500 kbit/s — the Volt's HS diagnostic bus. */
        private const val HS_PROTOCOL = "6"

        const val MONITOR_COMMAND = "STM"

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
