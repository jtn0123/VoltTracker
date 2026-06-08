package com.volttracker.obdpoc

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Append-only one-summary-per-line rollup at `files/obd-logs/sessions-summary.jsonl`.
 *
 * The per-session `.jsonl` is the full record; this file is the index used to render the last
 * connected badge and adapter-health pill without parsing every detail log.
 */
class SessionSummaryStore(
    private val file: File,
) {
    private val lock = Any()
    private var pendingStartMs = 0L
    private var pendingAdapter = ""
    private var pendingAddress = ""

    fun recordStart(
        startMs: Long,
        adapter: String?,
        address: String?,
    ) {
        synchronized(lock) {
            pendingStartMs = startMs
            pendingAdapter = adapter ?: ""
            pendingAddress = address ?: ""
        }
    }

    fun recordEnd(
        endMs: Long,
        outcome: String?,
        sampleCount: Int,
        failureClass: String?,
    ) {
        synchronized(lock) {
            val summary =
                SessionSummary(
                    pendingStartMs,
                    endMs,
                    outcome,
                    sampleCount,
                    pendingAdapter,
                    pendingAddress,
                    failureClass,
                )
            pendingStartMs = 0L
            pendingAdapter = ""
            pendingAddress = ""
            appendLineLocked(summary.toJson().toString())
        }
    }

    /** Returns up to [n] summaries, newest first. `n <= 0` returns empty. */
    fun getRecent(n: Int): List<SessionSummary> {
        if (n <= 0) {
            return emptyList()
        }
        synchronized(lock) {
            val lines = readAllLinesLocked()
            val from = maxOf(0, lines.size - n)
            val out = ArrayList<SessionSummary>(lines.size - from)
            for (i in lines.size - 1 downTo from) {
                try {
                    val summary = SessionSummary.fromJson(JSONObject(lines[i]))
                    if (summary != null) {
                        out.add(summary)
                    }
                } catch (_: JSONException) {
                    // Skip malformed lines rather than failing the whole call.
                }
            }
            return out
        }
    }

    /** Returns all summaries as JSON-array string for the JS bridge. Newest first. */
    fun getRecentAsJsonArray(n: Int): String {
        val recent = getRecent(n)
        return buildString {
            append('[')
            for (i in recent.indices) {
                if (i > 0) {
                    append(',')
                }
                append(recent[i].toJson().toString())
            }
            append(']')
        }
    }

    private fun appendLineLocked(line: String) {
        if (!ensureParentDir()) {
            return
        }
        try {
            file.appendText("$line\n")
        } catch (_: IOException) {
            return
        }
        truncateIfNeededLocked()
    }

    private fun truncateIfNeededLocked() {
        val lines = readAllLinesLocked()
        if (lines.size <= MAX_SESSIONS) {
            return
        }
        val tail = lines.subList(lines.size - MAX_SESSIONS, lines.size)
        try {
            file.bufferedWriter().use { writer ->
                for (line in tail) {
                    writer.write(line)
                    writer.newLine()
                }
            }
        } catch (_: IOException) {
            // Leave the file as-is; next recordEnd will retry truncation.
        }
    }

    private fun readAllLinesLocked(): List<String> {
        val out = ArrayList<String>()
        if (!file.exists()) {
            return out
        }
        try {
            file.bufferedReader().use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (line.isNotEmpty()) {
                        out.add(line)
                    }
                    line = reader.readLine()
                }
            }
        } catch (_: IOException) {
            // Return what we have.
        }
        return out
    }

    private fun ensureParentDir(): Boolean {
        val parent = file.parentFile ?: return false
        if (parent.isDirectory) {
            return true
        }
        return parent.mkdirs()
    }

    companion object {
        const val MAX_SESSIONS: Int = 100
        private const val FILE_NAME = "sessions-summary.jsonl"

        @Volatile private var singleton: SessionSummaryStore? = null

        /**
         * Returns the process-wide store rooted at `filesDir/obd-logs/`. The directory matches the
         * per-session JSONL location used by [ObdService].
         */
        @JvmStatic
        fun getInstance(filesDir: File): SessionSummaryStore {
            val existing = singleton
            if (existing != null) {
                return existing
            }
            synchronized(SessionSummaryStore::class.java) {
                val current = singleton
                if (current != null) {
                    return current
                }
                val dir = File(filesDir, "obd-logs")
                return SessionSummaryStore(File(dir, FILE_NAME)).also { singleton = it }
            }
        }

        /** Test seam: drops the cached instance so the next [getInstance] re-reads disk. */
        @JvmStatic
        fun resetForTests() {
            synchronized(SessionSummaryStore::class.java) {
                singleton = null
            }
        }
    }
}
