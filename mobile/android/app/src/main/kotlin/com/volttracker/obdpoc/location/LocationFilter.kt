package com.volttracker.obdpoc.location

import java.util.Locale

/**
 * Pure decision logic for accepting or rejecting a raw location fix before it becomes a stored
 * route point. Has no Android dependencies so it is unit-testable on the JVM.
 *
 * Rejection rules, applied in order:
 *  - accuracy worse than `maxAccuracyM`
 *  - a fix whose own timestamp is older than `staleFixMs` (a cached/replayed fix)
 *  - a coarse `network` fix that arrives while GPS fixes are still fresh
 *  - a physically impossible jump from the last accepted point
 *
 * The jump check is bypassed once `maxConsecutiveRejects` fixes in a row have been rejected, so a
 * stale "last accepted" point cannot wedge the filter shut forever.
 */
class LocationFilter(
    private val maxAccuracyM: Float,
    private val staleFixMs: Long,
    private val networkSuppressMs: Long,
    private val maxImpliedSpeedMps: Double,
    private val maxConsecutiveRejects: Int,
) {
    enum class Decision {
        ACCEPT,
        REJECT_ACCURACY,
        REJECT_STALE,
        REJECT_PROVIDER,
        REJECT_JUMP,
    }

    private var hasLast = false
    private var lastLat = 0.0
    private var lastLng = 0.0
    private var lastFixMs = 0L
    private var lastGpsAcceptMs = 0L
    private var rejectStreak = 0

    constructor() : this(
        DEFAULT_MAX_ACCURACY_M,
        DEFAULT_STALE_FIX_MS,
        DEFAULT_NETWORK_SUPPRESS_MS,
        DEFAULT_MAX_IMPLIED_SPEED_MPS,
        DEFAULT_MAX_CONSECUTIVE_REJECTS,
    )

    /**
     * @param accuracyM horizontal accuracy in metres, or a non-positive value when unknown
     * @param fixTimeMs the fix's own timestamp (epoch ms), or non-positive when unknown
     * @param provider the originating provider name (e.g. "gps", "network", "fused")
     * @param nowMs current wall-clock time
     */
    fun evaluate(
        lat: Double,
        lng: Double,
        accuracyM: Float,
        fixTimeMs: Long,
        provider: String?,
        nowMs: Long,
    ): Decision {
        if (accuracyM > 0f && accuracyM > maxAccuracyM) {
            return reject(Decision.REJECT_ACCURACY)
        }
        if (fixTimeMs > 0L && nowMs - fixTimeMs > staleFixMs) {
            return reject(Decision.REJECT_STALE)
        }
        val network = isNetworkProvider(provider)
        if (network && lastGpsAcceptMs > 0L && nowMs - lastGpsAcceptMs <= networkSuppressMs) {
            return reject(Decision.REJECT_PROVIDER)
        }
        val resync = rejectStreak >= maxConsecutiveRejects
        if (hasLast && !resync) {
            val effectiveFix = if (fixTimeMs > 0L) fixTimeMs else nowMs
            val dtSeconds = (effectiveFix - lastFixMs) / 1000.0
            if (dtSeconds > 0) {
                val mps = haversineMeters(lastLat, lastLng, lat, lng) / dtSeconds
                if (mps > maxImpliedSpeedMps) {
                    return reject(Decision.REJECT_JUMP)
                }
            }
        }
        accept(lat, lng, if (fixTimeMs > 0L) fixTimeMs else nowMs, network, nowMs)
        return Decision.ACCEPT
    }

    private fun accept(
        lat: Double,
        lng: Double,
        fixMs: Long,
        network: Boolean,
        nowMs: Long,
    ) {
        hasLast = true
        lastLat = lat
        lastLng = lng
        lastFixMs = fixMs
        if (!network) {
            lastGpsAcceptMs = nowMs
        }
        rejectStreak = 0
    }

    private fun reject(why: Decision): Decision {
        rejectStreak += 1
        return why
    }

    /** Number of fixes rejected since the last accepted fix; exposed for diagnostics/tests. */
    fun consecutiveRejects(): Int = rejectStreak

    /** Clears all per-session state so the filter can be reused for a fresh tracking session. */
    fun reset() {
        hasLast = false
        lastLat = 0.0
        lastLng = 0.0
        lastFixMs = 0L
        lastGpsAcceptMs = 0L
        rejectStreak = 0
    }

    companion object {
        const val DEFAULT_MAX_ACCURACY_M = 50f
        const val DEFAULT_STALE_FIX_MS = 10_000L
        const val DEFAULT_NETWORK_SUPPRESS_MS = 15_000L

        // ~150 mph; clearly a teleport above this
        const val DEFAULT_MAX_IMPLIED_SPEED_MPS = 67.0
        const val DEFAULT_MAX_CONSECUTIVE_REJECTS = 5

        @JvmStatic
        fun isNetworkProvider(provider: String?): Boolean =
            provider != null && provider.lowercase(Locale.US).contains("network")

        @JvmStatic
        fun haversineMeters(
            lat1: Double,
            lng1: Double,
            lat2: Double,
            lng2: Double,
        ): Double {
            val r = 6_371_000.0
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = Math.toRadians(lat2 - lat1)
            val dl = Math.toRadians(lng2 - lng1)
            val a =
                Math.sin(dp / 2) * Math.sin(dp / 2) +
                    Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
            return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        }
    }
}
