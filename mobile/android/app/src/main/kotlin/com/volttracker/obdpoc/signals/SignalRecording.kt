package com.volttracker.obdpoc.signals

import org.json.JSONObject

data class SignalSession(
    val id: Long,
    val startedAtMs: Long,
    val sampleCount: Int,
)

/** Null is an observed gap; the chart must never draw through it. */
data class SignalPoint(
    val atMs: Long,
    val value: Double?,
    val segment: Int = 0,
)

data class SignalRecording(
    val session: SignalSession,
    val firstAtMs: Long,
    val lastAtMs: Long,
    val series: Map<String, List<SignalPoint>>,
)

/** Streaming chart reduction: first/min/max/last and a gap per bucket, at most 600 points per signal. */
class SignalRecordingBuilder(
    private val session: SignalSession,
    private val firstAtMs: Long,
    private val lastAtMs: Long,
) {
    private class Bucket {
        var first: SignalPoint? = null
        var low: SignalPoint? = null
        var high: SignalPoint? = null
        var last: SignalPoint? = null
        var gap: SignalPoint? = null

        fun add(point: SignalPoint) {
            if (point.value == null) {
                if (gap == null) gap = point
            } else {
                if (first == null) first = point
                if (low == null || point.value < low!!.value!!) low = point
                if (high == null || point.value > high!!.value!!) high = point
                last = point
            }
        }

        fun points(): List<SignalPoint> =
            listOfNotNull(first, low, high, gap, last).distinctBy { it.atMs }.sortedBy { it.atMs }
    }

    private val bins = DiagnosticSignals.CHARTS.associate { it.key to sortedMapOf<Int, Bucket>() }
    private val lastObservation = mutableMapOf<String, Long>()
    private val segments = mutableMapOf<String, Int>()
    private val missing = mutableSetOf<String>()

    fun append(
        atMs: Long,
        sample: JSONObject,
    ) {
        for (signal in DiagnosticSignals.CHARTS) {
            val value = signal.value(sample)
            val observedAt = if (value == null) atMs else atMs - (signal.age(sample) ?: 0L)
            if (observedAt < firstAtMs || observedAt < (lastObservation[signal.key] ?: Long.MIN_VALUE)) continue
            if (value != null && observedAt == lastObservation[signal.key]) continue
            lastObservation[signal.key] = observedAt
            val bucket =
                (
                    (
                        (observedAt - firstAtMs).toDouble() /
                            (lastAtMs - firstAtMs + 1).coerceAtLeast(
                                1L,
                            )
                    ) * BUCKETS
                ).toInt().coerceIn(
                    0,
                    BUCKETS - 1,
                )
            if (value == null && missing.add(signal.key)) segments[signal.key] = (segments[signal.key] ?: 0) + 1
            if (value != null) missing.remove(signal.key)
            bins.getValue(signal.key).getOrPut(bucket) { Bucket() }.add(
                SignalPoint(
                    observedAt,
                    value,
                    segments[signal.key] ?: 0,
                ),
            )
        }
    }

    fun build(): SignalRecording =
        SignalRecording(
            session,
            firstAtMs,
            lastAtMs,
            bins.mapValues { (_, buckets) ->
                buckets.values.flatMap {
                    it.points()
                }
            },
        )

    private companion object {
        const val BUCKETS = 120
    }
}
