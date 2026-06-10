package com.volttracker.obdpoc

import android.content.Context
import android.net.Uri
import com.volttracker.obdpoc.data.BackupMigrator
import com.volttracker.obdpoc.data.ObdLocalStore
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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
        diagnostics.put(
            "sessionCommandTrace",
            recentLogEntries(sessionLogDir, "session-", ".jsonl", MAX_DEBUG_SESSION_LOGS),
        )
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
        if (store == null || !hasMinimumPassphrase(passphrase)) {
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
            BackupCrypto.encryptFile(source, dest, requireNotNull(passphrase))
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
            if (!hasMinimumPassphrase(passphrase)) {
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
                BackupCrypto.decryptFile(temp, candidate, requireNotNull(passphrase), MAX_RESTORE_BYTES)
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
                return RestoreStageOutcome(
                    null,
                    RestoreStageStatus.NOT_A_BACKUP,
                    encrypted = encrypted,
                    bytesRead = total,
                )
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
        const val MIN_PASSPHRASE_LENGTH = 8
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

        private const val IO_BUFFER_BYTES = 8192

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
        fun clearRegenerableRollupCache(file: File?) = RestoreValidator.clearRegenerableRollupCache(file)

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
        fun isEncryptedBackup(file: File?): Boolean = BackupCrypto.isEncryptedBackup(file)

        @JvmStatic
        fun isVoltTrackerBackup(file: File?): Boolean = RestoreValidator.isVoltTrackerBackup(file)

        @JvmStatic
        fun hasMinimumPassphrase(passphrase: String?): Boolean =
            (passphrase?.trim()?.length ?: 0) >= MIN_PASSPHRASE_LENGTH

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
