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
 * The app -- not the user -- decides what to include. Every per-session log is catalogued in the
 * MANIFEST so nothing is hidden, but only the most relevant bodies are embedded: the most recent
 * session is always included, then sessions whose logs contain `error` events, then the remaining
 * sessions by recency, until [budgetBytes] is consumed. Sessions that don't fit are listed in the
 * manifest with `included=false` so the caller can ask for one by name. All embedded text passes
 * through [DataBackup.redactDebugLogText].
 */
object DiagnosticsBundle {
    /** Default total byte budget (~50k tokens at ~4 bytes/token) -- a safe slice for an AI context. */
    const val DEFAULT_BUDGET_BYTES = 200 * 1024

    /** Zip entry name used when this digest is folded into the diagnostics share zip. */
    internal const val BUNDLE_ENTRY_NAME = "diagnostics-bundle.txt"

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

        val sessions = scanSessions(sessionDir)
        val plans = planSessions(sessions, summaryFile, appLogFile, budget)

        val out = StringBuilder()
        out.append("VoltTracker diagnostics digest\n")
        out.append("generated: ").append(isoUtc(nowMs)).append('\n')
        out.append("budget: ").append(budget).append(" bytes\n")
        out.append("note: text is redacted (bluetooth addresses, VINs, GPS coordinates).\n")
        out.append("note: the app auto-selects which session bodies fit the budget; see MANIFEST.\n\n")

        // The MANIFEST is the irreducible catalog -- it always ships so no session is hidden. It and the
        // session bodies below are both rendered from the same `plans`, so the manifest can never claim a
        // session is included/truncated differently from what is actually emitted. Budget safety comes
        // from planSessions reserving an upper bound for the manifest + fixed sections (see the reserves
        // in planSessions and MANIFEST_BYTES_PER_ENTRY), plus the runtime clamp on the summary/app-log
        // tails below for tiny caller budgets.
        out.append("===== MANIFEST (every session log; order = inclusion priority) =====\n")
        out.append(manifestJson(plans).toString(2)).append("\n\n")

        appendCappedFile(
            out,
            "SESSION SUMMARY ROLLUP ($SUMMARY_NAME)",
            summaryFile,
            minOf(SUMMARY_CAP, remainingBudget(out, budget)),
        )
        appendCappedFile(
            out,
            "APP LOG TAIL ($APP_LOG_DIR/$APP_LOG_NAME)",
            appLogFile,
            minOf(APP_LOG_CAP, remainingBudget(out, budget)),
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

    private fun scanSessions(sessionDir: File): List<SessionFile> {
        if (!sessionDir.isDirectory) {
            return emptyList()
        }
        val files =
            sessionDir.listFiles { _, name ->
                name.startsWith("session-") && name.endsWith(".jsonl")
            } ?: return emptyList()
        return files.map { scanSessionFile(it) }
    }

    private fun scanSessionFile(file: File): SessionFile {
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
            lastModified = file.lastModified(),
            errorLines = errors,
            tsFromName =
                NAME_TS
                    .find(file.name)
                    ?.groupValues
                    ?.get(1)
                    ?.toLongOrNull() ?: 0L,
        )
    }

    /**
     * The selection brain. Orders sessions (most recent first, then error sessions, then by recency)
     * and greedily assigns each a tail slice until the body budget is spent.
     */
    private fun planSessions(
        sessions: List<SessionFile>,
        summaryFile: File,
        appLogFile: File,
        budget: Int,
    ): List<Plan> {
        if (sessions.isEmpty()) {
            return emptyList()
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

        val summaryReserve = cappedSize(summaryFile, SUMMARY_CAP)
        val appLogReserve = cappedSize(appLogFile, APP_LOG_CAP)
        val manifestReserve = sessions.size * MANIFEST_BYTES_PER_ENTRY + 256
        var remaining = maxOf(0, budget - HEADER_RESERVE - summaryReserve - appLogReserve - manifestReserve)

        return ordered.mapIndexed { index, session ->
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
