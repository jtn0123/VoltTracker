package com.volttracker.obdpoc

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Exercises [VoltBridgeTripExports] directly against the [DashboardHost.exportTripFromBridge]
 * seam, using the shared [DataBridgeRecordingActivity] fixture. Focus is the per-format routing
 * (`gpx`/`csv`/`csv_all`/`csv_charges`/`card`) and the input clamps each forwarder applies before
 * the host seam sees the value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoltBridgeTripExportsTest {
    private var controller: ActivityController<DataBridgeRecordingActivity>? = null
    private lateinit var activity: DataBridgeRecordingActivity
    private lateinit var tripExports: VoltBridgeTripExports

    @Before
    fun setUp() {
        val controller = Robolectric.buildActivity(DataBridgeRecordingActivity::class.java).create()
        this.controller = controller
        activity = controller.get()
        tripExports = VoltBridgeTripExports(activity)
    }

    @After
    fun tearDown() {
        activity.store.close()
        try {
            controller?.destroy()
        } catch (ignored: RuntimeException) {
            // Activity teardown can race in Robolectric; it must not fail the assertions above.
        }
    }

    @Test
    fun tripExportForwardersTrimClampAndUseExpectedFormats() {
        val longRouteKey = "  " + "r".repeat(BRIDGE_MAX_LABEL_LEN + 20) + "  "

        assertTrue(JSONObject(tripExports.exportTripGpx(longRouteKey)).optBoolean("ok"))
        assertEquals("gpx", activity.lastExportFormat)
        assertEquals(BRIDGE_MAX_LABEL_LEN, activity.lastExportRouteKey!!.length)

        tripExports.exportTripCsv(" 12:1000:2000 ")
        assertEquals("csv", activity.lastExportFormat)
        assertEquals("12:1000:2000", activity.lastExportRouteKey)

        tripExports.exportAllTripsCsv()
        assertEquals("csv_all", activity.lastExportFormat)
        assertNull(activity.lastExportRouteKey)

        tripExports.exportChargeSessionsCsv(" 0.21 ")
        assertEquals("csv_charges", activity.lastExportFormat)
        assertEquals("0.21", activity.lastExportRouteKey)
    }

    @Test
    fun shareTripCardUsesTheWiderDetailClamp() {
        val cardJson = "x".repeat(BRIDGE_MAX_LABEL_LEN + 50)

        tripExports.shareTripCard(cardJson)

        assertEquals("card", activity.lastExportFormat)
        assertEquals(cardJson.length, activity.lastExportRouteKey!!.length)
    }
}
