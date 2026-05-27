package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Intent;
import android.os.Bundle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;

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
            Shadows.shadowOf(activity).getNextStartedActivityForResult();

            activity.backupController.launchRestorePicker();

            assertNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
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

            ShadowActivity.IntentForResult started =
                    Shadows.shadowOf(activity).getNextStartedActivityForResult();
            assertNotNull(started);
            assertEquals(BackupController.REQUEST_RESTORE, started.requestCode);
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.getAction());
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
    }
}
