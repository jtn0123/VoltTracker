package com.volttracker.obdpoc

import android.os.Looper
import org.json.JSONObject
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
 * Exercises [VoltBridgeBackups] directly against the [DashboardHost]/[BridgeStateProvider] seam,
 * using the shared [DataBridgeRecordingActivity] fixture. Focus is the debug-bundle export, the
 * backup/restore forwarders' blocked paths, the passphrase sanitizing, and the destructive
 * clear-stored-data flow (logging gate, confirm dialog, store failure). (The happy-path backup
 * flows route through the concrete DataBackup/BackupController collaborators that only exist after
 * a full Activity onCreate, so they are covered by their own controller tests.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoltBridgeBackupsTest {
    private var controller: ActivityController<DataBridgeRecordingActivity>? = null
    private lateinit var activity: DataBridgeRecordingActivity
    private lateinit var backups: VoltBridgeBackups

    @Before
    fun setUp() {
        val controller = Robolectric.buildActivity(DataBridgeRecordingActivity::class.java).create()
        this.controller = controller
        activity = controller.get()
        backups = VoltBridgeBackups(activity, activity)
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

    // ---- backup forwarders ------------------------------------------------------------------

    @Test
    fun exportDebugBundleUsesCurrentBridgeStateAndStorageJson() {
        activity.appStatePayload = JSONObject().put("state", "ready").toString()
        activity.storageJson = JSONObject().put("sessions", 3).toString()

        val payload = JSONObject(backups.exportDebugBundle())
        val content = JSONObject(payload.getString("content"))

        assertTrue(payload.getBoolean("ok"))
        assertEquals("ready", content.getJSONObject("appState").getString("state"))
        assertEquals(3, content.getJSONObject("storage").getInt("sessions"))
    }

    @Test
    fun backupForwardersMarshalThroughControllerBlockedPaths() {
        activity.loggingActive = true
        backups.shareBackup()
        drain()
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)

        activity.lastStatusState = null
        activity.lastStatusBlocked = false
        activity.loggingActive = false
        backups.shareEncryptedBackup("  short  ")
        drain()
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)

        activity.lastStatusState = null
        activity.lastStatusBlocked = false
        activity.loggingActive = true
        backups.restoreBackup()
        drain()
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)

        activity.lastStatusState = null
        activity.lastStatusBlocked = false
        activity.loggingActive = false
        backups.restoreEncryptedBackup("   ")
        drain()
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    @Test
    fun allWhitespacePassphraseIsRejectedWithAClearErrorOnShareAndRestore() {
        // E3: an all-spaces passphrase used to be silently trimmed to empty; now the bridge rejects
        // it outright with a message that names the problem, before the controller is involved.
        backups.shareEncryptedBackup("   ")
        drain()
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
        assertTrue(
            "the error must explain the all-spaces rejection, got: ${activity.lastStatusDetail}",
            activity.lastStatusDetail!!.contains("only spaces"),
        )

        activity.lastStatusState = null
        activity.lastStatusDetail = null
        activity.lastStatusBlocked = false
        backups.restoreEncryptedBackup("\t \n")
        drain()
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
        assertTrue(activity.lastStatusDetail!!.contains("only spaces"))
    }

    // ---- clearStoredData ---------------------------------------------------------------------

    @Test
    fun clearStoredDataConfirmsClearsStoreAndReportsReadyWhenIdle() {
        activity.loggingActive = false

        backups.clearStoredData()
        drain()

        assertEquals("Clear stored data?", activity.lastConfirmationTitle)
        assertEquals(1, activity.store.clearAllDataCalls)
        assertEquals("ready", activity.lastStatusState)
        assertFalse(activity.lastStatusBlocked)
        assertTrue("storage summary should be refreshed after a clear", activity.storageSummaryCalls > 0)
    }

    @Test
    fun clearStoredDataBlocksWithoutConfirmingOrTouchingStoreWhileLogging() {
        activity.loggingActive = true

        backups.clearStoredData()
        drain()

        assertNull("logging gate short-circuits before the confirm dialog", activity.lastConfirmationTitle)
        assertEquals(0, activity.store.clearAllDataCalls)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    @Test
    fun clearStoredDataBlocksIfLoggingStartsAfterConfirmation() {
        activity.loggingActive = false
        activity.activateLoggingBeforeConfirmed = true

        backups.clearStoredData()
        drain()

        assertEquals("Clear stored data?", activity.lastConfirmationTitle)
        assertEquals(0, activity.store.clearAllDataCalls)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    @Test
    fun clearStoredDataReportsBlockedWhenStoreClearThrows() {
        activity.store.throwClearAllData = true

        backups.clearStoredData()
        drain()

        assertEquals(1, activity.store.clearAllDataCalls)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
        assertEquals(0, activity.storageSummaryCalls)
    }
}
