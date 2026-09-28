package com.volttracker.obdpoc

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import com.volttracker.obdpoc.service.ObdService
import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper

/**
 * Health's Scan now / Clear codes / Share report: a real scan or clear goes to the remembered
 * adapter (a clear only after its warning), the demo sends nothing, and a scan or clear result
 * re-times the codes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeDtcActionsTest {
    private data class Start(
        val action: String?,
        val address: String?,
        val stage: String?,
    )

    private lateinit var activity: Activity
    private lateinit var prefs: SharedPreferences
    private val starts = mutableListOf<Start>()
    private var reloads = 0
    private val toasts = mutableListOf<String>()
    private val store = LiveUiStateStore { 0L }
    private lateinit var dtc: ComposeDtcActions

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(android.R.style.Theme_DeviceDefault)
        prefs = activity.getSharedPreferences("dtc-test", Context.MODE_PRIVATE)
        prefs.edit { clear() }
        val catalog = DeviceCatalog(activity, prefs)
        val host =
            object : DeviceCommands {
                override fun requireDeviceCatalog() = catalog

                override fun rememberDevice(
                    address: String?,
                    name: String?,
                ) = Unit

                override fun startObdService(
                    action: String?,
                    address: String?,
                    name: String?,
                ) {
                    starts += Start(action, address, null)
                }

                override fun startObdService(
                    action: String?,
                    address: String?,
                    name: String?,
                    detailStage: String?,
                ) {
                    starts += Start(action, address, detailStage)
                }

                override fun stopObdService() = Unit

                override fun isLoggingActive() = false
            }
        dtc = ComposeDtcActions(activity, host, { prefs }, store, toast = { toasts += it }) { reloads++ }
    }

    private fun rememberAdapter() =
        prefs.edit {
            putString(DeviceCatalog.PREF_LAST_ADDRESS, ADDRESS)
            putString(DeviceCatalog.PREF_LAST_NAME, "OBDLink MX+")
        }

    @Test
    fun scanRunsAQuickScanOnTheRememberedAdapter() {
        rememberAdapter()
        dtc.scan()
        assertEquals(listOf(Start(ObdService.ACTION_SCAN, ADDRESS, DiagnosticScanProfile.QUICK.wireName)), starts)
    }

    @Test
    fun withNoRememberedAdapterNothingStarts() {
        dtc.scan()
        dtc.clear()
        assertTrue(starts.isEmpty())
        assertNull("no clear warning either", ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(2, toasts.count { it.startsWith("No remembered adapter") })
    }

    @Test
    fun clearingNeedsTheWarningConfirmed() {
        rememberAdapter()
        dtc.clear()
        assertTrue("nothing is sent before confirming", starts.isEmpty())
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertTrue(shadowOf(dialog).message.toString().contains("readiness monitors"))
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(listOf(Start(ObdService.ACTION_CLEAR_DTC, ADDRESS, null)), starts)
    }

    @Test
    fun theDemoSimulatesScanAndClearAndSendsNothing() {
        rememberAdapter()
        store.onStatus(JSONObject().put("state", "demo"))
        dtc.scan()
        assertEquals(listOf(DEMO_SCAN_TOAST), toasts)
        dtc.clear()
        (ShadowAlertDialog.getLatestAlertDialog() as AlertDialog)
            .getButton(DialogInterface.BUTTON_POSITIVE)
            .performClick()
        ShadowLooper.idleMainLooper()
        assertTrue("nothing reaches the service", starts.isEmpty())
        assertEquals(emptyList<Any>(), store.state.value.diag.codes)
        assertEquals(listOf("P0420", "P0011"), store.state.value.diag.earlierCodes)
    }

    @Test
    fun aScanOrClearResultIsTimedAndReread() {
        assertNull(dtc.lastScanAtMs())
        dtc.onTelemetry(JSONObject().put("source", "scan").put("dtcScanValid", true).put("updatedAt", 5_000L))
        assertEquals(5_000L, dtc.lastScanAtMs())
        dtc.onTelemetry(JSONObject().put("source", "clear-dtc").put("clearDtcOk", true).put("updatedAt", 9_000L))
        assertEquals(9_000L, dtc.lastClearAtMs())
        assertEquals(2, reloads)
        // A failed scan, a refused clear, and live samples change nothing.
        dtc.onTelemetry(JSONObject().put("source", "scan").put("dtcScanValid", false).put("updatedAt", 7_000L))
        dtc.onTelemetry(JSONObject().put("source", "clear-dtc").put("clearDtcOk", false))
        dtc.onTelemetry(JSONObject().put("source", "obd").put("updatedAt", 8_000L))
        assertEquals(5_000L, dtc.lastScanAtMs())
        assertEquals(2, reloads)
    }

    @Test
    fun theAutoScanCountsAsTheLastRead() {
        EventNotificationPrefs(prefs).setLastAutoScanAtMs(12_000L)
        assertEquals(12_000L, dtc.lastScanAtMs())
    }

    @Test
    fun shareOpensTheShareSheetWithTheReport() {
        dtc.share("Volt Tracker health report")
        val chooser = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertEquals("Volt Tracker health report", send?.getStringExtra(Intent.EXTRA_TEXT))
    }

    private companion object {
        const val ADDRESS = "00:11:22:33:44:55"
        const val DEMO_SCAN_TOAST = "Demo / Testing: scan simulated. Nothing was sent to a car."
    }
}
