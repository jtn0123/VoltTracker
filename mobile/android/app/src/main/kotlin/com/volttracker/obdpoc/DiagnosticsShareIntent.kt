package com.volttracker.obdpoc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a one-zip share intent for the diagnostics share-sheet flow. The zip pulls together:
 *
 * - the most recent [MAX_SESSION_LOGS] per-session JSONLs from `files/obd-logs/`
 * - the rolling app log + its rolled sibling from `files/app-log/`
 * - the session summary rollup `sessions-summary.jsonl`
 *
 * The zip is written to `cacheDir/diagnostics/` and exposed via the project's `FileProvider` -- the
 * bridge call wraps the returned intent in [Intent.createChooser] and starts it.
 */
object DiagnosticsShareIntent {
    /** Most recent N per-session JSONLs we include. */
    const val MAX_SESSION_LOGS = 5

    private const val TAG = "DiagnosticsShareIntent"
    private const val SUBDIR = "diagnostics"
    private const val SESSION_LOG_DIR = "obd-logs"
    private const val APP_LOG_DIR = "app-log"
    private const val SUMMARY_NAME = "sessions-summary.jsonl"

    /**
     * Zips the diagnostics bundle and returns an [Intent.ACTION_SEND] intent ready for
     * [Intent.createChooser]. Returns `null` only if the zip couldn't be written (caller logs and
     * surfaces the failure to the UI).
     */
    @JvmStatic
    fun buildIntent(ctx: Context?): Intent? {
        if (ctx == null) {
            return null
        }
        val zip = buildZip(ctx) ?: return null
        val uri: Uri =
            try {
                FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", zip)
            } catch (ex: IllegalArgumentException) {
                // FileProvider couldn't expose the file -- usually a misconfigured file_paths.xml.
                Log.e(TAG, "FileProvider rejected diagnostics zip: $zip", ex)
                return null
            }
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.share_diagnostics_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Builds the zip file (exposed package-private so tests can verify its contents without driving
     * the FileProvider). Returns `null` on IO failure.
     */
    @JvmStatic
    fun buildZip(ctx: Context): File? {
        val outDir = File(ctx.cacheDir, SUBDIR)
        if (!outDir.exists() && !outDir.mkdirs()) {
            Log.e(TAG, "could not create diagnostics dir: $outDir")
            return null
        }
        // Clear stale zips so the cache doesn't accumulate one per share over the device's
        // lifetime. We keep only the one we're about to build.
        outDir.listFiles()?.forEach { stale ->
            if (stale.isFile && !stale.delete()) {
                Log.w(TAG, "could not delete stale diagnostics file: $stale")
            }
        }
        val zipFile = File(outDir, "diagnostics-${System.currentTimeMillis()}.zip")
        val filesDir = ctx.filesDir
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zipStream ->
                addRecentSessionLogs(zipStream, File(filesDir, SESSION_LOG_DIR))
                addAppLogs(zipStream, File(filesDir, APP_LOG_DIR))
                addSummary(zipStream, File(File(filesDir, SESSION_LOG_DIR), SUMMARY_NAME))
            }
        } catch (ex: IOException) {
            Log.e(TAG, "diagnostics zip failed", ex)
            // Best-effort cleanup so callers don't pick up a half-written zip.
            if (zipFile.exists() && !zipFile.delete()) {
                Log.w(TAG, "could not delete partial zip: $zipFile")
            }
            return null
        }
        return zipFile
    }

    @Throws(IOException::class)
    private fun addRecentSessionLogs(
        zipStream: ZipOutputStream,
        sessionLogDir: File,
    ) {
        if (!sessionLogDir.isDirectory) {
            return
        }
        // Filter to the per-session JSONLs (session-*.jsonl). The sessions-summary file is also
        // .jsonl but is added separately via addSummary; including it here would either crowd the
        // 5-file budget or -- worse -- duplicate the entry name in the zip and blow up putNextEntry.
        val files =
            sessionLogDir.listFiles { _, name ->
                name.startsWith("session-") && name.endsWith(".jsonl")
            }
        if (files.isNullOrEmpty()) {
            return
        }
        // Newest first so the slice gives the freshest N files.
        files
            .sortedByDescending { it.lastModified() }
            .take(MAX_SESSION_LOGS)
            .forEach { addFile(zipStream, it, "$SESSION_LOG_DIR/${it.name}", redactText = true) }
    }

    @Throws(IOException::class)
    private fun addAppLogs(
        zipStream: ZipOutputStream,
        appLogDir: File,
    ) {
        if (!appLogDir.isDirectory) {
            return
        }
        appLogDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                addFile(zipStream, file, "$APP_LOG_DIR/${file.name}", redactText = true)
            }
        }
    }

    @Throws(IOException::class)
    private fun addSummary(
        zipStream: ZipOutputStream,
        summary: File,
    ) {
        if (summary.isFile) {
            addFile(zipStream, summary, "$SESSION_LOG_DIR/${summary.name}", redactText = true)
        }
    }

    @Throws(IOException::class)
    private fun addFile(
        zipStream: ZipOutputStream,
        source: File,
        entryName: String,
        redactText: Boolean,
    ) {
        val entry =
            ZipEntry(entryName).apply {
                time = source.lastModified()
            }
        zipStream.putNextEntry(entry)
        if (redactText) {
            val redacted = DataBackup.redactDebugLogText(source.readText(Charsets.UTF_8))
            zipStream.write(redacted.toByteArray(Charsets.UTF_8))
        } else {
            BufferedInputStream(FileInputStream(source)).use { input ->
                val buffer = ByteArray(8192)
                var bytesRead = input.read(buffer)
                while (bytesRead > 0) {
                    zipStream.write(buffer, 0, bytesRead)
                    bytesRead = input.read(buffer)
                }
            }
        }
        zipStream.closeEntry()
    }
}
