package com.volttracker.obdpoc

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/**
 * The one-time first-launch explainer must appear on the first dashboard handshake of a fresh
 * install, and never again once dismissed (the marker is written when the dialog shows).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityOnboardingTest {
    private val controllers = mutableListOf<ActivityController<QuietActivity>>()

    @After
    fun tearDown() {
        controllers.forEach { runCatching { it.destroy() } }
    }

    private fun launch(): QuietActivity {
        val controller = Robolectric.buildActivity(QuietActivity::class.java).create()
        controllers += controller
        return controller.get()
    }

    @Test
    fun firstDashboardReadyShowsOnboardingDialogExactlyOnce() {
        val activity = launch()
        activity.onDashboardReady()
        assertNotNull("fresh install must show the onboarding dialog", ShadowAlertDialog.getLatestAlertDialog())

        // Relaunch: the persisted marker suppresses any further onboarding.
        ShadowAlertDialog.reset()
        val second = launch()
        second.onDashboardReady()
        assertNull("onboarding must not reappear after it was shown", ShadowAlertDialog.getLatestAlertDialog())
    }

    /** Publish seams neutralized so the handshake under test stays synchronous and quiet. */
    class QuietActivity : MainActivity() {
        override fun publishDeviceList() {}

        override fun publishStorageSummary() {}

        override fun markStorageSummaryDirty() {}

        override fun publishStatus(
            state: String?,
            detail: String?,
            blocked: Boolean,
        ) {}

        override fun onAdapterStatusForReadyNotify(status: JSONObject?) {}
    }
}
