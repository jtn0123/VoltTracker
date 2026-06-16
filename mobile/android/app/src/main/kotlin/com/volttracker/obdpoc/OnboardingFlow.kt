package com.volttracker.obdpoc

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Decision core for the guided first-run setup walkthrough (report item M7). Replaces the old
 * one-shot first-launch explainer: this models the short multi-step flow — pair adapter -> grant
 * Bluetooth -> grant location -> connect/demo -> done — and which of those steps are already
 * satisfied by the live permission/adapter state so they can be skipped.
 *
 * Pure and WebView-free on purpose: [MainActivity] renders each step as a native dialog (permissions
 * and pairing are native) and re-asks [stepsFor] between steps as the live state changes, while this
 * class stays unit-testable in isolation. Completion is persisted so the flow auto-shows only once,
 * but [MainActivity] can re-open it on demand (the "Setup guide" affordance) regardless of the
 * persisted marker.
 */
class OnboardingFlow(
    private val prefs: SharedPreferences,
) {
    /** A single onboarding step, in canonical order. */
    enum class Step {
        PAIR_ADAPTER,
        GRANT_BLUETOOTH,
        GRANT_LOCATION,
        CONNECT_OR_DEMO,
        ALL_SET,
    }

    /**
     * Live readiness snapshot the flow is computed against. [MainActivity] fills this from
     * [PermissionGate] + [DeviceCatalog] so the same grant checks drive onboarding and connect.
     */
    data class State(
        val hasPairedAdapter: Boolean,
        val hasBluetoothPermission: Boolean,
        val hasLocationPermission: Boolean,
        val loggingActive: Boolean,
    )

    /** A step paired with whether the live state has already satisfied it. */
    data class StepStatus(
        val step: Step,
        val done: Boolean,
    )

    /**
     * Auto-show only for a genuinely fresh user: never mid-session, never once completed, and never
     * when every setup prerequisite is already satisfied (adapter paired AND both permissions
     * granted — Connect is then just the normal call-to-action, nothing left to guide). Re-opening
     * from the Setup guide affordance bypasses this and always shows the flow.
     */
    fun shouldAutoShow(state: State): Boolean {
        if (state.loggingActive || prefs.getBoolean(KEY_COMPLETED, false)) {
            return false
        }
        return !prerequisitesMet(state)
    }

    /** True once the three configurable setup prerequisites are satisfied. */
    private fun prerequisitesMet(state: State): Boolean =
        state.hasPairedAdapter && state.hasBluetoothPermission && state.hasLocationPermission

    /**
     * The full ordered step list annotated with completion against [state]. A step is "done" when the
     * live state already satisfies it, so [MainActivity] can render it as skipped. CONNECT_OR_DEMO is
     * the call-to-action and counts as done only once a session is live; ALL_SET (the terminal
     * confirmation) is done once the three configurable prerequisites are satisfied.
     */
    fun stepsFor(state: State): List<StepStatus> =
        listOf(
            StepStatus(Step.PAIR_ADAPTER, state.hasPairedAdapter),
            StepStatus(Step.GRANT_BLUETOOTH, state.hasBluetoothPermission),
            StepStatus(Step.GRANT_LOCATION, state.hasLocationPermission),
            StepStatus(Step.CONNECT_OR_DEMO, state.loggingActive),
            StepStatus(Step.ALL_SET, prerequisitesMet(state)),
        )

    /**
     * Steps the user is actually walked through against [state]: every not-yet-satisfied actionable
     * step in canonical order, plus the terminal ALL_SET confirmation. A step the live state already
     * satisfied is omitted (it shows as skipped) — this is what makes both the dialog sequence and
     * the "Step N of M" count adapt to current readiness. [MainActivity] walks this list by cursor
     * so a "Skip for now" advances past a step the user chose not to act on.
     */
    fun presentedSteps(state: State): List<Step> {
        val pending =
            stepsFor(state)
                .filter { !it.done && it.step != Step.ALL_SET }
                .map { it.step }
        return pending + Step.ALL_SET
    }

    /**
     * Position of [step] among the [presentedSteps] for the "Step N of M" progress label (1-based).
     * A step not in the presented list (already satisfied) clamps to the end so the label never reads
     * "0 of N" when the flow is re-opened on a partly-configured device.
     */
    fun progressFor(
        state: State,
        step: Step,
    ): Progress {
        val presented = presentedSteps(state)
        val total = presented.size
        val index = presented.indexOf(step)
        val position = if (index >= 0) index + 1 else total
        return Progress(position, total)
    }

    fun markCompleted() {
        prefs.edit { putBoolean(KEY_COMPLETED, true) }
    }

    /** 1-based progress: the user is on [position] of [total] presented steps. */
    data class Progress(
        val position: Int,
        val total: Int,
    )

    companion object {
        const val KEY_COMPLETED = "onboarding_flow_completed"
    }
}
