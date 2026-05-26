package com.volttracker.obdpoc;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import com.volttracker.obdpoc.data.ObdLocalStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Owns the on-device data-backup file IO for {@link MainActivity}: writing the debug summary
 * export, snapshotting the live SQLite database for the share sheet, and staging/verifying a
 * user-picked restore file. The Activity keeps the share-sheet and file-picker intents plus the
 * live-store swap; this class handles only the IO.
 */
final class DataBackup {

    private final Context context;

    DataBackup(Context context) {
        this.context = context;
    }

    /** Writes a debug summary JSON to {@code exports/} and returns a result JSON string. */
    String exportDebugBundle(String appStateJson, String storageJson) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("createdAtMs", System.currentTimeMillis());
            payload.put("appState", MainActivityUtils.parseJson(appStateJson));
            payload.put("storage", MainActivityUtils.parseJson(storageJson));
            File dir = new File(context.getExternalFilesDir(null), "exports");
            if (!dir.exists() && !dir.mkdirs()) {
                payload.put("ok", false);
                payload.put("error", "Could not create export directory.");
                return payload.toString();
            }
            File file =
                    new File(
                            dir,
                            "volttracker-debug-summary-" + System.currentTimeMillis() + ".json");
            payload.put("ok", true);
            payload.put("path", file.getAbsolutePath());
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(payload.toString(2));
            }
        } catch (JSONException | IOException ex) {
            try {
                payload.put("ok", false);
                payload.put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
            } catch (JSONException ignored) {
                // Local strings are safe.
            }
        }
        return payload.toString();
    }

    /**
     * Checkpoints {@code store} and copies the live DB to a transient cache file. Null on failure.
     */
    File buildBackupFile(ObdLocalStore store) {
        if (store == null) {
            return null;
        }
        try {
            store.checkpoint();
            File source = store.getDatabaseFile();
            if (source == null || !source.exists()) {
                return null;
            }
            File dir = new File(context.getCacheDir(), "backups");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            clearOldBackups(dir);
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File dest = new File(dir, "volttracker-backup-" + stamp + ".db");
            copyFile(source, dest);
            return dest;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    /**
     * Copies a user-picked SAF {@code uri} into a temp cache file and verifies it is a Volt Tracker
     * database. Returns the verified file (caller owns deleting it), or null.
     */
    File stageRestoreFile(Uri uri) {
        // UUID-suffixed name so two concurrent restore picks (split-screen, share-sheet retry
        // mid-stage) can't race for the same temp file.
        File temp = new File(context.getCacheDir(), "restore-" + UUID.randomUUID() + ".db");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
                FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) {
                temp.delete();
                return null;
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException ex) {
            temp.delete();
            return null;
        }
        if (!isVoltTrackerBackup(temp)) {
            temp.delete();
            return null;
        }
        return temp;
    }

    private static void clearOldBackups(File dir) {
        File[] existing = dir.listFiles();
        if (existing == null) {
            return;
        }
        for (File file : existing) {
            // Backups are transient hand-off copies; keep only the freshest one. Filter on the
            // filename pattern so unrelated files that happen to land in this dir (e.g. anything
            // a future feature might cache here) aren't blown away on every backup.
            String name = file.getName();
            if (name.startsWith("volttracker-backup-") && name.endsWith(".db")) {
                file.delete();
            }
        }
    }

    /**
     * Confirms a restore file is a real Volt Tracker database: a SQLite file that contains the
     * app's core tables. A plain SQLite file with a foreign schema would leave the app's queries
     * broken after the swap.
     */
    static boolean isVoltTrackerBackup(File file) {
        byte[] header = new byte[16];
        try (FileInputStream in = new FileInputStream(file)) {
            if (in.read(header) != header.length
                    || !new String(header, StandardCharsets.US_ASCII)
                            .startsWith("SQLite format 3")) {
                return false;
            }
        } catch (IOException ex) {
            return false;
        }
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            try (Cursor cursor =
                    db.rawQuery(
                            "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN (?, ?)",
                            new String[] {"obd_sessions", "telemetry_samples"})) {
                return cursor.getCount() == 2;
            }
        } catch (RuntimeException ex) {
            return false;
        } finally {
            if (db != null) {
                db.close();
            }
        }
    }

    static void copyFile(File source, File dest) throws IOException {
        try (FileInputStream in = new FileInputStream(source);
                FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            // fsync so a process death immediately after this copy can't leave a truncated
            // file on disk. The next launch would otherwise treat the partial copy as the
            // live DB and corrupt user data on the first migration / write.
            out.getFD().sync();
        }
    }

    static void deleteIfExists(File file) {
        if (file.exists()) {
            file.delete();
        }
    }
}
