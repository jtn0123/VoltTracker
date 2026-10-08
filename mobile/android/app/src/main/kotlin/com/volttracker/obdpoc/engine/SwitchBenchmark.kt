package com.volttracker.obdpoc.engine

import com.volttracker.obdpoc.ObdProtocol
import com.volttracker.obdpoc.SwcanFrameDecoder
import com.volttracker.obdpoc.SwcanPrivacy
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
 * A cycle only counts when everything in it worked: each batched reply checked (the protocol, then an
 * `OK` per setup command), the monitor printing nothing but frames, monitoring until it was stopped,
 * for the whole [LISTEN_MS], the stop prompt back, HS restored without the fallback, and HS
 * answering. One that didn't is logged with why (`setup_failed`, `monitor_error`, `monitor_ended`,
 * `short_listen`, `no_stop_prompt`, `restore_fallback`, `hs_no_reply`, `restore_failed`,
 * `interrupted`) and left out of the medians.
 *
 * Each cycle logs `guided_bench`; each block `guided_bench_summary` with the medians of the cycles that
 * worked and the count of every one that didn't, a failed restore or a dropped link included. A
 * dropped link also logs the adapter's state as unknown.
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
        /** Why the cycle doesn't count, or "" when it does. */
        val failure: String,
    )

    private var identified = false

    /** The first batch's replies have been logged; later ones are only when they went wrong. */
    private var replyLogged = false

    /** `STBC` answered `?` once: the batched blocks are skipped from then on. */
    private var batchUnsupported = false

    /** A cycle has started and not yet been added to its block: what a dropped link interrupts. */
    private var inCycle = false

    fun reset() {
        identified = false
        replyLogged = false
        batchUnsupported = false
    }

    /**
     * Runs one block of [cycles], holding the adapter, and returns [OK], [PARTIAL] (some cycles
     * failed), [UNSUPPORTED], [RESTORE_FAILED] (the adapter was re-initialised) or [HS_FAILED].
     * [cancelled] is asked between cycles.
     */
    @Throws(IOException::class)
    fun run(
        label: String,
        batched: Boolean,
        cycles: Int,
        cancelled: () -> Boolean,
    ): String {
        if (batched && batchUnsupported) return UNSUPPORTED
        val done = mutableListOf<Cycle>()
        inCycle = false
        return io.exclusive {
            try {
                runBlock(label, batched, cycles, cancelled, done)
            } catch (ex: IOException) {
                // Batching and the bus are whatever they were; the reconnect's re-init (ATZ) resets both.
                interrupted(label, batched, done, ex)
                throw ex
            } catch (ex: RuntimeException) {
                interrupted(label, batched, done, ex)
                try {
                    if (batched) sendOk("STBC 0")
                    bus.leave()
                } catch (restoreFailed: IOException) {
                    io.logEvent("guided_restore_failed", "step", label, "error", restoreFailed.javaClass.simpleName)
                }
                throw ex
            }
        }
    }

    /** The cycle the link dropped in, if it was in one, is logged as `interrupted`, then the block's summary. */
    private fun interrupted(
        label: String,
        batched: Boolean,
        done: MutableList<Cycle>,
        ex: Exception,
    ) {
        if (inCycle) {
            inCycle = false
            done += failedCycle(INTERRUPTED)
            logCycle(label, batched, done.size, done.last())
        }
        logSummary(label, batched, done)
        io.logEvent("guided_bus_state_unknown", "step", label, "error", ex.javaClass.simpleName)
    }

    @Throws(IOException::class)
    private fun runBlock(
        label: String,
        batched: Boolean,
        cycles: Int,
        cancelled: () -> Boolean,
        done: MutableList<Cycle>,
    ): String =
        run {
            identify()
            if (batched && !sendOk("STBC 1")) {
                batchUnsupported = true
                io.logEvent("guided_bench_summary", "block", label, "mode", "batched", "supported", "false")
                return@run UNSUPPORTED
            }
            var outcome = OK
            var hsMisses = 0
            while (done.size < cycles && !cancelled()) {
                inCycle = true
                val cycle =
                    (if (batched) batchedCycle() else separateCycle())
                        ?: return@run restoreFailed(batched, label, done)
                inCycle = false
                done += cycle
                logCycle(label, batched, done.size, cycle)
                hsMisses = if (cycle.hsOk) 0 else hsMisses + 1
                if (hsMisses >= MAX_HS_MISSES) {
                    outcome = HS_FAILED
                    break
                }
            }
            if (outcome == OK && done.any { it.failure.isNotEmpty() }) outcome = PARTIAL
            if (batched && !endBatching()) outcome = RESTORE_FAILED
            logSummary(label, batched, done)
            outcome
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
        val failure = if (heard == null) SETUP_FAILED else heard.failure()
        return finishCycle(startedAt, setupAt, heard, stoppedAt, fellBack = false, failure)
    }

    private fun batchedCycle(): Cycle? {
        val startedAt = clock()
        val heard = listen(BATCH_SETUP, setupAt = -1L)
        val stoppedAt = clock()
        val setupOk = heard.setupAt >= 0 && heard.protocolOk && !heard.setupBroken
        if (!setupOk || !replyLogged) {
            // How this firmware answers a batch: once, and for every batch that went wrong.
            replyLogged = true
            io.logEvent(
                "guided_bench_batch_reply",
                "replies",
                heard.replies.joinToString("|"),
                "setupOk",
                setupOk.toString(),
            )
        }
        val restored = setupOk && heard.gotPrompt && restoreBatched()
        if (!restored && !bus.leave()) return null
        val setupAt = if (heard.setupAt >= 0) heard.setupAt else stoppedAt
        val failure =
            when {
                !setupOk -> SETUP_FAILED
                heard.failure().isNotEmpty() -> heard.failure()
                !restored -> RESTORE_FALLBACK
                else -> ""
            }
        return finishCycle(startedAt, setupAt, heard.takeIf { setupOk }, stoppedAt, fellBack = !restored, failure)
    }

    private fun finishCycle(
        startedAt: Long,
        setupAt: Long,
        heard: Heard?,
        stoppedAt: Long,
        fellBack: Boolean,
        failure: String,
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
            failure = failure.ifEmpty { if (hsOk) "" else HS_NO_REPLY },
        )
    }

    /** What one monitor heard: when the setup was acknowledged, the first frame, and the stop. */
    private inner class Heard(
        var setupAt: Long,
    ) {
        var firstFrameAt = -1L
        var listenEndedAt = -1L
        var protocolOk = false

        /** A setup command in the batch answered something other than `OK`. */
        var setupBroken = false
        var gotPrompt = false

        /** The adapter ended monitoring by itself, before the listen was over. */
        var endedEarly = false

        /** The first line after the setup that wasn't a frame (`CAN ERROR`, `?`), as a status word only. */
        var monitorError = ""
        private var answered = 0

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
                    monitorError.isEmpty() -> monitorError = SwcanPrivacy.statusWord(text) ?: UNPARSED
                }
            }
            val goOn = setupAt < 0 || now - setupAt < LISTEN_MS
            if (!goOn) listenEndedAt = now
            return goOn
        }

        /** Why the listen doesn't count, or "" when it does: the setup itself is checked by the caller. */
        fun failure(): String =
            when {
                monitorError.isNotEmpty() -> MONITOR_ERROR
                endedEarly -> MONITOR_ENDED
                !gotPrompt -> NO_STOP_PROMPT
                setupAt < 0 || listenEndedAt - setupAt < LISTEN_MS -> SHORT_LISTEN
                else -> ""
            }

        /** The batch's first reply is the protocol, then one `OK` per setup command; anything else breaks it. */
        private fun reply(
            text: String,
            now: Long,
        ) {
            val upper = text.uppercase(Locale.US)
            when {
                answered == 0 -> protocolOk = upper.removePrefix("A") == HS_PROTOCOL
                upper != "OK" -> setupBroken = true
            }
            answered += 1
            // Status words only, so never a frame's bytes, nor a fragment of a header.
            if (replies.size < MAX_REPLIES) replies += SwcanPrivacy.statusWord(text) ?: SwcanPrivacy.WITHHELD
            if (answered == BATCH_SETUP_REPLIES) setupAt = now
        }
    }

    private fun listen(
        command: String,
        setupAt: Long,
    ): Heard {
        val heard = Heard(setupAt)
        // Lines after the stop byte are the adapter's queue, not part of the timed listen.
        val result = io.monitorStream(command, MONITOR_CAP_MS, STOP_TIMEOUT_MS, heard::onLine) { }
        if (heard.listenEndedAt < 0) heard.listenEndedAt = clock()
        heard.gotPrompt = result.gotPrompt
        heard.endedEarly = result.endedEarly
        if (heard.monitorError.isNotEmpty()) io.logEvent("guided_bench_monitor_error", "reply", heard.monitorError)
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

    /** HS didn't come back in a cycle: it is logged as `restore_failed`, the adapter re-initialised. */
    private fun restoreFailed(
        batched: Boolean,
        label: String,
        done: MutableList<Cycle>,
    ): String {
        inCycle = false
        done += failedCycle(RESTORE_FAILED)
        logCycle(label, batched, done.size, done.last())
        if (batched) sendOk("STBC 0")
        reinitialize("bench_restore_failed")
        logSummary(label, batched, done)
        return RESTORE_FAILED
    }

    /** A cycle that ended before it could be timed: only why. */
    private fun failedCycle(failure: String) = Cycle(-1L, -1L, -1L, -1L, -1L, -1L, false, false, failure)

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
            "failure",
            cycle.failure,
        )
    }

    private fun logSummary(
        label: String,
        batched: Boolean,
        done: List<Cycle>,
    ) {
        val valid = done.filter { it.failure.isEmpty() }
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
            "validCycles",
            valid.size.toString(),
            "failures",
            done
                .filter { it.failure.isNotEmpty() }
                .groupingBy { it.failure }
                .eachCount()
                .entries
                .joinToString(",") { "${it.key}:${it.value}" },
            "medianTotalMs",
            median(valid.map { it.totalMs }).toString(),
            "medianSetupMs",
            median(valid.map { it.setupMs }).toString(),
            "medianFirstFrameMs",
            median(valid.map { it.firstFrameMs }.filter { it >= 0 }).toString(),
            "medianStopMs",
            median(valid.map { it.stopMs }).toString(),
            "medianRestoreMs",
            median(valid.map { it.restoreMs }).toString(),
            "medianHsMs",
            median(valid.map { it.hsMs }).toString(),
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
        const val PARTIAL = "timed_partly"
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
        private const val MAX_REPLIES = 24
        private const val HS_PROTOCOL = "6"
        private const val SETUP_FAILED = "setup_failed"
        private const val NO_STOP_PROMPT = "no_stop_prompt"
        private const val RESTORE_FALLBACK = "restore_fallback"
        private const val HS_NO_REPLY = "hs_no_reply"
        private const val MONITOR_ERROR = "monitor_error"
        private const val MONITOR_ENDED = "monitor_ended"
        private const val SHORT_LISTEN = "short_listen"
        private const val INTERRUPTED = "interrupted"
        private const val UNPARSED = "unparsed"

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
