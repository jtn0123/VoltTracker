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
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Owns the on-device data-backup file IO for {@link MainActivity}: writing the debug summary
 * export, snapshotting the live SQLite database for the share sheet, and staging/verifying a
 * user-picked restore file. The Activity keeps the share-sheet and file-picker intents plus the
 * live-store swap; this class handles only the IO.
 */
final class DataBackup {

    private static final long MAX_RESTORE_BYTES = 128L * 1024L * 1024L;
    private static final int CURRENT_RESTORE_SCHEMA_VERSION = 8;
    private static final int IO_BUFFER_BYTES = 8192;
    private static final byte[] ENCRYPTED_BACKUP_MAGIC =
            new byte[] {'V', 'T', 'B', 'K', 'E', 'N', '1', '\n'};
    private static final int ENCRYPTION_SALT_BYTES = 16;
    private static final int ENCRYPTION_IV_BYTES = 12;
    private static final int ENCRYPTION_KEY_BITS = 256;
    private static final int ENCRYPTION_PBKDF2_ITERATIONS = 150_000;
    private static final String[] REQUIRED_RESTORE_TABLES = {
        "obd_sessions",
        "telemetry_samples",
        "status_events",
        "adapter_history",
        "pid_observations",
        "diagnostic_codes",
        "location_samples",
        "vehicles",
        "field_capabilities",
        "trip_segments",
        "charge_sessions",
        "battery_snapshots",
        "cell_snapshots",
        "exports"
    };
    private static final String[][] REQUIRED_RESTORE_COLUMNS = {
        {"obd_sessions", "_id", "mode", "started_at_ms", "status", "sample_count"},
        {
            "telemetry_samples",
            "_id",
            "session_id",
            "captured_at_ms",
            "pack_voltage",
            "pack_current_a",
            "json"
        },
        {"status_events", "_id", "occurred_at_ms", "kind", "payload"},
        {"adapter_history", "adapter_key", "last_seen_ms", "last_status"},
        {"diagnostic_codes", "_id", "dtc", "status", "last_seen_ms"},
        {"location_samples", "_id", "session_id", "captured_at_ms", "latitude", "longitude"},
        {"vehicles", "_id", "vin_hash", "vin_redacted", "last_seen_ms"},
        {"exports", "_id", "created_at_ms", "export_type", "status"}
    };

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
            File root = context.getExternalFilesDir(null);
            if (root == null) {
                root = context.getCacheDir();
            }
            File dir = new File(root, "exports");
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
        } catch (JSONException | IOException | RuntimeException ex) {
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
     * Builds a passphrase-encrypted portable backup. The plaintext database remains only in the
     * app's private database path; the share-sheet hand-off file is AES-GCM encrypted.
     */
    File buildEncryptedBackupFile(ObdLocalStore store, String passphrase) {
        if (store == null || !hasPassphrase(passphrase)) {
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
            File dest = new File(dir, "volttracker-backup-" + stamp + ".vtdb");
            encryptFile(source, dest, passphrase);
            return dest;
        } catch (IOException | GeneralSecurityException | RuntimeException ex) {
            return null;
        }
    }

    /**
     * Copies a user-picked SAF {@code uri} into a temp cache file and verifies it is a Volt Tracker
     * database. Returns the verified file (caller owns deleting it), or null.
     */
    File stageRestoreFile(Uri uri) {
        return stageRestoreFile(uri, null);
    }

    /**
     * Stages plaintext or encrypted backups. Encrypted backups require the same passphrase used at
     * export time; plaintext backups continue through the existing schema/integrity validation.
     */
    File stageRestoreFile(Uri uri, String passphrase) {
        // UUID-suffixed name so two concurrent restore picks (split-screen, share-sheet retry
        // mid-stage) can't race for the same temp file.
        File temp = new File(context.getCacheDir(), "restore-" + UUID.randomUUID() + ".backup");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
                FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) {
                temp.delete();
                return null;
            }
            byte[] buffer = new byte[IO_BUFFER_BYTES];
            int read;
            long total = 0L;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > MAX_RESTORE_BYTES) {
                    temp.delete();
                    return null;
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException ex) {
            temp.delete();
            return null;
        }
        File candidate = temp;
        if (isEncryptedBackup(temp)) {
            if (!hasPassphrase(passphrase)) {
                temp.delete();
                return null;
            }
            candidate = new File(context.getCacheDir(), "restore-" + UUID.randomUUID() + ".db");
            try {
                decryptFile(temp, candidate, passphrase);
            } catch (IOException | GeneralSecurityException | RuntimeException ex) {
                temp.delete();
                candidate.delete();
                return null;
            }
            temp.delete();
        }
        if (!isVoltTrackerBackup(candidate)) {
            candidate.delete();
            return null;
        }
        return candidate;
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
            if (name.startsWith("volttracker-backup-")
                    && (name.endsWith(".db") || name.endsWith(".vtdb"))) {
                file.delete();
            }
        }
    }

    static boolean isEncryptedBackup(File file) {
        byte[] header = new byte[ENCRYPTED_BACKUP_MAGIC.length];
        try (FileInputStream in = new FileInputStream(file)) {
            if (in.read(header) != header.length) {
                return false;
            }
            for (int i = 0; i < ENCRYPTED_BACKUP_MAGIC.length; i++) {
                if (header[i] != ENCRYPTED_BACKUP_MAGIC[i]) {
                    return false;
                }
            }
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    /**
     * Confirms a restore file is a real Volt Tracker database: a SQLite file at the current schema
     * version, with the app's core tables/columns and no integrity or foreign-key failures. A plain
     * SQLite file with a similar table list would leave the app's queries broken after the swap.
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
            if (db.getVersion() != CURRENT_RESTORE_SCHEMA_VERSION) {
                return false;
            }
            try (Cursor cursor = db.rawQuery(requiredTablesSql(), REQUIRED_RESTORE_TABLES)) {
                if (!cursor.moveToFirst() || cursor.getInt(0) != REQUIRED_RESTORE_TABLES.length) {
                    return false;
                }
            }
            if (!hasRequiredColumns(db)) {
                return false;
            }
            if (!integrityCheckOk(db)) {
                return false;
            }
            return foreignKeyCheckOk(db);
        } catch (RuntimeException ex) {
            return false;
        } finally {
            if (db != null) {
                db.close();
            }
        }
    }

    private static boolean hasRequiredColumns(SQLiteDatabase db) {
        for (String[] tableAndColumns : REQUIRED_RESTORE_COLUMNS) {
            String table = tableAndColumns[0];
            java.util.HashSet<String> columns = new java.util.HashSet<>();
            try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
                int nameIndex = cursor.getColumnIndex("name");
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(nameIndex));
                }
            }
            for (int i = 1; i < tableAndColumns.length; i++) {
                if (!columns.contains(tableAndColumns[i])) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean integrityCheckOk(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("PRAGMA integrity_check", null)) {
            return cursor.moveToFirst() && "ok".equalsIgnoreCase(cursor.getString(0));
        }
    }

    private static boolean foreignKeyCheckOk(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("PRAGMA foreign_key_check", null)) {
            return !cursor.moveToFirst();
        }
    }

    private static String requiredTablesSql() {
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < REQUIRED_RESTORE_TABLES.length; i++) {
            if (i > 0) {
                placeholders.append(", ");
            }
            placeholders.append("?");
        }
        return "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name IN ("
                + placeholders
                + ")";
    }

    private static boolean hasPassphrase(String passphrase) {
        return passphrase != null && !passphrase.trim().isEmpty();
    }

    private static void encryptFile(File source, File dest, String passphrase)
            throws IOException, GeneralSecurityException {
        byte[] salt = new byte[ENCRYPTION_SALT_BYTES];
        byte[] iv = new byte[ENCRYPTION_IV_BYTES];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), new GCMParameterSpec(128, iv));
        try (FileInputStream in = new FileInputStream(source);
                FileOutputStream fileOut = new FileOutputStream(dest)) {
            fileOut.write(ENCRYPTED_BACKUP_MAGIC);
            fileOut.write(salt);
            fileOut.write(iv);
            try (CipherOutputStream out = new CipherOutputStream(fileOut, cipher)) {
                copyStream(in, out);
            }
        }
    }

    private static void decryptFile(File source, File dest, String passphrase)
            throws IOException, GeneralSecurityException {
        byte[] magic = new byte[ENCRYPTED_BACKUP_MAGIC.length];
        byte[] salt = new byte[ENCRYPTION_SALT_BYTES];
        byte[] iv = new byte[ENCRYPTION_IV_BYTES];
        try (FileInputStream fileIn = new FileInputStream(source)) {
            if (fileIn.read(magic) != magic.length || !isMagic(magic)) {
                throw new IOException("Not an encrypted Volt Tracker backup");
            }
            if (fileIn.read(salt) != salt.length || fileIn.read(iv) != iv.length) {
                throw new IOException("Encrypted backup header is truncated");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    deriveKey(passphrase, salt),
                    new GCMParameterSpec(128, iv));
            try (CipherInputStream in = new CipherInputStream(fileIn, cipher);
                    FileOutputStream out = new FileOutputStream(dest)) {
                copyStream(in, out);
                out.getFD().sync();
            }
        }
    }

    private static SecretKeySpec deriveKey(String passphrase, byte[] salt)
            throws GeneralSecurityException {
        PBEKeySpec spec =
                new PBEKeySpec(
                        passphrase.toCharArray(),
                        salt,
                        ENCRYPTION_PBKDF2_ITERATIONS,
                        ENCRYPTION_KEY_BITS);
        try {
            byte[] key =
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                            .generateSecret(spec)
                            .getEncoded();
            return new SecretKeySpec(key, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static boolean isMagic(byte[] header) {
        if (header.length != ENCRYPTED_BACKUP_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < header.length; i++) {
            if (header[i] != ENCRYPTED_BACKUP_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static void copyStream(InputStream in, java.io.OutputStream out) throws IOException {
        byte[] buffer = new byte[IO_BUFFER_BYTES];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
    }

    static void copyFile(File source, File dest) throws IOException {
        try (FileInputStream in = new FileInputStream(source);
                FileOutputStream out = new FileOutputStream(dest)) {
            copyStream(in, out);
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
