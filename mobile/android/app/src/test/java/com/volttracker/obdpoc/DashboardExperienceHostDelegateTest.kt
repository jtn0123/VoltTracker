package com.volttracker.obdpoc

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardExperienceHostDelegateTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity
    private var logging = true
    private var appStatePublishes = 0

    @Before
    fun setUp() {
        RuntimeEnvironment
            .getApplication()
            .getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        activity = controller.get()
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    @Test
    fun keepAwakeOnlyAppliesToResumedLiveDriveAndMap() {
        val prefs = activity.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        val delegate =
            DashboardExperienceHostDelegate(
                activity,
                { prefs },
                { logging },
                { appStatePublishes += 1 },
            )

        delegate.setKeepScreenAwakeEnabled(true)
        assertFalse(hasKeepScreenFlag())

        delegate.onResume()
        assertTrue(hasKeepScreenFlag())

        delegate.setActiveDashboardView("settings")
        assertFalse(hasKeepScreenFlag())

        delegate.setActiveDashboardView("map")
        assertTrue(hasKeepScreenFlag())

        logging = false
        delegate.onLoggingStateChanged()
        assertFalse(hasKeepScreenFlag())

        logging = true
        delegate.onLoggingStateChanged()
        assertTrue(hasKeepScreenFlag())

        delegate.onPause()
        assertFalse(hasKeepScreenFlag())
        assertEquals(1, appStatePublishes)
    }

    @Test
    fun experienceStateRoundTripsBothNativePreferences() {
        val prefs = activity.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        val delegate = DashboardExperienceHostDelegate(activity, { prefs }, { false }, {})

        delegate.setKeepScreenAwakeEnabled(true)
        delegate.setTripSummaryEnabled(true)

        val state = JSONObject(delegate.getDashboardExperienceStateJson())
        assertTrue(state.getBoolean("keepScreenAwake"))
        assertTrue(state.getBoolean("tripSummary"))
    }

    @Test
    fun activeViewRoundTripsForActivityRecreationButRejectsUnknownViews() {
        val prefs = activity.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        val first = DashboardExperienceHostDelegate(activity, { prefs }, { false }, {})
        first.setActiveDashboardView("settings")

        val restored = DashboardExperienceHostDelegate(activity, { prefs }, { false }, {})
        restored.restoreActiveView(first.activeViewForSave())
        assertEquals("settings", restored.activeViewForSave())

        restored.restoreActiveView("removed-tab")
        assertEquals("drive", restored.activeViewForSave())
    }

    private fun hasKeepScreenFlag(): Boolean =
        activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
}
