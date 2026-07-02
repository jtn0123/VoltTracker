package com.volttracker.obdpoc

import android.os.Looper
import android.webkit.JavascriptInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Pins the [VoltBridge] ABI: the dashboard WebView calls these methods by name from JavaScript, so
 * an accidental rename or signature change would silently break the UI with no compile-time
 * failure. Reflection-based assertions guard that contract; a small set of input-safety calls
 * confirms the new `safe()` helper coerces null and oversized strings without throwing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoltBridgeTest {
    /**
     * Builds [MainActivity] via Robolectric and hands back its bridge — the real one wired into the
     * WebView. Heavy I/O (Bluetooth, WebView page loads) is gated behind `onResume`/`onStart` so
     * `create()` alone is enough for the assertions below.
     */
    private fun buildBridge(): VoltBridgeUnderTest {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        return VoltBridgeUnderTest(controller, VoltBridge(activity))
    }

    /** Small holder so tests can clean up the activity controller after use. */
    private class VoltBridgeUnderTest(
        val controller: ActivityController<MainActivity>,
        val bridge: VoltBridge,
    ) : AutoCloseable {
        override fun close() {
            try {
                controller.destroy()
            } catch (ignored: RuntimeException) {
                // Activity teardown can race with the WebView background work in Robolectric;
                // failing to destroy must not fail the test under verification.
            }
        }
    }

    // ---- ABI contract: exact method signatures the dashboard expects ----------------

    @Test
    fun connectMethodSignatureIsTwoStrings() {
        val method =
            VoltBridge::class.java.getMethod("connect", String::class.java, String::class.java)
        assertEquals(Void.TYPE, method.returnType)
        assertNotNull(
            "connect(String, String) must be @JavascriptInterface",
            method.getAnnotation(JavascriptInterface::class.java),
        )
    }

    @Test
    fun scanMethodSignatureIsTwoStrings() {
        val method =
            VoltBridge::class.java.getMethod("scan", String::class.java, String::class.java)
        assertEquals(Void.TYPE, method.returnType)
        assertNotNull(method.getAnnotation(JavascriptInterface::class.java))
    }

    @Test
    fun quickScanMethodSignatureIsTwoStrings() {
        val method =
            VoltBridge::class.java.getMethod("quickScan", String::class.java, String::class.java)
        assertEquals(Void.TYPE, method.returnType)
        assertNotNull(method.getAnnotation(JavascriptInterface::class.java))
    }

    @Test
    fun tpmsScanMethodSignatureIsTwoStrings() {
        val method =
            VoltBridge::class.java.getMethod("tpmsScan", String::class.java, String::class.java)
        assertEquals(Void.TYPE, method.returnType)
        assertNotNull(method.getAnnotation(JavascriptInterface::class.java))
    }

    @Test
    fun detailProbeMethodSignatureIsThreeStrings() {
        val method =
            VoltBridge::class.java.getMethod(
                "detailProbe",
                String::class.java,
                String::class.java,
                String::class.java,
            )
        assertEquals(Void.TYPE, method.returnType)
        assertNotNull(method.getAnnotation(JavascriptInterface::class.java))
    }

    @Test
    fun rememberDeviceMethodSignatureIsTwoStrings() {
        val method =
            VoltBridge::class.java.getMethod(
                "rememberDevice",
                String::class.java,
                String::class.java,
            )
        assertEquals(Void.TYPE, method.returnType)
        assertNotNull(method.getAnnotation(JavascriptInterface::class.java))
    }

    @Test
    fun logClientErrorMethodSignatureIsTwoStrings() {
        val method =
            VoltBridge::class.java.getMethod(
                "logClientError",
                String::class.java,
                String::class.java,
            )
        assertEquals(Void.TYPE, method.returnType)
        assertNotNull(method.getAnnotation(JavascriptInterface::class.java))
    }

    @Test
    fun openExternalSearchMethodSignatureIsSingleString() {
        val method = VoltBridge::class.java.getMethod("openExternalSearch", String::class.java)
        assertEquals(Void.TYPE, method.returnType)
        assertNotNull(method.getAnnotation(JavascriptInterface::class.java))
    }

    @Test
    fun encryptedBackupMethodsTakePassphraseString() {
        for (name in arrayOf("shareEncryptedBackup", "restoreEncryptedBackup")) {
            val method = VoltBridge::class.java.getMethod(name, String::class.java)
            assertEquals(Void.TYPE, method.returnType)
            assertNotNull(method.getAnnotation(JavascriptInterface::class.java))
        }
    }

    @Test
    fun getterMethodsReturnString() {
        for (
        getter in
        arrayOf(
            "listDevices",
            "getLastDevice",
            "getDeviceHistory",
            "getStorageSummary",
            "getStorageDetails",
            "exportDebugBundle",
            "exportDetailedSignalLogs",
            "getTrips",
            "getInsights",
        )
        ) {
            val method = VoltBridge::class.java.getMethod(getter)
            assertEquals("$getter must return String", String::class.java, method.returnType)
            assertNotNull(
                "$getter must be @JavascriptInterface",
                method.getAnnotation(JavascriptInterface::class.java),
            )
        }
    }

    @Test
    fun signalLogBridgeMethodsUseStableSignatures() {
        val exportOne = VoltBridge::class.java.getMethod("exportDetailedSignalLog", String::class.java)
        assertEquals(String::class.java, exportOne.returnType)
        assertNotNull(exportOne.getAnnotation(JavascriptInterface::class.java))

        val deleteOne = VoltBridge::class.java.getMethod("deleteDetailedSignalLog", String::class.java)
        assertEquals(Void.TYPE, deleteOne.returnType)
        assertNotNull(deleteOne.getAnnotation(JavascriptInterface::class.java))

        val probeLast = VoltBridge::class.java.getMethod("detailProbeLast", String::class.java)
        assertEquals(Void.TYPE, probeLast.returnType)
        assertNotNull(probeLast.getAnnotation(JavascriptInterface::class.java))
    }

    @Test
    fun voidNoArgMethodsAreAnnotated() {
        for (
        name in
        arrayOf(
            "requestPermissions",
            "refreshDevices",
            "shareBackup",
            "restoreBackup",
            "clearStoredData",
            "connectLast",
            "scanLast",
            "quickScanLast",
            "tpmsScanLast",
            "demo",
            "disconnect",
            "clearVehicleDtcCodes",
        )
        ) {
            val method = VoltBridge::class.java.getMethod(name)
            assertEquals("$name must return void", Void.TYPE, method.returnType)
            assertNotNull(
                "$name must be @JavascriptInterface",
                method.getAnnotation(JavascriptInterface::class.java),
            )
        }
    }

    @Test
    fun allExpectedBridgeMethodsExistAndAreAnnotated() {
        val seen = HashSet<String>()
        for (method in VoltBridge::class.java.methods) {
            if (method.getAnnotation(JavascriptInterface::class.java) != null) {
                seen.add(method.name)
            }
        }
        assertTrue(
            "Bridge surface lost methods: " + diff(EXPECTED_BRIDGE_METHODS, seen),
            seen.containsAll(EXPECTED_BRIDGE_METHODS),
        )
        // Drift the other way is also worth surfacing — a new bridge method should be
        // added to the expected set so we keep the dashboard ABI explicit.
        val unexpected = diff(seen, EXPECTED_BRIDGE_METHODS)
        assertTrue("New bridge methods are not yet pinned: $unexpected", unexpected.isEmpty())
    }

    @Test
    fun dashboardReadyMarksActivityReadyOnlyAfterJsHandshake() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            assertFalse(activity.isDashboardReadyForTest())

            VoltBridge(activity).dashboardReady()
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(activity.isDashboardReadyForTest())
        } finally {
            controller.destroy()
        }
    }

    // ---- input safety: the new safe() helper must coerce null and bound oversize ----

    @Test
    fun logClientErrorAcceptsNullsWithoutThrowing() {
        buildBridge().use { t ->
            try {
                t.bridge.logClientError(null, null)
            } catch (ex: RuntimeException) {
                fail("logClientError(null, null) must not throw, was: $ex")
            }
        }
    }

    @Test
    fun logClientErrorTruncatesOversizedInputs() {
        buildBridge().use { t ->
            // 10k chars each — bigger than MAX_LABEL_LEN (128) and MAX_DETAIL_LEN (4096).
            val giant = "x".repeat(10_000)
            try {
                t.bridge.logClientError(giant, giant)
            } catch (ex: RuntimeException) {
                fail("logClientError(giant, giant) must not throw, was: $ex")
            }
        }
    }

    @Test
    fun connectAcceptsNullsWithoutThrowing() {
        buildBridge().use { t ->
            try {
                // safe() should coerce both args to empty strings before runOnUiThread.
                t.bridge.connect(null, null)
            } catch (ex: RuntimeException) {
                fail("connect(null, null) must not throw, was: $ex")
            }
        }
    }

    // ---- E2: logClientError is token-bucket rate limited -----------------------------

    @Test
    fun logClientErrorEmitsNormalVolumeUnthrottled() {
        buildBridge().use { t ->
            ShadowLog.clear()
            // A handful of calls — well under the burst capacity — must all reach logcat.
            for (i in 0 until 5) {
                t.bridge.logClientError("err", "detail $i")
            }
            assertEquals("All normal-volume calls should log", 5, countDashboardErrorLogs())
        }
    }

    @Test
    fun logClientErrorDropsRunawayCallerAfterBurst() {
        buildBridge().use { t ->
            ShadowLog.clear()
            // 200 instantaneous calls: only the burst capacity (10) can pass before the bucket
            // empties; the refill is time-based and ~no time elapses in this loop. The runaway
            // caller is throttled rather than spamming the log unbounded.
            for (i in 0 until 200) {
                t.bridge.logClientError("err", "spam $i")
            }
            val logged = countDashboardErrorLogs()
            assertTrue("Burst should admit at least 1 call, was $logged", logged >= 1)
            assertTrue("Runaway caller must be throttled well below 200, was $logged", logged <= 20)
        }
    }

    private companion object {
        /** Bridge methods that the dashboard JS calls. Update only when the ABI is renamed. */
        private val EXPECTED_BRIDGE_METHODS: Set<String> =
            hashSetOf(
                "listDevices",
                "dashboardReady",
                "startupMark",
                "requestPermissions",
                "refreshDevices",
                "connect",
                "scan",
                "quickScan",
                "tpmsScan",
                "detailProbe",
                "getLastDevice",
                "getDeviceHistory",
                "getAutoConnectState",
                "setAutoConnectEnabled",
                // M1 event notifications + M3 auto-scan settings.
                "getEventNotificationState",
                "setChargeCompleteNotify",
                "setNewDtcNotify",
                "setLowSocNotify",
                "setHighPackTempNotify",
                // M2 charge target SOC.
                "setChargeTargetSoc",
                "setAutoScanOnConnect",
                // M2 maintenance-overdue alert toggle.
                "setMaintenanceDueNotify",
                "getStorageSummary",
                "requestStorageSummary",
                "getStorageDetails",
                "requestStorageDetails",
                "exportDebugBundle",
                "shareBackup",
                "shareEncryptedBackup",
                "restoreBackup",
                "restoreEncryptedBackup",
                "getTrips",
                "getInsights",
                "getTripRoute",
                "getCurrentSessionRoute",
                "getBatterySohHistory",
                "requestTrips",
                "requestInsights",
                "requestTripRoute",
                "requestCurrentSessionRoute",
                "requestBatterySohHistory",
                "clearStoredData",
                "rememberDevice",
                "connectLast",
                "scanLast",
                "quickScanLast",
                "tpmsScanLast",
                "detailProbeLast",
                "exportDetailedSignalLog",
                "exportDetailedSignalLogs",
                "exportTripGpx",
                "exportTripCsv",
                "exportAllTripsCsv",
                "exportChargeSessionsCsv",
                "deleteDetailedSignalLog",
                "markTripNotTrip",
                "setTripLabel",
                "setTripFavorite",
                "addMaintenanceEntry",
                "getMaintenanceLog",
                "deleteMaintenanceEntry",
                "demo",
                "disconnect",
                "logClientError",
                "clearVehicleDtcCodes",
                "openExternalSearch",
                // Bucket 4a — connection-troubleshooter bridge surface.
                "getRecentSessions",
                "forceStopPackage",
                "cancelRetry",
                "tryReconnectNow",
                "openBluetoothSettings",
                "openSetupGuide",
                // Bucket 4b — status & proactive tools bridge surface.
                "shareDiagnostics",
                "shareDiagnosticsDigest",
                "startTestConnection",
                "scheduleAdapterReadyNotify",
                "cancelAdapterReadyNotify",
            )

        private fun <T> diff(
            a: Set<T>,
            b: Set<T>,
        ): Set<T> {
            val copy = HashSet(a)
            copy.removeAll(b)
            return copy
        }

        private fun countDashboardErrorLogs(): Int {
            var count = 0
            for (item in ShadowLog.getLogsForTag(MainActivity.TAG)) {
                if (item.msg?.startsWith("dashboard client error") == true) {
                    count++
                }
            }
            return count
        }
    }
}
