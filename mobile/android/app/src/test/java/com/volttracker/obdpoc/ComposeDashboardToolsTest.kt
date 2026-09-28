package com.volttracker.obdpoc

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.core.content.edit
import com.volttracker.obdpoc.ui.settings.SettingsCommand
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.atomic.AtomicInteger

/** The native Settings tools run through the shared helpers, with the restore guards in front. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeDashboardToolsTest {
    private lateinit var controller: ActivityController<ComposeDashboardActivity>
    private lateinit var activity: ComposeDashboardActivity

    @Before
    fun setUp() {
        while (ClassicDashboardPresence.isAlive()) ClassicDashboardPresence.onDestroyed()
        controller = Robolectric.buildActivity(ComposeDashboardActivity::class.java).create()
        activity = controller.get()
        activity.loggingProbe = { false }
    }

    @After
    fun tearDown() {
        while (ClassicDashboardPresence.isAlive()) ClassicDashboardPresence.onDestroyed()
        controller.destroy()
    }

    @Test
    fun restoreIsRefusedWhileASessionIsLogging() {
        activity.loggingProbe = { true }
        activity.runCommand(SettingsCommand.Restore(null))
        assertEquals(
            activity.getString(R.string.status_stop_logging_before_restore),
            ShadowToast.getTextOfLatestToast(),
        )
        assertNull("no file picker opens", shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun restoreIsHandedToTheClassicDashboardWhileItIsOpen() {
        ClassicDashboardPresence.onCreated()
        activity.runCommand(SettingsCommand.Restore(null))
        assertEquals(activity.getString(R.string.compose_restore_use_classic), ShadowToast.getTextOfLatestToast())
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started.component?.className)
    }

    @Test
    fun restoreOpensTheFilePickerWhenNothingElseHoldsTheDatabase() {
        activity.runCommand(SettingsCommand.Restore(null))
        val started = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.action)
    }

    @Test
    fun backUpOpensTheDatabaseAndTheDisclosureThenStoppingHandsTheDatabaseBack() {
        activity.runCommand(SettingsCommand.BackUp(null))
        assertNotNull("the privacy disclosure shows first", ShadowAlertDialog.getLatestAlertDialog())
        assertNotNull(activity.localStore)
        controller
            .start()
            .resume()
            .pause()
            .stop()
        assertNull("closed once the screen stops", activity.localStore)
    }

    @Test
    fun aShortEncryptionPassphraseIsRefused() {
        activity.runCommand(SettingsCommand.BackUp("short"))
        assertNotNull(ShadowToast.getTextOfLatestToast())
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test
    fun testConnectionAndWaitingNeedARememberedAdapter() {
        activity.runCommand(SettingsCommand.TestConnection)
        val noAdapter = activity.getString(R.string.status_no_remembered_adapter_yet)
        assertEquals(noAdapter, ShadowToast.getTextOfLatestToast())
        ShadowToast.reset()
        activity.runCommand(SettingsCommand.WaitForAdapter(on = true, minutes = 5))
        assertEquals(noAdapter, ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun waitingArmsAndCancelsWithARememberedAdapter() {
        activity
            .getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
            .edit { clear() }
        DeviceCatalog(activity, activity.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE))
            .remember("00:11:22:33:44:55", "OBDLink MX+")
        ShadowToast.reset()
        activity.runCommand(SettingsCommand.WaitForAdapter(on = true, minutes = 10))
        assertNull("no complaint when an adapter is remembered", ShadowToast.getTextOfLatestToast())
        activity.runCommand(SettingsCommand.WaitForAdapter(on = false, minutes = 10))
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun exportingWithNoTripsSaysSo() {
        activity.runCommand(SettingsCommand.ExportTrips)
        val expected = activity.getString(R.string.status_all_trips_export_empty)
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (ShadowToast.getTextOfLatestToast() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(expected, ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun progressAndTheBackupReceiptAreAcceptedWithoutAWebView() {
        activity.publishRestoreProgress(true, true, "Preparing backup", null, "ok", null, 0, 0, 0, 0, 40, -1, "backup")
        activity.publishDashboardPayload("setBackupReceipt", "{}")
        activity.publishDashboardPayload("somethingElse", null)
        activity.publishDeviceList()
        activity.publishStorageSummary()
        assertNotNull(activity.getStorageSummaryJson())
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val POLL_MS = 20L
    }

    @Test
    fun showingTheChargeTabReadsTheLoggedChargesIntoTheScreen() {
        activity.chargeHistoryReader = {
            JSONArray().put(
                JSONObject()
                    .put("startedAtMs", 1_000L)
                    .put("endedAtMs", 2_000L)
                    .put("chargerType", "level2")
                    .put("startSoc", 30)
                    .put("endSoc", 90)
                    .put("energyKwh", 8.4),
            )
        }
        activity.loadChargeHistory()
        waitFor {
            activity
                .uiState()
                .charge.sessions
                .isNotEmpty()
        }
        val sessions = activity.uiState().charge.sessions
        assertEquals(1, sessions.size)
        assertEquals("L2", sessions[0].level)
        assertEquals(8.4, sessions[0].energyKwh ?: Double.NaN, 1e-9)
    }

    @Test
    fun aFailedChargeReadLeavesTheListAloneAndCanBeRetried() {
        val reads = AtomicInteger()
        activity.chargeHistoryReader = {
            reads.incrementAndGet()
            throw IllegalStateException("database locked")
        }
        activity.loadChargeHistory()
        waitFor { reads.get() == 1 }
        assertTrue(
            activity
                .uiState()
                .charge.sessions
                .isEmpty(),
        )
        // Once the failed read has finished, the next visit reads again.
        waitFor {
            activity.loadChargeHistory()
            reads.get() >= 2
        }
        assertTrue(reads.get() >= 2)
    }

    @Test
    fun theChargeReadWaitsOutABackupOrRestore() {
        val reads = AtomicInteger()
        activity.chargeHistoryReader = {
            reads.incrementAndGet()
            JSONArray()
        }
        val lease = DatabaseOperationLease.tryAcquire("test") ?: error("lease expected")
        try {
            activity.loadChargeHistory()
            Thread.sleep(WAIT_MS / 50)
            assertEquals(0, reads.get())
        } finally {
            lease.close()
        }
    }

    /** Polls [done] (running main-thread posts between tries) until it holds or [WAIT_MS] passes. */
    private fun waitFor(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!done() && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
