package com.volttracker.obdpoc;

import android.app.Activity;
import android.app.AlertDialog;
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
    private static final long RESTORE_STOP_TIMEOUT_MS = 2_000L;

    private final MainActivity activity;
    private final DataBackup dataBackup;
    private final ExecutorService executor;
    private String pendingRestorePassphrase;

    private enum RestoreResult {
        OK,
        INVALID_FILE,
        LOGGING_ACTIVE,
        OTHER
    }

    BackupController(MainActivity activity, DataBackup dataBackup, ExecutorService executor) {
        this.activity = activity;
        this.dataBackup = dataBackup;
        this.executor = executor;
    }

    // Produces a complete on-device data backup and hands it to the Android share sheet
    // so the user can save it anywhere (cloud, PC) — no server involved.
    void launchShare() {
        // Explicit PII disclosure before any file is built: the user needs to know what
        // they're about to hand to the share sheet (and from there, potentially to a third
        // party). The list mirrors what DataBackup.buildBackupFile actually exports.
        showShareDisclosure(this::performBackupAndShare);
    }

    void launchEncryptedShare(String passphrase) {
        if (!hasPassphrase(passphrase)) {
            activity.publishStatus("blocked", "Enter a backup passphrase first.", true);
            return;
        }
        showShareDisclosure(() -> performBackupAndShare(true, passphrase));
    }

    // Visible for tests / future toggles; kept package-private.
    String shareDisclosureMessage() {
        return "This backup is a full copy of Volt Tracker's on-device database and contains:\n"
                + "• Raw OBD session logs and telemetry\n"
                + "• Precise GPS coordinates from your trips\n"
                + "• Bluetooth adapter addresses\n"
                + "• Redacted VIN and vehicle records if your car shared them\n"
                + "\n"
                + "Encrypted backups are protected by your passphrase; plaintext backups are not.\n"
                + "Only share with people you trust.";
    }

    private void showShareDisclosure(Runnable onConfirmed) {
        try {
            new AlertDialog.Builder(activity)
                    .setTitle("Share Volt Tracker backup")
                    .setMessage(shareDisclosureMessage())
                    .setPositiveButton("Share anyway", (dialog, which) -> onConfirmed.run())
                    .setNegativeButton(
                            "Cancel",
                            (dialog, which) ->
                                    activity.publishStatus("ready", "Backup cancelled.", false))
                    .setOnCancelListener(
                            dialog -> activity.publishStatus("ready", "Backup cancelled.", false))
                    .show();
        } catch (RuntimeException ex) {
            // If we cannot even display the disclosure (e.g. activity finishing), do NOT
            // silently fall through to sharing — refuse and surface the failure.
            activity.publishStatus("blocked", "Could not show the backup disclosure.", true);
        }
    }

    private void performBackupAndShare() {
        performBackupAndShare(false, null);
    }

    private void performBackupAndShare(boolean encrypted, String passphrase) {
        activity.publishStatus(
                "ready",
                encrypted ? "Preparing encrypted data backup..." : "Preparing data backup...",
                false);
        executor.execute(
                () -> {
                    final File backup =
                            encrypted
                                    ? dataBackup.buildEncryptedBackupFile(
                                            activity.localStore, passphrase)
                                    : dataBackup.buildBackupFile(activity.localStore);
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
                                            encrypted
                                                    ? "Encrypted backup ready - choose where to save it."
                                                    : "Backup ready - choose where to save it.",
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
        if (requestCode != REQUEST_RESTORE) {
            return;
        }
        if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            String passphrase = pendingRestorePassphrase;
            pendingRestorePassphrase = null;
            restoreFromUri(data.getData(), passphrase);
        } else {
            pendingRestorePassphrase = null;
        }
    }

    void launchRestorePicker() {
        launchRestorePicker(null);
    }

    void launchEncryptedRestorePicker(String passphrase) {
        if (!hasPassphrase(passphrase)) {
            activity.publishStatus("blocked", "Enter the backup passphrase first.", true);
            return;
        }
        launchRestorePicker(passphrase);
    }

    private void launchRestorePicker(String passphrase) {
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
            pendingRestorePassphrase = passphrase;
            activity.startActivityForResult(pick, REQUEST_RESTORE);
        } catch (RuntimeException ex) {
            pendingRestorePassphrase = null;
            activity.publishStatus("blocked", "Could not open the file picker.", true);
        }
    }

    private void restoreFromUri(Uri uri, String passphrase) {
        activity.publishStatus("ready", "Restoring backup...", false);
        executor.execute(
                () -> {
                    final RestoreResult result = applyRestore(uri, passphrase);
                    activity.runOnUiThread(
                            () -> {
                                if (result == RestoreResult.OK) {
                                    activity.publishDeviceList();
                                    activity.publishStorageSummary();
                                    activity.publishStatus(
                                            "ready",
                                            "Backup restored - reconnect to resume logging.",
                                            false);
                                } else {
                                    publishRestoreFailure(result);
                                }
                            });
                });
    }

    private void publishRestoreFailure(RestoreResult result) {
        String message;
        if (result == RestoreResult.LOGGING_ACTIVE) {
            message = "Restore failed - logging is still active. Stop logging and try again.";
        } else if (result == RestoreResult.INVALID_FILE) {
            message = "Restore failed - that file is not a valid Volt Tracker backup.";
        } else {
            message = "Restore failed - could not replace the on-device database.";
        }
        activity.publishStatus("blocked", message, true);
    }

    // Replaces the on-device database with a user-picked backup file. The file is staged
    // and verified as a Volt Tracker SQLite database before the live database is touched.
    private RestoreResult applyRestore(Uri uri, String passphrase) {
        File staged = dataBackup.stageRestoreFile(uri, passphrase);
        if (staged == null) {
            return RestoreResult.INVALID_FILE;
        }
        try {
            File dbFile =
                    activity.localStore == null ? null : activity.localStore.getDatabaseFile();
            if (dbFile == null) {
                return RestoreResult.OTHER;
            }
            // Stop any logging session so the database file is not held open.
            if (!stopLoggingForRestore()) {
                return RestoreResult.LOGGING_ACTIVE;
            }
            if (activity.localStore != null) {
                activity.localStore.close();
                activity.localStore = null;
            }
            File restoreTemp = new File(dbFile.getPath() + ".restore-tmp");
            File restoreBackup = new File(dbFile.getPath() + ".restore-backup");
            DataBackup.deleteIfExists(restoreTemp);
            DataBackup.deleteIfExists(restoreBackup);
            DataBackup.copyFile(staged, restoreTemp);
            if (dbFile.exists()) {
                DataBackup.copyFile(dbFile, restoreBackup);
            }
            try {
                DataBackup.copyFile(restoreTemp, dbFile);
            } catch (IOException | RuntimeException ex) {
                restoreOriginalDatabase(dbFile, restoreBackup);
                throw ex;
            }
            DataBackup.deleteIfExists(new File(dbFile.getPath() + "-wal"));
            DataBackup.deleteIfExists(new File(dbFile.getPath() + "-shm"));
            try {
                activity.localStore = new ObdLocalStore(activity);
            } catch (RuntimeException ex) {
                restoreOriginalDatabase(dbFile, restoreBackup);
                activity.localStore = new ObdLocalStore(activity);
                throw ex;
            }
            DataBackup.deleteIfExists(restoreTemp);
            DataBackup.deleteIfExists(restoreBackup);
            return RestoreResult.OK;
        } catch (IOException | RuntimeException ex) {
            if (activity.localStore == null) {
                try {
                    activity.localStore = new ObdLocalStore(activity);
                } catch (RuntimeException ignored) {
                    // Nothing more we can do; the next launch will recreate it.
                }
            }
            return RestoreResult.OTHER;
        } finally {
            staged.delete();
        }
    }

    private static boolean hasPassphrase(String passphrase) {
        return passphrase != null && !passphrase.trim().isEmpty();
    }

    private static void restoreOriginalDatabase(File dbFile, File restoreBackup) {
        if (restoreBackup == null || !restoreBackup.exists()) {
            return;
        }
        try {
            DataBackup.copyFile(restoreBackup, dbFile);
            DataBackup.deleteIfExists(new File(dbFile.getPath() + "-wal"));
            DataBackup.deleteIfExists(new File(dbFile.getPath() + "-shm"));
        } catch (IOException | RuntimeException ignored) {
            // The Activity will attempt to reopen the store in the caller's catch path.
        }
    }

    private boolean stopLoggingForRestore() {
        if (!activity.isLoggingActive()) {
            return true;
        }
        try {
            activity.stopObdService();
        } catch (RuntimeException ex) {
            return false;
        }
        long deadline = System.currentTimeMillis() + RESTORE_STOP_TIMEOUT_MS;
        while (activity.isLoggingActive() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !activity.isLoggingActive();
    }
}
