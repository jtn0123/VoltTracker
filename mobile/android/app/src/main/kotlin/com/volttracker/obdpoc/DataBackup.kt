package com.volttracker.obdpoc

import android.content.Context
import android.net.Uri
import android.util.Log
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
import java.io.InterruptedIOException
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
    /**
     * Best-effort sweep of leftover partial restore/backup/export temp files from prior runs. This
     * used to run in `init {}` on whatever thread constructs DataBackup — i.e. the cold-start main
     * thread (MainActivity.onCreate) — costing three cache-dir traversals before first frame. It is
     * pure housekeeping (nothing depends on it having completed; the export/backup paths recreate
     * their own directories on demand), so MainActivity now schedules it on its background executor.
     */
    fun sweepTransientCacheFiles() {
        cleanupTransientRestoreFiles(context.cacheDir)
        cleanupTransientBackupFiles(File(context.cacheDir, "backups"))
        cleanupTransientExportFiles(File(context.cacheDir, "exports"))
    }

    fun exportDebugBundle(
        appStateJson: String?,
        storageJson: String?,
    ): String {
        val result = JSONObject()
        try {
            val payload = JSONObject()
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
                result.put("ok", false)
                result.put("error", "Could not create export directory.")
                return result.toString()
            }
            val file = File(dir, "volttracker-debug-summary-${System.currentTimeMillis()}.json")
            val content = payload.toString(2)
            FileWriter(file).use { writer -> writer.write(content) }
            result.put("ok", true)
            result.put("path", file.absolutePath)
            result.put("filename", file.name)
            result.put("content", content)
        } catch (ex: Exception) {
            if (ex is JSONException || ex is IOException || ex is RuntimeException) {
                try {
                    result.put("ok", false)
                    result.put("error", "${ex.javaClass.simpleName}: ${ex.message}")
                } catch (ignored: JSONException) {
                    // Local strings are safe.
                }
            } else {
                throw ex
            }
        }
        return result.toString()
    }

    private fun buildDiagnosticsSnapshot(): JSONObject {
        val diagnostics = JSONObject()
        val sessionLogDir = File(context.filesDir, "obd-logs")
        val appLogDir = File(context.filesDir, "app-log")
        diagnostics.put(
            "sessionCommandTrace",
            recentLogEntries(sessionLogDir, "session-", ".jsonl", MAX_DEBUG_SESSION_LOGS),
        )
        diagnostics.put("appLog", recentLogEntries(appLogDir, APP_LOG_SENTINEL, "", MAX_DEBUG_APP_LOGS))
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
                    } else if (prefix == APP_LOG_SENTINEL) {
                        file.name in APP_LOG_NAMES
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
            // When the tail window starts mid-character (skipBytes > 0 can land inside a
            // multi-byte UTF-8 sequence), drop the leading continuation bytes (10xxxxxx) so the
            // excerpt begins on a valid character. Otherwise the first line decodes with U+FFFD
            // garbling, which can also defeat the redaction regexes (Bluetooth address / VIN /
            // coordinates) on that line.
            var start = 0
            if (skipBytes > 0L) {
                while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) {
                    start++
                }
            }
            var text = String(bytes, start, bytes.size - start, StandardCharsets.UTF_8)
            if (skipBytes > 0L) {
                // The window also starts mid-line: drop the partial first line so a Bluetooth
                // address / VIN / coordinate that straddles the cut cannot evade the redaction
                // regexes (which require whole, anchored tokens). Mirrors DiagnosticsBundle.readTail.
                // If there is no newline at all, the entire window is one partial line we can't
                // safely redact, so drop it rather than leak a truncated token.
                val newline = text.indexOf('\n')
                text = if (newline >= 0) text.substring(newline + 1) else ""
            }
            return text
        }
    }

    data class ProgressSnapshot(
        val phase: String,
        val detail: String? = null,
        val bytesDone: Long = -1L,
        val bytesTotal: Long = -1L,
        val rowsDone: Long = -1L,
        val rowsTotal: Long = -1L,
        /** True when this snapshot reports a non-fatal problem the user should see (work continues). */
        val warning: Boolean = false,
    )

    fun interface ProgressListener {
        fun onProgress(snapshot: ProgressSnapshot)
    }

    fun buildBackupFile(
        store: ObdLocalStore?,
        progress: ProgressListener? = null,
    ): File? {
        if (store == null) {
            return null
        }
        return try {
            progress?.onProgress(
                ProgressSnapshot(
                    "Preparing backup",
                    "Making the latest database writes safe to copy.",
                ),
            )
            store.checkpoint()
            warnIfIntegrityCheckFails(store, progress)
            val source = store.getDatabaseFile()
            if (!source.exists()) {
                return null
            }
            val dir = File(context.cacheDir, "backups")
            if (!dir.exists() && !dir.mkdirs()) {
                return null
            }
            clearOldBackups(dir)
            val dest = File(dir, backupFileName("db"))
            copyFile(
                source,
                dest,
                progress,
                "Writing backup",
                "Copying your on-phone Volt Tracker data.",
            )
            dest
        } catch (ex: Exception) {
            if (ex is IOException || ex is RuntimeException) null else throw ex
        }
    }

    fun buildEncryptedBackupFile(
        store: ObdLocalStore?,
        passphrase: String?,
        progress: ProgressListener? = null,
    ): File? {
        if (store == null || !hasMinimumPassphrase(passphrase)) {
            return null
        }
        return try {
            progress?.onProgress(
                ProgressSnapshot(
                    "Preparing backup",
                    "Making the latest database writes safe to encrypt.",
                ),
            )
            store.checkpoint()
            warnIfIntegrityCheckFails(store, progress)
            val source = store.getDatabaseFile()
            if (!source.exists()) {
                return null
            }
            val dir = File(context.cacheDir, "backups")
            if (!dir.exists() && !dir.mkdirs()) {
                return null
            }
            clearOldBackups(dir)
            val dest = File(dir, backupFileName("vtdb"))
            progress?.onProgress(
                ProgressSnapshot(
                    "Encrypting backup",
                    "Encrypting the backup before sharing.",
                    bytesDone = 0L,
                    bytesTotal = source.length(),
                ),
            )
            BackupCrypto.encryptFile(source, dest, requireNotNull(passphrase))
            progress?.onProgress(
                ProgressSnapshot(
                    "Encrypting backup",
                    "Backup encrypted.",
                    bytesDone = source.length(),
                    bytesTotal = source.length(),
                ),
            )
            dest
        } catch (ex: Exception) {
            if (ex is IOException || ex is GeneralSecurityException || ex is RuntimeException) null else throw ex
        }
    }

    /**
     * Pre-export integrity gate: runs `PRAGMA quick_check` (never throws) and, when the live
     * database reports corruption, logs loudly AND emits a [ProgressSnapshot] flagged with
     * [ProgressSnapshot.warning] through [progress] so the UI can tell the user. The export
     * still proceeds — a backup of a partially corrupt database beats no backup — so callers
     * surface the warning alongside the success status instead of aborting.
     */
    private fun warnIfIntegrityCheckFails(
        store: ObdLocalStore,
        progress: ProgressListener?,
    ) {
        val integrity = store.quickCheck()
        if (!integrity.ok) {
            Log.w(
                TAG,
                "Backup integrity warning: quick_check reported problems; exporting current file anyway: " +
                    integrity.problems.joinToString("; "),
            )
            val firstProblem = integrity.problems.firstOrNull() ?: "unknown integrity problem"
            progress?.onProgress(
                ProgressSnapshot(
                    "Integrity warning",
                    "The database check reported a problem ($firstProblem). Creating the backup anyway.",
                    warning = true,
                ),
            )
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
        progress: ProgressListener? = null,
    ): RestoreStageOutcome {
        if (uri == null) {
            return RestoreStageOutcome(null, RestoreStageStatus.NO_FILE)
        }
        val temp = File(context.cacheDir, "restore-${UUID.randomUUID()}.backup")
        var total = 0L
        val expectedBytes = contentLength(context, uri)
        try {
            progress?.onProgress(
                ProgressSnapshot(
                    "Reading backup",
                    "Copying the selected file into Volt Tracker.",
                    bytesDone = 0L,
                    bytesTotal = expectedBytes,
                ),
            )
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
                            progress?.onProgress(
                                ProgressSnapshot(
                                    "Reading backup",
                                    "The selected file is larger than Volt Tracker can import on this phone.",
                                    bytesDone = total,
                                    bytesTotal = expectedBytes,
                                ),
                            )
                            return RestoreStageOutcome(null, RestoreStageStatus.TOO_LARGE, bytesRead = total)
                        }
                        out.write(buffer, 0, read)
                        progress?.onProgress(
                            ProgressSnapshot(
                                "Reading backup",
                                "Copying the selected file into Volt Tracker.",
                                bytesDone = total,
                                bytesTotal = expectedBytes,
                            ),
                        )
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
            // Any non-empty passphrase may unlock a restore; MIN_PASSPHRASE_LENGTH only gates
            // creating new backups, and older backups can carry shorter passphrases.
            if (passphrase.isNullOrBlank()) {
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
                progress?.onProgress(
                    ProgressSnapshot(
                        "Decrypting backup",
                        "Unlocking the encrypted backup with your passphrase.",
                        bytesDone = 0L,
                        bytesTotal = temp.length(),
                    ),
                )
                BackupCrypto.decryptFile(temp, candidate, requireNotNull(passphrase), MAX_RESTORE_BYTES)
                progress?.onProgress(
                    ProgressSnapshot(
                        "Decrypting backup",
                        "Encrypted backup unlocked.",
                        bytesDone = temp.length(),
                        bytesTotal = temp.length(),
                    ),
                )
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

        progress?.onProgress(
            ProgressSnapshot(
                "Checking backup",
                "Verifying the database and applying any needed compatibility updates.",
            ),
        )
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
        progress?.onProgress(
            ProgressSnapshot(
                "Preparing restore",
                "Clearing temporary trip summaries so maps and trips rebuild from restored data.",
            ),
        )
        clearRegenerableRollupCache(candidate)
        progress?.onProgress(
            ProgressSnapshot(
                "Backup verified",
                "Choose whether to merge it into this phone or replace everything.",
                bytesDone = total,
                bytesTotal = if (expectedBytes >= 0L) expectedBytes else total,
            ),
        )
        return RestoreStageOutcome(
            candidate,
            RestoreStageStatus.OK,
            encrypted = encrypted,
            migrated = migration == BackupMigrator.Result.MIGRATED,
            bytesRead = total,
        )
    }

    companion object {
        private const val TAG = "DataBackup"

        // Restore is staged to a temp file and opened as a SQLite DB (BackupMigrator) — it is
        // disk-bound, not loaded into memory — so this ceiling only guards against a runaway file
        // filling cache storage, not an OOM. Raised 512 MiB → 4 GiB so multi-hundred-MB / ~1 GB
        // histories (lots of logged drives) import; a genuinely too-large file still fails cleanly
        // with TOO_LARGE. Free cache space is the real practical limit and surfaces as an IO error.
        internal const val MAX_RESTORE_MIB = 4096L
        internal const val MAX_RESTORE_BYTES = MAX_RESTORE_MIB * 1024L * 1024L
        const val MIN_PASSPHRASE_LENGTH = 8
        private const val MAX_DEBUG_LOG_BYTES = 64 * 1024
        private const val MAX_DEBUG_SESSION_LOGS = 3
        private const val MAX_DEBUG_APP_LOGS = 2

        // Backup filenames embed a timestamp using this pattern (see buildBackupFile /
        // buildEncryptedBackupFile).
        private const val BACKUP_TIMESTAMP_PATTERN = "yyyyMMdd-HHmmss"

        // Sentinel prefix passed to recentLogEntries to request exact-app-log matching (the live
        // "app.log" plus its rolled "app.log.1" sibling) instead of the usual prefix/suffix match.
        // Mirrors RollingAppLog.LIVE_NAME / ROLLED_NAME, which are private there.
        private const val APP_LOG_SENTINEL = "app.log"
        private val APP_LOG_NAMES = setOf("app.log", "app.log.1")
        private val BLUETOOTH_ADDRESS_RE = Regex("\\b[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}\\b")
        private val VIN_RE = Regex("\\b[A-HJ-NPR-Za-hj-npr-z0-9]{17}\\b")
        private val COORDINATE_FIELD_RE =
            Regex(
                "(\"(?:latitude|longitude|lat|lng)\"\\s*:\\s*)(?:\"-?\\d{1,3}(?:\\.\\d+)?\"|-?\\d{1,3}(?:\\.\\d+)?)",
                RegexOption.IGNORE_CASE,
            )
        private val COORDINATE_ARRAY_FIELD_RE =
            Regex(
                "(\"(?:coordinates|coordinate|latLng|lngLat)\"\\s*:\\s*)\\[(?:\\s*\\[?\\s*-?\\d{1,3}(?:\\.\\d+)?\\s*,\\s*-?\\d{1,3}(?:\\.\\d+)?\\s*\\]?\\s*,?)+\\]",
                RegexOption.IGNORE_CASE,
            )

        private const val IO_BUFFER_BYTES = 8192
        private const val STALE_BACKUP_TTL_MS = 60L * 60L * 1000L

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
                }.replace(COORDINATE_ARRAY_FIELD_RE) { match ->
                    match.groupValues[1] + "\"[coordinate-redacted]\""
                }
        }

        @JvmStatic
        fun clearRegenerableRollupCache(file: File?) = RestoreValidator.clearRegenerableRollupCache(file)

        private fun backupFileName(extension: String): String {
            val stamp = SimpleDateFormat(BACKUP_TIMESTAMP_PATTERN, Locale.US).format(Date())
            return "volttracker-backup-$stamp-${UUID.randomUUID()}.$extension"
        }

        private fun clearOldBackups(dir: File) {
            cleanupTransientBackupFiles(dir)
        }

        private fun cleanupTransientBackupFiles(dir: File?) {
            val existing = dir?.listFiles() ?: return
            val cutoff = System.currentTimeMillis() - STALE_BACKUP_TTL_MS
            for (file in existing) {
                val name = file.name
                if (
                    name.startsWith("volttracker-backup-") &&
                    (name.endsWith(".db") || name.endsWith(".vtdb")) &&
                    file.lastModified() < cutoff
                ) {
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
        fun hasMinimumPassphrase(passphrase: String?): Boolean {
            // Count Unicode code points, not UTF-16 units, so the "8 characters" the UI promises is
            // enforced as user-perceived characters (4 astral-plane emoji are 8 UTF-16 units but only
            // 4 characters, and must not pass the gate).
            val trimmed = passphrase?.trim() ?: return false
            return trimmed.codePointCount(0, trimmed.length) >= MIN_PASSPHRASE_LENGTH
        }

        private fun contentLength(
            context: Context,
            uri: Uri,
        ): Long =
            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                    descriptor.length
                } ?: -1L
            } catch (ex: RuntimeException) {
                -1L
            } catch (ex: IOException) {
                -1L
            }

        @Throws(IOException::class)
        private fun copyStream(
            input: InputStream,
            out: OutputStream,
        ) {
            copyStream(input, out, Long.MAX_VALUE, null, null, null, -1L)
        }

        @Throws(IOException::class)
        private fun copyStream(
            input: InputStream,
            out: OutputStream,
            maxBytes: Long,
            progress: ProgressListener? = null,
            phase: String? = null,
            detail: String? = null,
            totalBytes: Long = -1L,
        ) {
            val buffer = ByteArray(IO_BUFFER_BYTES)
            var total = 0L
            while (true) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedIOException("Backup copy interrupted").apply {
                        bytesTransferred = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    }
                }
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                total += read.toLong()
                if (total > maxBytes) {
                    throw IOException("Restore file exceeds size limit")
                }
                out.write(buffer, 0, read)
                if (progress != null && phase != null) {
                    progress.onProgress(
                        ProgressSnapshot(
                            phase,
                            detail,
                            bytesDone = total,
                            bytesTotal = totalBytes,
                        ),
                    )
                }
            }
        }

        @JvmStatic
        @Throws(IOException::class)
        fun copyFile(
            source: File,
            dest: File,
            progress: ProgressListener? = null,
            phase: String = "Copying backup",
            detail: String? = null,
        ) {
            FileInputStream(source).use { input ->
                FileOutputStream(dest).use { out ->
                    progress?.onProgress(
                        ProgressSnapshot(
                            phase,
                            detail,
                            bytesDone = 0L,
                            bytesTotal = source.length(),
                        ),
                    )
                    copyStream(input, out, Long.MAX_VALUE, progress, phase, detail, source.length())
                    out.fd.sync()
                    progress?.onProgress(
                        ProgressSnapshot(
                            phase,
                            detail,
                            bytesDone = source.length(),
                            bytesTotal = source.length(),
                        ),
                    )
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
