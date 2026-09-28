package com.volttracker.obdpoc.ui.diag

import org.json.JSONObject

/**
 * The saved trouble codes in a store diagnostics summary (`latestDiagnosticCodes`, newest first),
 * named and graded by [catalog]. A code seen by several modules is listed once, at its newest.
 */
fun savedCodes(
    summary: JSONObject,
    catalog: DtcCatalog,
): List<DtcCode> {
    val rows = summary.optJSONArray("latestDiagnosticCodes") ?: return emptyList()
    val codes = LinkedHashMap<String, DtcCode>()
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
        val known = codes[code.code]
        if (known == null || code.lastSeenMs > known.lastSeenMs) codes[code.code] = code
    }
    return codes.values.sortedByDescending { it.lastSeenMs }
}
