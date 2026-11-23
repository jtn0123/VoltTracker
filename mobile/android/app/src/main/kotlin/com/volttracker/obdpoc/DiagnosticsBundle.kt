package com.volttracker.obdpoc

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a single, budget-bounded, redacted text digest of on-device diagnostics, sized so it can be
 * handed to an external debugging tool (for example an AI assistant) without overflowing its context.
 *
 * The app -- not the user -- decides what to include. The newest [MAX_LISTED_SESSIONS] per-session
 * logs are scanned and catalogued in the MANIFEST; older ones are only counted, so the work stays
 * bounded however much history piles up. Only the most relevant bodies are embedded: the most recent
 * session is always included (its slice is reserved before any other section), then sessions whose
 * logs contain `error` events, then the remaining sessions by recency, until [budgetBytes] is
 * consumed. Sessions that don't fit are listed in the manifest with `included=false` so the caller
 * can ask for one by name. All embedded text passes through [DataBackup.redactDebugLogText].
 */
object DiagnosticsBundle {
    /** Default total byte budget (~50k tokens at ~4 bytes/token) -- a safe slice for an AI context. */
    const val DEFAULT_BUDGET_BYTES = 200 * 1024

    /** Zip entry name used when this digest is folded into the diagnostics share zip. */
    internal const val BUNDLE_ENTRY_NAME = "diagnostics-bundle.txt"

    /**
     * How many of the newest session logs are read (to count error lines), listed in the MANIFEST and
     * eligible for embedding. The logs are never pruned, so reading all of them would grow without
     * bound; older ones are reported as a single count instead.
     */
    internal const val MAX_LISTED_SESSIONS = 40

    private const val SESSION_LOG_DIR = "obd-logs"
    private const val APP_LOG_DIR = "app-log"
    private const val SUMMARY_NAME = "sessions-summary.jsonl"
    private const val APP_LOG_NAME = "app.log"

    // Per-section caps so one noisy source can't crowd out the others.
    private const val SUMMARY_CAP = 16 * 1024
    private const val APP_LOG_CAP = 32 * 1024
    private const val PER_SESSION_CAP = 64 * 1024
    private const val MIN_SESSION_BYTES = 256
    private const val HEADER_RESERVE = 1024

    // Slice held for the most recent session before the summary and app-log sections are sized, so a
    // tight budget never squeezes out the session a user is most likely debugging.
    private const val NEWEST_SESSION_RESERVE = 16 * 1024

    // Upper bound on the one-line "older sessions" note that follows the MANIFEST.
    private const val OLDER_NOTE_RESERVE = 160

    // Upper bound on the marginal cost of one pretty-printed MANIFEST entry. A worst-case entry — long
    // filename, max-width byte/ms numbers, longest reason — adds ~260 chars per entry inside the
    // 2-space-indented JSONArray (verified empirically), so 256 actually UNDER-reserves once enough
    // sessions accumulate. Keeping this a true over-estimate means planSessions reserves at least the
    // real manifest size, so the session bodies it admits are guaranteed to fit even though they are
    // emitted verbatim (not re-clamped) for MANIFEST/body parity.
    private const val MANIFEST_BYTES_PER_ENTRY = 320

    // Bytes held back so the trailing OMITTED/END sections always fit within the advertised budget.
    private const val FOOTER_RESERVE = 256

    // Session JSONL lines are JSONObject.toString() output, so the type pair is contiguous regardless
    // of key order -- a substring match is enough to flag a session that logged an error event.
    private const val ERROR_MARKER = "\"type\":\"error\""

    private val NAME_TS = Regex("^session-(\\d+)-")

    private data class SessionFile(
        val file: File,
        val bytes: Long,
        val lastModified: Long,
        val errorLines: Int,
        val tsFromName: Long,
    )

    private class Plan(
        val source: SessionFile,
        val included: Boolean,
        val tailBytes: Int,
        val reason: String,
    ) {
        val truncated: Boolean get() = included && tailBytes < source.bytes
    }

    /** The newest sessions (scanned) plus a count of the older ones that were left unread. */
    private class SessionScan(
        val recent: List<SessionFile>,
        val olderCount: Int,
        val olderBytes: Long,
    )

    /** Per-session plans plus the byte caps left over for the summary and app-log sections. */
    private class Layout(
        val plans: List<Plan>,
        val summaryCap: Int,
        val appLogCap: Int,
    )

    @JvmStatic
    fun build(
        filesDir: File,
        budgetBytes: Int = DEFAULT_BUDGET_BYTES,
    ): String = build(filesDir, budgetBytes, System.currentTimeMillis())

    /** Deterministic entry point: [nowMs] feeds the header timestamp so tests can pin the output. */
    @JvmStatic
    internal fun build(
        filesDir: File,
        budgetBytes: Int,
        nowMs: Long,
    ): String {
        val budget = maxOf(HEADER_RESERVE, budgetBytes)
        val sessionDir = File(filesDir, SESSION_LOG_DIR)
        val appLogDir = File(filesDir, APP_LOG_DIR)
        val summaryFile = File(sessionDir, SUMMARY_NAME)
        val appLogFile = File(appLogDir, APP_LOG_NAME)

        val scan = scanSessions(sessionDir)
        val layout = planSessions(scan, summaryFile, appLogFile, budget)
        val plans = layout.plans

        val out = StringBuilder()
        out.append("VoltTracker diagnostics digest\n")
        out.append("generated: ").append(isoUtc(nowMs)).append('\n')
        out.append("budget: ").append(budget).append(" bytes\n")
        out.append("note: text is redacted (bluetooth addresses, VINs, GPS coordinates).\n")
        out.append("note: the app auto-selects which session bodies fit the budget; see MANIFEST.\n\n")

        // The MANIFEST is the irreducible catalog -- it always ships, listing the newest sessions and
        // counting the older ones, so no session is silently hidden. It and the session bodies below are
        // both rendered from the same `plans`, so the manifest can never claim a session is
        // included/truncated differently from what is actually emitted. Budget safety comes
        // from planSessions reserving an upper bound for the manifest + fixed sections (see the reserves
        // in planSessions and MANIFEST_BYTES_PER_ENTRY), plus the runtime clamp on the summary/app-log
        // tails below for tiny caller budgets.
        out.append(
            "===== MANIFEST (up to the newest $MAX_LISTED_SESSIONS session logs; order = inclusion priority) =====\n",
        )
        out.append(manifestJson(plans).toString(2)).append("\n")
        if (scan.olderCount > 0) {
            out
                .append("older: ")
                .append(scan.olderCount)
                .append(" older session log(s), ")
                .append(scan.olderBytes)
                .append(" bytes, not scanned or listed.\n")
        }
        out.append('\n')

        appendCappedFile(
            out,
            "SESSION SUMMARY ROLLUP ($SUMMARY_NAME)",
            summaryFile,
            minOf(layout.summaryCap, remainingBudget(out, budget)),
        )
        appendCappedFile(
            out,
            "APP LOG TAIL ($APP_LOG_DIR/$APP_LOG_NAME)",
            appLogFile,
            minOf(layout.appLogCap, remainingBudget(out, budget)),
        )

        for (plan in plans) {
            if (!plan.included) {
                continue
            }
            // Emit exactly what the MANIFEST advertised for this plan -- same tailBytes, same truncated
            // flag -- so the catalog and the embedded bodies stay in lockstep. planSessions already
            // sized these to fit the budget (its reserves upper-bound the manifest + fixed sections).
            val name = plan.source.file.name
            out
                .append("===== SESSION: ")
                .append(name)
                .append(" (bytes=")
                .append(plan.source.bytes)
                .append(", errors=")
                .append(plan.source.errorLines)
                .append(if (plan.truncated) ", tail-trimmed" else "")
                .append(") =====\n")
            out.append(DataBackup.redactDebugLogText(readTail(plan.source.file, plan.tailBytes)))
            if (out.isNotEmpty() && out.last() != '\n') {
                out.append('\n')
            }
            out.append('\n')
        }

        val omitted = plans.count { !it.included }
        if (omitted > 0) {
            out
                .append("===== OMITTED =====\n")
                .append(omitted)
                .append(" session log(s) left out to stay within budget; request one by name from the MANIFEST.\n\n")
        }

        out.append("===== END (approx ").append(out.length).append(" bytes) =====\n")
        return out.toString()
    }

    private fun scanSessions(sessionDir: File): SessionScan {
        val none = SessionScan(emptyList(), 0, 0L)
        if (!sessionDir.isDirectory) {
            return none
        }
        val files =
            sessionDir.listFiles { _, name ->
                name.startsWith("session-") && name.endsWith(".jsonl")
            } ?: return none
        // Rank by file metadata only; just the newest MAX_LISTED_SESSIONS are opened and read.
        val ranked =
            files
                .map { Triple(it, it.lastModified(), tsFromName(it)) }
                .sortedWith(compareByDescending<Triple<File, Long, Long>> { it.second }.thenByDescending { it.third })
        val older = ranked.drop(MAX_LISTED_SESSIONS)
        return SessionScan(
            recent =
                ranked.take(MAX_LISTED_SESSIONS).map { (file, modified, ts) ->
                    scanSessionFile(file, modified, ts)
                },
            olderCount = older.size,
            olderBytes = older.sumOf { it.first.length() },
        )
    }

    private fun tsFromName(file: File): Long =
        NAME_TS
            .find(file.name)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull() ?: 0L

    private fun scanSessionFile(
        file: File,
        lastModified: Long,
        tsFromName: Long,
    ): SessionFile {
        var errors = 0
        try {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.contains(ERROR_MARKER)) {
                        errors++
                    }
                }
            }
        } catch (_: IOException) {
            // Best-effort: a session we can't scan still appears in the manifest with whatever we have.
        }
        return SessionFile(
            file = file,
            bytes = file.length(),
            lastModified = lastModified,
            errorLines = errors,
            tsFromName = tsFromName,
        )
    }

    /**
     * The selection brain. Orders sessions (most recent first, then error sessions, then by recency)
     * and greedily assigns each a tail slice until the body budget is spent.
     */
    private fun planSessions(
        scan: SessionScan,
        summaryFile: File,
        appLogFile: File,
        budget: Int,
    ): Layout {
        val sessions = scan.recent
        val olderNoteReserve = if (scan.olderCount > 0) OLDER_NOTE_RESERVE else 0
        // The manifest lists at most MAX_LISTED_SESSIONS entries, so this reserve is bounded too.
        val manifestReserve = sessions.size * MANIFEST_BYTES_PER_ENTRY + 256 + olderNoteReserve
        var remaining = maxOf(0, budget - HEADER_RESERVE - manifestReserve)
        if (sessions.isEmpty()) {
            val summaryCap = minOf(cappedSize(summaryFile, SUMMARY_CAP), remaining)
            return Layout(emptyList(), summaryCap, minOf(cappedSize(appLogFile, APP_LOG_CAP), remaining - summaryCap))
        }
        val mostRecent = sessions.maxWithOrNull(compareBy({ it.lastModified }, { it.tsFromName }))
        val rest =
            sessions
                .filter { it !== mostRecent }
                .sortedWith(
                    compareByDescending<SessionFile> { it.errorLines > 0 }
                        .thenByDescending { it.lastModified }
                        .thenByDescending { it.tsFromName },
                )
        val ordered = listOfNotNull(mostRecent) + rest

        // Hold the most recent session's slice first, then size the summary and app-log sections from
        // what is left, then hand the held slice back so the session loop below can spend it.
        val newestReserve = minOf(ordered.first().bytes, NEWEST_SESSION_RESERVE.toLong(), remaining.toLong()).toInt()
        remaining -= newestReserve
        val summaryCap = minOf(cappedSize(summaryFile, SUMMARY_CAP), remaining)
        remaining -= summaryCap
        val appLogCap = minOf(cappedSize(appLogFile, APP_LOG_CAP), remaining)
        remaining -= appLogCap
        remaining += newestReserve

        val plans =
            ordered.mapIndexed { index, session ->
                val cap = minOf(session.bytes, PER_SESSION_CAP.toLong()).toInt()
                val tail = minOf(cap, remaining)
                val wholeFileFits = session.bytes <= tail.toLong()
                // Always give the most recent session a real slice even when the budget is tight, since it
                // is the session a user is most likely debugging right now. Also keep any session that fits
                // in full even when it is under MIN_SESSION_BYTES, so short error sessions stay eligible
                // instead of being dropped by the min-slice floor (which would break "errors before clean").
                val include =
                    session.bytes > 0L &&
                        (wholeFileFits || tail >= MIN_SESSION_BYTES || (index == 0 && tail > 0))
                if (include) {
                    remaining -= tail
                    Plan(session, true, tail, reasonFor(index, session))
                } else {
                    Plan(session, false, 0, "budget exceeded")
                }
            }
        return Layout(plans, summaryCap, appLogCap)
    }

    private fun reasonFor(
        index: Int,
        session: SessionFile,
    ): String =
        when {
            index == 0 -> "most recent session"
            session.errorLines > 0 -> "logged ${session.errorLines} error event(s)"
            else -> "recent session"
        }

    private fun manifestJson(plans: List<Plan>): JSONArray {
        val arr = JSONArray()
        for (plan in plans) {
            val entry = JSONObject()
            entry.put("name", plan.source.file.name)
            entry.put("modifiedMs", plan.source.lastModified)
            entry.put("bytes", plan.source.bytes)
            entry.put("errorLines", plan.source.errorLines)
            entry.put("included", plan.included)
            entry.put("truncated", plan.truncated)
            entry.put("reason", plan.reason)
            arr.put(entry)
        }
        return arr
    }

    private fun appendCappedFile(
        out: StringBuilder,
        label: String,
        file: File,
        cap: Int,
    ) {
        if (cap <= 0 || !file.isFile || file.length() == 0L) {
            return
        }
        val truncated = file.length() > cap
        out
            .append("===== ")
            .append(label)
            .append(if (truncated) " [tail-trimmed]" else "")
            .append(" =====\n")
        out.append(DataBackup.redactDebugLogText(readTail(file, cap)))
        if (out.isNotEmpty() && out.last() != '\n') {
            out.append('\n')
        }
        out.append('\n')
    }

    private fun cappedSize(
        file: File,
        cap: Int,
    ): Int = if (file.isFile) minOf(file.length(), cap.toLong()).toInt() else 0

    /** Bytes still spendable for the next section, holding back [FOOTER_RESERVE] for the END/OMITTED tail. */
    private fun remainingBudget(
        out: StringBuilder,
        budget: Int,
    ): Int = maxOf(0, budget - out.length - FOOTER_RESERVE)

    /** Reads the last [maxBytes] of [file], dropping a partial leading line when the head was cut. */
    private fun readTail(
        file: File,
        maxBytes: Int,
    ): String {
        val length = file.length()
        val want = minOf(maxBytes.toLong(), length)
        val skip = length - want
        return try {
            FileInputStream(file).use { input ->
                var remaining = skip
                while (remaining > 0L) {
                    val skipped = input.skip(remaining)
                    if (skipped <= 0L) {
                        break
                    }
                    remaining -= skipped
                }
                var text = String(input.readBytes(), Charsets.UTF_8)
                if (skip > 0L) {
                    val newline = text.indexOf('\n')
                    if (newline in 0 until text.length - 1) {
                        text = text.substring(newline + 1)
                    }
                }
                text
            }
        } catch (_: IOException) {
            ""
        }
    }

    private fun isoUtc(ms: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return format.format(Date(ms))
    }
}
