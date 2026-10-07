package com.volttracker.obdpoc.engine

import com.volttracker.obdpoc.SwcanFrame
import com.volttracker.obdpoc.SwcanPrivacy
import java.util.Locale

/**
 * What one guided-test listen heard, written out a chunk at a time ([flush]) so a listen cut short
 * by a dropped link keeps everything up to its last chunk. Per 29-bit id: the frame count, the first
 * and last arrival and the shortest and longest gap between arrivals. For [TIMED_PIDS] every arrival
 * time too, which is how the tyre broadcast's interval gets measured. For [CHANGE_PIDS] each new
 * payload, so a door or a seat heater shows when it changed ([CHANGE_BYTES] narrows that to the
 * bytes worth following, for a frame whose last bytes change by the second). Times are
 * milliseconds after [startMs], as the phone received them (Bluetooth buffering included); a time
 * marked `*` is a frame the adapter printed after it was told to stop (its queue), so it arrived
 * late by an unknown amount.
 *
 * Only allowlisted payloads are kept ([SwcanPrivacy.storable], with its byte masks); sensitive frames
 * are not even counted. Nothing is dropped silently: arrivals and changes past their caps are
 * counted, and a field cut to [MAX_FIELD_CHARS] is named in the chunk's `truncated`.
 */
class GuidedCarRecorder(
    private val startMs: Long,
) {
    /** One chunk, ready to log: each value is a `guided_capture` field. */
    class Chunk(
        val fields: List<Pair<String, String>>,
        val truncated: Boolean,
    )

    private class Change(
        val atMs: Long,
        val id: Int,
        val data: String,
        val drained: Boolean,
    )

    private class Stats(
        val firstMs: Long,
    ) {
        var count = 1
        var lastMs = firstMs
        var minGapMs = Long.MAX_VALUE
        var maxGapMs = 0L
    }

    // This chunk's; cleared by flush.
    private val stats = sortedMapOf<Int, Stats>()
    private val arrivals = sortedMapOf<Int, MutableList<String>>()
    private val changesPerId = mutableMapOf<Int, Int>()
    private val changeList = mutableListOf<Change>()
    private var chunkFrames = 0
    private var chunkDrained = 0
    private var droppedChanges = 0
    private var droppedArrivals = 0
    private var chunkFromMs = 0L

    /** Kept across chunks, so a payload is only a change when it differs from the last one heard. */
    private val lastPayload = mutableMapOf<Int, String>()

    /** Chunks written so far. */
    var chunks = 0
        private set

    /** Every frame counted, all chunks. */
    var frames = 0
        private set

    /** Frames that arrived after a stop byte, all chunks. */
    var drained = 0
        private set

    /** A chunk had a field cut short, or arrivals or changes past their caps. */
    var lossy = false
        private set

    fun add(
        frame: SwcanFrame,
        atMs: Long,
        drainedFrame: Boolean = false,
    ) {
        if (!SwcanPrivacy.countable(frame)) return
        frames += 1
        chunkFrames += 1
        if (drainedFrame) {
            drained += 1
            chunkDrained += 1
        }
        val t = atMs - startMs
        val seen = stats[frame.id]
        if (seen == null) {
            stats[frame.id] = Stats(t)
        } else {
            val gap = t - seen.lastMs
            seen.count += 1
            seen.lastMs = t
            seen.minGapMs = minOf(seen.minGapMs, gap)
            seen.maxGapMs = maxOf(seen.maxGapMs, gap)
        }
        if (frame.gmlanPid in TIMED_PIDS) {
            val times = arrivals.getOrPut(frame.gmlanPid) { mutableListOf() }
            if (times.size < MAX_ARRIVALS_PER_PID) times += mark(t, drainedFrame) else droppedArrivals += 1
        }
        if (frame.gmlanPid in CHANGE_PIDS && SwcanPrivacy.storable(frame)) noteChange(frame, t, drainedFrame)
    }

    private fun noteChange(
        frame: SwcanFrame,
        t: Long,
        drainedFrame: Boolean,
    ) {
        val bytes = SwcanPrivacy.storedBytes(frame)
        val followed = bytes.take(CHANGE_BYTES[frame.gmlanPid] ?: bytes.size)
        val data = followed.joinToString(" ") { "%02X".format(Locale.US, it) }
        if (lastPayload.put(frame.id, data) == data) return
        val count = (changesPerId[frame.id] ?: 0) + 1
        changesPerId[frame.id] = count
        if (count > MAX_CHANGES_PER_ID) {
            droppedChanges += 1
        } else {
            changeList += Change(t, frame.id, data, drainedFrame)
        }
    }

    /**
     * This chunk, from the end of the last one to [atMs], as `guided_capture` fields, then starts
     * the next. Each field is capped at [MAX_FIELD_CHARS] and a cut one named in `truncated`.
     */
    fun flush(atMs: Long): Chunk {
        chunks += 1
        val toMs = atMs - startMs
        val cut = mutableListOf<String>()

        fun capped(
            name: String,
            text: String,
        ): Pair<String, String> {
            if (text.length > MAX_FIELD_CHARS) cut += name
            return name to text.take(MAX_FIELD_CHARS)
        }
        val fields =
            listOf(
                "chunk" to chunks.toString(),
                "fromMs" to chunkFromMs.toString(),
                "toMs" to toMs.toString(),
                "frames" to chunkFrames.toString(),
                "drainedFrames" to chunkDrained.toString(),
                capped("ids", idSummary()),
                capped("arrivals", arrivalSummary()),
                capped(
                    "changes",
                    changeList.joinToString(" | ") {
                        "%s %08X %s".format(Locale.US, mark(it.atMs, it.drained), it.id, it.data)
                    },
                ),
                "droppedChanges" to droppedChanges.toString(),
                "droppedArrivals" to droppedArrivals.toString(),
            )
        val truncated = cut.isNotEmpty() || droppedChanges > 0 || droppedArrivals > 0
        lossy = lossy || truncated
        val chunk = Chunk(fields + ("truncated" to cut.joinToString(",")), truncated)
        stats.clear()
        arrivals.clear()
        changesPerId.clear()
        changeList.clear()
        chunkFrames = 0
        chunkDrained = 0
        droppedChanges = 0
        droppedArrivals = 0
        chunkFromMs = toMs
        return chunk
    }

    /** `10248040:12/120-9870/500-1200 …`: id, count/first-last/shortest-longest gap (ms); a single frame has no gaps. */
    private fun idSummary(): String =
        stats.entries.joinToString(" ") { (id, s) ->
            val gaps = if (s.count > 1) "/${s.minGapMs}-${s.maxGapMs}" else ""
            "%08X:%d/%d-%d%s".format(Locale.US, id, s.count, s.firstMs, s.lastMs, gaps)
        }

    /** `1EA:120,5120 121:400,5400*`: every arrival of each [TIMED_PIDS] id, in ms. */
    private fun arrivalSummary(): String =
        arrivals.entries.joinToString(" ") { (pid, times) -> "%03X:%s".format(Locale.US, pid, times.joinToString(",")) }

    private fun mark(
        t: Long,
        drainedFrame: Boolean,
    ): String = if (drainedFrame) "$t*" else t.toString()

    companion object {
        /** Tyres, power mode, oil life and the two slower warning broadcasts: their intervals are the unknowns. */
        @JvmField
        val TIMED_PIDS: Set<Int> = setOf(0x1EA, 0x121, 0x168, 0x3C0, 0x3C4)

        /**
         * The body frames whose payload changes are logged: windows, lock, doors, hatch, hood, tyres,
         * washer, bulbs, seat heat (indicators, switches, requests), fuel and charge-port doors,
         * charge cord, power mode, climate and dash warnings. All in [SwcanPrivacy.PAYLOAD_PIDS].
         */
        @JvmField
        val CHANGE_PIDS: Set<Int> =
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
                0x39A,
                0x40A,
                0x132,
                0x3C0,
                0x3C4,
            )

        /**
         * Frames whose changes are followed in their first bytes only. HS_Indications_Fast (0x132):
         * bytes 0-5 hold its lamps and status bits, while bytes 6-7 carry the instantaneous fuel
         * rate (and an axle mode); the rate changed about every frame on the car (2026-10-07) and
         * filled the frame's change cap in seconds. Its arrivals are still counted and timed like
         * any frame's.
         */
        @JvmField
        val CHANGE_BYTES: Map<Int, Int> = mapOf(0x132 to 6)

        /** Per id, per chunk. */
        private const val MAX_ARRIVALS_PER_PID = 300
        private const val MAX_CHANGES_PER_ID = 40
        const val MAX_FIELD_CHARS = 16_000
    }
}
