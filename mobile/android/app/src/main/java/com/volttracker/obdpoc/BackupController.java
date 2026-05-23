package com.volttracker.obdpoc;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;
import com.volttracker.obdpoc.data.ObdLocalStore;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;

/**
 * Drives the backup/restore user flows for {@link MainActivity}: the share-sheet hand-off, the
 * file-picker launch, and swapping the live database for a restored one. The low-level file IO
 * lives in {@link DataBackup}; this class owns the Activity-facing orchestration.
 */
final class BackupController {

    static final int REQUEST_RESTORE = 4202;

    private final MainActivity activity;
    private final DataBackup dataBackup;
    private final ExecutorService executor;

    BackupController(MainActivity activity, DataBackup dataBackup, ExecutorService executor) {
        this.activity = activity;
        this.dataBackup = dataBackup;
        this.executor = executor;
    }

    // Produces a complete on-device data backup and hands it to the Android share sheet
    // so the user can save it anywhere (cloud, PC) — no server involved.
    void launchShare() {
        activity.publishStatus("ready", "Preparing data backup...", false);
        executor.execute(
                () -> {
                    final File backup = dataBackup.buildBackupFile(activity.localStore);
                    activity.runOnUiThread(
                            () -> {
                                if (backup == null) {
                                    activity.publishStatus(
                                            "blocked", "Could not create the backup file.", true);
                                    return;
                                }
                                try {
                                    Uri uri =
                                            FileProvider.getUriForFile(
                                                    activity,
                                                    activity.getPackageName() + ".fileprovider",
                                                    backup);
                                    Intent share = new Intent(Intent.ACTION_SEND);
                                    share.setType("application/octet-stream");
                                    share.putExtra(Intent.EXTRA_STREAM, uri);
                                    share.putExtra(Intent.EXTRA_SUBJECT, backup.getName());
                                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                    activity.startActivity(
                                            Intent.createChooser(
                                                    share, "Back up Volt Tracker data"));
                                    activity.publishStatus(
                                            "ready",
                                            "Backup ready - choose where to save it.",
                                            false);
                                } catch (RuntimeException ex) {
                                    activity.publishStatus(
                                            "blocked", "Could not open the share sheet.", true);
                                }
                            });
                });
    }

    /** Handles the SAF result for {@link #REQUEST_RESTORE}; a no-op for other requests. */
    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_RESTORE
                && resultCode == Activity.RESULT_OK
                && data != null
                && data.getData() != null) {
            restoreFromUri(data.getData());
        }
    }

    void launchRestorePicker() {
        // Refuse restore while a logging session is active so the swap cannot race
        // in-flight ObdService database writes.
        if (activity.isLoggingActive()) {
            activity.publishStatus("blocked", "Stop logging before restoring a backup.", true);
            return;
        }
        try {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("*/*");
            activity.startActivityForResult(pick, REQUEST_RESTORE);
        } catch (RuntimeException ex) {
            activity.publishStatus("blocked", "Could not open the file picker.", true);
        }
    }

    private void restoreFromUri(Uri uri) {
        activity.publishStatus("ready", "Restoring backup...", false);
        executor.execute(
                () -> {
                    final boolean ok = applyRestore(uri);
                    activity.runOnUiThread(
                            () -> {
                                if (ok) {
                                    activity.publishDeviceList();
                                    activity.publishStorageSummary();
                                    activity.publishStatus(
                                            "ready",
                                            "Backup restored - reconnect to resume logging.",
                                            false);
                                } else {
                                    activity.publishStatus(
                                            "blocked",
                                            "Restore failed - that file is not a valid Volt Tracker backup.",
                                            true);
                                }
                            });
                });
    }

    // Replaces the on-device database with a user-picked backup file. The file is staged
    // and verified as a Volt Tracker SQLite database before the live database is touched.
    private boolean applyRestore(Uri uri) {
        File staged = dataBackup.stageRestoreFile(uri);
        if (staged == null) {
            return false;
        }
        try {
            File dbFile =
                    activity.localStore == null ? null : activity.localStore.getDatabaseFile();
            if (dbFile == null) {
                return false;
            }
            // Stop any logging session so the database file is not held open.
            stopLoggingForRestore();
            if (activity.localStore != null) {
                activity.localStore.close();
                activity.localStore = null;
            }
            DataBackup.copyFile(staged, dbFile);
            DataBackup.deleteIfExists(new File(dbFile.getPath() + "-wal"));
            DataBackup.deleteIfExists(new File(dbFile.getPath() + "-shm"));
            activity.localStore = new ObdLocalStore(activity);
            return true;
        } catch (IOException | RuntimeException ex) {
            if (activity.localStore == null) {
                try {
                    activity.localStore = new ObdLocalStore(activity);
                } catch (RuntimeException ignored) {
                    // Nothing more we can do; the next launch will recreate it.
                }
            }
            return false;
        } finally {
            staged.delete();
        }
    }

    private void stopLoggingForRestore() {
        try {
            activity.stopObdService();
        } catch (RuntimeException ignored) {
            // Best effort; restore proceeds regardless.
        }
    }
}
