package com.volttracker.obdpoc

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Bundle
import android.os.Looper
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
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

/**
 * A1 — exercises the trivial branches of [TroubleshooterBridge] so the new code added by the
 * round-6 refactor is covered by the suite. Heavier paths (the test-connection probe, the
 * notify-when-ready handler loop, the system Notification post) rely on system-service plumbing
 * that's expensive to fake; we leave those for an integration test on a real device. What we DO
 * cover here is every defensive early-return path, since those are the lines most likely to be
 * touched by a future refactor and the cheapest to pin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TroubleshooterBridgeTest {
    private var controller: ActivityController<HarnessActivity>? = null
    private var bridge: TroubleshooterBridge? = null

    @Before
    fun setUp() {
        val controller = Robolectric.buildActivity(HarnessActivity::class.java).create()
        this.controller = controller
        val activity = controller.get()
        wipe(File(activity.filesDir, "obd-logs"))
        wipe(File(activity.filesDir, "app-log"))
        wipe(File(activity.cacheDir, "diagnostics"))
        resetFileProviderPathCache()
        SessionSummaryStore.resetForTests()
        ShadowAlertDialog.reset()
        bridge = TroubleshooterBridge(activity)
    }

    @After
    fun tearDown() {
        bridge?.shutdown()
        val controller = this.controller
        if (controller != null) {
            val activity = controller.get()
            wipe(File(activity.filesDir, "obd-logs"))
            wipe(File(activity.filesDir, "app-log"))
            wipe(File(activity.cacheDir, "diagnostics"))
            SessionSummaryStore.resetForTests()
            ShadowAlertDialog.reset()
            controller.destroy()
        }
    }

    @Test
    fun forceStopPackage_rejectsNullAndEmpty() {
        assertFalse(bridge!!.forceStopPackage(null))
        assertFalse(bridge!!.forceStopPackage(""))
    }

    @Test
    fun forceStopPackage_rejectsUninstalledPackages() {
        // No app on the test JVM has this name, so the PackageManager.NameNotFoundException
        // branch fires and the bridge returns false honestly.
        assertFalse(bridge!!.forceStopPackage("org.prowl.torque"))
    }

    @Test
    fun forceStopPackage_rejectsInstalledPackagesOutsideObdAllowlist() {
        assertFalse(bridge!!.forceStopPackage(controller!!.get().packageName))
    }

    @Test
    fun forceStopPackage_returnsFalseWhenActivityManagerIsUnavailable() {
        val activity = controller!!.get()
        shadowOf(activity.packageManager)
            .installPackage(PackageInfo().apply { packageName = "org.prowl.torque" })
        activity.activityManagerUnavailable = true

        assertFalse(bridge!!.forceStopPackage("org.prowl.torque"))
    }

    @Test
    fun forceStopPackage_killsInstalledKnownObdPackage() {
        val activity = controller!!.get()
        shadowOf(activity.packageManager)
            .installPackage(PackageInfo().apply { packageName = "org.prowl.torque" })

        assertTrue(bridge!!.forceStopPackage("org.prowl.torque"))
    }

    @Test
    fun getRecentSessionsJson_zeroOrNegativeReturnsEmptyArray() {
        // n <= 0 short-circuits before touching SessionSummaryStore. Both paths must yield
        // a well-formed empty JSON array (the dashboard JSON.parse path keys off this exact
        // shape — anything else and the troubleshooter modal crashes during render).
        assertEquals("[]", bridge!!.getRecentSessionsJson(0))
        assertEquals("[]", bridge!!.getRecentSessionsJson(-3))
        // Sanity: confirm the string is valid JSON the dashboard could actually consume.
        assertEquals(0, JSONArray(bridge!!.getRecentSessionsJson(0)).length())
    }

    @Test
    fun getRecentSessionsJson_readsNewestSummariesFirst() {
        val activity = controller!!.get()
        val store = SessionSummaryStore.getInstance(activity.filesDir)
        store.recordStart(1_000L, "Old adapter", "AA:BB:CC:DD:EE:01")
        store.recordEnd(1_500L, SessionSummary.OUTCOME_SUCCESS, 3, null)
        store.recordStart(2_000L, "New adapter", "AA:BB:CC:DD:EE:02")
        store.recordEnd(2_500L, SessionSummary.OUTCOME_FAILED, 0, "TimeoutException")
        SessionSummaryStore.resetForTests()

        val recent = JSONArray(bridge!!.getRecentSessionsJson(2))

        assertEquals(2, recent.length())
        assertEquals("New adapter", recent.getJSONObject(0).optString("adapter"))
        assertEquals("AA:BB:CC:DD:EE:02", recent.getJSONObject(0).optString("adapterAddress"))
        assertEquals(SessionSummary.OUTCOME_FAILED, recent.getJSONObject(0).optString("outcome"))
        assertEquals("TimeoutException", recent.getJSONObject(0).optString("failureClass"))
        assertEquals("Old adapter", recent.getJSONObject(1).optString("adapter"))
    }

    @Test
    fun getRecentSessionsJson_returnsEmptyArrayWhenStoreCannotOpen() {
        val activity = controller!!.get()
        activity.filesDirFailure = IllegalStateException("files unavailable")
        try {
            assertEquals("[]", bridge!!.getRecentSessionsJson(3))
        } finally {
            activity.filesDirFailure = null
        }
    }

    @Test
    fun cancelRetry_neverThrows() {
        // No bound service in the test JVM — the bridge swallows IllegalStateException
        // and returns cleanly so the UI tap doesn't propagate a crash.
        bridge!!.cancelRetry()
    }

    @Test
    fun cancelRetry_neverThrows_whenServiceDispatchIsDenied() {
        val activity = controller!!.get()
        activity.startServiceFailure = SecurityException("foreground service dispatch denied")

        bridge!!.cancelRetry()

        assertEquals(1, activity.startServiceCalls)
    }

    @Test
    fun openBluetoothSettings_neverThrows_evenIfActivityResolveFails() {
        // Robolectric provides the Settings intent path so this should succeed without
        // throwing on either branch.
        bridge!!.openBluetoothSettings()
    }

    @Test
    fun openBluetoothSettingsPublishesBlockedWhenActivityLaunchFails() {
        val activity = controller!!.get()
        activity.startActivityFailure = ActivityNotFoundException("settings unavailable")

        bridge!!.openBluetoothSettings()

        assertEquals("blocked", activity.lastState)
        assertTrue(activity.lastDetail?.contains("Bluetooth settings") == true)
    }

    @Test
    fun shareDiagnostics_neverThrows_whenNothingToShare() {
        // DiagnosticsShareIntent returns null when no logs exist yet; bridge surfaces a
        // status broadcast rather than throwing. We just want the no-throw guarantee here.
        bridge!!.shareDiagnostics()
    }

    @Test
    fun shareDiagnosticsBuildsZipAndShowsDisclosure() {
        val activity = controller!!.get()
        val logDir = File(activity.filesDir, "obd-logs")
        assertTrue(logDir.mkdirs() || logDir.isDirectory)
        File(logDir, "session-1000-obd.jsonl").writeText("{\"state\":\"connected\"}\n")

        bridge!!.shareDiagnostics()

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("diagnostics zip share should show disclosure", dialog)
        assertEquals(activity.getString(R.string.dialog_diagnostics_title), shadowOf(dialog).title.toString())
        assertNull("chooser must wait for confirmation", activity.lastStartedIntent)
    }

    @Test
    fun shareDiagnosticsReportsBuildFailureWhenCacheIsUnavailable() {
        val activity = controller!!.get()
        val logDir = File(activity.filesDir, "obd-logs")
        assertTrue(logDir.mkdirs() || logDir.isDirectory)
        File(logDir, "session-1000-obd.jsonl").writeText("{\"state\":\"connected\"}\n")
        activity.cacheDirFailure = IllegalStateException("cache unavailable")
        try {
            bridge!!.shareDiagnostics()
        } finally {
            activity.cacheDirFailure = null
        }

        assertEquals("blocked", activity.lastState)
        assertEquals("Could not build the diagnostics share.", activity.lastDetail)
    }

    @Test
    fun shareDiagnosticsDigestBuildsTextFileAndShowsDisclosure() {
        val activity = controller!!.get()

        bridge!!.shareDiagnosticsDigest()

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("diagnostics digest share should show disclosure", dialog)
        assertEquals(activity.getString(R.string.dialog_diagnostics_title), shadowOf(dialog).title.toString())
        assertNull("chooser must wait for confirmation", activity.lastStartedIntent)
    }

    @Test
    fun shareDiagnosticsDigestReportsBuildFailureWhenCacheIsUnavailable() {
        val activity = controller!!.get()
        activity.cacheDirFailure = IllegalStateException("cache unavailable")
        try {
            bridge!!.shareDiagnosticsDigest()
        } finally {
            activity.cacheDirFailure = null
        }

        assertEquals("blocked", activity.lastState)
        assertEquals("Could not build the diagnostics share.", activity.lastDetail)
    }

    @Test
    fun diagnosticsShareShowsDisclosureBeforeChooser() {
        bridge!!.showDiagnosticsDisclosure(Intent(Intent.ACTION_SEND))

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("diagnostics share should show a disclosure first", dialog)
        assertTrue(dialog!!.isShowing)
        assertEquals(
            "Share anyway",
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)!!.text.toString(),
        )
        assertTrue(
            "message should disclose redaction before sharing",
            bridge!!.diagnosticsDisclosureMessage().contains("redacted"),
        )
    }

    @Test
    fun diagnosticsShareNegativeClickCancelsWithoutChooser() {
        val activity = controller!!.get()
        bridge!!.showDiagnosticsDisclosure(Intent(Intent.ACTION_SEND))

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("diagnostics share should show a disclosure first", dialog)
        dialog!!.getButton(AlertDialog.BUTTON_NEGATIVE)!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(activity.lastStartedIntent)
        assertEquals("ready", activity.lastState)
        assertEquals("Diagnostics share cancelled.", activity.lastDetail)
    }

    @Test
    fun diagnosticsShareDialogCancelPublishesCancelledStatus() {
        val activity = controller!!.get()
        bridge!!.showDiagnosticsDisclosure(Intent(Intent.ACTION_SEND))

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("diagnostics share should show a disclosure first", dialog)
        dialog!!.cancel()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(activity.lastStartedIntent)
        assertEquals("ready", activity.lastState)
        assertEquals("Diagnostics share cancelled.", activity.lastDetail)
    }

    @Test
    fun diagnosticsDisclosureSkipsFinishingActivity() {
        val activity = controller!!.get()
        activity.finish()
        ShadowAlertDialog.reset()

        bridge!!.showDiagnosticsDisclosure(Intent(Intent.ACTION_SEND))

        assertNull(
            "finishing activities must not show a diagnostics disclosure dialog",
            ShadowAlertDialog.getLatestAlertDialog(),
        )
    }

    @Test
    fun diagnosticsSharePositiveClickStartsChooser() {
        val activity = controller!!.get()
        bridge!!.showDiagnosticsDisclosure(Intent(Intent.ACTION_SEND))

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("diagnostics share should show a disclosure first", dialog)
        dialog!!.getButton(AlertDialog.BUTTON_POSITIVE)!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val chooser = activity.lastStartedIntent
        assertNotNull("confirming diagnostics disclosure should launch a chooser", chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
    }

    @Test
    fun diagnosticsSharePositiveClickReportsChooserLaunchFailure() {
        val activity = controller!!.get()
        activity.startActivityFailure = ActivityNotFoundException("no chooser")
        bridge!!.showDiagnosticsDisclosure(Intent(Intent.ACTION_SEND))

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("diagnostics share should show a disclosure first", dialog)
        dialog!!.getButton(AlertDialog.BUTTON_POSITIVE)!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("blocked", activity.lastState)
        assertTrue(
            "detail should explain that diagnostics could not be shared, got: ${activity.lastDetail}",
            activity.lastDetail?.contains("share diagnostics") == true,
        )
    }

    @Test
    fun cancelAdapterReadyNotify_isANoopWhenNothingScheduled() {
        // Calling cancel before schedule must not initialize the handler nor throw.
        bridge!!.cancelAdapterReadyNotify()
    }

    @Test
    fun clearPendingTestConnectionStop_isANoopWhenNothingScheduled() {
        bridge!!.clearPendingTestConnectionStop()
    }

    @Test
    fun scheduleAdapterReadyNotify_zeroMinutesExpiresImmediately() {
        // Schedules with a deadline at "now" — the tick should observe the expired
        // deadline on its first run and tear itself down. The important assertion is
        // that the schedule fires + clears without any throws.
        bridge!!.scheduleAdapterReadyNotify(0)
        shadowOf(Looper.getMainLooper()).idle()
        bridge!!.cancelAdapterReadyNotify()
    }

    @Test
    fun shutdown_drainsBothHandlersWithoutThrowing() {
        // Schedule both, then shut down. Without the drain, posted callbacks could fire
        // on a destroyed Activity context and crash the next session.
        bridge!!.scheduleAdapterReadyNotify(1)
        bridge!!.clearPendingTestConnectionStop()
        bridge!!.shutdown()
        // Calling shutdown again must remain idempotent.
        bridge!!.shutdown()
    }

    @Test
    fun onAdapterStatusForReadyNotify_doesNothingWhenScheduleInactive() {
        // No schedule active → must NOT post a notification, even on "connected".
        bridge!!.onAdapterStatusForReadyNotify(status("connected", 14.2))
        bridge!!.onAdapterStatusForReadyNotify(status("idle", 14.2))
        bridge!!.onAdapterStatusForReadyNotify(null)
    }

    @Test
    fun statusMeansAdapterReadyForNotification_requiresConnectedAwakeVoltage() {
        assertTrue(
            "connected with DC-DC voltage should be notification-ready",
            TroubleshooterBridge.statusMeansAdapterReadyForNotification(
                status("connected", 14.2),
            ),
        )
        assertFalse(
            "car-off voltage must not fire an adapter-ready notification",
            TroubleshooterBridge.statusMeansAdapterReadyForNotification(
                status("connected", 12.4),
            ),
        )
        assertFalse(
            "exact threshold voltage must not fire an adapter-ready notification",
            TroubleshooterBridge.statusMeansAdapterReadyForNotification(
                status("connected", 13.0),
            ),
        )
        assertFalse(
            "adapter-only connection without voltage is not enough",
            TroubleshooterBridge.statusMeansAdapterReadyForNotification(
                statusWithoutVoltage("connected"),
            ),
        )
        assertFalse(
            TroubleshooterBridge.statusMeansAdapterReadyForNotification(
                status("connecting", 14.2),
            ),
        )
    }

    /** Builds a [HarnessActivity] override of [MainActivity] that records the last published status. */
    class HarnessActivity : MainActivity() {
        var lastState: String? = null
        var lastDetail: String? = null
        var startActivityFailure: RuntimeException? = null
        var lastStartedIntent: Intent? = null
        var startServiceFailure: RuntimeException? = null
        var startServiceCalls = 0
        var activityManagerUnavailable = false
        var filesDirFailure: RuntimeException? = null
        var cacheDirFailure: RuntimeException? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            deviceCatalog = DeviceCatalog(this, getSharedPreferences("troubleshooter-test", 0))
        }

        override fun getSystemService(name: String): Any? {
            if (activityManagerUnavailable && name == Context.ACTIVITY_SERVICE) {
                return null
            }
            return super.getSystemService(name)
        }

        override fun getFilesDir(): File {
            filesDirFailure?.let { throw it }
            return super.getFilesDir()
        }

        override fun getCacheDir(): File {
            cacheDirFailure?.let { throw it }
            return super.getCacheDir()
        }

        override fun startActivity(intent: Intent?) {
            startActivityFailure?.let { throw it }
            lastStartedIntent = intent
        }

        override fun startService(service: Intent?): ComponentName? {
            startServiceCalls += 1
            startServiceFailure?.let { throw it }
            return super.startService(service)
        }

        override fun publishStatus(
            state: String?,
            detail: String?,
            blocked: Boolean,
        ) {
            lastState = state
            lastDetail = detail
        }
    }

    private companion object {
        private fun status(
            state: String,
            lastVoltage: Double,
        ): JSONObject =
            try {
                JSONObject().put("state", state).put("lastVoltage", lastVoltage)
            } catch (ex: JSONException) {
                throw AssertionError(ex)
            }

        private fun statusWithoutVoltage(state: String): JSONObject =
            try {
                JSONObject().put("state", state)
            } catch (ex: JSONException) {
                throw AssertionError(ex)
            }

        private fun wipe(f: File?) {
            if (f == null || !f.exists()) {
                return
            }
            if (f.isDirectory) {
                val kids = f.listFiles()
                if (kids != null) {
                    for (k in kids) {
                        wipe(k)
                    }
                }
            }
            @Suppress("UNUSED_VARIABLE")
            val ignored = f.delete()
        }

        private fun resetFileProviderPathCache() {
            for (field in FileProvider::class.java.declaredFields) {
                if (Modifier.isStatic(field.modifiers) && MutableMap::class.java.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    (field.get(null) as? MutableMap<*, *>)?.clear()
                }
            }
        }
    }
}
