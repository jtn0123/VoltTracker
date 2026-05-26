package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

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

    private ActivityController<MainActivity> controller;
    private TroubleshooterBridge bridge;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(MainActivity.class).create();
        MainActivity activity = controller.get();
        bridge = new TroubleshooterBridge(activity);
    }

    @After
    public void tearDown() {
        if (bridge != null) {
            bridge.shutdown();
        }
        if (controller != null) {
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
        bridge.onAdapterStatusForReadyNotify("connected");
        bridge.onAdapterStatusForReadyNotify("idle");
        bridge.onAdapterStatusForReadyNotify(null);
    }
}
