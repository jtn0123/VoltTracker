package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import com.volttracker.obdpoc.data.ObdLocalStore;
import java.io.File;
import java.io.FileInputStream;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
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
 * Drives the native Replace / Merge / Cancel dialog in {@link BackupController}. This is the one
 * restore-flow surface Playwright can't reach (it's an Android AlertDialog, not the WebView): after
 * a picked file is staged + verified, the dialog must offer the three choices, and each button must
 * route to the right outcome (additive merge / destructive replace / cancel-and-clean-up).
 *
 * <p>Uses a direct (inline) executor and a Robolectric content URI so the whole stage → dialog →
 * button-click → outcome chain runs synchronously on the test thread.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BackupControllerDialogTest {

    private ActivityController<HarnessActivity> controller;
    private HarnessActivity activity;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(HarnessActivity.class).create();
        activity = controller.get();
        activity.localStore.clearAllData();
    }

    @After
    public void tearDown() {
        try {
            controller.destroy();
        } catch (RuntimeException ignored) {
            // WebView teardown can race Robolectric background work; not part of these assertions.
        }
    }

    /**
     * Seeds one session, exports a backup, then clears the live store so a merge has work to do.
     */
    private Uri stagedBackupOfOneClearedSession() throws Exception {
        long id = activity.localStore.startSession("obd", "AA:BB", "Adapter");
        activity.localStore.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 4000, "");
        File backup = new DataBackup(activity).buildBackupFile(activity.localStore);
        assertNotNull("buildBackupFile should produce a backup", backup);
        activity.localStore.clearAllData();
        Uri uri = Uri.parse("content://test/" + backup.getName());
        shadowOf(activity.getContentResolver())
                .registerInputStream(uri, new FileInputStream(backup));
        return uri;
    }

    /** AlertDialog button clicks post their listener to the main looper; run it. */
    private void clickAndSettle(AlertDialog dialog, int which) {
        dialog.getButton(which).performClick();
        shadowOf(Looper.getMainLooper()).idle();
    }

    private AlertDialog openRestoreDialog(Uri uri) {
        Intent data = new Intent();
        data.setData(uri);
        activity.backupController.onRestorePickerResult(Activity.RESULT_OK, data);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("a verified backup must offer the restore-mode dialog", dialog);
        return dialog;
    }

    @Test
    public void verifiedBackupOffersMergeReplaceAndCancel() throws Exception {
        AlertDialog dialog = openRestoreDialog(stagedBackupOfOneClearedSession());
        assertEquals(
                "Merge", dialog.getButton(DialogInterface.BUTTON_POSITIVE).getText().toString());
        assertEquals(
                "Replace all",
                dialog.getButton(DialogInterface.BUTTON_NEGATIVE).getText().toString());
        assertEquals(
                "Cancel", dialog.getButton(DialogInterface.BUTTON_NEUTRAL).getText().toString());
    }

    @Test
    public void mergeButtonFoldsTheBackupIntoTheLiveStore() throws Exception {
        AlertDialog dialog = openRestoreDialog(stagedBackupOfOneClearedSession());

        clickAndSettle(dialog, DialogInterface.BUTTON_POSITIVE); // Merge

        assertEquals("ready", activity.lastState);
        assertTrue(
                "merge status should report the folded-in session, got: " + activity.lastDetail,
                activity.lastDetail.contains("Merged"));
        // The cleared live store has the donor session back.
        assertEquals(1, activity.localStore.getRecentSessions(10).size());
    }

    @Test
    public void replaceButtonRestoresTheBackupWholesale() throws Exception {
        AlertDialog dialog = openRestoreDialog(stagedBackupOfOneClearedSession());

        clickAndSettle(dialog, DialogInterface.BUTTON_NEGATIVE); // Replace all

        assertEquals("ready", activity.lastState);
        assertTrue(
                "replace status should confirm the restore, got: " + activity.lastDetail,
                activity.lastDetail.contains("Backup restored"));
        assertEquals(1, activity.localStore.getRecentSessions(10).size());
    }

    @Test
    public void cancelButtonAbortsWithoutChangingData() throws Exception {
        AlertDialog dialog = openRestoreDialog(stagedBackupOfOneClearedSession());

        clickAndSettle(dialog, DialogInterface.BUTTON_NEUTRAL); // Cancel

        assertEquals("ready", activity.lastState);
        assertEquals("Restore cancelled.", activity.lastDetail);
        // The store stays as it was (cleared) — cancel imports nothing.
        assertEquals(0, activity.localStore.getRecentSessions(10).size());
    }

    @Test
    public void invalidFileIsRejectedWithoutOfferingTheDialog() {
        Uri uri = Uri.parse("content://test/not-a-backup.db");
        shadowOf(activity.getContentResolver())
                .registerInputStream(uri, new java.io.ByteArrayInputStream("garbage".getBytes()));
        Intent data = new Intent();
        data.setData(uri);

        activity.backupController.onRestorePickerResult(Activity.RESULT_OK, data);

        assertEquals("blocked", activity.lastState);
        assertTrue(activity.lastDetail.contains("not a valid Volt Tracker backup"));
    }

    /** Inline executor: stage/apply work runs synchronously so the test can assert outcomes. */
    private static final class DirectExecutorService extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    /** Minimal MainActivity that wires a real store + inline executor and captures status. */
    public static class HarnessActivity extends MainActivity {
        String lastState;
        String lastDetail;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            localStore = new ObdLocalStore(this);
            backupController =
                    new BackupController(this, new DataBackup(this), new DirectExecutorService());
        }

        @Override
        boolean isLoggingActive() {
            return false;
        }

        @Override
        void publishStatus(String state, String detail, boolean blocked) {
            lastState = state;
            lastDetail = detail;
        }

        @Override
        void publishDeviceList() {
            // No WebView in the harness.
        }

        @Override
        void publishStorageSummary() {
            // No WebView in the harness.
        }
    }
}
