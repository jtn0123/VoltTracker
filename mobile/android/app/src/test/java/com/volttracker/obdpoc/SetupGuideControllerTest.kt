package com.volttracker.obdpoc

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import androidx.activity.ComponentActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/**
 * Drives [SetupGuideController]'s step-cursor bookkeeping directly through its injected readiness
 * lambdas (report item I4), rather than only through the MainActivity path. Pins: each readiness
 * state presents/skips the right first step; "Skip for now" advances the cursor; a permission grant
 * that lands mid-flow re-reads state and resumes past the satisfied step; tapping the demo CTA and
 * the completion Done both persist completion; and the auto-show / re-open contract.
 *
 * The controller renders native [android.app.AlertDialog]s, so it is hosted on a real Robolectric
 * Activity (needed for the dialog theme); the title carries the step + "Step N of M" progress the
 * cursor computes, which is what these assertions read via [ShadowAlertDialog].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetupGuideControllerTest {
    private lateinit var activity: Activity
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var flow: OnboardingFlow

    // Mutable readiness the injected lambdas read, so a test can flip a permission mid-flow.
    private var paired = false
    private var bluetooth = false
    private var location = false
    private var logging = false

    // Recorded side effects.
    private var openBluetoothSettingsCalls = 0
    private var startDemoCalls = 0

    // Permission-ensure return values: true = already granted (advance now); false = a prompt was
    // launched (the controller parks until resumeAfterPermissionResult).
    private var bluetoothEnsureGranted = false
    private var locationEnsureGranted = false

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        prefs =
            RuntimeEnvironment
                .getApplication()
                .getSharedPreferences("setup-guide-test-${System.nanoTime()}", Context.MODE_PRIVATE)
        flow = OnboardingFlow(prefs)
    }

    @After
    fun tearDown() {
        ShadowAlertDialog.reset()
    }

    private fun newController() =
        SetupGuideController(
            context = activity,
            flow = { flow },
            hasPairedAdapter = { paired },
            hasBluetoothPermission = { bluetooth },
            hasLocationPermission = { location },
            loggingActive = { logging },
            openBluetoothSettings = { openBluetoothSettingsCalls += 1 },
            ensureBluetoothPermission = {
                if (bluetoothEnsureGranted) bluetooth = true
                bluetoothEnsureGranted
            },
            ensureLocationPermission = {
                if (locationEnsureGranted) location = true
                locationEnsureGranted
            },
            startDemo = { startDemoCalls += 1 },
        )

    private fun latestDialog() = ShadowAlertDialog.getLatestAlertDialog()

    private fun latestTitle(): String = shadowOf(latestDialog()).title.toString()

    private fun clickPositive() {
        latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle()
    }

    private fun clickSkip() {
        latestDialog().getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
        settle()
    }

    private fun settle() {
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun freshStateOpensOnPairStepAsStepOneOfFive() {
        val controller = newController()
        controller.open()

        assertNotNull("open() must surface the walkthrough", latestDialog())
        assertTrue("a fresh device opens on the pair step: ${latestTitle()}", latestTitle().contains("Pair your OBD"))
        assertTrue("fresh flow has 5 presented steps: ${latestTitle()}", latestTitle().contains("Step 1 of 5"))
        assertTrue("the cursor is active while a step is shown", controller.isActive())
    }

    @Test
    fun satisfiedLeadingStepsAreSkippedSoTheFirstShownIsTheFirstPendingOne() {
        paired = true
        bluetooth = true
        val controller = newController()
        controller.open()

        // Pair + Bluetooth are satisfied, so the first presented step is location, in a shorter flow
        // (location, connect + completion = 3).
        assertTrue("the first unsatisfied step is location: ${latestTitle()}", latestTitle().contains("location"))
        assertTrue(
            "the total shrinks to reflect skipped steps: ${latestTitle()}",
            latestTitle().contains("Step 1 of 3"),
        )
    }

    @Test
    fun skipForNowAdvancesTheCursorToTheNextPresentedStep() {
        val controller = newController()
        controller.open()
        assertTrue(latestTitle().contains("Pair your OBD"))

        clickSkip()
        assertTrue(
            "skip advances from pair to the bluetooth step: ${latestTitle()}",
            latestTitle().contains("Bluetooth"),
        )

        clickSkip()
        assertTrue("skip advances to the location step: ${latestTitle()}", latestTitle().contains("location"))
    }

    @Test
    fun pairActionOpensBluetoothSettingsThenAdvances() {
        val controller = newController()
        controller.open()
        assertTrue(latestTitle().contains("Pair your OBD"))

        clickPositive()
        assertEquals("the pair action opens Bluetooth settings", 1, openBluetoothSettingsCalls)
        // The user left for settings; the flow advances so they aren't stuck on the pair step.
        assertTrue("after the pair action the flow advances: ${latestTitle()}", latestTitle().contains("Bluetooth"))
    }

    @Test
    fun anAlreadyGrantedBluetoothActionAdvancesImmediately() {
        // Start past pairing so the Bluetooth step is first.
        paired = true
        bluetoothEnsureGranted = true
        val controller = newController()
        controller.open()
        assertTrue("opens on the bluetooth step: ${latestTitle()}", latestTitle().contains("Bluetooth"))

        clickPositive()
        // ensureBluetoothPermission returned true (already granted), so the controller advances now;
        // re-reading state drops the now-satisfied bluetooth step, landing on location.
        assertTrue(
            "an already-granted permission advances at once: ${latestTitle()}",
            latestTitle().contains("location"),
        )
    }

    @Test
    fun aPermissionGrantThatLandsMidFlowResumesPastTheSatisfiedStep() {
        paired = true
        // The Bluetooth action launches a prompt (returns false), so the controller parks on it.
        bluetoothEnsureGranted = false
        val controller = newController()
        controller.open()
        assertTrue("opens on the bluetooth step: ${latestTitle()}", latestTitle().contains("Bluetooth"))

        clickPositive()
        // The prompt was launched; the cursor stays on the Bluetooth step (no advance yet).
        assertTrue(
            "the flow parks on bluetooth until the prompt resolves: ${latestTitle()}",
            latestTitle().contains("Bluetooth"),
        )
        assertTrue(controller.isActive())

        // The user grants the permission; MainActivity calls back into resumeAfterPermissionResult,
        // which re-reads live state and advances past the now-satisfied step.
        bluetooth = true
        controller.resumeAfterPermissionResult()
        assertTrue(
            "resuming after the grant advances to location: ${latestTitle()}",
            latestTitle().contains("location"),
        )
    }

    @Test
    fun demoCtaStartsDemoAndPersistsCompletion() {
        // Everything satisfied except the connect/demo CTA, so open() lands on it.
        paired = true
        bluetooth = true
        location = true
        val controller = newController()
        controller.open()
        assertTrue("opens on the connect/demo CTA: ${latestTitle()}", latestTitle().contains("demo"))

        clickPositive()
        assertEquals("the demo CTA starts a demo session", 1, startDemoCalls)
        assertFalse("the cursor is cleared once the flow finishes", controller.isActive())
        assertTrue(
            "finishing via the demo CTA persists completion",
            prefs.getBoolean(OnboardingFlow.KEY_COMPLETED, false),
        )
    }

    @Test
    fun skippingThroughToCompletionAndTappingDonePersistsCompletion() {
        val controller = newController()
        controller.open()

        // Skip every actionable step (pair, bluetooth, location, connect) to reach the completion screen.
        repeat(4) { clickSkip() }
        assertTrue("skipping reaches the completion screen: ${latestTitle()}", latestTitle().contains("all set"))
        assertFalse(
            "completion isn't persisted until Done is tapped",
            prefs.getBoolean(OnboardingFlow.KEY_COMPLETED, false),
        )

        clickPositive()
        assertTrue("tapping Done persists completion", prefs.getBoolean(OnboardingFlow.KEY_COMPLETED, false))
        assertFalse(controller.isActive())
    }

    @Test
    fun maybeAutoShowShowsForAFreshUserButNotAfterCompletion() {
        val first = newController()
        first.maybeAutoShow()
        assertNotNull("a fresh user auto-shows the guide", latestDialog())

        flow.markCompleted()
        ShadowAlertDialog.reset()

        val second = newController()
        second.maybeAutoShow()
        assertNull("a completed user does not auto-show again", latestDialog())
    }

    @Test
    fun maybeAutoShowIsOneShotEvenWhenDismissed() {
        val first = newController()
        first.maybeAutoShow()
        assertNotNull("a fresh user auto-shows the guide", latestDialog())

        ShadowAlertDialog.reset()

        val second = newController()
        second.maybeAutoShow()
        assertNull("auto-show should not nag again after the first automatic presentation", latestDialog())

        second.open()
        assertNotNull("manual Setup guide remains available", latestDialog())
    }

    @Test
    fun maybeAutoShowIsSuppressedWhileASessionIsLogging() {
        logging = true
        newController().maybeAutoShow()
        assertNull("an active logging session suppresses the auto-show", latestDialog())
    }

    @Test
    fun resumeWithoutAnActiveStepIsANoOp() {
        // Never opened: there is no parked step, so a stray permission callback must not throw or
        // surface a dialog.
        newController().resumeAfterPermissionResult()
        assertNull(latestDialog())
    }
}
