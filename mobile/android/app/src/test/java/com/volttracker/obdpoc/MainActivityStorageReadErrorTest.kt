package com.volttracker.obdpoc

import android.content.Context
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.data.StorageSummaryRecord
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityStorageReadErrorTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(MainActivity::class.java).create()
        activity = controller.get()
        activity.localStore?.close()
        activity.localStore = ThrowingStore(activity)
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    @Test
    fun storageSummaryFailureReturnsStructuredError() {
        val payload = JSONObject(activity.getStorageSummaryJson())

        assertFalse(payload.optBoolean("ok", true))
        assertEquals("storage_summary_failed", payload.optString("error"))
        assertEquals("Could not read local storage summary.", payload.optString("message"))
    }

    @Test
    fun storageDetailsFailureReturnsStructuredError() {
        val payload = JSONObject(activity.getStorageDetailsJson())

        assertFalse(payload.optBoolean("ok", true))
        assertEquals("storage_details_failed", payload.optString("error"))
        assertEquals("Could not read local storage details.", payload.optString("message"))
    }

    @Test
    fun tripsFailureReturnsStructuredErrorInsteadOfEmptyArray() {
        val payload = JSONObject(activity.getTripsJson())

        assertFalse(payload.optBoolean("ok", true))
        assertEquals("trips_read_failed", payload.optString("error"))
        assertEquals("Could not read logged trips.", payload.optString("message"))
    }

    @Test
    fun insightsFailureReturnsStructuredError() {
        val payload = JSONObject(activity.getInsightsJson())

        assertFalse(payload.optBoolean("ok", true))
        assertEquals("insights_read_failed", payload.optString("error"))
        assertEquals("Could not read vehicle insights.", payload.optString("message"))
    }

    private class ThrowingStore(
        context: Context,
    ) : ObdLocalStore(context) {
        override fun getStorageSummaryRecord(): StorageSummaryRecord = throw RuntimeException("summary boom")

        override fun getStorageOverviewRecord(): StorageSummaryRecord = throw RuntimeException("overview boom")

        override fun getStorageDetailsJson(): JSONObject = throw RuntimeException("details boom")

        override fun getTripsJson(limit: Int): JSONArray = throw RuntimeException("trips boom")

        override fun getInsightsJson(): JSONObject = throw RuntimeException("insights boom")
    }
}
