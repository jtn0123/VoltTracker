package com.volttracker.obdpoc.ui.diag

import org.json.JSONObject
import java.util.Locale

/**
 * The saved trouble codes in a store diagnostics summary (`latestDiagnosticCodes`, newest first),
 * named and graded by [catalog]. A code seen by several modules or services is listed once, at its
 * newest.
 *
 * Its status is the strongest one its newest session reported: stored, then permanent, then
 * pending, then freeze frame. One scan saves a stored code up to three times (Mode 03, then 0A,
 * then the freeze frame's 02), each a little later than the last, so the newest row alone would
 * label it by whichever reply came last. A rescan after a clear is a session of its own, so a code
 * it only found permanent is shown as permanent.
 */
fun savedCodes(
    summary: JSONObject,
    catalog: DtcCatalog,
): List<DtcCode> {
    val rows = summary.optJSONArray("latestDiagnosticCodes") ?: return emptyList()
    val seen = ArrayList<Pair<DtcCode, Long?>>()
    for (i in 0 until rows.length()) {
        val row = rows.optJSONObject(i) ?: continue
        val dtc = row.optString("dtc", "").trim()
        if (dtc.isEmpty()) continue
        val code =
            catalog.describe(
                code = dtc,
                status = row.optString("status", "").trim().ifEmpty { DtcCode.STATUS_STORED },
                firstSeenMs = row.optLong("firstSeenMs", 0L),
                lastSeenMs = row.optLong("lastSeenMs", 0L),
                seenCount = row.optLong("seenCount", 1L).toInt().coerceAtLeast(1),
            )
        val session = if (row.isNull("lastSessionId")) null else row.optLong("lastSessionId")
        seen += code to session
    }
    return seen
        .groupBy { it.first.code }
        .values
        .map { reports ->
            // maxBy keeps the first of equally new rows, as the summary lists them.
            val newest = reports.maxBy { it.first.lastSeenMs }
            val strongest = reports.filter { it.second == newest.second }.minBy { statusRank(it.first.status) }
            newest.first.copy(status = strongest.first.status)
        }.sortedByDescending { it.lastSeenMs }
}

private fun statusRank(status: String): Int =
    when (status.lowercase(Locale.US)) {
        DtcCode.STATUS_PERMANENT -> 1
        DtcCode.STATUS_PENDING -> 2
        DtcCode.STATUS_FREEZE_FRAME -> 3
        else -> 0
    }
