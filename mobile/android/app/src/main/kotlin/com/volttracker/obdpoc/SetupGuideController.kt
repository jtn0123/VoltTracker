package com.volttracker.obdpoc

import android.app.AlertDialog
import android.content.Context
import android.util.Log

/**
 * Renders the guided first-run setup walkthrough (report item M7) as a sequence of native dialogs,
 * one per [OnboardingFlow.Step], driven by the live permission/adapter readiness. Permissions and
 * Bluetooth pairing are native, so the flow stays native (a staged [AlertDialog] sequence) rather
 * than a WebView overlay.
 *
 * Extracted from [MainActivity] (like [EventNotificationHostDelegate] / [TripExportController]) so
 * the dialog body and step-cursor bookkeeping live off the Activity's surface. The decision logic
 * (which steps are presented, the "Step N of M" math, completion persistence) lives in the injected
 * [OnboardingFlow]; collaborators are narrow lambdas so the controller is unit-testable without a
 * WebView and the Activity keeps a small, overridable seam.
 */
class SetupGuideController(
    private val context: Context,
    private val flow: () -> OnboardingFlow?,
    private val hasPairedAdapter: () -> Boolean,
    private val hasBluetoothPermission: () -> Boolean,
    private val hasLocationPermission: () -> Boolean,
    private val loggingActive: () -> Boolean,
    private val openBluetoothSettings: () -> Unit,
    private val ensureBluetoothPermission: () -> Boolean,
    private val ensureLocationPermission: () -> Boolean,
    private val startDemo: () -> Unit,
) {
    /** Live readiness the walkthrough adapts to: a satisfied step shows as done and is skipped. */
    private fun readState(): OnboardingFlow.State =
        OnboardingFlow.State(
            hasPairedAdapter = hasPairedAdapter(),
            hasBluetoothPermission = hasBluetoothPermission(),
            hasLocationPermission = hasLocationPermission(),
            loggingActive = loggingActive(),
        )

    // The step currently shown, or null when the walkthrough is not running. Written/read on the UI
    // thread only.
    private var currentStep: OnboardingFlow.Step? = null

    /** True while a runtime-permission result should advance the walkthrough rather than the
     *  connect-path messaging. */
    fun isActive(): Boolean = currentStep != null

    /**
     * Auto-shows the walkthrough once for a genuinely fresh install. Completion is persisted in
     * [OnboardingFlow], so this shows at most once.
     */
    fun maybeAutoShow() {
        val active = flow() ?: return
        if (active.shouldAutoShow(readState())) {
            active.markAutoShown()
            show(active)
        }
    }

    /** Re-opens the walkthrough on demand (the "Setup guide" affordance), ignoring the marker. */
    fun open() {
        flow()?.let { show(it) }
    }

    private fun show(active: OnboardingFlow) {
        // Open on the first step still presented against the live state (satisfied steps are
        // skipped). presentedSteps always ends with ALL_SET, so first() is safe.
        render(active, active.presentedSteps(readState()).first())
    }

    /**
     * Renders the dialog for [step]. "Step N of M" is computed against the current state, so it
     * reflects what is actually presented. "Skip for now" / an action advances to the next presented
     * step; a permission grant that lands while active resumes via [resumeAfterPermissionResult].
     */
    private fun render(
        active: OnboardingFlow,
        step: OnboardingFlow.Step,
    ) {
        currentStep = step
        val state = readState()
        val spec = SetupStepSpec.forStep(step)
        val progress = active.progressFor(state, step)
        val title = context.getString(spec.titleRes)
        val progressLabel = context.getString(R.string.setup_step_progress, progress.position, progress.total)
        try {
            val builder =
                AlertDialog
                    .Builder(context)
                    .setTitle("$title  ($progressLabel)")
                    .setMessage(spec.messageRes)
            if (step == OnboardingFlow.Step.ALL_SET) {
                builder.setPositiveButton(R.string.setup_done) { _, _ -> finish(active) }
                builder.setOnCancelListener { finish(active) }
            } else {
                builder.setPositiveButton(spec.actionRes) { _, _ -> runAction(active, step) }
                builder.setNeutralButton(R.string.setup_skip) { _, _ -> advance(active, step) }
                builder.setOnCancelListener { currentStep = null }
            }
            builder.show()
        } catch (ex: RuntimeException) {
            // Same contract as confirmBridgeAction: a dying Activity must not crash over a dialog.
            Log.w(MainActivity.TAG, "setup guide dialog failed to show", ex)
            currentStep = null
        }
    }

    /**
     * Advances past [from] to the next step still presented against the live state. Re-reads
     * readiness so a step the user just satisfied drops out; ALL_SET is always present, so the walk
     * always terminates on the completion screen.
     */
    private fun advance(
        active: OnboardingFlow,
        from: OnboardingFlow.Step,
    ) {
        if (currentStep == null) {
            return
        }
        val next =
            active
                .presentedSteps(readState())
                .firstOrNull { it.ordinal > from.ordinal }
                ?: OnboardingFlow.Step.ALL_SET
        render(active, next)
    }

    /** Performs the native side effect for [step], then advances the walkthrough. */
    private fun runAction(
        active: OnboardingFlow,
        step: OnboardingFlow.Step,
    ) {
        when (step) {
            OnboardingFlow.Step.PAIR_ADAPTER -> {
                openBluetoothSettings()
                // The user leaves for Bluetooth settings; advance so a freshly paired adapter (read
                // live on the next render) is reflected and they aren't stuck on the pair step.
                advance(active, step)
            }
            OnboardingFlow.Step.GRANT_BLUETOOTH ->
                // Reuse the connect-path permission request. true means it was already granted (no
                // prompt), so advance now; otherwise resumeAfterPermissionResult resumes once the
                // prompt resolves.
                if (ensureBluetoothPermission()) {
                    advance(active, step)
                }
            OnboardingFlow.Step.GRANT_LOCATION ->
                if (ensureLocationPermission()) {
                    advance(active, step)
                }
            OnboardingFlow.Step.CONNECT_OR_DEMO -> {
                // "Try the demo" is the optional skip: a synthetic session with no Bluetooth needed.
                startDemo()
                finish(active)
            }
            OnboardingFlow.Step.ALL_SET -> finish(active)
        }
    }

    /** Resumes after a permission prompt resolved, advancing from the parked step. */
    fun resumeAfterPermissionResult() {
        val active = flow() ?: return
        val step = currentStep ?: return
        advance(active, step)
    }

    private fun finish(active: OnboardingFlow) {
        currentStep = null
        active.markCompleted()
    }

    /** The strings backing one onboarding step's dialog. */
    private data class SetupStepSpec(
        val titleRes: Int,
        val messageRes: Int,
        val actionRes: Int,
    ) {
        companion object {
            fun forStep(step: OnboardingFlow.Step): SetupStepSpec =
                when (step) {
                    OnboardingFlow.Step.PAIR_ADAPTER ->
                        SetupStepSpec(
                            R.string.setup_pair_title,
                            R.string.setup_pair_message,
                            R.string.setup_pair_action,
                        )
                    OnboardingFlow.Step.GRANT_BLUETOOTH ->
                        SetupStepSpec(
                            R.string.setup_bluetooth_title,
                            R.string.setup_bluetooth_message,
                            R.string.setup_bluetooth_action,
                        )
                    OnboardingFlow.Step.GRANT_LOCATION ->
                        SetupStepSpec(
                            R.string.setup_location_title,
                            R.string.setup_location_message,
                            R.string.setup_location_action,
                        )
                    OnboardingFlow.Step.CONNECT_OR_DEMO ->
                        SetupStepSpec(
                            R.string.setup_connect_title,
                            R.string.setup_connect_message,
                            R.string.setup_connect_action,
                        )
                    OnboardingFlow.Step.ALL_SET ->
                        SetupStepSpec(
                            R.string.setup_complete_title,
                            R.string.setup_complete_message,
                            R.string.setup_done,
                        )
                }
        }
    }
}
