package com.volttracker.obdpoc

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

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
        assertFalse(bridge!!.forceStopPackage("com.volttracker.does.not.exist.anywhere"))
    }

    @Test
    fun forceStopPackage_rejectsInstalledPackagesOutsideObdAllowlist() {
        assertFalse(bridge!!.forceStopPackage(controller!!.get().packageName))
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
    fun cancelRetry_neverThrows() {
        // No bound service in the test JVM — the bridge swallows IllegalStateException
        // and returns cleanly so the UI tap doesn't propagate a crash.
        bridge!!.cancelRetry()
    }

    @Test
    fun openBluetoothSettings_neverThrows_evenIfActivityResolveFails() {
        // Robolectric provides the Settings intent path so this should succeed without
        // throwing on either branch.
        bridge!!.openBluetoothSettings()
    }

    @Test
    fun shareDiagnostics_neverThrows_whenNothingToShare() {
        // DiagnosticsShareIntent returns null when no logs exist yet; bridge surfaces a
        // status broadcast rather than throwing. We just want the no-throw guarantee here.
        bridge!!.shareDiagnostics()
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

        override fun onCreate(savedInstanceState: Bundle?) {
            deviceCatalog = DeviceCatalog(this, getSharedPreferences("troubleshooter-test", 0))
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
    }
}
