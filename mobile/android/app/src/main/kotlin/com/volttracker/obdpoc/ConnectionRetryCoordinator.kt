package com.volttracker.obdpoc

/**
 * Pure retry-policy state for the Bluetooth connection loop.
 *
 * [ObdPollingEngine] owns socket IO and user-visible side effects. This class owns the policy that
 * decides when retries are exhausted, when instant drops become wedged-adapter mode, and which
 * backoff curve applies next.
 */
class ConnectionRetryCoordinator(
    private val maxAttempts: Int = ObdProbes.MAX_RECONNECT_ATTEMPTS,
) {
    var attempt: Int = 0
        private set

    var everConnected: Boolean = false
        private set

    private var consecutiveInstantDrops: Int = 0
    private var wedgedMode: Boolean = false

    fun noteConnected() {
        everConnected = true
        consecutiveInstantDrops = 0
        wedgedMode = false
    }

    fun resetAttemptBudget() {
        attempt = 0
    }

    fun recordFailure(
        failureClass: FailureClass,
        attemptDurationMs: Long,
    ): RetryDecision {
        if (failureClass == FailureClass.INSTANT_DROP &&
            attemptDurationMs < ConnectionFailureClassifier.INSTANT_DROP_THRESHOLD_MS
        ) {
            consecutiveInstantDrops += 1
        } else {
            consecutiveInstantDrops = 0
            wedgedMode = false
        }

        attempt += 1
        if (attempt > maxAttempts) {
            return RetryDecision(
                attempt = attempt,
                everConnected = everConnected,
                exhausted = true,
                wedgedMode = wedgedMode,
                wedgedModeStarted = false,
                backoffMs = 0L,
                consecutiveInstantDrops = consecutiveInstantDrops,
            )
        }

        var wedgedModeStarted = false
        if (consecutiveInstantDrops >= 2 && !wedgedMode) {
            wedgedMode = true
            wedgedModeStarted = true
        }

        return RetryDecision(
            attempt = attempt,
            everConnected = everConnected,
            exhausted = false,
            wedgedMode = wedgedMode,
            wedgedModeStarted = wedgedModeStarted,
            backoffMs = computeBackoffMs(attempt, everConnected, wedgedMode),
            consecutiveInstantDrops = consecutiveInstantDrops,
        )
    }

    data class RetryDecision(
        val attempt: Int,
        val everConnected: Boolean,
        val exhausted: Boolean,
        val wedgedMode: Boolean,
        val wedgedModeStarted: Boolean,
        val backoffMs: Long,
        val consecutiveInstantDrops: Int,
    )

    companion object {
        @JvmField val LONG_BACKOFFS_MS = longArrayOf(8_000L, 12_000L)

        @JvmStatic
        fun computeBackoffMs(
            attempt: Int,
            everConnected: Boolean,
            wedgedMode: Boolean,
        ): Long {
            if (wedgedMode) {
                val wedgeIndex = maxOf(0, attempt - 3)
                if (wedgeIndex < LONG_BACKOFFS_MS.size) {
                    return LONG_BACKOFFS_MS[wedgeIndex]
                }
            }
            return if (everConnected) {
                ObdElmDecode.reconnectBackoffMs(attempt)
            } else {
                ObdElmDecode.initialConnectBackoffMs(attempt)
            }
        }
    }
}
