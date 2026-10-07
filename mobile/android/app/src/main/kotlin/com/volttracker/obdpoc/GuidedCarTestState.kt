package com.volttracker.obdpoc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the guided car test is, for the Car tab's status card. */
data class GuidedTestStatus(
    val running: Boolean = false,
    /** 1-based; 0 before the first step. */
    val step: Int = 0,
    val steps: Int = 0,
    /** What the phone just said, shown under it. */
    val instruction: String = "",
    /** What the last step heard ("Heard it", "Didn't hear it", "Skipped"), or "". */
    val lastResult: String = "",
    /** Set once the test ends: "Finished", "Stopped", or why it could not go on. */
    val ended: String = "",
)

/**
 * The guided car test's status, shared within the app process: the poll thread publishes it, the
 * Car tab collects it. Kept out of the live sample because it is the test's own progress, not a
 * reading from the car.
 */
object GuidedCarTestState {
    private val mutable = MutableStateFlow(GuidedTestStatus())
    val status: StateFlow<GuidedTestStatus> = mutable.asStateFlow()

    fun publish(status: GuidedTestStatus) {
        mutable.value = status
    }
}
