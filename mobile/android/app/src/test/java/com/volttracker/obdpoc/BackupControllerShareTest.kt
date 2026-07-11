package com.volttracker.obdpoc

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.core.content.FileProvider
import com.volttracker.obdpoc.data.DatabaseMerger
import com.volttracker.obdpoc.data.ObdDbMaintenanceStore
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.data.ObdStoreMaintenance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.io.File
import java.lang.reflect.Modifier
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Drives the share-out side of [BackupController]: the disclosure dialog
 * ([BackupController.showShareDisclosure]), the plaintext / encrypted backup-and-share flow
 * ([BackupController.performBackupAndShare] via [BackupController.launchShare] and
 * [BackupController.launchEncryptedShare]), and the encrypted restore picker
 * ([BackupController.launchEncryptedRestorePicker]).
 *
 * These surfaces fire native [AlertDialog]s and `ACTION_SEND` / `ACTION_OPEN_DOCUMENT` Intents that
 * Playwright can't reach (they live outside the WebView). A direct (inline) executor plus a real
 * [DataBackup] and store let the build → share-intent chain run synchronously on the test thread,
 * so each test asserts the observable effect (dialog text, started Intent, status) rather than
 * internals.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupControllerShareTest {
    private lateinit var controller: ActivityController<HarnessActivity>
    private lateinit var activity: HarnessActivity

    @Before
    fun setUp() {
        // Robolectric hands each test method a fresh app whose cacheDir differs, but FileProvider
        // caches its PathStrategy in a static map keyed by authority (seeded from the FIRST test
        // that calls getUriForFile). A later test's backup lives under a different cacheDir, so the
        // stale strategy throws "Failed to find configured root" and the share is silently swallowed
        // ("Could not open the share sheet"). Reset the cache so each test rebuilds it for its own
        // cacheDir. This is a Robolectric-only concern; on a real device the cacheDir is stable.
        resetFileProviderPathCache()
        controller = Robolectric.buildActivity(HarnessActivity::class.java).create()
        activity = controller.get()
        activity.localStore!!.clearAllData()
    }

    /** Clears FileProvider's static authority→PathStrategy cache (robust to the field name). */
    private fun resetFileProviderPathCache() {
        for (field in FileProvider::class.java.declaredFields) {
            if (Modifier.isStatic(field.modifiers) && MutableMap::class.java.isAssignableFrom(field.type)) {
                field.isAccessible = true
                (field.get(null) as? MutableMap<*, *>)?.clear()
            }
        }
    }

    @After
    fun tearDown() {
        try {
            controller.destroy()
        } catch (ignored: RuntimeException) {
            // WebView teardown can race Robolectric background work; not part of these assertions.
        }
    }

    /** Seeds one finished session so the backup file has real content to copy. */
    private fun seedOneSession() {
        val id = activity.localStore!!.startSession("obd", "AA:BB", "Adapter")
        activity.localStore!!.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 4000L, "")
    }

    /** Drains the main looper so the post-backup `runOnUiThread` block (share intent) runs. */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun latestDialog(): AlertDialog {
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("expected an AlertDialog to be shown", dialog)
        return dialog
    }

    /**
     * Returns the `ACTION_SEND` Intent that was started. Production wraps it in a chooser via
     * [Intent.createChooser], so the share intent lives under [Intent.EXTRA_INTENT]; fall back to
     * the started Intent itself if the platform didn't wrap it.
     */
    private fun startedShareIntent(): Intent {
        val started = activity.lastStartedIntent
        assertNotNull(
            "a share/chooser Intent should have been started; " +
                "status=${activity.lastState}/${activity.lastDetail}",
            started,
        )
        if (started!!.action == Intent.ACTION_SEND) {
            return started
        }
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        val inner = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull("chooser must wrap the ACTION_SEND intent", inner)
        return inner!!
    }

    // --- showShareDisclosure -------------------------------------------------

    @Test
    fun shareDisclosureShowsTheFullPrivacyMessage() {
        seedOneSession()

        activity.backupController!!.launchShare()

        val dialog = latestDialog()
        val shadow = shadowOf(dialog)
        assertEquals("Share Volt Tracker backup", shadow.title.toString())
        assertEquals(
            activity.backupController!!.shareDisclosureMessage(),
            shadow.message.toString(),
        )
        assertTrue(
            "encrypted backup disclosure should set passphrase-strength expectations",
            shadow.message.toString().contains("at least 8 characters"),
        )
        assertTrue(
            "encrypted backup disclosure should explain lost passphrases cannot be recovered",
            shadow.message.toString().contains("cannot recover it if it is lost"),
        )
        assertEquals(
            "Share anyway",
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).text.toString(),
        )
        assertEquals(
            "Cancel",
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).text.toString(),
        )
        // Disclosure only — nothing shared until the user confirms.
        assertNull(activity.lastStartedIntent)
    }

    @Test
    fun confirmingDisclosureProceedsToShare() {
        seedOneSession()

        activity.backupController!!.launchShare()
        latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle()

        val share = startedShareIntent()
        assertEquals(Intent.ACTION_SEND, share.action)
        assertEquals("ready", activity.lastState)
        assertTrue(
            "post-share status should confirm the backup is ready, got: " + activity.lastDetail,
            activity.lastDetail!!.contains("Backup ready"),
        )
        val prefs = activity.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        assertTrue(prefs.getLong(BackupController.PREF_LAST_BACKUP_AT_MS, 0L) > 0L)
        assertTrue(prefs.contains(BackupController.PREF_LAST_BACKUP_TRIPS))
    }

    @Test
    fun cancellingDisclosureSharesNothing() {
        seedOneSession()

        activity.backupController!!.launchShare()
        latestDialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        settle()

        assertNull(activity.lastStartedIntent)
        assertEquals("ready", activity.lastState)
        assertEquals("Backup cancelled.", activity.lastDetail)
    }

    @Test
    fun cancellingDisclosureWithBackButtonSharesNothingAndAllowsNextRequest() {
        seedOneSession()

        activity.backupController!!.launchShare()
        latestDialog().cancel()
        settle()

        assertNull(activity.lastStartedIntent)
        assertEquals("ready", activity.lastState)
        assertEquals("Backup cancelled.", activity.lastDetail)

        activity.backupController!!.launchEncryptedShare("hunter22")

        assertEquals("Share Volt Tracker backup", shadowOf(latestDialog()).title.toString())
    }

    @Test
    fun cancellingEncryptedDisclosureClearsInFlightState() {
        seedOneSession()

        activity.backupController!!.launchEncryptedShare("hunter22")
        latestDialog().cancel()
        settle()

        assertNull(activity.lastStartedIntent)
        assertEquals("ready", activity.lastState)
        assertEquals("Backup cancelled.", activity.lastDetail)

        activity.backupController!!.launchShare()

        assertEquals("Share Volt Tracker backup", shadowOf(latestDialog()).title.toString())
    }

    @Test
    fun shareDisclosureIsSkippedWhenActivityIsFinishing() {
        seedOneSession()
        activity.finish()

        activity.backupController!!.launchShare()

        assertNull(activity.lastStartedIntent)
        assertNull(activity.lastState)
        assertNull(activity.lastDetail)
    }

    @Test
    fun secondBackupRequestIsBlockedWhileDisclosureIsOpen() {
        seedOneSession()

        activity.backupController!!.launchShare()
        activity.backupController!!.launchEncryptedShare("hunter22")

        assertNull(activity.lastStartedIntent)
        assertEquals("blocked", activity.lastState)
        assertEquals("Backup already in progress.", activity.lastDetail)

        latestDialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        activity.backupController!!.launchEncryptedShare("hunter22")

        assertEquals("Share Volt Tracker backup", shadowOf(latestDialog()).title.toString())
    }

    @Test
    fun secondPlainBackupRequestIsBlockedWhileDisclosureIsOpen() {
        seedOneSession()

        activity.backupController!!.launchShare()
        val firstDialog = latestDialog()
        activity.backupController!!.launchShare()

        assertNull(activity.lastStartedIntent)
        assertEquals("blocked", activity.lastState)
        assertEquals("Backup already in progress.", activity.lastDetail)

        firstDialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        activity.backupController!!.launchShare()

        assertEquals("Share Volt Tracker backup", shadowOf(latestDialog()).title.toString())
    }

    // --- launchShare / performBackupAndShare ---------------------------------

    @Test
    fun launchShareWhileLoggingIsBlockedAndShowsNoDialog() {
        activity.loggingActive = true

        activity.backupController!!.launchShare()

        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertNull(activity.lastStartedIntent)
        assertEquals("blocked", activity.lastState)
        assertEquals("Stop logging before creating a backup.", activity.lastDetail)
    }

    @Test
    fun launchEncryptedShareWhileLoggingIsBlockedAndShowsNoDialog() {
        activity.loggingActive = true

        activity.backupController!!.launchEncryptedShare("hunter22")

        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertNull(activity.lastStartedIntent)
        assertEquals("blocked", activity.lastState)
        assertEquals("Stop logging before creating a backup.", activity.lastDetail)
    }

    @Test
    fun confirmingDisclosureAfterLoggingStartsBlocksBeforeBuildingBackup() {
        seedOneSession()

        activity.backupController!!.launchShare()
        activity.loggingActive = true
        latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle()

        assertNull(activity.lastStartedIntent)
        assertEquals("blocked", activity.lastState)
        assertEquals("Stop logging before creating a backup.", activity.lastDetail)

        activity.loggingActive = false
        activity.backupController!!.launchEncryptedShare("hunter22")
        latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle()

        assertEquals(Intent.ACTION_SEND, startedShareIntent().action)
        assertEquals("ready", activity.lastState)
        assertTrue(activity.lastDetail!!.contains("Encrypted backup ready"))
    }

    @Test
    fun plaintextShareIntentCarriesTheBackupFileUriAndMime() {
        seedOneSession()

        activity.backupController!!.launchShare()
        latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle()

        val share = startedShareIntent()
        assertEquals("application/octet-stream", share.type)
        val uri = share.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertNotNull("share intent must carry the backup file Uri", uri)
        assertTrue(
            "FileProvider Uri should point at the project authority, got: $uri",
            uri.toString().contains(activity.packageName + ".fileprovider"),
        )
        val subject = share.getStringExtra(Intent.EXTRA_SUBJECT)
        assertNotNull("share intent should name the backup file", subject)
        assertTrue(
            "plaintext backups use the .db extension, got: $subject",
            subject!!.endsWith(".db"),
        )
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
            share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }

    @Test
    fun backupBuildFailureClearsBusyProgressAndStatus() {
        activity.localStore?.close()
        activity.localStore = null

        activity.backupController!!.launchShare()
        latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle()

        assertNull(activity.lastStartedIntent)
        assertEquals("blocked", activity.lastState)
        assertEquals("Could not create the backup file.", activity.lastDetail)
        assertEquals("Backup failed", activity.lastProgressTitle)
        assertEquals("Could not create the backup file.", activity.lastProgressDetail)
        assertEquals(false, activity.lastProgressBusy)
        assertEquals("blocked", activity.lastProgressTone)
        assertEquals("backup", activity.lastProgressOperation)
    }

    @Test
    fun shareSheetFailureClearsBusyProgressAndStatus() {
        seedOneSession()
        activity.rejectStartActivity = true

        activity.backupController!!.launchShare()
        latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle()

        assertNull(activity.lastStartedIntent)
        assertEquals("blocked", activity.lastState)
        assertEquals("Could not open the share sheet.", activity.lastDetail)
        assertEquals("Backup failed", activity.lastProgressTitle)
        assertEquals("Could not open the share sheet.", activity.lastProgressDetail)
        assertEquals(false, activity.lastProgressBusy)
        assertEquals("blocked", activity.lastProgressTone)
        assertEquals("backup", activity.lastProgressOperation)

        activity.rejectStartActivity = false
        activity.backupController!!.launchEncryptedShare("hunter22")

        assertEquals("Share Volt Tracker backup", shadowOf(latestDialog()).title.toString())
    }

    // --- integrity warning surfacing ------------------------------------------

    @Test
    fun shareWithHealthyDatabaseDoesNotWarnAboutIntegrity() {
        seedOneSession()

        activity.backupController!!.launchShare()
        latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle()

        assertEquals("ready", activity.lastState)
        assertTrue(activity.lastDetail!!.contains("Backup ready"))
        assertFalse(
            "a healthy database must not surface an integrity warning, got: " + activity.lastDetail,
            activity.lastDetail!!.contains("integrity check"),
        )
    }

    @Test
    fun shareWithCorruptDatabaseStillSharesAndWarnsInFinalStatus() {
        seedOneSession()
        activity.localStore!!.close()
        activity.localStore = CorruptQuickCheckStore(activity)

        activity.backupController!!.launchShare()
        latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle()

        // A possibly damaged backup beats no backup: the share must still go out…
        val share = startedShareIntent()
        assertEquals(Intent.ACTION_SEND, share.action)
        // …but the final success status must carry the integrity warning instead of
        // leaving it buried in logcat.
        assertEquals("ready", activity.lastState)
        assertTrue(activity.lastDetail!!.contains("Backup ready"))
        assertTrue(
            "final status should surface the quick_check warning, got: " + activity.lastDetail,
            activity.lastDetail!!.contains("integrity check reported problems"),
        )
    }

    @Test
    fun encryptedShareWithCorruptDatabaseWarnsInFinalStatus() {
        seedOneSession()
        activity.localStore!!.close()
        activity.localStore = CorruptQuickCheckStore(activity)

        activity.backupController!!.launchEncryptedShare("hunter22")
        latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle()

        val share = startedShareIntent()
        assertTrue(
            "encrypted backups use the .vtdb extension",
            share.getStringExtra(Intent.EXTRA_SUBJECT)!!.endsWith(".vtdb"),
        )
        assertEquals("ready", activity.lastState)
        assertTrue(activity.lastDetail!!.contains("Encrypted backup ready"))
        assertTrue(
            "final encrypted-share status should surface the quick_check warning, got: " + activity.lastDetail,
            activity.lastDetail!!.contains("integrity check reported problems"),
        )
    }

    @Test
    fun rejectedBackupWorkerClearsBusyProgress() {
        seedOneSession()
        activity.backupController =
            BackupController(activity, DataBackup(activity), RejectingExecutorService())

        activity.backupController!!.launchShare()
        latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle()

        assertNull(activity.lastStartedIntent)
        assertEquals("blocked", activity.lastState)
        assertEquals("Could not start the backup worker.", activity.lastDetail)
        assertEquals("Backup failed", activity.lastProgressTitle)
        assertEquals("Could not start the backup worker.", activity.lastProgressDetail)
        assertEquals(false, activity.lastProgressBusy)
        assertEquals("blocked", activity.lastProgressTone)
        assertEquals("backup", activity.lastProgressOperation)
    }

    @Test
    fun runtimeThrowingBackupWorkerClearsBusyProgress() {
        seedOneSession()
        activity.backupController =
            BackupController(activity, DataBackup(activity), RuntimeThrowingExecutorService())

        activity.backupController!!.launchShare()
        latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle()

        assertNull(activity.lastStartedIntent)
        assertEquals("blocked", activity.lastState)
        assertEquals("Could not start the backup worker.", activity.lastDetail)
        assertEquals("Backup failed", activity.lastProgressTitle)
        assertEquals("Could not start the backup worker.", activity.lastProgressDetail)
        assertEquals(false, activity.lastProgressBusy)
        assertEquals("blocked", activity.lastProgressTone)
        assertEquals("backup", activity.lastProgressOperation)
    }

    // --- launchEncryptedShare ------------------------------------------------

    @Test
    fun encryptedShareWithoutPassphraseIsBlocked() {
        activity.backupController!!.launchEncryptedShare("   ")

        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertNull(activity.lastStartedIntent)
        assertEquals("blocked", activity.lastState)
        assertEquals("Enter a backup passphrase with at least 8 characters.", activity.lastDetail)
    }

    @Test
    fun encryptedShareProducesEncryptedBackupAndShareIntent() {
        seedOneSession()

        activity.backupController!!.launchEncryptedShare("hunter22")
        latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle()

        val share = startedShareIntent()
        assertEquals(Intent.ACTION_SEND, share.action)
        assertEquals("application/octet-stream", share.type)
        val subject = share.getStringExtra(Intent.EXTRA_SUBJECT)
        assertNotNull(subject)
        assertTrue(
            "encrypted backups use the .vtdb extension, got: $subject",
            subject!!.endsWith(".vtdb"),
        )
        assertEquals("ready", activity.lastState)
        assertTrue(
            "encrypted share status should mention the encrypted backup, got: " + activity.lastDetail,
            activity.lastDetail!!.contains("Encrypted backup ready"),
        )
    }

    // --- launchEncryptedRestorePicker ----------------------------------------

    @Test
    fun encryptedRestorePickerWithoutPassphraseIsBlocked() {
        activity.backupController!!.launchEncryptedRestorePicker("")

        assertNull(activity.launchedRestoreIntent)
        assertEquals("blocked", activity.lastState)
        assertEquals("Enter the backup passphrase first.", activity.lastDetail)
    }

    @Test
    fun encryptedRestorePickerAcceptsAShortLegacyPassphrase() {
        // The 8-character minimum gates CREATING backups only. Restore must accept any non-empty
        // passphrase so backups created before the minimum existed stay restorable.
        activity.backupController!!.launchEncryptedRestorePicker("old1")

        assertNotNull(activity.launchedRestoreIntent)
    }

    @Test
    fun encryptedRestorePickerLaunchesSafIntentWhenIdle() {
        activity.backupController!!.launchEncryptedRestorePicker("hunter22")

        val started = activity.launchedRestoreIntent
        assertNotNull(started)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started!!.action)
        assertEquals("application/octet-stream", started.type)
        assertTrue(
            "the picker should accept openable documents",
            started.categories?.contains(Intent.CATEGORY_OPENABLE) == true,
        )
    }

    /** Real store whose `PRAGMA quick_check` is stubbed to always report corruption. */
    private class CorruptQuickCheckStore(
        context: Context,
    ) : ObdLocalStore(context) {
        override val dbMaintenance: ObdDbMaintenanceStore =
            FixedQuickCheckMaintenance(
                ObdStoreMaintenance.IntegrityResult(
                    false,
                    listOf("row 7 missing from index idx_samples"),
                ),
            )
    }

    /** [ObdDbMaintenanceStore] fake serving a canned `PRAGMA quick_check` result. */
    private class FixedQuickCheckMaintenance(
        private val result: ObdStoreMaintenance.IntegrityResult,
    ) : ObdDbMaintenanceStore {
        override fun quickCheck(maxProblems: Int): ObdStoreMaintenance.IntegrityResult = result

        override fun mergeFrom(
            donorDbFile: File?,
            progressListener: DatabaseMerger.ProgressListener?,
        ): DatabaseMerger.MergeResult = throw UnsupportedOperationException("unused")

        override fun runStartupMaintenance(keepDays: Int): Int = throw UnsupportedOperationException("unused")
    }

    /** Inline executor: build/share work runs synchronously so the test can assert outcomes. */
    private class DirectExecutorService : AbstractExecutorService() {
        @Volatile
        private var shutdown = false

        override fun execute(command: Runnable) {
            command.run()
        }

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): List<Runnable> {
            shutdown = true
            return emptyList()
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown

        override fun awaitTermination(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = true
    }

    /** Executor that rejects immediately so runBackground() exercises its !started branch. */
    private class RejectingExecutorService : AbstractExecutorService() {
        override fun execute(command: Runnable): Unit = throw RejectedExecutionException("worker rejected")

        override fun shutdown() = Unit

        override fun shutdownNow(): List<Runnable> = emptyList()

        override fun isShutdown(): Boolean = false

        override fun isTerminated(): Boolean = false

        override fun awaitTermination(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = false
    }

    /** Executor that throws a generic runtime failure so runBackground() covers its broad catch. */
    private class RuntimeThrowingExecutorService : AbstractExecutorService() {
        override fun execute(command: Runnable): Unit = throw IllegalStateException("worker failed")

        override fun shutdown() = Unit

        override fun shutdownNow(): List<Runnable> = emptyList()

        override fun isShutdown(): Boolean = false

        override fun isTerminated(): Boolean = false

        override fun awaitTermination(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = false
    }

    /** Minimal MainActivity that wires a real store + inline executor and captures status/intents. */
    class HarnessActivity : MainActivity() {
        @JvmField var loggingActive = false

        @JvmField var launchedRestoreIntent: Intent? = null

        @JvmField var lastStartedIntent: Intent? = null

        @JvmField var lastState: String? = null

        @JvmField var lastDetail: String? = null

        @JvmField var lastProgressTitle: String? = null

        @JvmField var lastProgressDetail: String? = null

        @JvmField var lastProgressTone: String? = null

        @JvmField var lastProgressBusy: Boolean? = null

        @JvmField var lastProgressOperation: String? = null

        @JvmField var rejectStartActivity = false

        override fun onCreate(savedInstanceState: Bundle?) {
            localStore = ObdLocalStore(this)
            backupController =
                BackupController(this, DataBackup(this), DirectExecutorService())
        }

        override fun isLoggingActive(): Boolean = loggingActive

        override fun launchRestoreFilePicker(intent: Intent) {
            launchedRestoreIntent = intent
        }

        // Capture the share/chooser Intent directly instead of routing through Robolectric's
        // real startActivity, whose content-URI permission-grant path is fragile when the same
        // backup file name recurs across tests in one JVM (it throws on the second share).
        override fun startActivity(intent: Intent?) {
            if (rejectStartActivity) {
                throw IllegalStateException("share sheet unavailable")
            }
            lastStartedIntent = intent
        }

        override fun publishStatus(
            state: String?,
            detail: String?,
            blocked: Boolean,
        ) {
            lastState = state
            lastDetail = detail
        }

        override fun publishDeviceList() {
            // No WebView in the harness.
        }

        override fun publishStorageSummary() {
            // No WebView in the harness.
        }

        override fun publishRestoreProgress(
            visible: Boolean,
            busy: Boolean,
            title: String?,
            detail: String?,
            tone: String?,
            phase: String?,
            bytesDone: Long,
            bytesTotal: Long,
            rowsDone: Long,
            rowsTotal: Long,
            percent: Int,
            etaSeconds: Long,
            operation: String?,
        ) {
            lastProgressTitle = title
            lastProgressDetail = detail
            lastProgressTone = tone
            lastProgressBusy = busy
            lastProgressOperation = operation
        }
    }
}
