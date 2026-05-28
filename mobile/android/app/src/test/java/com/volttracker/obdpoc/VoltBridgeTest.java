package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.webkit.JavascriptInterface;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * Pins the {@link VoltBridge} ABI: the dashboard WebView calls these methods by name from
 * JavaScript, so an accidental rename or signature change would silently break the UI with no
 * compile-time failure. Reflection-based assertions guard that contract; a small set of
 * input-safety calls confirms the new {@code safe()} helper coerces null and oversized strings
 * without throwing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class VoltBridgeTest {

    /** Bridge methods that the dashboard JS calls. Update only when the ABI is renamed. */
    private static final Set<String> EXPECTED_BRIDGE_METHODS =
            new HashSet<>(
                    Arrays.asList(
                            "listDevices",
                            "dashboardReady",
                            "requestPermissions",
                            "refreshDevices",
                            "connect",
                            "scan",
                            "getLastDevice",
                            "getDeviceHistory",
                            "getStorageSummary",
                            "exportDebugBundle",
                            "shareBackup",
                            "shareEncryptedBackup",
                            "restoreBackup",
                            "restoreEncryptedBackup",
                            "getTrips",
                            "getInsights",
                            "clearStoredData",
                            "rememberDevice",
                            "connectLast",
                            "scanLast",
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
                            // Bucket 4b — status & proactive tools bridge surface.
                            "shareDiagnostics",
                            "startTestConnection",
                            "scheduleAdapterReadyNotify",
                            "cancelAdapterReadyNotify"));

    /**
     * Builds {@link MainActivity} via Robolectric and hands back its bridge — the real one wired
     * into the WebView. Heavy I/O (Bluetooth, WebView page loads) is gated behind {@code
     * onResume}/{@code onStart} so {@code create()} alone is enough for the assertions below.
     */
    private VoltBridgeUnderTest buildBridge() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).create();
        MainActivity activity = controller.get();
        return new VoltBridgeUnderTest(controller, new VoltBridge(activity));
    }

    /** Small holder so tests can clean up the activity controller after use. */
    private static final class VoltBridgeUnderTest implements AutoCloseable {
        final ActivityController<MainActivity> controller;
        final VoltBridge bridge;

        VoltBridgeUnderTest(ActivityController<MainActivity> controller, VoltBridge bridge) {
            this.controller = controller;
            this.bridge = bridge;
        }

        @Override
        public void close() {
            try {
                controller.destroy();
            } catch (RuntimeException ignored) {
                // Activity teardown can race with the WebView background work in Robolectric;
                // failing to destroy must not fail the test under verification.
            }
        }
    }

    // ---- ABI contract: exact method signatures the dashboard expects ----------------

    @Test
    public void connectMethodSignatureIsTwoStrings() throws NoSuchMethodException {
        Method method = VoltBridge.class.getMethod("connect", String.class, String.class);
        assertEquals(void.class, method.getReturnType());
        assertNotNull(
                "connect(String, String) must be @JavascriptInterface",
                method.getAnnotation(JavascriptInterface.class));
    }

    @Test
    public void scanMethodSignatureIsTwoStrings() throws NoSuchMethodException {
        Method method = VoltBridge.class.getMethod("scan", String.class, String.class);
        assertEquals(void.class, method.getReturnType());
        assertNotNull(method.getAnnotation(JavascriptInterface.class));
    }

    @Test
    public void rememberDeviceMethodSignatureIsTwoStrings() throws NoSuchMethodException {
        Method method = VoltBridge.class.getMethod("rememberDevice", String.class, String.class);
        assertEquals(void.class, method.getReturnType());
        assertNotNull(method.getAnnotation(JavascriptInterface.class));
    }

    @Test
    public void logClientErrorMethodSignatureIsTwoStrings() throws NoSuchMethodException {
        Method method = VoltBridge.class.getMethod("logClientError", String.class, String.class);
        assertEquals(void.class, method.getReturnType());
        assertNotNull(method.getAnnotation(JavascriptInterface.class));
    }

    @Test
    public void openExternalSearchMethodSignatureIsSingleString() throws NoSuchMethodException {
        Method method = VoltBridge.class.getMethod("openExternalSearch", String.class);
        assertEquals(void.class, method.getReturnType());
        assertNotNull(method.getAnnotation(JavascriptInterface.class));
    }

    @Test
    public void encryptedBackupMethodsTakePassphraseString() throws NoSuchMethodException {
        for (String name : new String[] {"shareEncryptedBackup", "restoreEncryptedBackup"}) {
            Method method = VoltBridge.class.getMethod(name, String.class);
            assertEquals(void.class, method.getReturnType());
            assertNotNull(method.getAnnotation(JavascriptInterface.class));
        }
    }

    @Test
    public void getterMethodsReturnString() throws NoSuchMethodException {
        for (String getter :
                new String[] {
                    "listDevices",
                    "getLastDevice",
                    "getDeviceHistory",
                    "getStorageSummary",
                    "exportDebugBundle",
                    "getTrips",
                    "getInsights"
                }) {
            Method method = VoltBridge.class.getMethod(getter);
            assertEquals(getter + " must return String", String.class, method.getReturnType());
            assertNotNull(
                    getter + " must be @JavascriptInterface",
                    method.getAnnotation(JavascriptInterface.class));
        }
    }

    @Test
    public void voidNoArgMethodsAreAnnotated() throws NoSuchMethodException {
        for (String name :
                new String[] {
                    "requestPermissions",
                    "refreshDevices",
                    "shareBackup",
                    "restoreBackup",
                    "clearStoredData",
                    "connectLast",
                    "scanLast",
                    "demo",
                    "disconnect",
                    "clearVehicleDtcCodes"
                }) {
            Method method = VoltBridge.class.getMethod(name);
            assertEquals(name + " must return void", void.class, method.getReturnType());
            assertNotNull(
                    name + " must be @JavascriptInterface",
                    method.getAnnotation(JavascriptInterface.class));
        }
    }

    @Test
    public void allExpectedBridgeMethodsExistAndAreAnnotated() {
        Set<String> seen = new HashSet<>();
        for (Method method : VoltBridge.class.getMethods()) {
            if (method.getAnnotation(JavascriptInterface.class) != null) {
                seen.add(method.getName());
            }
        }
        assertTrue(
                "Bridge surface lost methods: " + diff(EXPECTED_BRIDGE_METHODS, seen),
                seen.containsAll(EXPECTED_BRIDGE_METHODS));
        // Drift the other way is also worth surfacing — a new bridge method should be
        // added to the expected set so we keep the dashboard ABI explicit.
        Set<String> unexpected = diff(seen, EXPECTED_BRIDGE_METHODS);
        assertTrue("New bridge methods are not yet pinned: " + unexpected, unexpected.isEmpty());
    }

    @Test
    public void dashboardReadyMarksActivityReadyOnlyAfterJsHandshake() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).create();
        try {
            MainActivity activity = controller.get();
            assertFalse(activity.isDashboardReadyForTest());

            new VoltBridge(activity).dashboardReady();
            shadowOf(Looper.getMainLooper()).idle();

            assertTrue(activity.isDashboardReadyForTest());
        } finally {
            controller.destroy();
        }
    }

    private static <T> Set<T> diff(Set<T> a, Set<T> b) {
        Set<T> copy = new HashSet<>(a);
        copy.removeAll(b);
        return copy;
    }

    // ---- input safety: the new safe() helper must coerce null and bound oversize ----

    @Test
    public void logClientErrorAcceptsNullsWithoutThrowing() {
        try (VoltBridgeUnderTest t = buildBridge()) {
            try {
                t.bridge.logClientError(null, null);
            } catch (RuntimeException ex) {
                fail("logClientError(null, null) must not throw, was: " + ex);
            }
        }
    }

    @Test
    public void logClientErrorTruncatesOversizedInputs() {
        try (VoltBridgeUnderTest t = buildBridge()) {
            // 10k chars each — bigger than MAX_LABEL_LEN (128) and MAX_DETAIL_LEN (4096).
            String giant = repeat('x', 10_000);
            try {
                t.bridge.logClientError(giant, giant);
            } catch (RuntimeException ex) {
                fail("logClientError(giant, giant) must not throw, was: " + ex);
            }
        }
    }

    @Test
    public void connectAcceptsNullsWithoutThrowing() {
        try (VoltBridgeUnderTest t = buildBridge()) {
            try {
                // safe() should coerce both args to empty strings before runOnUiThread.
                t.bridge.connect(null, null);
            } catch (RuntimeException ex) {
                fail("connect(null, null) must not throw, was: " + ex);
            }
        }
    }

    private static String repeat(char ch, int count) {
        char[] buffer = new char[count];
        Arrays.fill(buffer, ch);
        return new String(buffer);
    }
}
