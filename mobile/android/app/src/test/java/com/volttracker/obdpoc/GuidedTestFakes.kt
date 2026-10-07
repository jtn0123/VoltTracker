package com.volttracker.obdpoc

import com.volttracker.obdpoc.engine.ElmConnection
import com.volttracker.obdpoc.engine.GuidedCarTest
import com.volttracker.obdpoc.engine.SwcanListenRunner

/** Fake time for the guided-test fakes: only they move it. */
class FakeClock {
    var now = 0L

    fun advance(ms: Long) {
        now += ms
    }
}

/**
 * The adapter and the car behind it, in fake time. Monitoring streams the frames queued with
 * [broadcast] as their time comes, and an idle tick (an empty line) every [TICK_MS] of silence;
 * commands take [commandMs] and answer from [replies] (or [replyQueue] first).
 */
class FakeCarIo(
    private val clock: FakeClock,
) : SwcanListenRunner.Io {
    val commands = mutableListOf<String>()
    val events = mutableListOf<Pair<String, Map<String, String>>>()
    val replies =
        mutableMapOf(
            "ATDPN" to "A6\r\r>",
            "010D" to "410D00\r\r>",
            "STI" to "STN2255 v5.10.3\r\r>",
            "STDI" to "OBDLink MX+ r1.2\r\r>",
        )
    val replyQueue = mutableMapOf<String, ArrayDeque<String>>()
    private val broadcasts = mutableListOf<Pair<Long, String>>()
    var commandMs = 40L
    var stopMs = 60L
    var reinitCount = 0
    var stationary = true

    /** Lines the adapter prints first on each monitor, before any frame. */
    var preamble: (String) -> List<String> = { emptyList() }

    /** Once set, the next monitor ends by itself this long in (the adapter's buffer filling). */
    var endEarlyAfterMs: Long? = null

    /** Called every tick while monitoring: a test's "driver" acting at a given time. */
    var onTick: (Long) -> Unit = {}

    fun broadcast(
        atMs: Long,
        line: String,
    ) {
        broadcasts += atMs to line
    }

    override fun send(
        command: String,
        timeoutMs: Long,
    ): String {
        commands += command
        clock.advance(commandMs)
        replyQueue[command]?.removeFirstOrNull()?.let { return it }
        return replies[command] ?: "OK\r\r>"
    }

    override fun monitor(
        command: String,
        listenMs: Long,
        stopTimeoutMs: Long,
    ): ElmConnection.MonitorResult = throw AssertionError("the guided test streams; it never collects a monitor reply")

    override fun monitorStream(
        command: String,
        listenMs: Long,
        stopTimeoutMs: Long,
        onLine: (String) -> Boolean,
    ): ElmConnection.MonitorResult {
        commands += command
        val start = clock.now
        var listening = true
        for (line in preamble(command)) listening = onLine(line) && listening
        while (listening && clock.now - start < listenMs) {
            endEarlyAfterMs?.let { after ->
                if (clock.now - start >= after) {
                    endEarlyAfterMs = null
                    onLine("BUFFER FULL")
                    return ElmConnection.MonitorResult("", true, true, false)
                }
            }
            clock.advance(TICK_MS)
            onTick(clock.now)
            val due = broadcasts.filter { it.first <= clock.now }
            broadcasts.removeAll(due)
            listening =
                if (due.isEmpty()) {
                    onLine("")
                } else {
                    due.fold(true) { goOn, (_, line) -> onLine(line) && goOn }
                }
        }
        clock.advance(stopMs)
        return ElmConnection.MonitorResult("STOPPED\r>", true, false, false)
    }

    override fun reinitialize() {
        reinitCount += 1
        commands += "<reinit>"
    }

    override fun liveCycleCount(): Long = 0L

    override fun msSinceLiveData(): Long = 0L

    override fun <T> exclusive(block: () -> T): T = block()

    override fun isStationary(): Boolean = stationary

    override fun logEvent(
        event: String,
        vararg pairs: String,
    ) {
        events += event to pairs.toList().chunked(2).associate { it[0] to it[1] }
    }

    fun all(name: String): List<Map<String, String>> = events.filter { it.first == name }.map { it.second }

    fun event(name: String): Map<String, String>? = all(name).lastOrNull()

    companion object {
        const val TICK_MS = 250L
    }
}

/** The SW-CAN switch, recorded; [leaveResults] scripts failed restores. */
class FakeBus(
    private val io: FakeCarIo,
) : GuidedCarTest.Bus {
    var ready: Boolean? = true
    var enterFails: String? = null
    val leaveResults = ArrayDeque<Boolean>()
    val recorded = mutableListOf<SwcanReading>()

    override fun canListen(): Boolean? = ready

    override fun enter(): String? {
        io.commands += "<enter>"
        return enterFails
    }

    override fun leave(): Boolean {
        io.commands += "<leave>"
        return leaveResults.removeFirstOrNull() ?: true
    }

    override fun record(decoded: List<SwcanReading>) {
        recorded += decoded
    }
}

/** Speaks for 50 ms a character in fake time; [onSay] lets a test's "driver" react to an instruction. */
class FakeVoice(
    private val clock: FakeClock,
) : GuidedCarTest.Voice {
    val said = mutableListOf<String>()
    var closed = false
    var onSay: (String) -> Unit = {}
    private var speakingUntil = 0L

    override fun say(text: String) {
        said += text
        speakingUntil = maxOf(speakingUntil, clock.now) + text.length * MS_PER_CHAR
        onSay(text)
    }

    override fun speaking(): Boolean = clock.now < speakingUntil

    override fun close() {
        closed = true
    }

    private companion object {
        const val MS_PER_CHAR = 50L
    }
}
