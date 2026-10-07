package com.volttracker.obdpoc.engine

import com.volttracker.obdpoc.ObdProtocol
import com.volttracker.obdpoc.SwcanFrameDecoder
import java.io.IOException
import java.util.Locale

/**
 * Times the HS -> SW-CAN -> HS switch on the car, for the guided car test: how long HS polling is
 * away for one body-bus listen, and where that time goes. Each cycle sets SW-CAN up, listens
 * [LISTEN_MS] past the setup, stops, restores HS and asks the car for its speed (`010D`) to prove HS
 * answers again.
 *
 * Two ways are timed. Separate: one command per round trip, as [SwcanListenRunner]'s windows do
 * today. Batched: the STN `STBC 1` mode, where `|`-joined commands go in one line (the protocol
 * check, the setup and `STM` last in one, the restore and the check in another); an error ends a
 * batch early, so a short reply falls back to the separate restore. Firmware without `STBC` answers
 * `?`, and the batched blocks are then skipped. Batching is switched off again and HS restored the
 * separate way before the step ends. Every command is one the listener already sends, plus
 * `STBC`, `STI`/`STDI` (adapter-local) and `010D` (a normal OBD read).
 *
 * Each cycle logs `guided_bench`; each block `guided_bench_summary` with the medians.
 */
class SwitchBenchmark(
    private val io: SwcanListenRunner.Io,
    private val bus: GuidedCarTest.Bus,
    private val clock: () -> Long,
) {
    private class Cycle(
        val setupMs: Long,
        val firstFrameMs: Long,
        val stopMs: Long,
        val restoreMs: Long,
        val hsMs: Long,
        val totalMs: Long,
        val hsOk: Boolean,
        val fellBack: Boolean,
    )

    private var identified = false

    /** `STBC` answered `?` once: the batched blocks are skipped from then on. */
    private var batchUnsupported = false

    fun reset() {
        identified = false
        batchUnsupported = false
    }

    /**
     * Runs one block of [cycles], holding the adapter, and returns [OK], [UNSUPPORTED],
     * [RESTORE_FAILED] (the adapter was re-initialised) or [HS_FAILED]. [cancelled] is asked
     * between cycles.
     */
    @Throws(IOException::class)
    fun run(
        label: String,
        batched: Boolean,
        cycles: Int,
        cancelled: () -> Boolean,
    ): String {
        if (batched && batchUnsupported) return UNSUPPORTED
        return io.exclusive {
            identify()
            if (batched && !sendOk("STBC 1")) {
                batchUnsupported = true
                io.logEvent("guided_bench_summary", "block", label, "mode", "batched", "supported", "false")
                return@exclusive UNSUPPORTED
            }
            val done = mutableListOf<Cycle>()
            var outcome = OK
            var hsMisses = 0
            while (done.size < cycles && !cancelled()) {
                val cycle =
                    (if (batched) batchedCycle(done.isEmpty()) else separateCycle())
                        ?: return@exclusive restoreFailed(batched, label, done)
                done += cycle
                logCycle(label, batched, done.size, cycle)
                hsMisses = if (cycle.hsOk) 0 else hsMisses + 1
                if (hsMisses >= MAX_HS_MISSES) {
                    outcome = HS_FAILED
                    break
                }
            }
            if (batched && !endBatching()) outcome = RESTORE_FAILED
            logSummary(label, batched, done)
            outcome
        }
    }

    /** The adapter's firmware and hardware, once per test: which firmware batching was timed on. */
    private fun identify() {
        if (identified) return
        identified = true
        io.logEvent(
            "guided_bench_adapter",
            "firmware",
            ObdProtocol.summarize(io.send("STI", COMMAND_TIMEOUT_MS)).take(MAX_ID_CHARS),
            "device",
            ObdProtocol.summarize(io.send("STDI", COMMAND_TIMEOUT_MS)).take(MAX_ID_CHARS),
        )
    }

    /** Null when HS could not be restored; the adapter has then been re-initialised. */
    private fun separateCycle(): Cycle? {
        val startedAt = clock()
        val failed = bus.enter()
        val setupAt = clock()
        val heard = if (failed == null) listen(SwcanListenRunner.MONITOR_COMMAND, setupAt) else null
        val stoppedAt = clock()
        if (!bus.leave()) return null
        return finishCycle(startedAt, setupAt, heard, stoppedAt, fellBack = false)
    }

    private fun batchedCycle(first: Boolean): Cycle? {
        val startedAt = clock()
        val heard = listen(BATCH_SETUP, setupAt = -1L)
        val stoppedAt = clock()
        if (first) io.logEvent("guided_bench_batch_reply", "replies", heard.replies.joinToString("|"))
        val setupOk = heard.setupAt >= 0 && heard.protocolOk
        val restored = setupOk && restoreBatched()
        if (!restored && !bus.leave()) return null
        val setupAt = if (heard.setupAt >= 0) heard.setupAt else stoppedAt
        return finishCycle(startedAt, setupAt, heard.takeIf { setupOk }, stoppedAt, fellBack = !restored)
    }

    private fun finishCycle(
        startedAt: Long,
        setupAt: Long,
        heard: Heard?,
        stoppedAt: Long,
        fellBack: Boolean,
    ): Cycle {
        val restoredAt = clock()
        val hsOk = ObdProtocol.summarize(io.send("010D", COMMAND_TIMEOUT_MS)).replace(" ", "").contains("410D")
        val endedAt = clock()
        return Cycle(
            setupMs = setupAt - startedAt,
            firstFrameMs = heard?.firstFrameAt?.let { if (it >= 0) it - startedAt else -1L } ?: -1L,
            stopMs = heard?.let { stoppedAt - it.listenEndedAt } ?: -1L,
            restoreMs = restoredAt - stoppedAt,
            hsMs = endedAt - restoredAt,
            totalMs = endedAt - startedAt,
            hsOk = hsOk,
            fellBack = fellBack,
        )
    }

    /** What one monitor heard: when the setup was acknowledged, the first frame, and the stop. */
    private inner class Heard(
        var setupAt: Long,
    ) {
        var firstFrameAt = -1L
        var listenEndedAt = -1L
        var protocolOk = false

        /** A batch's own replies (`A6`, `OK`, `?`), kept to log how this firmware answers. */
        val replies = mutableListOf<String>()

        fun onLine(line: String): Boolean {
            // Lines after the stop byte are the adapter's queue, not part of the timed listen.
            if (listenEndedAt >= 0) return false
            val now = clock()
            // A batch's replies come `|`-joined; a frame may share the line with the last of them.
            for (piece in line.split('|')) {
                val text = piece.trim()
                when {
                    text.isEmpty() -> Unit
                    SwcanFrameDecoder.parseLine(text) != null -> if (firstFrameAt < 0) firstFrameAt = now
                    setupAt < 0 -> reply(text, now)
                }
            }
            val goOn = setupAt < 0 || now - setupAt < LISTEN_MS
            if (!goOn) listenEndedAt = now
            return goOn
        }

        /** The batch's first reply is the protocol, then one OK per setup command. */
        private fun reply(
            text: String,
            now: Long,
        ) {
            if (replies.isEmpty()) protocolOk = text.uppercase(Locale.US).removePrefix("A") == HS_PROTOCOL
            // Short pieces only: nothing longer than a status word is kept, so never a frame's bytes.
            if (text.length <= MAX_REPLY_CHARS && replies.size < MAX_REPLIES) replies += text
            if (replies.size == BATCH_SETUP_REPLIES) setupAt = now
        }
    }

    private fun listen(
        command: String,
        setupAt: Long,
    ): Heard {
        val heard = Heard(setupAt)
        val result = io.monitorStream(command, MONITOR_CAP_MS, STOP_TIMEOUT_MS, heard::onLine)
        if (heard.listenEndedAt < 0) heard.listenEndedAt = clock()
        if (!result.gotPrompt) heard.setupAt = -1L
        return heard
    }

    /** The batched restore and protocol check: eight OKs then `6` (or `A6`). */
    private fun restoreBatched(): Boolean {
        val replies =
            ObdProtocol
                .summarize(io.send(BATCH_RESTORE, BATCH_TIMEOUT_MS))
                .split('|')
                .map { it.trim().uppercase(Locale.US) }
                .filter { it.isNotEmpty() }
        return replies.size == BATCH_RESTORE_REPLIES &&
            replies.dropLast(1).all { it == "OK" } &&
            replies.last().removePrefix("A") == HS_PROTOCOL
    }

    /** `STBC 0`, then the separate restore so HS is exactly as polling left it. */
    private fun endBatching(): Boolean {
        sendOk("STBC 0")
        if (bus.leave()) return true
        reinitialize("bench_restore_failed")
        return false
    }

    private fun restoreFailed(
        batched: Boolean,
        label: String,
        done: List<Cycle>,
    ): String {
        if (batched) sendOk("STBC 0")
        reinitialize("bench_restore_failed")
        logSummary(label, batched, done)
        return RESTORE_FAILED
    }

    private fun reinitialize(reason: String) {
        io.logEvent("swcan_hs_reinit", "reason", reason)
        io.reinitialize()
    }

    private fun sendOk(command: String): Boolean =
        io.send(command, COMMAND_TIMEOUT_MS).uppercase(Locale.US).contains("OK")

    private fun logCycle(
        label: String,
        batched: Boolean,
        number: Int,
        cycle: Cycle,
    ) {
        io.logEvent(
            "guided_bench",
            "block",
            label,
            "mode",
            mode(batched),
            "cycle",
            number.toString(),
            "setupMs",
            cycle.setupMs.toString(),
            "firstFrameMs",
            cycle.firstFrameMs.toString(),
            "stopMs",
            cycle.stopMs.toString(),
            "restoreMs",
            cycle.restoreMs.toString(),
            "hsMs",
            cycle.hsMs.toString(),
            "totalMs",
            cycle.totalMs.toString(),
            "hsOk",
            cycle.hsOk.toString(),
            "fellBack",
            cycle.fellBack.toString(),
        )
    }

    private fun logSummary(
        label: String,
        batched: Boolean,
        done: List<Cycle>,
    ) {
        io.logEvent(
            "guided_bench_summary",
            "block",
            label,
            "mode",
            mode(batched),
            "supported",
            "true",
            "cycles",
            done.size.toString(),
            "medianTotalMs",
            median(done.map { it.totalMs }).toString(),
            "medianSetupMs",
            median(done.map { it.setupMs }).toString(),
            "medianFirstFrameMs",
            median(done.map { it.firstFrameMs }.filter { it >= 0 }).toString(),
            "medianStopMs",
            median(done.map { it.stopMs }.filter { it >= 0 }).toString(),
            "medianRestoreMs",
            median(done.map { it.restoreMs }).toString(),
            "medianHsMs",
            median(done.map { it.hsMs }).toString(),
            "hsOk",
            done.count { it.hsOk }.toString(),
            "fellBack",
            done.count { it.fellBack }.toString(),
        )
    }

    private fun mode(batched: Boolean) = if (batched) "batched" else "separate"

    /** The middle value (the upper middle of an even count), or -1 for none. */
    private fun median(values: List<Long>): Long = values.sorted().getOrElse(values.size / 2) { -1L }

    companion object {
        const val OK = "timed"
        const val UNSUPPORTED = "unsupported"
        const val RESTORE_FAILED = "restore_failed"
        const val HS_FAILED = "hs_failed"

        /** Listening past the setup, about what a moving window listens today. */
        const val LISTEN_MS = 1_000L

        private const val MONITOR_CAP_MS = 4_000L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val COMMAND_TIMEOUT_MS = 1_000L
        private const val BATCH_TIMEOUT_MS = 2_000L
        private const val MAX_HS_MISSES = 2
        private const val MAX_ID_CHARS = 64
        private const val MAX_REPLY_CHARS = 8
        private const val MAX_REPLIES = 24
        private const val HS_PROTOCOL = "6"

        /** The protocol check and the setup, monitor last (STBC runs `STM` only as a batch's last command). */
        @JvmField
        val BATCH_SETUP =
            (
                listOf(
                    "ATDPN",
                ) + SwcanListenRunner.SETUP_COMMANDS + SwcanListenRunner.MONITOR_COMMAND
            ).joinToString("|")
        private val BATCH_SETUP_REPLIES = 1 + SwcanListenRunner.SETUP_COMMANDS.size

        @JvmField
        val BATCH_RESTORE = (SwcanListenRunner.RESTORE_COMMANDS + "ATDPN").joinToString("|")
        private val BATCH_RESTORE_REPLIES = SwcanListenRunner.RESTORE_COMMANDS.size + 1
    }
}
