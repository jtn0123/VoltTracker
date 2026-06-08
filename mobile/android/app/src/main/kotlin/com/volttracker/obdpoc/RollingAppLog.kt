package com.volttracker.obdpoc

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
            BufferedWriter(FileWriter(liveFile, true)).use { writer ->
                // ISO-8601-ish with millis. Locale.US so the formatter is stable regardless of
                // device locale; SimpleDateFormat per-write avoids holding a non-thread-safe
                // formatter as a field even though this whole method is synchronized.
                val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
                writer.write(fmt.format(Date(clock.now())))
                writer.write(' '.code)
                writer.write(safe(level))
                writer.write(' '.code)
                writer.write(safe(tag))
                writer.write(": ")
                writer.write(safe(msg))
                writer.newLine()
            }
        } catch (ignored: IOException) {
            // Logging that the log failed would risk an infinite loop; just drop the line.
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
        val bornMs = readBornMs()
        if (bornMs <= 0L) {
            return
        }
        val age = clock.now() - bornMs
        if (age < ROTATE_AGE_MS) {
            return
        }
        // Roll: overwrite any previous rolled file with the current live file.
        if (rolledFile.exists() && !rolledFile.delete()) {
            // If we can't make room for the rolled file, leave the live file in place and keep
            // appending -- better to keep one oversize log than to start dropping lines silently.
            return
        }
        if (!liveFile.renameTo(rolledFile)) {
            // Rename failed; live file stays in place.
            return
        }
        // The live file is now gone; the FileWriter below will recreate it on next write.
        // Reset the birth marker so the new live file starts a fresh 7-day window.
        if (bornFile.exists() && !bornFile.delete()) {
            // Best-effort: a stale marker would make the next rotation fire too early (also
            // acceptable) rather than too late, so it's not catastrophic. Try once.
        }
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
        } catch (ignored: IOException) {
            // Best-effort: a missing marker on the next call just makes the age-check skip
            // (treat as fresh). We'll try again on the next write.
        }
    }

    companion object {
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
