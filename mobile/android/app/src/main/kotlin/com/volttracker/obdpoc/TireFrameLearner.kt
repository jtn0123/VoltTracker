package com.volttracker.obdpoc

import java.util.Locale

/** A frame layout that looks like the four tire pressures: [id]'s bytes [offset]..[offset]+3. */
data class TireCandidate(
    val id: Int,
    val offset: Int,
    /** How many frames of [id] the layout held for. */
    val frames: Int,
    /** Each wheel's typical pressure in kPa, in byte order. */
    val pressuresKpa: List<Int>,
) {
    /** "103D6060@2 240/244/240/248kPa n12" — compact enough to log a handful per window. */
    fun summary(): String = "%08X@%d %skPa n%d".format(Locale.US, id, offset, pressuresKpa.joinToString("/"), frames)
}

/**
 * Looks for the tire-pressure frame among the SW-CAN frames nothing decodes yet. The decoder expects
 * it on 0x103D4040, but the car never sent that id on two drives (2026-09-29, 2026-10-03), while its
 * neighbours 0x103D6060 and 0x103D20BC were on the bus. Rather than guess which, the learner scores
 * every undecoded 29-bit id for the shape a tire frame has: four bytes side by side that read as
 * pressures at GM's 4 kPa per count (160–360 kPa, about 23–52 psi), agree with each other within a
 * few psi, and hold steady from frame to frame. Coolant-style temperatures can share that shape, so
 * candidates are leads for the session log, confirmed on a real car before any reaches the screen.
 */
class TireFrameLearner(
    private val maxFramesPerId: Int = MAX_FRAMES_PER_ID,
) {
    private val history = LinkedHashMap<Int, ArrayDeque<IntArray>>()

    fun observe(frames: List<SwcanFrame>) {
        for (frame in frames) {
            if (!frame.extended || frame.dlc < WHEELS || SwcanFrameDecoder.decode(frame).isNotEmpty()) continue
            if (frame.id !in history && history.size >= MAX_IDS) continue
            val kept = history.getOrPut(frame.id) { ArrayDeque() }
            kept.addLast(frame.data)
            if (kept.size > maxFramesPerId) kept.removeFirst()
        }
    }

    /** The layouts that fit, most-heard first. */
    fun candidates(): List<TireCandidate> =
        history
            .flatMap { (id, frames) -> offsets(frames).mapNotNull { candidate(id, frames, it) } }
            .sortedWith(compareByDescending<TireCandidate> { it.frames }.thenBy { it.id }.thenBy { it.offset })
            .take(MAX_CANDIDATES)

    fun reset() = history.clear()

    private fun offsets(frames: Collection<IntArray>): IntRange = 0..(frames.minOf { it.size } - WHEELS)

    private fun candidate(
        id: Int,
        frames: Collection<IntArray>,
        offset: Int,
    ): TireCandidate? {
        if (frames.size < MIN_FRAMES) return null
        val wheels = (0 until WHEELS).map { wheel -> frames.map { it[offset + wheel] } }
        // Every reading a plausible pressure, and each wheel steady across the frames.
        if (wheels.any { values -> values.any { it !in PLAUSIBLE_RAW } || values.max() - values.min() > MAX_DRIFT }) {
            return null
        }
        val typical = wheels.map { values -> values.sorted()[values.size / 2] }
        if (typical.max() - typical.min() > MAX_SPREAD) return null
        return TireCandidate(id, offset, frames.size, typical.map { it * KPA_PER_COUNT })
    }

    private companion object {
        const val WHEELS = 4
        const val KPA_PER_COUNT = 4

        /** 160..360 kPa at 4 kPa per count. */
        val PLAUSIBLE_RAW = 0x28..0x5A

        /** A wheel may wander 3 counts (12 kPa, ~1.7 psi) as the tire warms. */
        const val MAX_DRIFT = 3

        /** The four wheels agree within 8 counts (32 kPa, ~4.6 psi). */
        const val MAX_SPREAD = 8
        const val MIN_FRAMES = 3
        const val MAX_FRAMES_PER_ID = 32
        const val MAX_IDS = 256
        const val MAX_CANDIDATES = 5
    }
}
