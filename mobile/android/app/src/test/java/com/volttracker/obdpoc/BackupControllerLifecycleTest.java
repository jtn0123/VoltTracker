package com.volttracker.obdpoc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 34)
public class BackupControllerLifecycleTest {

    @Test
    public void restorePickerIsBlockedWhileLoggingIsActive() {
        ActivityController<HarnessActivity> controller =
                Robolectric.buildActivity(HarnessActivity.class).create();
        try {
            HarnessActivity activity = controller.get();
            activity.loggingActive = true;

            activity.backupController.launchRestorePicker();

            assertNull(activity.launchedRestoreIntent);
            assertEquals("blocked", activity.lastState);
            assertEquals("Stop logging before restoring a backup.", activity.lastDetail);
        } finally {
            destroyQuietly(controller);
        }
    }

    @Test
    public void restorePickerStartsSafIntentWhenIdle() {
        ActivityController<HarnessActivity> controller =
                Robolectric.buildActivity(HarnessActivity.class).create();
        try {
            HarnessActivity activity = controller.get();
            activity.loggingActive = false;

            activity.backupController.launchRestorePicker();

            Intent started = activity.launchedRestoreIntent;
            assertNotNull(started);
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.getAction());
            assertEquals("application/octet-stream", started.getType());
            assertArrayEquals(
                    new String[] {
                        "application/octet-stream",
                        "application/vnd.sqlite3",
                        "application/x-sqlite3"
                    },
                    started.getStringArrayExtra(Intent.EXTRA_MIME_TYPES));
        } finally {
            destroyQuietly(controller);
        }
    }

    @Test
    public void stopObdServiceSurfacesRejectedServiceStart() {
        ActivityController<HarnessActivity> controller =
                Robolectric.buildActivity(HarnessActivity.class).create();
        try {
            HarnessActivity activity = controller.get();
            activity.rejectStartService = true;

            activity.stopObdService();

            assertEquals("ready", activity.lastState);
            assertEquals(
                    "Stop request noted; reopen the app if logging is still active.",
                    activity.lastDetail);
        } finally {
            destroyQuietly(controller);
        }
    }

    private static void destroyQuietly(ActivityController<? extends MainActivity> controller) {
        try {
            controller.destroy();
        } catch (RuntimeException ignored) {
            // WebView teardown can race Robolectric background work; not part of this assertion.
        }
    }

    public static class HarnessActivity extends MainActivity {
        boolean loggingActive;
        boolean rejectStartService;
        Intent launchedRestoreIntent;
        String lastState;
        String lastDetail;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            backupController = new BackupController(this, new DataBackup(this), null);
        }

        @Override
        boolean isLoggingActive() {
            return loggingActive;
        }

        @Override
        void publishStatus(String state, String detail, boolean blocked) {
            lastState = state;
            lastDetail = detail;
        }

        @Override
        void launchRestoreFilePicker(Intent intent) {
            launchedRestoreIntent = intent;
        }

        @Override
        public ComponentName startService(Intent service) {
            if (rejectStartService) {
                throw new IllegalStateException("background service start blocked");
            }
            return new ComponentName(this, ObdService.class);
        }
    }
}
