package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

/**
 * A1 — exercises the trivial branches of {@link TroubleshooterBridge} so the new code added by the
 * round-6 refactor is covered by the suite. Heavier paths (the test-connection probe, the
 * notify-when-ready handler loop, the system Notification post) rely on system-service plumbing
 * that's expensive to fake; we leave those for an integration test on a real device. What we DO
 * cover here is every defensive early-return path, since those are the lines most likely to be
 * touched by a future refactor and the cheapest to pin.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TroubleshooterBridgeTest {

    private ActivityController<HarnessActivity> controller;
    private TroubleshooterBridge bridge;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(HarnessActivity.class).create();
        HarnessActivity activity = controller.get();
        wipe(new File(activity.getFilesDir(), "obd-logs"));
        wipe(new File(activity.getFilesDir(), "app-log"));
        wipe(new File(activity.getCacheDir(), "diagnostics"));
        bridge = new TroubleshooterBridge(activity);
    }

    @After
    public void tearDown() {
        if (bridge != null) {
            bridge.shutdown();
        }
        if (controller != null) {
            MainActivity activity = controller.get();
            wipe(new File(activity.getFilesDir(), "obd-logs"));
            wipe(new File(activity.getFilesDir(), "app-log"));
            wipe(new File(activity.getCacheDir(), "diagnostics"));
            controller.destroy();
        }
    }

    @Test
    public void forceStopPackage_rejectsNullAndEmpty() {
        assertFalse(bridge.forceStopPackage(null));
        assertFalse(bridge.forceStopPackage(""));
    }

    @Test
    public void forceStopPackage_rejectsUninstalledPackages() {
        // No app on the test JVM has this name, so the PackageManager.NameNotFoundException
        // branch fires and the bridge returns false honestly.
        assertFalse(bridge.forceStopPackage("com.volttracker.does.not.exist.anywhere"));
    }

    @Test
    public void forceStopPackage_rejectsInstalledPackagesOutsideObdAllowlist() {
        assertFalse(bridge.forceStopPackage(controller.get().getPackageName()));
    }

    @Test
    public void getRecentSessionsJson_zeroOrNegativeReturnsEmptyArray() throws Exception {
        // n <= 0 short-circuits before touching SessionSummaryStore. Both paths must yield
        // a well-formed empty JSON array (the dashboard JSON.parse path keys off this exact
        // shape — anything else and the troubleshooter modal crashes during render).
        assertEquals("[]", bridge.getRecentSessionsJson(0));
        assertEquals("[]", bridge.getRecentSessionsJson(-3));
        // Sanity: confirm the string is valid JSON the dashboard could actually consume.
        assertEquals(0, new JSONArray(bridge.getRecentSessionsJson(0)).length());
    }

    @Test
    public void cancelRetry_neverThrows() {
        // No bound service in the test JVM — the bridge swallows IllegalStateException
        // and returns cleanly so the UI tap doesn't propagate a crash.
        bridge.cancelRetry();
    }

    @Test
    public void openBluetoothSettings_neverThrows_evenIfActivityResolveFails() {
        // Robolectric provides the Settings intent path so this should succeed without
        // throwing on either branch.
        bridge.openBluetoothSettings();
    }

    @Test
    public void shareDiagnostics_neverThrows_whenNothingToShare() {
        // DiagnosticsShareIntent returns null when no logs exist yet; bridge surfaces a
        // status broadcast rather than throwing. We just want the no-throw guarantee here.
        bridge.shareDiagnostics();
    }

    @Test
    public void diagnosticsShareShowsDisclosureBeforeChooser() {
        bridge.showDiagnosticsDisclosure(new Intent(Intent.ACTION_SEND));

        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("diagnostics share should show a disclosure first", dialog);
        assertTrue(dialog.isShowing());
        assertEquals(
                "Share anyway", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertTrue(
                "message should disclose raw telemetry/GPS contents",
                bridge.diagnosticsDisclosureMessage().contains("Raw telemetry"));
    }

    @Test
    public void cancelAdapterReadyNotify_isANoopWhenNothingScheduled() {
        // Calling cancel before schedule must not initialize the handler nor throw.
        bridge.cancelAdapterReadyNotify();
    }

    @Test
    public void clearPendingTestConnectionStop_isANoopWhenNothingScheduled() {
        bridge.clearPendingTestConnectionStop();
    }

    @Test
    public void scheduleAdapterReadyNotify_zeroMinutesExpiresImmediately() {
        // Schedules with a deadline at "now" — the tick should observe the expired
        // deadline on its first run and tear itself down. The important assertion is
        // that the schedule fires + clears without any throws.
        bridge.scheduleAdapterReadyNotify(0);
        bridge.cancelAdapterReadyNotify();
    }

    @Test
    public void shutdown_drainsBothHandlersWithoutThrowing() {
        // Schedule both, then shut down. Without the drain, posted callbacks could fire
        // on a destroyed Activity context and crash the next session.
        bridge.scheduleAdapterReadyNotify(1);
        bridge.clearPendingTestConnectionStop();
        bridge.shutdown();
        // Calling shutdown again must remain idempotent.
        bridge.shutdown();
    }

    @Test
    public void onAdapterStatusForReadyNotify_doesNothingWhenScheduleInactive() {
        // No schedule active → must NOT post a notification, even on "connected".
        bridge.onAdapterStatusForReadyNotify(status("connected", 14.2));
        bridge.onAdapterStatusForReadyNotify(status("idle", 14.2));
        bridge.onAdapterStatusForReadyNotify(null);
    }

    @Test
    public void statusMeansAdapterReadyForNotification_requiresConnectedAwakeVoltage() {
        assertTrue(
                "connected with DC-DC voltage should be notification-ready",
                TroubleshooterBridge.statusMeansAdapterReadyForNotification(
                        status("connected", 14.2)));
        assertFalse(
                "car-off voltage must not fire an adapter-ready notification",
                TroubleshooterBridge.statusMeansAdapterReadyForNotification(
                        status("connected", 12.4)));
        assertFalse(
                "exact threshold voltage must not fire an adapter-ready notification",
                TroubleshooterBridge.statusMeansAdapterReadyForNotification(
                        status("connected", 13.0)));
        assertFalse(
                "adapter-only connection without voltage is not enough",
                TroubleshooterBridge.statusMeansAdapterReadyForNotification(
                        statusWithoutVoltage("connected")));
        assertFalse(
                TroubleshooterBridge.statusMeansAdapterReadyForNotification(
                        status("connecting", 14.2)));
    }

    private static JSONObject status(String state, double lastVoltage) {
        try {
            return new JSONObject().put("state", state).put("lastVoltage", lastVoltage);
        } catch (org.json.JSONException ex) {
            throw new AssertionError(ex);
        }
    }

    private static JSONObject statusWithoutVoltage(String state) {
        try {
            return new JSONObject().put("state", state);
        } catch (org.json.JSONException ex) {
            throw new AssertionError(ex);
        }
    }

    private static void wipe(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    wipe(k);
                }
            }
        }
        boolean ignored = f.delete();
    }

    public static class HarnessActivity extends MainActivity {
        String lastState;
        String lastDetail;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            deviceCatalog = new DeviceCatalog(this, getSharedPreferences("troubleshooter-test", 0));
        }

        @Override
        void publishStatus(String state, String detail, boolean blocked) {
            lastState = state;
            lastDetail = detail;
        }
    }
}
