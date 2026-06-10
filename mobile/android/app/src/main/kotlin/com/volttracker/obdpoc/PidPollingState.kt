package com.volttracker.obdpoc

import com.volttracker.obdpoc.PidSchedule.Header
import com.volttracker.obdpoc.PidSchedule.PidSpec
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** Owns live-poll PID scheduling, batching, carry-forward raw values, and stale timers. */
class PidPollingState(
    private val service: EngineHost,
    private val engine: ObdPollingEngine,
) {
    private var cycleNum = 0
    private var mode01BatchSupported = false
    private val lastRawByCommand = HashMap<String, String>()
    private val lastPolledAtMsByCommand = HashMap<String, Long>()
    private val lastRawSetAtMsByCommand = HashMap<String, Long>()
    private var clock = Clock { System.currentTimeMillis() }

    /** Time source, overridable in tests. */
    fun interface Clock {
        fun nowMs(): Long
    }

    fun setClockForTesting(clock: Clock) {
        this.clock = clock
    }

    fun reset() {
        cycleNum = 0
        mode01BatchSupported = false
        lastRawByCommand.clear()
        lastPolledAtMsByCommand.clear()
        lastRawSetAtMsByCommand.clear()
    }

    fun isInitialCycle(): Boolean = cycleNum == 0

    fun dueForCurrentCycle(): List<PidSpec> =
        if (isInitialCycle()) PidSchedule.SPECS else PidSchedule.dueOnCycle(cycleNum)

    fun advanceCycle() {
        cycleNum += 1
    }

    fun setMode01BatchSupported(supported: Boolean) {
        mode01BatchSupported = supported
    }

    fun lastRaw(command: String): String? {
        val setAtMs = lastRawSetAtMsByCommand[command]
        if (setAtMs != null && clock.nowMs() - setAtMs > carryForwardMaxAgeMsFor(command)) {
            lastRawByCommand.remove(command)
            lastRawSetAtMsByCommand.remove(command)
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
        val batchedTier1 = tryBatchTier1Mode01(due, rawThisCycle)
        var switchedHeader = false
        var lastHeaderSet = Header.BROADCAST
        for (header in Header.entries) {
            val headerSpecs = filterByHeader(due, header)
            if (headerSpecs.isEmpty()) {
                continue
            }
            if (header != Header.BROADCAST && header != lastHeaderSet) {
                val headerCommand = header.atCommand
                if (headerCommand == null) {
                    service.recorder?.logEvent(
                        "pid_header_skipped",
                        "header",
                        header.name,
                        "reason",
                        "missing_at_command",
                    )
                    continue
                }
                engine.sendCommand(headerCommand, 1500)
                switchedHeader = true
                lastHeaderSet = header
            }
            val extraBatch =
                if (mode01BatchSupported) {
                    batchableMode01(headerSpecs, batchedTier1, header)
                } else {
                    emptyList()
                }
            val batchedExtra = extraBatch.size >= 2 && tryBatchMode01Group(extraBatch, rawThisCycle)
            for (spec in headerSpecs) {
                if (
                    batchedTier1 &&
                    header == Header.BROADCAST &&
                    PidSchedule.MODE_01_BATCH_COMMANDS.contains(spec.command)
                ) {
                    continue
                }
                if (batchedExtra && extraBatch.contains(spec)) {
                    continue
                }
                val response = engine.sendRecoverableCommand(spec.command, 1500)
                appendRawTo(rawThisCycle, spec.command, response)
                val now = clock.nowMs()
                putLastRaw(spec.command, response, now)
                lastPolledAtMsByCommand[spec.command] = now
            }
        }
        if (switchedHeader) {
            engine.sendCommand(PidSchedule.RESTORE_BROADCAST_HEADER_COMMAND, 1500)
        }
    }

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
            mode01BatchSupported = false
            service.recorder?.logEvent(
                "mode01_batch_disabled",
                "reason",
                "incomplete_response",
                "response",
                ObdProtocol.summarize(response),
            )
            return false
        }
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
        private const val CARRY_FORWARD_MS_PER_SCHEDULE_CYCLE: Long = 2_500L

        @JvmStatic
        fun carryForwardMaxAgeMsFor(command: String): Long {
            var periodCycles = 1
            for (spec in PidSchedule.SPECS) {
                if (spec.command == command) {
                    periodCycles = maxOf(periodCycles, spec.periodCycles)
                    break
                }
            }
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
                if (batchedTier1 && PidSchedule.MODE_01_BATCH_COMMANDS.contains(spec.command)) {
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

        private fun filterByHeader(
            specs: List<PidSpec>,
            header: Header,
        ): List<PidSpec> = specs.filter { it.header == header }

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
