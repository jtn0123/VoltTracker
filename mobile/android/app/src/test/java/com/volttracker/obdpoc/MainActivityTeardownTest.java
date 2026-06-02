package com.volttracker.obdpoc;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * Regression guard for the startup/teardown race that surfaced repeatedly in the emulator smoke:
 * onDestroy shuts down the background executor with {@code shutdownNow()}, but the WebView's {@code
 * dashboardReady} handshake (and late status broadcasts) can still fire afterwards and route
 * through {@link MainActivity#publishStorageSummary()}, which submits to that executor. Before the
 * fix that threw {@link java.util.concurrent.RejectedExecutionException} on the main thread and
 * crashed the process. These assert the post-shutdown paths now drop the work instead of throwing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityTeardownTest {

    @Test
    public void dashboardHandshakeAfterDestroyDoesNotCrash() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).create();
        MainActivity activity = controller.get();

        // onDestroy() calls backgroundExecutor.shutdownNow().
        controller.destroy();

        // The WebView can still call back after destroy; this must not throw.
        activity.onDashboardReady();
        activity.publishStorageSummary();

        assertTrue("post-destroy callbacks completed without crashing", true);
    }

    @Test
    public void runOnBackgroundAfterShutdownIsDropped() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).create();
        MainActivity activity = controller.get();
        controller.destroy();

        // A late background submission (e.g. a bridge call racing teardown) is swallowed, not
        // thrown.
        activity.runOnBackground(() -> {});

        assertTrue("late runOnBackground was dropped without crashing", true);
    }
}
