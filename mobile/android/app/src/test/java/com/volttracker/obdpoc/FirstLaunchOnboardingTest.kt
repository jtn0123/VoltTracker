package com.volttracker.obdpoc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FirstLaunchOnboardingTest {
    private fun newPrefs() =
        RuntimeEnvironment
            .getApplication()
            .getSharedPreferences("onboarding-test-${System.nanoTime()}", android.content.Context.MODE_PRIVATE)

    @Test
    fun freshUserWithNoHistoryShouldSeeOnboarding() {
        val onboarding = FirstLaunchOnboarding(newPrefs())
        assertTrue(onboarding.shouldShow(hasAdapterHistory = false, loggingActive = false))
    }

    @Test
    fun adapterHistorySuppressesOnboarding() {
        val onboarding = FirstLaunchOnboarding(newPrefs())
        assertFalse(onboarding.shouldShow(hasAdapterHistory = true, loggingActive = false))
    }

    @Test
    fun activeSessionSuppressesOnboarding() {
        val onboarding = FirstLaunchOnboarding(newPrefs())
        assertFalse(onboarding.shouldShow(hasAdapterHistory = false, loggingActive = true))
    }

    @Test
    fun markShownIsSticky() {
        val prefs = newPrefs()
        val onboarding = FirstLaunchOnboarding(prefs)
        onboarding.markShown()
        assertFalse(onboarding.shouldShow(hasAdapterHistory = false, loggingActive = false))
        // A new instance over the same prefs stays suppressed (persistence, not in-memory state).
        assertFalse(FirstLaunchOnboarding(prefs).shouldShow(hasAdapterHistory = false, loggingActive = false))
    }
}
