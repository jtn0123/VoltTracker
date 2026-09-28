package com.volttracker.obdpoc

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.core.content.edit
import com.volttracker.obdpoc.ui.settings.SettingsCommand
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
