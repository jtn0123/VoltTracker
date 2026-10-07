package com.volttracker.obdpoc

import com.volttracker.obdpoc.engine.ElmConnection
import com.volttracker.obdpoc.engine.GuidedCarTest
import com.volttracker.obdpoc.engine.SwcanListenRunner
import java.io.IOException

/** Fake time for the guided-test fakes: only they move it. */
class FakeClock {
    var now = 0L

    fun advance(ms: Long) {
        now += ms
    }
}

/**
 * The adapter and the car behind it, in fake time. Monitoring streams the frames queued with
 * [broadcast] as their time comes (and [heartbeat] every second), and an idle tick (an empty line)
 * every [TICK_MS] of silence; after the stop byte it prints [stopTail], [STOP_LINE_MS] apart, to the
 * drain. Commands take [commandMs] and answer from [replies] (or [replyQueue] first). [dropLinkAt]
 * makes the link fail at a given time.
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
    var inPark = true
    var motionNoted = 0
    var gearReads = 0

    /** What [motionCount] answers: a test bumps it for motion HS polling saw. */
    var motions = 0L

    /** A body frame the car sends every second, or null for a silent car. */
    var heartbeat: String? = null
    private var nextBeatAt = 0L

    /** What the adapter still prints after the stop byte (its queue). */
    var stopTail: List<String> = emptyList()

    /** False: the stop prompt never comes. */
    var stopPrompt = true

    /** The link drops (an IOException from the monitor) once fake time reaches this. */
    var dropLinkAt: Long? = null

    /** Thrown from the monitor once fake time reaches this: a bug, not a dropped link. */
    var crashAt: Long? = null

    /** Lines the adapter prints first on each monitor, before any frame. */
    var preamble: (String) -> List<String> = { emptyList() }

    /** Once set, the next monitor ends by itself this long in (the adapter's buffer filling). */
    var endEarlyAfterMs: Long? = null

    /** What the adapter prints as it ends a monitor by itself, or null for nothing. */
    var earlyEndLine: String? = "BUFFER FULL"

    /** Each monitor is cut this long in, the way a session ending cuts it: no early end, the stop as usual. */
    var cutAfterMs: Long? = null

    /** Every monitor ends by itself at once. */
    var alwaysEndsAtOnce = false

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
        onDrain: (String) -> Unit,
    ): ElmConnection.MonitorResult {
        commands += command
        val start = clock.now
        var listening = true
        for (line in preamble(command)) listening = onLine(line) && listening
        if (alwaysEndsAtOnce) {
            earlyEndLine?.let(onLine)
            return ElmConnection.MonitorResult("", true, true, false)
        }
        val runMs = minOf(listenMs, cutAfterMs ?: listenMs)
        while (listening && clock.now - start < runMs) {
            endEarlyAfterMs?.let { after ->
                if (clock.now - start >= after) {
                    endEarlyAfterMs = null
                    earlyEndLine?.let(onLine)
                    return ElmConnection.MonitorResult("", true, true, false)
                }
            }
            clock.advance(TICK_MS)
            dropLinkAt?.let { if (clock.now >= it) throw IOException("Broken pipe") }
            crashAt?.let { if (clock.now >= it) throw IllegalStateException("bug") }
            onTick(clock.now)
            heartbeat?.let {
                if (clock.now >= nextBeatAt) {
                    broadcasts += clock.now to it
                    nextBeatAt = clock.now + 1_000L
                }
            }
            val due = broadcasts.filter { it.first <= clock.now }
            broadcasts.removeAll(due)
            listening =
                if (due.isEmpty()) {
                    onLine("")
                } else {
                    due.fold(true) { goOn, (_, line) -> onLine(line) && goOn }
                }
        }
        for (line in stopTail) {
            clock.advance(STOP_LINE_MS)
            onDrain(line)
        }
        clock.advance(stopMs)
        if (!stopPrompt) return ElmConnection.MonitorResult("", false, false, false)
        onDrain("STOPPED")
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

    override fun isInPark(): Boolean = inPark

    override fun requestGearRead() {
        gearReads += 1
    }

    override fun motionCount(): Long = motions

    override fun noteMotion() {
        motionNoted += 1
        motions += 1
        inPark = false
    }

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
        const val STOP_LINE_MS = 500L
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

/**
 * Speaks for 50 ms a character in fake time; [onSay] lets a test's "driver" react to an instruction.
 * [failed] scripts a phone that can't speak.
 */
class FakeVoice(
    private val clock: FakeClock,
) : GuidedCarTest.Voice {
    val said = mutableListOf<String>()
    var closed = false
    var interrupts = 0
    var failed = false
    var onSay: (String) -> Unit = {}
    private var speakingUntil = 0L

    override fun say(text: String) {
        said += text
        speakingUntil = maxOf(speakingUntil, clock.now) + text.length * MS_PER_CHAR
        onSay(text)
    }

    override fun speaking(): Boolean = clock.now < speakingUntil

    override fun failed(): Boolean = failed

    override fun interrupt() {
        interrupts += 1
        speakingUntil = 0L
    }

    override fun close() {
        closed = true
    }

    private companion object {
        const val MS_PER_CHAR = 50L
    }
}
