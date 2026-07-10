package com.volttracker.obdpoc

import androidx.annotation.VisibleForTesting
import com.volttracker.obdpoc.PidSchedule.Header
import com.volttracker.obdpoc.PidSchedule.PidSpec
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Collections
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap

/** Owns live-poll PID scheduling, batching, carry-forward raw values, and stale timers. */
class PidPollingState(
    private val service: EngineHost,
    private val engine: ObdPollingEngine,
) {
    private var cycleNum = 0
    private var mode01BatchSupported = false

    // Consecutive cycles where the mode-01 batch returned an incomplete frame. A single transient
    // miss (a dropped/garbled BT frame) shouldn't cost the rest of the drive its multi-PID batching,
    // so we only fall back to single-PID reads permanently after [MAX_CONSECUTIVE_BATCH_MISSES] in a
    // row. Reset on any complete batch and by reset().
    private var consecutiveBatchMisses = 0

    // These carry-forward maps are read-and-mutated (including remove-on-age-out in lastRaw) on the
    // single poll thread today. They are ConcurrentHashMaps so a future "current value" query from
    // the broadcast/main thread can't structurally corrupt them under concurrent modification —
    // cheap insurance for an invariant that is otherwise only enforced by call-site discipline.
    private val lastRawByCommand = ConcurrentHashMap<String, String>()
    private val lastPolledAtMsByCommand = ConcurrentHashMap<String, Long>()
    private val lastRawSetAtMsByCommand = ConcurrentHashMap<String, Long>()

    // Negative-PID cache: PIDs that answer "NO DATA" on this car for [MAX_CONSECUTIVE_NO_DATA]
    // consecutive polls *while the bus is otherwise alive* are dropped from the schedule for the rest
    // of the session, so we stop paying their (often full-timeout) round-trip every rotation. Cleared
    // by reset(), so each new session re-probes every PID from scratch.
    private val consecutiveNoDataByCommand = ConcurrentHashMap<String, Int>()

    // newSetFromMap(ConcurrentHashMap) rather than ConcurrentHashMap.newKeySet(): the latter needs
    // API 24 and minSdk is 23. Same concurrent-set semantics, available on every supported device.
    private val disabledCommands: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    // Wall-clock of the last cycle that returned at least one fresh PID value. The engine reads the
    // age of this (see [msSinceLastLiveData]) to notice a fully asleep bus and end the session
    // cleanly instead of polling a dead bus until Bluetooth eventually drops. @Volatile is cheap
    // insurance: it is written and read on the poll thread today, but the engine could move the
    // read off-thread without that being obviously unsafe.
    @Volatile private var lastLiveDataAtMs = 0L
    private var clock = Clock { System.currentTimeMillis() }

    /** Time source, overridable in tests. */
    fun interface Clock {
        fun nowMs(): Long
    }

    @VisibleForTesting
    fun setClockForTesting(clock: Clock) {
        this.clock = clock
    }

    fun reset() {
        cycleNum = 0
        mode01BatchSupported = false
        consecutiveBatchMisses = 0
        lastRawByCommand.clear()
        lastPolledAtMsByCommand.clear()
        lastRawSetAtMsByCommand.clear()
        consecutiveNoDataByCommand.clear()
        disabledCommands.clear()
        lastLiveDataAtMs = clock.nowMs()
    }

    fun isInitialCycle(): Boolean = cycleNum == 0

    fun dueForCurrentCycle(): List<PidSpec> {
        val due = if (isInitialCycle()) PidSchedule.firstSampleSpecs() else PidSchedule.dueOnCycle(cycleNum)
        // Drop PIDs the negative-PID cache has retired this session so we stop re-issuing dead reads.
        return if (disabledCommands.isEmpty()) due else due.filterNot { disabledCommands.contains(it.command) }
    }

    fun advanceCycle() {
        cycleNum += 1
    }

    fun setMode01BatchSupported(supported: Boolean) {
        mode01BatchSupported = supported
        if (supported) {
            // Re-enabling batching (the mode-01 batch probe fires on every (re)connect, not just via
            // reset()) must also clear any stale miss streak carried over from a prior connection —
            // otherwise a leftover count of MAX_CONSECUTIVE_BATCH_MISSES - 1 would disable batching on
            // the very first post-reconnect miss instead of tolerating one transient BT hiccup.
            consecutiveBatchMisses = 0
        }
    }

    fun lastRaw(command: String): String? {
        val setAtMs = lastRawSetAtMsByCommand[command]
        if (setAtMs != null && clock.nowMs() - setAtMs > carryForwardMaxAgeMsFor(command)) {
            lastRawByCommand.remove(command)
            lastRawSetAtMsByCommand.remove(command)
            lastPolledAtMsByCommand.remove(command)
            return null
        }
        return lastRawByCommand[command]
    }

    private fun putLastRaw(
        command: String,
        response: String,
        nowMs: Long,
    ) {
        lastRawByCommand[command] = response
        lastRawSetAtMsByCommand[command] = nowMs
    }

    @Throws(JSONException::class)
    fun putStaleMsIfTracked(
        sample: JSONObject,
        key: String,
        command: String,
        now: Long,
    ) {
        val staleMs = staleMsFor(command, now)
        if (staleMs != null) {
            sample.put(key, staleMs)
        }
    }

    fun staleMsFor(
        command: String,
        now: Long,
    ): Long? {
        val polledAtMs = lastPolledAtMsByCommand[command]
        return if (polledAtMs == null) null else maxOf(0L, now - polledAtMs)
    }

    /**
     * Issues every PID read due on this cycle, grouped by ATSH header so we don't pay an
     * unnecessary header switch for headers that have nothing due.
     */
    @Throws(IOException::class)
    fun runScheduledPolls(
        due: List<PidSpec>,
        rawThisCycle: StringBuilder,
    ) {
        if (due.isEmpty()) {
            return
        }
        // (command -> outcome) for every PID actually issued this cycle; feeds the negative-PID
        // cache once the whole cycle is known (so a single live read can shield the rest).
        val outcomes = ArrayList<Pair<String, PollOutcome>>()
        val batchedTier1 = tryBatchTier1Mode01(due, rawThisCycle)
        if (batchedTier1) {
            // A successful batch means every batched PID returned data.
            PidSchedule.MODE_01_BATCH_COMMANDS.forEach { outcomes.add(it to PollOutcome.LIVE) }
        }
        val specsByHeader = groupByHeader(due)
        var switched = false
        for (header in Header.entries) {
            val headerSpecs = specsByHeader[header] ?: continue
            if (header != Header.BROADCAST) {
                val headerCommand = header.atCommand
                if (headerCommand == null) {
                    service.recorder.logEvent(
                        "pid_header_skipped",
                        "header",
                        header.name,
                        "reason",
                        "missing_at_command",
                    )
                    continue
                }
                engine.sendCommand(headerCommand, 1500)
                switched = true
            }
            val extraBatch =
                if (mode01BatchSupported) {
                    batchableMode01(headerSpecs, batchedTier1, header)
                } else {
                    emptyList()
                }
            val batchedExtra = extraBatch.size >= 2 && tryBatchMode01Group(extraBatch, rawThisCycle)
            if (batchedExtra) {
                extraBatch.forEach { outcomes.add(it.command to PollOutcome.LIVE) }
            }
            for (spec in headerSpecs) {
                if (
                    batchedTier1 &&
                    header == Header.BROADCAST &&
                    PidSchedule.isMode01BatchCommand(spec.command)
                ) {
                    continue
                }
                if (batchedExtra && extraBatch.contains(spec)) {
                    continue
                }
                val response = engine.sendRecoverableCommand(spec.command, 1500)
                appendRawTo(rawThisCycle, spec.command, response)
                val now = clock.nowMs()
                lastPolledAtMsByCommand[spec.command] = now
                val outcome = classifyOutcome(response)
                if (outcome == PollOutcome.LIVE) {
                    putLastRaw(spec.command, response, now)
                }
                outcomes.add(spec.command to outcome)
            }
        }
        if (switched) {
            engine.sendCommand(PidSchedule.RESTORE_BROADCAST_HEADER_COMMAND, 1500)
        }
        applyNoDataCache(outcomes)
    }

    /**
     * Per-cycle update of the negative-PID cache and the live-data clock. A PID's NO-DATA only counts
     * toward disabling it when the bus is otherwise alive this cycle (some other PID answered) — a
     * fully silent cycle means the whole car is asleep, not that any single PID is unsupported, so no
     * PID is ever disabled during a bus-wide outage. [PidSchedule.isConditional] PIDs (charger etc.)
     * are exempt because they legitimately go NO-DATA outside their vehicle mode.
     */
    private fun applyNoDataCache(outcomes: List<Pair<String, PollOutcome>>) {
        if (outcomes.isEmpty()) {
            return
        }
        val anyLive = outcomes.any { it.second == PollOutcome.LIVE }
        if (anyLive) {
            lastLiveDataAtMs = clock.nowMs()
        }
        for ((command, outcome) in outcomes) {
            if (outcome == PollOutcome.LIVE) {
                consecutiveNoDataByCommand.remove(command)
                continue
            }
            if (outcome == PollOutcome.ERROR) {
                continue
            }
            if (!anyLive || PidSchedule.isConditional(command)) {
                continue
            }
            val streak = (consecutiveNoDataByCommand[command] ?: 0) + 1
            consecutiveNoDataByCommand[command] = streak
            if (streak >= MAX_CONSECUTIVE_NO_DATA && disabledCommands.add(command)) {
                service.recorder.logEvent(
                    "pid_disabled_no_data",
                    "command",
                    command,
                    "consecutiveNoData",
                    streak.toString(),
                )
            }
        }
    }

    private fun classifyOutcome(response: String): PollOutcome =
        when {
            ObdElmDecode.isNoDataResponse(response) -> PollOutcome.NO_DATA
            ObdElmDecode.isElmErrorResponse(response) -> PollOutcome.ERROR
            else -> PollOutcome.LIVE
        }

    private enum class PollOutcome {
        LIVE,
        NO_DATA,
        ERROR,
    }

    /** Milliseconds since the most recent cycle that returned any fresh PID value. */
    fun msSinceLastLiveData(): Long = maxOf(0L, clock.nowMs() - lastLiveDataAtMs)

    /** Count of PIDs the negative-PID cache has disabled this session (logging + tests). */
    fun disabledCommandCount(): Int = disabledCommands.size

    /** True if [command] is currently retired by the negative-PID cache. */
    fun isCommandDisabled(command: String): Boolean = disabledCommands.contains(command)

    @Throws(IOException::class)
    private fun tryBatchTier1Mode01(
        due: List<PidSpec>,
        rawThisCycle: StringBuilder,
    ): Boolean {
        if (!mode01BatchSupported) {
            return false
        }
        for (batchCommand in PidSchedule.MODE_01_BATCH_COMMANDS) {
            if (!commandDueOnBroadcast(due, batchCommand)) {
                return false
            }
        }
        val batched = ObdProtocol.buildMode01MultiCommand(PidSchedule.MODE_01_BATCH_PIDS_HEX)
        val response = engine.sendRecoverableCommand(batched, 1500)
        if (!ObdProtocol.responseContainsAllMode01Pids(response, PidSchedule.MODE_01_BATCH_PIDS_HEX)) {
            consecutiveBatchMisses += 1
            if (consecutiveBatchMisses >= MAX_CONSECUTIVE_BATCH_MISSES) {
                mode01BatchSupported = false
                service.recorder.logEvent(
                    "mode01_batch_disabled",
                    "reason",
                    "incomplete_response",
                    "consecutiveMisses",
                    consecutiveBatchMisses.toString(),
                    "response",
                    ObdProtocol.summarize(response),
                )
            } else {
                // Transient miss — keep batching enabled, just fall back to single-PID reads this
                // cycle. Avoids losing the rest of the drive's batching to one dropped BT frame.
                service.recorder.logEvent(
                    "mode01_batch_miss",
                    "consecutiveMisses",
                    consecutiveBatchMisses.toString(),
                    "response",
                    ObdProtocol.summarize(response),
                )
            }
            return false
        }
        consecutiveBatchMisses = 0
        val now = clock.nowMs()
        appendRawTo(rawThisCycle, batched, response)
        for (command in PidSchedule.MODE_01_BATCH_COMMANDS) {
            putLastRaw(command, response, now)
            lastPolledAtMsByCommand[command] = now
        }
        return true
    }

    @Throws(IOException::class)
    private fun tryBatchMode01Group(
        group: List<PidSpec>,
        rawThisCycle: StringBuilder,
    ): Boolean {
        val pidHex = group.map { it.command.substring(2) }
        val batched = ObdProtocol.buildMode01MultiCommand(pidHex)
        val response = engine.sendRecoverableCommand(batched, 1500)
        if (!ObdProtocol.responseContainsAllMode01Pids(response, pidHex)) {
            return false
        }
        val now = clock.nowMs()
        appendRawTo(rawThisCycle, batched, response)
        for (spec in group) {
            putLastRaw(spec.command, response, now)
            lastPolledAtMsByCommand[spec.command] = now
        }
        return true
    }

    companion object {
        const val CARRY_FORWARD_MAX_AGE_MS: Long = 30_000L

        // Headroom added on top of the post-cycle idle sleep to account for a cycle's own PID/header
        // round-trips. A period-N PID is re-read N cycles later, and each idle cycle costs the
        // IDLE_POLL_INTERVAL_MS post-cycle sleep PLUS that cycle's I/O — budgeting only the bare sleep
        // (the old 2_500L) evicted long-period 7E4 values (cell balance, precise SOC) a beat before
        // their next scheduled read, blanking those panels at idle. One command timeout of slack keeps
        // an on-schedule value alive to its next poll while a truly-stale one still ages out.
        private const val IDLE_CYCLE_IO_HEADROOM_MS: Long = 1_500L
        private const val CARRY_FORWARD_MS_PER_SCHEDULE_CYCLE: Long =
            ObdPollingEngine.IDLE_POLL_INTERVAL_MS + IDLE_CYCLE_IO_HEADROOM_MS

        /**
         * Consecutive bus-alive NO-DATA replies before the negative-PID cache stops scheduling a PID
         * for the rest of the session. 3 keeps a single transient miss from disabling a supported PID
         * while still retiring a genuinely-unsupported one within a few of its poll cycles.
         */
        const val MAX_CONSECUTIVE_NO_DATA: Int = 3

        /**
         * Consecutive incomplete mode-01 batch frames before we permanently fall back to single-PID
         * reads for the session. 2 tolerates a one-off dropped/garbled BT frame (which would otherwise
         * cost the rest of the drive its multi-PID batching) while still giving up quickly on an
         * adapter that genuinely can't batch.
         */
        const val MAX_CONSECUTIVE_BATCH_MISSES: Int = 2

        @JvmStatic
        fun carryForwardMaxAgeMsFor(command: String): Long {
            val periodCycles = PidSchedule.specFor(command)?.periodCycles ?: 1
            return maxOf(CARRY_FORWARD_MAX_AGE_MS, periodCycles * CARRY_FORWARD_MS_PER_SCHEDULE_CYCLE)
        }

        private fun batchableMode01(
            headerSpecs: List<PidSpec>,
            batchedTier1: Boolean,
            header: Header,
        ): List<PidSpec> {
            if (header != Header.BROADCAST) {
                return emptyList()
            }
            val out = ArrayList<PidSpec>()
            for (spec in headerSpecs) {
                if (!isMode01Command(spec.command)) {
                    continue
                }
                if (batchedTier1 && PidSchedule.isMode01BatchCommand(spec.command)) {
                    continue
                }
                out.add(spec)
            }
            return out
        }

        private fun isMode01Command(command: String?): Boolean =
            command != null && command.length == 4 && command.startsWith("01")

        @JvmStatic
        fun wasPolledThisCycle(
            due: List<PidSpec>,
            command: String,
        ): Boolean = due.any { it.command == command }

        @JvmStatic
        fun boundedRawTranscript(rawThisCycle: StringBuilder?): String {
            val raw = rawThisCycle?.toString()?.trim() ?: ""
            if (raw.length <= ObdPollingEngine.RAW_TRANSCRIPT_MAX_CHARS) {
                return raw
            }
            val omitted = raw.length - ObdPollingEngine.RAW_TRANSCRIPT_MAX_CHARS
            return raw.substring(0, ObdPollingEngine.RAW_TRANSCRIPT_MAX_CHARS) +
                "... [truncated $omitted chars]"
        }

        private fun commandDueOnBroadcast(
            due: List<PidSpec>,
            command: String,
        ): Boolean = due.any { it.header == Header.BROADCAST && it.command == command }

        private fun groupByHeader(specs: List<PidSpec>): EnumMap<Header, MutableList<PidSpec>> {
            val grouped = EnumMap<Header, MutableList<PidSpec>>(Header::class.java)
            for (spec in specs) {
                grouped.getOrPut(spec.header) { ArrayList() }.add(spec)
            }
            return grouped
        }

        private fun appendRawTo(
            buf: StringBuilder,
            command: String,
            response: String,
        ) {
            if (buf.isNotEmpty()) {
                buf.append(' ')
            }
            buf
                .append('[')
                .append(command)
                .append("] ")
                .append(ObdProtocol.summarize(response))
        }
    }
}
