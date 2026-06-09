package com.volttracker.obdpoc

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.volttracker.obdpoc.data.BackupMigrator
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.data.VoltTrackerDb
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Owns on-device backup file IO for [MainActivity]. */
class DataBackup(
    private val context: Context,
) {
    init {
        cleanupTransientRestoreFiles(context.cacheDir)
        cleanupTransientBackupFiles(File(context.cacheDir, "backups"))
        cleanupTransientExportFiles(File(context.cacheDir, "exports"))
    }

    fun exportDebugBundle(
        appStateJson: String?,
        storageJson: String?,
    ): String {
        val payload = JSONObject()
        try {
            payload.put("createdAtMs", System.currentTimeMillis())
            payload.put("appState", MainActivityUtils.parseJson(appStateJson))
            payload.put("storage", MainActivityUtils.parseJson(storageJson))
            payload.put("diagnostics", buildDiagnosticsSnapshot())
            // Internal cache, not external storage: the debug bundle contains app state + storage
            // summary plus bounded diagnostic log excerpts in cleartext. App-scoped EXTERNAL
            // storage is reachable via adb/backup tooling; internal cacheDir is private and gets
            // swept on init.
            val dir = File(context.cacheDir, "exports")
            if (!dir.exists() && !dir.mkdirs()) {
                payload.put("ok", false)
                payload.put("error", "Could not create export directory.")
                return payload.toString()
            }
            val file = File(dir, "volttracker-debug-summary-${System.currentTimeMillis()}.json")
            payload.put("ok", true)
            payload.put("path", file.absolutePath)
            FileWriter(file).use { writer -> writer.write(payload.toString(2)) }
        } catch (ex: Exception) {
            if (ex is JSONException || ex is IOException || ex is RuntimeException) {
                try {
                    payload.put("ok", false)
                    payload.put("error", "${ex.javaClass.simpleName}: ${ex.message}")
                } catch (ignored: JSONException) {
                    // Local strings are safe.
                }
            } else {
                throw ex
            }
        }
        return payload.toString()
    }

    private fun buildDiagnosticsSnapshot(): JSONObject {
        val diagnostics = JSONObject()
        val sessionLogDir = File(context.filesDir, "obd-logs")
        val appLogDir = File(context.filesDir, "app-log")
        diagnostics.put("sessionCommandTrace", recentLogEntries(sessionLogDir, "session-", ".jsonl", MAX_DEBUG_SESSION_LOGS))
        diagnostics.put("appLog", recentLogEntries(appLogDir, "app.log", "", MAX_DEBUG_APP_LOGS))
        return diagnostics
    }

    private fun recentLogEntries(
        dir: File,
        prefix: String,
        suffix: String,
        maxFiles: Int,
    ): JSONArray {
        val entries = JSONArray()
        val files =
            dir
                .listFiles { file ->
                    if (!file.isFile) {
                        false
                    } else if (prefix == "app.log") {
                        file.name == "app.log" || file.name == "app.log.1"
                    } else {
                        file.name.startsWith(prefix) && (suffix.isEmpty() || file.name.endsWith(suffix))
                    }
                }?.sortedByDescending { it.lastModified() }
                ?.take(maxFiles)
                ?: return entries
        for (file in files) {
            val item = JSONObject()
            item.put("name", file.name)
            item.put("lastModifiedMs", file.lastModified())
            item.put("bytes", file.length())
            try {
                val text = readTailText(file, MAX_DEBUG_LOG_BYTES)
                item.put("truncated", file.length() > MAX_DEBUG_LOG_BYTES)
                item.put("text", redactDebugLogText(text))
            } catch (ex: IOException) {
                item.put("error", "${ex.javaClass.simpleName}: ${ex.message}")
            }
            entries.put(item)
        }
        return entries
    }

    @Throws(IOException::class)
    private fun readTailText(
        file: File,
        maxBytes: Int,
    ): String {
        val length = file.length()
        val skipBytes = maxOf(0L, length - maxBytes.toLong())
        FileInputStream(file).use { input ->
            var remaining = skipBytes
            while (remaining > 0L) {
                val skipped = input.skip(remaining)
                if (skipped <= 0L) {
                    break
                }
                remaining -= skipped
            }
            val bytes = input.readBytes()
            return String(bytes, StandardCharsets.UTF_8)
        }
    }

    fun buildBackupFile(store: ObdLocalStore?): File? {
        if (store == null) {
            return null
        }
        return try {
            store.checkpoint()
            val source = store.getDatabaseFile()
            if (!source.exists()) {
                return null
            }
            val dir = File(context.cacheDir, "backups")
            if (!dir.exists() && !dir.mkdirs()) {
                return null
            }
            clearOldBackups(dir)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val dest = File(dir, "volttracker-backup-$stamp.db")
            copyFile(source, dest)
            dest
        } catch (ex: Exception) {
            if (ex is IOException || ex is RuntimeException) null else throw ex
        }
    }

    fun buildEncryptedBackupFile(
        store: ObdLocalStore?,
        passphrase: String?,
    ): File? {
        if (store == null || !hasPassphrase(passphrase)) {
            return null
        }
        return try {
            store.checkpoint()
            val source = store.getDatabaseFile()
            if (!source.exists()) {
                return null
            }
            val dir = File(context.cacheDir, "backups")
            if (!dir.exists() && !dir.mkdirs()) {
                return null
            }
            clearOldBackups(dir)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val dest = File(dir, "volttracker-backup-$stamp.vtdb")
            encryptFile(source, dest, requireNotNull(passphrase))
            dest
        } catch (ex: Exception) {
            if (ex is IOException || ex is GeneralSecurityException || ex is RuntimeException) null else throw ex
        }
    }

    data class RestoreStageOutcome(
        val file: File?,
        val status: RestoreStageStatus,
        val encrypted: Boolean = false,
        val migrated: Boolean = false,
        val bytesRead: Long = 0L,
    ) {
        val ok: Boolean
            get() = status == RestoreStageStatus.OK && file != null
    }

    enum class RestoreStageStatus {
        OK,
        NO_FILE,
        OPEN_FAILED,
        TOO_LARGE,
        MISSING_PASSPHRASE,
        DECRYPT_FAILED,
        TOO_NEW,
        NOT_A_BACKUP,
        MIGRATION_FAILED,
    }

    fun stageRestoreFile(uri: Uri?): File? = stageRestoreFile(uri, null)

    fun stageRestoreFile(
        uri: Uri?,
        passphrase: String?,
    ): File? = stageRestoreFileWithStatus(uri, passphrase).file

    fun stageRestoreFileWithStatus(
        uri: Uri?,
        passphrase: String?,
    ): RestoreStageOutcome {
        if (uri == null) {
            return RestoreStageOutcome(null, RestoreStageStatus.NO_FILE)
        }
        val temp = File(context.cacheDir, "restore-${UUID.randomUUID()}.backup")
        var total = 0L
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                FileOutputStream(temp).use { out ->
                    if (input == null) {
                        temp.delete()
                        return RestoreStageOutcome(null, RestoreStageStatus.OPEN_FAILED)
                    }
                    val buffer = ByteArray(IO_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) {
                            break
                        }
                        total += read.toLong()
                        if (total > MAX_RESTORE_BYTES) {
                            temp.delete()
                            return RestoreStageOutcome(null, RestoreStageStatus.TOO_LARGE, bytesRead = total)
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }
        } catch (ex: Exception) {
            if (ex is IOException || ex is RuntimeException) {
                temp.delete()
                return RestoreStageOutcome(null, RestoreStageStatus.OPEN_FAILED, bytesRead = total)
            }
            throw ex
        }

        var candidate = temp
        var encrypted = false
        if (isEncryptedBackup(temp)) {
            encrypted = true
            if (!hasPassphrase(passphrase)) {
                temp.delete()
                return RestoreStageOutcome(
                    null,
                    RestoreStageStatus.MISSING_PASSPHRASE,
                    encrypted = true,
                    bytesRead = total,
                )
            }
            candidate = File(context.cacheDir, "restore-${UUID.randomUUID()}.db")
            try {
                decryptFile(temp, candidate, requireNotNull(passphrase), MAX_RESTORE_BYTES)
            } catch (ex: Exception) {
                if (ex is IOException || ex is GeneralSecurityException || ex is RuntimeException) {
                    temp.delete()
                    candidate.delete()
                    return RestoreStageOutcome(
                        null,
                        RestoreStageStatus.DECRYPT_FAILED,
                        encrypted = true,
                        bytesRead = total,
                    )
                }
                throw ex
            }
            temp.delete()
        }

        val migration = BackupMigrator.migrateToCurrentVersion(context, candidate)
        when (migration) {
            BackupMigrator.Result.TOO_NEW -> {
                candidate.delete()
                return RestoreStageOutcome(null, RestoreStageStatus.TOO_NEW, encrypted = encrypted, bytesRead = total)
            }
            BackupMigrator.Result.NOT_A_BACKUP -> {
                candidate.delete()
                return RestoreStageOutcome(null, RestoreStageStatus.NOT_A_BACKUP, encrypted = encrypted, bytesRead = total)
            }
            BackupMigrator.Result.FAILED -> {
                candidate.delete()
                return RestoreStageOutcome(
                    null,
                    RestoreStageStatus.MIGRATION_FAILED,
                    encrypted = encrypted,
                    bytesRead = total,
                )
            }
            BackupMigrator.Result.ALREADY_CURRENT,
            BackupMigrator.Result.MIGRATED,
            -> {
                // Continue validation below.
            }
        }
        if (!isVoltTrackerBackup(candidate)) {
            candidate.delete()
            return RestoreStageOutcome(null, RestoreStageStatus.NOT_A_BACKUP, encrypted = encrypted, bytesRead = total)
        }
        clearRegenerableRollupCache(candidate)
        return RestoreStageOutcome(
            candidate,
            RestoreStageStatus.OK,
            encrypted = encrypted,
            migrated = migration == BackupMigrator.Result.MIGRATED,
            bytesRead = total,
        )
    }

    companion object {
        internal const val MAX_RESTORE_MIB = 512L
        internal const val MAX_RESTORE_BYTES = MAX_RESTORE_MIB * 1024L * 1024L
        private const val MAX_DEBUG_LOG_BYTES = 64 * 1024
        private const val MAX_DEBUG_SESSION_LOGS = 3
        private const val MAX_DEBUG_APP_LOGS = 2
        private val BLUETOOTH_ADDRESS_RE = Regex("\\b[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}\\b")
        private val VIN_RE = Regex("\\b[A-HJ-NPR-Za-hj-npr-z0-9]{17}\\b")
        private val COORDINATE_FIELD_RE =
            Regex(
                "(\"(?:latitude|longitude|lat|lng)\"\\s*:\\s*)-?\\d{1,3}(?:\\.\\d+)?",
                RegexOption.IGNORE_CASE,
            )

        // Track the live schema version so a future migration bump can't silently make restore
        // reject freshly-created backups (a v11 backup must validate as current, not "too new").
        private const val CURRENT_RESTORE_SCHEMA_VERSION = VoltTrackerDb.DATABASE_VERSION
        private const val IO_BUFFER_BYTES = 8192
        private val ENCRYPTED_BACKUP_MAGIC =
            byteArrayOf(
                'V'.code.toByte(),
                'T'.code.toByte(),
                'B'.code.toByte(),
                'K'.code.toByte(),
                'E'.code.toByte(),
                'N'.code.toByte(),
                '1'.code.toByte(),
                '\n'.code.toByte(),
            )

        // v2 = the v1 magic with the version byte ('1' at index 6) bumped to '2'. v2 backups feed
        // the magic+salt+IV header to AES/GCM as AAD, so the auth tag authenticates the cleartext
        // header (not just the ciphertext). v1 backups predate this; the decrypt branch below skips
        // AAD for them so they still open. Same 8-byte length, so header parsing is unchanged.
        private val ENCRYPTED_BACKUP_MAGIC_V2 =
            ENCRYPTED_BACKUP_MAGIC.copyOf().also { it[6] = '2'.code.toByte() }
        private const val ENCRYPTION_SALT_BYTES = 16
        private const val ENCRYPTION_IV_BYTES = 12
        private const val ENCRYPTION_KEY_BITS = 256
        private const val ENCRYPTION_PBKDF2_ITERATIONS = 150_000
        private val REQUIRED_RESTORE_TABLES =
            arrayOf(
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
                "session_trip_rollups",
                "charge_sessions",
                "battery_snapshots",
                "cell_snapshots",
                "exports",
            )
        private val REQUIRED_RESTORE_COLUMNS =
            arrayOf(
                arrayOf("obd_sessions", "_id", "mode", "started_at_ms", "status", "sample_count"),
                arrayOf("telemetry_samples", "_id", "session_id", "captured_at_ms", "pack_voltage", "pack_current_a", "json"),
                arrayOf("status_events", "_id", "occurred_at_ms", "kind", "payload"),
                arrayOf("adapter_history", "adapter_key", "last_seen_ms", "last_status"),
                arrayOf("pid_observations", "_id", "session_id", "observed_at_ms", "json"),
                arrayOf("diagnostic_codes", "_id", "dtc", "status", "last_seen_ms"),
                arrayOf("location_samples", "_id", "session_id", "captured_at_ms", "latitude", "longitude"),
                arrayOf("vehicles", "_id", "vin_hash", "vin_redacted", "last_seen_ms"),
                arrayOf("field_capabilities", "_id", "command", "first_seen_ms", "last_seen_ms"),
                arrayOf("trip_segments", "_id", "started_at_ms", "created_at_ms"),
                arrayOf("session_trip_rollups", "session_id", "counted", "distance_m", "duration_ms", "started_at_ms", "rollup_version"),
                arrayOf("charge_sessions", "_id", "started_at_ms", "created_at_ms"),
                arrayOf("battery_snapshots", "_id", "captured_at_ms", "created_at_ms"),
                arrayOf("cell_snapshots", "_id", "battery_snapshot_id", "cell_index"),
                arrayOf("exports", "_id", "created_at_ms", "export_type", "status"),
            )

        @JvmStatic
        fun redactDebugLogText(text: String?): String {
            if (text.isNullOrEmpty()) {
                return ""
            }
            return text
                .replace(BLUETOOTH_ADDRESS_RE, "[bluetooth-address-redacted]")
                .replace(VIN_RE, "[vin-redacted]")
                .replace(COORDINATE_FIELD_RE) { match ->
                    match.groupValues[1] + "\"[coordinate-redacted]\""
                }
        }

        @JvmStatic
        fun clearRegenerableRollupCache(file: File?) {
            if (file == null) {
                return
            }
            var db: SQLiteDatabase? = null
            try {
                db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
                db.execSQL("DELETE FROM session_trip_rollups")
            } catch (ex: RuntimeException) {
                // Best-effort: the cache rebuilds lazily.
            } finally {
                db?.close()
            }
        }

        private fun clearOldBackups(dir: File) {
            cleanupTransientBackupFiles(dir)
        }

        private fun cleanupTransientBackupFiles(dir: File?) {
            val existing = dir?.listFiles() ?: return
            for (file in existing) {
                val name = file.name
                if (name.startsWith("volttracker-backup-") && (name.endsWith(".db") || name.endsWith(".vtdb"))) {
                    file.delete()
                }
            }
        }

        private fun cleanupTransientRestoreFiles(cacheDir: File?) {
            val existing = cacheDir?.listFiles() ?: return
            for (file in existing) {
                val name = file.name
                if (name.startsWith("restore-") && (name.endsWith(".backup") || name.endsWith(".db"))) {
                    file.delete()
                }
            }
        }

        private fun cleanupTransientExportFiles(dir: File?) {
            val existing = dir?.listFiles() ?: return
            for (file in existing) {
                val name = file.name
                if (name.startsWith("volttracker-debug-summary-") && name.endsWith(".json")) {
                    file.delete()
                }
            }
        }

        @JvmStatic
        fun isEncryptedBackup(file: File?): Boolean {
            if (file == null) {
                return false
            }
            val header = ByteArray(ENCRYPTED_BACKUP_MAGIC.size)
            return try {
                FileInputStream(file).use { input ->
                    if (input.read(header) != header.size) {
                        return false
                    }
                    isMagic(header)
                }
            } catch (ex: IOException) {
                false
            }
        }

        @JvmStatic
        fun isVoltTrackerBackup(file: File?): Boolean {
            if (file == null) {
                return false
            }
            val header = ByteArray(16)
            try {
                FileInputStream(file).use { input ->
                    if (input.read(header) != header.size ||
                        !String(header, StandardCharsets.US_ASCII).startsWith("SQLite format 3")
                    ) {
                        return false
                    }
                }
            } catch (ex: IOException) {
                return false
            }

            var db: SQLiteDatabase? = null
            return try {
                db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
                if (db.version != CURRENT_RESTORE_SCHEMA_VERSION) {
                    return false
                }
                db.rawQuery(requiredTablesSql(), REQUIRED_RESTORE_TABLES).use { cursor ->
                    if (!cursor.moveToFirst() || cursor.getInt(0) != REQUIRED_RESTORE_TABLES.size) {
                        return false
                    }
                }
                if (!hasRequiredColumns(db)) {
                    return false
                }
                if (!integrityCheckOk(db)) {
                    return false
                }
                foreignKeyCheckOk(db)
            } catch (ex: RuntimeException) {
                false
            } finally {
                db?.close()
            }
        }

        private fun hasRequiredColumns(db: SQLiteDatabase): Boolean {
            for (tableAndColumns in REQUIRED_RESTORE_COLUMNS) {
                val table = tableAndColumns[0]
                val columns = HashSet<String>()
                db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        columns.add(cursor.getString(nameIndex))
                    }
                }
                for (i in 1 until tableAndColumns.size) {
                    if (!columns.contains(tableAndColumns[i])) {
                        return false
                    }
                }
            }
            return true
        }

        private fun integrityCheckOk(db: SQLiteDatabase): Boolean =
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                cursor.moveToFirst() && "ok".equals(cursor.getString(0), ignoreCase = true)
            }

        private fun foreignKeyCheckOk(db: SQLiteDatabase): Boolean =
            db.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                !cursor.moveToFirst()
            }

        private fun requiredTablesSql(): String {
            val placeholders = REQUIRED_RESTORE_TABLES.joinToString(", ") { "?" }
            return "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name IN ($placeholders)"
        }

        private fun hasPassphrase(passphrase: String?): Boolean = !passphrase?.trim().isNullOrEmpty()

        @Throws(IOException::class, GeneralSecurityException::class)
        private fun encryptFile(
            source: File,
            dest: File,
            passphrase: String,
        ) {
            val salt = ByteArray(ENCRYPTION_SALT_BYTES)
            val iv = ByteArray(ENCRYPTION_IV_BYTES)
            SecureRandom().apply {
                nextBytes(salt)
                nextBytes(iv)
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, iv))
            // Authenticate the cleartext header into the GCM tag (must precede any encryption).
            cipher.updateAAD(ENCRYPTED_BACKUP_MAGIC_V2)
            cipher.updateAAD(salt)
            cipher.updateAAD(iv)
            FileInputStream(source).use { input ->
                FileOutputStream(dest).use { fileOut ->
                    fileOut.write(ENCRYPTED_BACKUP_MAGIC_V2)
                    fileOut.write(salt)
                    fileOut.write(iv)
                    CipherOutputStream(fileOut, cipher).use { out -> copyStream(input, out) }
                }
            }
        }

        @Throws(IOException::class, GeneralSecurityException::class)
        private fun decryptFile(
            source: File,
            dest: File,
            passphrase: String,
            maxPlaintextBytes: Long,
        ) {
            val magic = ByteArray(ENCRYPTED_BACKUP_MAGIC.size)
            val salt = ByteArray(ENCRYPTION_SALT_BYTES)
            val iv = ByteArray(ENCRYPTION_IV_BYTES)
            FileInputStream(source).use { fileIn ->
                if (fileIn.read(magic) != magic.size || !isMagic(magic)) {
                    throw IOException("Not an encrypted Volt Tracker backup")
                }
                if (fileIn.read(salt) != salt.size || fileIn.read(iv) != iv.size) {
                    throw IOException("Encrypted backup header is truncated")
                }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, iv))
                // v2 backups authenticated the header as AAD at encrypt time; feed the SAME bytes
                // back (a tampered header then fails the tag check). v1 backups had no AAD, so adding
                // it would break their tag — skip it for them.
                if (magic.contentEquals(ENCRYPTED_BACKUP_MAGIC_V2)) {
                    cipher.updateAAD(magic)
                    cipher.updateAAD(salt)
                    cipher.updateAAD(iv)
                }
                CipherInputStream(fileIn, cipher).use { input ->
                    FileOutputStream(dest).use { out ->
                        copyStream(input, out, maxPlaintextBytes)
                        out.fd.sync()
                    }
                }
            }
        }

        @Throws(GeneralSecurityException::class)
        private fun deriveKey(
            passphrase: String,
            salt: ByteArray,
        ): SecretKeySpec {
            val spec = PBEKeySpec(passphrase.toCharArray(), salt, ENCRYPTION_PBKDF2_ITERATIONS, ENCRYPTION_KEY_BITS)
            return try {
                val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
                SecretKeySpec(key, "AES")
            } finally {
                spec.clearPassword()
            }
        }

        private fun isMagic(header: ByteArray): Boolean =
            header.contentEquals(ENCRYPTED_BACKUP_MAGIC) || header.contentEquals(ENCRYPTED_BACKUP_MAGIC_V2)

        @Throws(IOException::class)
        private fun copyStream(
            input: InputStream,
            out: OutputStream,
        ) {
            copyStream(input, out, Long.MAX_VALUE)
        }

        @Throws(IOException::class)
        private fun copyStream(
            input: InputStream,
            out: OutputStream,
            maxBytes: Long,
        ) {
            val buffer = ByteArray(IO_BUFFER_BYTES)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                total += read.toLong()
                if (total > maxBytes) {
                    throw IOException("Restore file exceeds size limit")
                }
                out.write(buffer, 0, read)
            }
        }

        @JvmStatic
        @Throws(IOException::class)
        fun copyFile(
            source: File,
            dest: File,
        ) {
            FileInputStream(source).use { input ->
                FileOutputStream(dest).use { out ->
                    copyStream(input, out)
                    out.fd.sync()
                }
            }
        }

        @JvmStatic
        @Throws(IOException::class)
        fun renameFile(
            source: File,
            dest: File,
        ) {
            if (!source.renameTo(dest)) {
                throw IOException("Could not move $source to $dest")
            }
        }

        @JvmStatic
        fun deleteIfExists(file: File?) {
            if (file != null && file.exists()) {
                file.delete()
            }
        }
    }
}
