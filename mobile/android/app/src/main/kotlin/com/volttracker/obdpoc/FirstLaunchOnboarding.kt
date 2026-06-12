package com.volttracker.obdpoc

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Decides whether the one-time first-launch explainer should appear. A fresh user landing on
 * the dashboard with no adapter ever connected gets one dialog pointing them at Android's
 * Bluetooth pairing screen (and the demo); anyone with adapter history, an active session, or
 * who has already seen it never sees it again. UI lives in [MainActivity]; this class is the
 * decision so it can be unit-tested without a WebView.
 */
class FirstLaunchOnboarding(
    private val prefs: SharedPreferences,
) {
    fun shouldShow(
        hasAdapterHistory: Boolean,
        loggingActive: Boolean,
    ): Boolean = !loggingActive && !hasAdapterHistory && !prefs.getBoolean(KEY_SHOWN, false)

    fun markShown() {
        prefs.edit { putBoolean(KEY_SHOWN, true) }
    }

    companion object {
        const val KEY_SHOWN = "onboarding_shown"
    }
}
