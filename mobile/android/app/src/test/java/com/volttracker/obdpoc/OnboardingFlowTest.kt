package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OnboardingFlowTest {
    private fun newPrefs() =
        RuntimeEnvironment
            .getApplication()
            .getSharedPreferences("onboarding-flow-${System.nanoTime()}", android.content.Context.MODE_PRIVATE)

    private fun freshState() =
        OnboardingFlow.State(
            hasPairedAdapter = false,
            hasBluetoothPermission = false,
            hasLocationPermission = false,
            loggingActive = false,
        )

    @Test
    fun freshUserStartsAtPairAdapter() {
        val flow = OnboardingFlow(newPrefs())
        assertTrue(flow.shouldAutoShow(freshState()))
        assertEquals(OnboardingFlow.Step.PAIR_ADAPTER, flow.presentedSteps(freshState()).first())
    }

    @Test
    fun pairedAdapterMarksPairStepDoneAndSkipsIt() {
        val flow = OnboardingFlow(newPrefs())
        val state = freshState().copy(hasPairedAdapter = true)

        val pairStatus = flow.stepsFor(state).first { it.step == OnboardingFlow.Step.PAIR_ADAPTER }
        assertTrue("a paired adapter should mark the pair step done", pairStatus.done)
        val presented = flow.presentedSteps(state)
        assertEquals("the pair step should be skipped", OnboardingFlow.Step.GRANT_BLUETOOTH, presented.first())
        assertFalse("a satisfied step is not presented", presented.contains(OnboardingFlow.Step.PAIR_ADAPTER))
    }

    @Test
    fun grantedBluetoothPermissionMarksThatStepDoneAndSkipsIt() {
        val flow = OnboardingFlow(newPrefs())
        // Adapter paired + BT granted -> location is the first thing left to act on.
        val state = freshState().copy(hasPairedAdapter = true, hasBluetoothPermission = true)

        val btStatus = flow.stepsFor(state).first { it.step == OnboardingFlow.Step.GRANT_BLUETOOTH }
        assertTrue("granted Bluetooth permission must show as done/skipped", btStatus.done)
        assertEquals(OnboardingFlow.Step.GRANT_LOCATION, flow.presentedSteps(state).first())
    }

    @Test
    fun fullyReadyUserHasNothingToGuideAndDoesNotAutoShow() {
        val flow = OnboardingFlow(newPrefs())
        val state =
            OnboardingFlow.State(
                hasPairedAdapter = true,
                hasBluetoothPermission = true,
                hasLocationPermission = true,
                loggingActive = false,
            )

        // Connect is still the call-to-action, plus the completion screen.
        assertEquals(
            listOf(OnboardingFlow.Step.CONNECT_OR_DEMO, OnboardingFlow.Step.ALL_SET),
            flow.presentedSteps(state),
        )
        assertFalse("every prerequisite is satisfied, so don't auto-show", flow.shouldAutoShow(state))
    }

    @Test
    fun activeSessionSuppressesAutoShow() {
        val flow = OnboardingFlow(newPrefs())
        assertFalse(flow.shouldAutoShow(freshState().copy(loggingActive = true)))
    }

    @Test
    fun completionPersistsAndSuppressesAutoShow() {
        val prefs = newPrefs()
        val flow = OnboardingFlow(prefs)
        flow.markCompleted()
        assertFalse("completion must suppress the auto-show", flow.shouldAutoShow(freshState()))
        // A new instance over the same prefs stays suppressed (persistence, not in-memory state).
        assertFalse(OnboardingFlow(prefs).shouldAutoShow(freshState()))
    }

    @Test
    fun autoShownMarkerPersistsAndSuppressesFutureAutoShows() {
        val prefs = newPrefs()
        val flow = OnboardingFlow(prefs)
        flow.markAutoShown()

        assertFalse("auto-show should be one-shot even if the user dismisses it", flow.shouldAutoShow(freshState()))
        assertFalse(OnboardingFlow(prefs).shouldAutoShow(freshState()))
    }

    @Test
    fun progressCountsOnlyPresentedStepsForFreshUser() {
        val flow = OnboardingFlow(newPrefs())
        val state = freshState()
        // Fresh: pair, bluetooth, location, connect (4 actionable) + ALL_SET = 5 presented steps.
        val pair = flow.progressFor(state, OnboardingFlow.Step.PAIR_ADAPTER)
        assertEquals(1, pair.position)
        assertEquals(5, pair.total)

        val allSet = flow.progressFor(state, OnboardingFlow.Step.ALL_SET)
        assertEquals(5, allSet.position)
        assertEquals(5, allSet.total)
    }

    @Test
    fun progressTotalShrinksWhenSomeStepsAreAlreadySatisfied() {
        val flow = OnboardingFlow(newPrefs())
        // Adapter already paired -> pair step is skipped, so location is presented as step 1 of a
        // smaller flow (bluetooth, location, connect + ALL_SET = 4).
        val state = freshState().copy(hasPairedAdapter = true)
        val bluetooth = flow.progressFor(state, OnboardingFlow.Step.GRANT_BLUETOOTH)
        assertEquals(1, bluetooth.position)
        assertEquals(4, bluetooth.total)
    }
}
