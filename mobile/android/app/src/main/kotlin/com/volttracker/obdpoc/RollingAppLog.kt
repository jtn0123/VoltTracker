package com.volttracker.obdpoc

import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide append-only log file shared across all sessions. Used as the destination for
 * [OBDLog.mirror] so logcat events also land on disk for inclusion in the diagnostics share zip.
 *
 * One log file lives at `files/app-log/app.log`; once it crosses 7 days old it's rolled to
 * `app.log.1` (overwriting any previous rolled file -- we keep at most one). The cap is
 * intentionally small: an app log this app's size shouldn't approach megabytes in a week, and
 * keeping only one rolled file simplifies the diagnostics-zip contents.
 *
 * All public methods are synchronized so cross-thread callers (poll loop, main thread, the BT
 * receiver) can write without coordination.
 */
class RollingAppLog {
    private val dir: File
    private val liveFile: File
    private val rolledFile: File
    private val bornFile: File
    private val clock: Clock

    // Long-lived append writer for the live file, opened lazily on first write and reused across
    // appends — so the hot path no longer does an open()/close() syscall per log line. Closed and
    // nulled only on rotation (the live file is renamed away) or a write error, so the next write
    // reopens against the fresh file. All access is under this object's monitor (every public method
    // is @Synchronized), so the non-thread-safe writer is never touched concurrently.
    private var writer: BufferedWriter? = null

    // Set once by close() (owner teardown). Permanent: a straggler write from a still-draining
    // thread after close() is dropped rather than reopening — and re-leaking — the file handle.
    // Touched only under this object's monitor (write()/close() are @Synchronized).
    private var closed = false

    // Cached birth-marker timestamp (epoch ms; 0 = unknown/not yet read). Avoids re-opening and
    // reading the sidecar file on every write() via rotateIfStale() — that per-line syscall would
    // defeat the long-lived-writer optimization above. Populated lazily from disk, set directly by
    // writeBorn(), and invalidated on rotation. Touched only under this object's monitor.
    private var cachedBornMs: Long = 0L

    // Cached timestamp formatter. SimpleDateFormat is not thread-safe, but it's only used from the
    // synchronized write() path, so a single cached instance is safe and avoids rebuilding it (and
    // re-parsing the pattern) on every line. Locale.US keeps the format stable across device locales.
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    /** Source of "now" for the rotate-on-age check. Default is wall clock. */
    fun interface Clock {
        fun now(): Long
    }

    constructor(dir: File) : this(dir, Clock { System.currentTimeMillis() })

    constructor(dir: File, clock: Clock) {
        this.dir = dir
        liveFile = File(dir, LIVE_NAME)
        rolledFile = File(dir, ROLLED_NAME)
        bornFile = File(dir, BORN_NAME)
        this.clock = clock
    }

    /** File the most recent line was appended to. Exposed for the diagnostics zip. */
    fun liveFile(): File = liveFile

    /** Rolled file (may not exist). Exposed for the diagnostics zip. */
    fun rolledFile(): File = rolledFile

    /**
     * Appends one `<iso-ts> <level> <tag>: <msg>` line. Best-effort: write failures are swallowed
     * so a full disk doesn't tear down the calling thread.
     */
    @Synchronized
    fun write(
        level: String?,
        tag: String?,
        msg: String?,
    ) {
        if (closed) {
            // The owner has torn this log down; drop the line rather than reopening the handle.
            return
        }
        if (!ensureDir()) {
            return
        }
        rotateIfStale()
        // Ensure the birth marker exists for the current live file before we append. This catches
        // the cold-start case (no born file yet) and the post-rotation case (we just wiped it).
        if (!bornFile.exists()) {
            writeBorn(clock.now())
        }
        try {
            val out = openWriter()
            // ISO-8601-ish with millis from the cached, lock-guarded formatter.
            out.write(timestampFormat.format(Date(clock.now())))
            out.write(' '.code)
            out.write(safe(level))
            out.write(' '.code)
            out.write(safe(tag))
            out.write(": ")
            out.write(safe(msg))
            out.newLine()
            // Flush every line so the diagnostics zip and the immediate-read callers see the line
            // right away (matching the old open-write-close durability), while still keeping the
            // file handle open so we avoid the per-line open()/close() syscalls.
            out.flush()
        } catch (ex: IOException) {
            // Mirroring this failure back through OBDLog would recurse into this class; a
            // best-effort plain logcat line is the most we can safely do before dropping it.
            // Drop the (possibly half-broken) writer so the next write reopens cleanly.
            closeWriter()
            Log.w(TAG, "app log append failed; line dropped", ex)
        }
    }

    /**
     * Flushes and releases the held live-file writer. Call when the owner is going away (e.g. the
     * service's `onDestroy`) so the long-lived file handle isn't leaked for the rest of the process
     * lifetime. Idempotent. Permanent: any [write] after close() is dropped rather than reopening —
     * and re-leaking — the handle, so a straggler line from a still-draining thread is safe.
     */
    @Synchronized
    fun close() {
        closed = true
        closeWriter()
    }

    /** Returns the live-file append writer, opening it lazily on first use after start/rotation. */
    @Throws(IOException::class)
    private fun openWriter(): BufferedWriter {
        val existing = writer
        if (existing != null) {
            return existing
        }
        val opened = BufferedWriter(FileWriter(liveFile, true))
        writer = opened
        return opened
    }

    /** Closes and forgets the live-file writer (best-effort), so the next write reopens it. */
    private fun closeWriter() {
        val existing = writer ?: return
        writer = null
        try {
            existing.close()
        } catch (ex: IOException) {
            Log.w(TAG, "app log writer close failed", ex)
        }
    }

    private fun ensureDir(): Boolean {
        if (dir.isDirectory) {
            return true
        }
        return dir.mkdirs()
    }

    private fun rotateIfStale() {
        if (!liveFile.exists()) {
            return
        }
        // Anchor age to the stable birth marker, NOT liveFile.lastModified() -- every append
        // touches mtime, so an active log would never rotate. If the marker is missing or
        // unreadable, treat the live file as fresh (and the write path will write a fresh marker
        // afterwards), since we'd rather skip a rotation than rotate spuriously.
        val bornMs = bornMs()
        if (bornMs <= 0L) {
            return
        }
        val age = clock.now() - bornMs
        if (age < ROTATE_AGE_MS) {
            return
        }
        // Roll: overwrite any previous rolled file with the current live file. Close the held writer
        // first so the live-file handle is released before the rename and the next write reopens
        // against the fresh live file (on Windows an open handle would also block the rename).
        if (rolledFile.exists() && !rolledFile.delete()) {
            // If we can't make room for the rolled file, leave the live file in place and keep
            // appending -- better to keep one oversize log than to start dropping lines silently.
            return
        }
        closeWriter()
        if (!liveFile.renameTo(rolledFile)) {
            // Rename failed; live file stays in place. The writer was closed above; the next write
            // simply reopens it against the same (un-rotated) live file.
            return
        }
        // The live file is now gone; the FileWriter below will recreate it on next write.
        // Reset the birth marker so the new live file starts a fresh 7-day window. Best-effort:
        // a stale marker would make the next rotation fire too early (also acceptable) rather than
        // too late, so it's not catastrophic. Try once.
        bornFile.delete()
        cachedBornMs = 0L
    }

    /** Birth-marker timestamp, reading the sidecar once and caching it for subsequent writes. */
    private fun bornMs(): Long {
        val cached = cachedBornMs
        if (cached > 0L) {
            return cached
        }
        val read = readBornMs()
        if (read > 0L) {
            cachedBornMs = read
        }
        return read
    }

    private fun readBornMs(): Long {
        if (!bornFile.exists()) {
            return -1L
        }
        return try {
            BufferedReader(FileReader(bornFile)).use { reader ->
                val line = reader.readLine() ?: return -1L
                line.trim().toLong()
            }
        } catch (ex: IOException) {
            -1L
        } catch (ex: NumberFormatException) {
            -1L
        }
    }

    private fun writeBorn(whenMs: Long) {
        try {
            BufferedWriter(FileWriter(bornFile, false)).use { writer ->
                writer.write(whenMs.toString())
                writer.newLine()
            }
            cachedBornMs = whenMs
        } catch (ex: IOException) {
            // Best-effort: a missing marker on the next call just makes the age-check skip
            // (treat as fresh). We'll try again on the next write. Plain logcat only — going
            // through OBDLog's mirror would recurse into this class.
            Log.w(TAG, "app log birth marker write failed", ex)
        }
    }

    companion object {
        private const val TAG = "RollingAppLog"

        /**
         * Directory (under the app's `files/`) the process-wide app log lives in. Shared by the
         * two writers ([ObdService]'s mirror and [VoltTrackerApp]'s crash capture) so both append
         * to the same `app.log`; every write is a single flushed line under the instance monitor,
         * and the file is opened in append mode, so the two instances interleave safely at line
         * granularity.
         */
        const val DIR_NAME = "app-log"

        /** Rotate the live log file when it's at least this old. */
        const val ROTATE_AGE_MS: Long = 7L * 24L * 60L * 60L * 1000L

        private const val LIVE_NAME = "app.log"
        private const val ROLLED_NAME = "app.log.1"

        // Sidecar file: stores the epoch ms when the current live file was first created. We can't
        // use liveFile.lastModified() because every append updates that, so an actively-used log
        // would never age past ROTATE_AGE_MS and rotation would never fire. A tiny sidecar keeps
        // the anchor stable across appends and survives process restarts.
        private const val BORN_NAME = "app.log.born"

        private fun safe(s: String?): String = s?.replace('\n', ' ')?.replace('\r', ' ') ?: "-"
    }
}
