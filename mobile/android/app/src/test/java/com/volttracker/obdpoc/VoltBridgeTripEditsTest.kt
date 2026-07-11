package com.volttracker.obdpoc

import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Exercises [VoltBridgeTripEdits] directly against the [DashboardHost] seam, using the shared
 * [DataBridgeRecordingActivity] fixture. Focus is the hide/restore confirm flow, the label and
 * favorite sanitizing/persistence, and the logging-gated action-confirmation contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoltBridgeTripEditsTest {
    private var controller: ActivityController<DataBridgeRecordingActivity>? = null
    private lateinit var activity: DataBridgeRecordingActivity
    private lateinit var tripEdits: VoltBridgeTripEdits

    @Before
    fun setUp() {
        val controller = Robolectric.buildActivity(DataBridgeRecordingActivity::class.java).create()
        this.controller = controller
        activity = controller.get()
        tripEdits = VoltBridgeTripEdits(activity)
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

    /** Drains the main looper so the `runOnUiThread` body the bridge posts actually executes. */
    private fun drain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    // ---- markTripNotTrip ---------------------------------------------------------------------

    @Test
    fun markTripNotTripConfirmsAndRefreshesStorageOnSuccess() {
        activity.store.markTripReturn = true

        tripEdits.markTripNotTrip("12:1000:2000")
        drain()

        assertEquals("Mark as not a trip?", activity.lastConfirmationTitle)
        assertEquals("12:1000:2000", activity.store.lastMarkedTripRouteKey)
        assertEquals("ready", activity.lastStatusState)
        assertFalse(activity.lastStatusBlocked)
        assertTrue(activity.storageSummaryCalls > 0)
    }

    @Test
    fun markTripNotTripReportsBlockedWhenStoreReportsNoChange() {
        activity.store.markTripReturn = false

        tripEdits.markTripNotTrip("12:1000:2000")
        drain()

        assertEquals("12:1000:2000", activity.store.lastMarkedTripRouteKey)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    @Test
    fun markTripNotTripReportsBlockedWhenStoreThrows() {
        activity.store.throwMarkTrip = true

        tripEdits.markTripNotTrip("12:1000:2000")
        drain()

        assertEquals("12:1000:2000", activity.store.lastMarkedTripRouteKey)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    @Test
    fun markTripNotTripRejectsBlankRouteKeyWithoutConfirming() {
        tripEdits.markTripNotTrip("   ")
        drain()

        assertNull("blank route key short-circuits before the confirm dialog", activity.lastConfirmationTitle)
        assertNull(activity.store.lastMarkedTripRouteKey)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    @Test
    fun restoreTripRefreshesStorageAndConfirmsSuccess() {
        activity.store.restoreTripReturn = true

        tripEdits.restoreTrip("12:1000:2000")
        drain()

        assertEquals("12:1000:2000", activity.store.lastRestoredTripRouteKey)
        assertEquals("ready", activity.lastStatusState)
        assertFalse(activity.lastStatusBlocked)
        assertTrue(activity.storageSummaryCalls > 0)
    }

    // ---- setTripLabel (M4) -------------------------------------------------------------------

    @Test
    fun setTripLabelPersistsAndRefreshesStorageOnSuccess() {
        activity.store.setTripLabelReturn = true

        tripEdits.setTripLabel("12:1000:2000", "Commute")
        drain()

        assertEquals("12:1000:2000", activity.store.lastLabelRouteKey)
        assertEquals("Commute", activity.store.lastLabelValue)
        assertEquals("ready", activity.lastStatusState)
        assertFalse(activity.lastStatusBlocked)
        assertTrue(activity.storageSummaryCalls > 0)
    }

    @Test
    fun setTripLabelClearsWithEmptyLabel() {
        activity.store.setTripLabelReturn = true

        tripEdits.setTripLabel("12:1000:2000", "   ")
        drain()

        // Whitespace-only label is sanitized to empty before reaching the store.
        assertEquals("", activity.store.lastLabelValue)
        assertEquals("ready", activity.lastStatusState)
    }

    @Test
    fun setTripLabelReportsBlockedWhenStoreReportsNoChange() {
        activity.store.setTripLabelReturn = false

        tripEdits.setTripLabel("12:1000:2000", "x")
        drain()

        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    @Test
    fun setTripLabelTrimsAndClampsBeforePersisting() {
        activity.store.setTripLabelReturn = true
        val longLabel = "  " + "x".repeat(com.volttracker.obdpoc.data.ObdTripLabels.MAX_LABEL_LEN + 20) + "  "

        tripEdits.setTripLabel("12:1000:2000", longLabel)
        drain()

        assertEquals(com.volttracker.obdpoc.data.ObdTripLabels.MAX_LABEL_LEN, activity.store.lastLabelValue!!.length)
        assertEquals("ready", activity.lastStatusState)
    }

    @Test
    fun setTripLabelReportsBlockedWhenStoreThrows() {
        activity.store.throwSetTripLabel = true

        tripEdits.setTripLabel("12:1000:2000", "x")
        drain()

        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    @Test
    fun setTripLabelRejectsBlankRouteKeyWithoutTouchingStore() {
        tripEdits.setTripLabel("   ", "x")
        drain()

        assertNull(activity.store.lastLabelRouteKey)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    // ---- setTripFavorite (M4) -----------------------------------------------------------------

    @Test
    fun setTripFavoritePersistsTrueAndRefreshesStorageOnSuccess() {
        activity.store.setTripFavoriteReturn = true

        tripEdits.setTripFavorite("12:1000:2000", true)
        drain()

        assertEquals("12:1000:2000", activity.store.lastFavoriteRouteKey)
        assertEquals(true, activity.store.lastFavoriteValue)
        assertEquals("ready", activity.lastStatusState)
        assertFalse(activity.lastStatusBlocked)
        assertTrue(activity.storageSummaryCalls > 0)
    }

    @Test
    fun setTripFavoritePersistsFalseAndReportsRemoved() {
        activity.store.setTripFavoriteReturn = true

        tripEdits.setTripFavorite("12:1000:2000", false)
        drain()

        assertEquals(false, activity.store.lastFavoriteValue)
        assertEquals("ready", activity.lastStatusState)
        assertFalse(activity.lastStatusBlocked)
    }

    @Test
    fun setTripFavoriteReportsBlockedWhenStoreRejectsOrThrows() {
        activity.store.setTripFavoriteReturn = false
        tripEdits.setTripFavorite("12:1000:2000", true)
        drain()
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)

        activity.lastStatusState = null
        activity.lastStatusBlocked = false
        activity.store.throwSetTripFavorite = true
        tripEdits.setTripFavorite("12:1000:2000", true)
        drain()
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    @Test
    fun setTripFavoriteRejectsBlankRouteKeyWithoutTouchingStore() {
        tripEdits.setTripFavorite("   ", true)
        drain()

        assertNull(activity.store.lastFavoriteRouteKey)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    // ---- action-confirmation logging gate ----------------------------------------------------

    @Test
    fun actionConfirmationIsSuppressedWhileLoggingButMutationStillApplies() {
        // publishActionConfirmation must NOT push a "ready"/"blocked" onto the shared status channel
        // while a drive is logging: that flips the live badge out of "logging" and primes a telemetry
        // reset. The underlying edit and the storage refresh must still happen.
        activity.store.setTripFavoriteReturn = true
        activity.loggingActive = true

        tripEdits.setTripFavorite("12:1000:2000", true)
        drain()

        assertEquals(true, activity.store.lastFavoriteValue)
        assertTrue("the edit still refreshes storage while logging", activity.storageSummaryCalls > 0)
        assertNull("the confirmation status must be suppressed while logging", activity.lastStatusState)
    }

    @Test
    fun actionConfirmationPublishesStatusWhenNotLogging() {
        // The mirror of the suppression case: with no active session the confirmation is published as
        // before, so users still get the "ready" feedback when they are not mid-drive.
        activity.store.setTripFavoriteReturn = true
        activity.loggingActive = false

        tripEdits.setTripFavorite("12:1000:2000", true)
        drain()

        assertEquals("ready", activity.lastStatusState)
        assertFalse(activity.lastStatusBlocked)
    }

    @Test
    fun publishActionConfirmationDirectlyGatesOnLoggingState() {
        // Direct coverage of the DashboardHost.publishActionConfirmation default method itself: while
        // logging it must publish nothing (so it can't disturb the live status stream); otherwise it
        // forwards state/detail/blocked to publishStatus verbatim.
        activity.loggingActive = true
        activity.publishActionConfirmation("ready", "Trip added to favorites.", false)
        assertNull("no status may be published while logging", activity.lastStatusState)

        activity.loggingActive = false
        activity.publishActionConfirmation("ready", "Trip added to favorites.", false)
        assertEquals("ready", activity.lastStatusState)
        assertEquals("Trip added to favorites.", activity.lastStatusDetail)
        assertFalse(activity.lastStatusBlocked)
    }
}
