package com.volttracker.obdpoc.engine

import com.volttracker.obdpoc.SwcanFrame
import com.volttracker.obdpoc.SwcanPrivacy
import java.util.Locale

/**
 * What one guided-test listen heard, kept small enough to log. Per 29-bit id: the frame count, the
 * first and last arrival and the shortest and longest gap between arrivals. For [TIMED_PIDS] every
 * arrival time too, which is how the tyre broadcast's interval gets measured. For [CHANGE_PIDS] each
 * new payload, so a door or a seat heater shows when it changed. Times are milliseconds after
 * [startMs], as the phone received them (Bluetooth buffering included). Sensitive frames are never
 * kept ([SwcanPrivacy]), and the fuel frame keeps only its fuel-door byte.
 */
class GuidedCarRecorder(
    private val startMs: Long,
) {
    /** One payload change: when, which frame, and its data bytes as hex. */
    class Change(
        val atMs: Long,
        val id: Int,
        val data: String,
    )

    private class Stats(
        val firstMs: Long,
    ) {
        var count = 1
        var lastMs = firstMs
        var minGapMs = Long.MAX_VALUE
        var maxGapMs = 0L
    }

    private val stats = sortedMapOf<Int, Stats>()
    private val arrivals = sortedMapOf<Int, MutableList<Long>>()
    private val lastPayload = mutableMapOf<Int, String>()
    private val changesPerId = mutableMapOf<Int, Int>()
    private val changeList = mutableListOf<Change>()

    var frames = 0
        private set

    /** Payload changes past [MAX_CHANGES_PER_ID] for one id: counted, not kept. */
    var droppedChanges = 0
        private set

    val changes: List<Change> get() = changeList

    fun add(
        frame: SwcanFrame,
        atMs: Long,
    ) {
        if (!frame.extended || frame.gmlanPid in SwcanPrivacy.SENSITIVE_PIDS) return
        frames += 1
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
            if (times.size < MAX_ARRIVALS_PER_PID) times += t
        }
        if (frame.gmlanPid in CHANGE_PIDS) noteChange(frame, t)
    }

    private fun noteChange(
        frame: SwcanFrame,
        t: Long,
    ) {
        val bytes = if (frame.gmlanPid == SwcanPrivacy.PID_VICM_INFO) frame.data.take(1) else frame.data.toList()
        val data = bytes.joinToString(" ") { "%02X".format(Locale.US, it) }
        if (lastPayload.put(frame.id, data) == data) return
        val count = (changesPerId[frame.id] ?: 0) + 1
        changesPerId[frame.id] = count
        if (count > MAX_CHANGES_PER_ID) {
            droppedChanges += 1
        } else {
            changeList += Change(t, frame.id, data)
        }
    }

    /** `10248040:12/120-9870/500-1200 …`: id, count/first-last/shortest-longest gap (ms); a single frame has no gaps. */
    fun idSummary(): String =
        stats.entries.joinToString(" ") { (id, s) ->
            val gaps = if (s.count > 1) "/${s.minGapMs}-${s.maxGapMs}" else ""
            "%08X:%d/%d-%d%s".format(Locale.US, id, s.count, s.firstMs, s.lastMs, gaps)
        }

    /** `1EA:120,5120 121:400,5400`: every arrival of each [TIMED_PIDS] id, in ms. */
    fun arrivalSummary(): String =
        arrivals.entries.joinToString(" ") { (pid, times) -> "%03X:%s".format(Locale.US, pid, times.joinToString(",")) }

    companion object {
        /** Tyres, power mode, oil life and the two slower warning broadcasts: their intervals are the unknowns. */
        @JvmField
        val TIMED_PIDS: Set<Int> = setOf(0x1EA, 0x121, 0x168, 0x3C0, 0x3C4)

        /**
         * The body frames whose payload changes are logged: windows, lock, doors, hatch, hood, tyres,
         * washer, bulbs, seat heat, fuel and charge-port doors, power mode, climate and dash warnings.
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

        private const val MAX_ARRIVALS_PER_PID = 300
        private const val MAX_CHANGES_PER_ID = 20
    }
}
