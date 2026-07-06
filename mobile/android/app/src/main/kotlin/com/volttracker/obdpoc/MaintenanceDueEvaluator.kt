package com.volttracker.obdpoc

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Pure, Android-free decision core for the maintenance-overdue notification (M2). It mirrors the
 * dashboard's per-entry due math in `storage-status.ts` (`maintenanceDue`) so the app fires a
 * one-shot notification when a logged maintenance item *crosses* into overdue, matching the
 * "overdue" line the maintenance card already shows visually.
 *
 * Unlike [EventNotificationDecider], this is NOT a per-telemetry-sample core: it runs on app-open
 * against the whole maintenance log + the latest odometer, so it carries no in-memory arming state.
 * Instead the caller passes the set of crossing signatures that have already fired (persisted in
 * [EventNotificationPrefs]); the evaluator only returns the entries whose overdue crossing is NOT in
 * that set, and reports the signature to persist so the same crossing never re-nags.
 *
 * A "crossing signature" pins an entry's overdue crossing by entry id alone (`<id>:overdue`),
 * independent of WHICH interval (km or months) is currently the worse-by-magnitude one. That
 * independence matters: an entry overdue on both distance and time can see the dominant interval
 * flip from km to months as the days-overdue grows and overtakes the km magnitude, and a
 * signature that encoded the interval would change under the same continuous overdue state and
 * re-nag. Re-logging the service (a new entry id) still re-arms a fresh crossing, while a
 * still-overdue old entry stays quiet across app-opens.
 */
class MaintenanceDueEvaluator {
    /**
     * One maintenance-log row the evaluator scores. Mirrors the dashboard `VoltMaintenanceEntry`
     * fields that feed `maintenanceDue`: the logged odometer/time the service happened at and the
     * optional service interval. [intervalKm]/[intervalMonths] are null when that interval is unset
     * (a plain history row that never fires), matching the JSON null the store emits.
     */
    data class Entry(
        val id: Long,
        val type: String,
        val odometerKm: Double?,
        val createdAtMs: Long,
        val intervalKm: Double?,
        val intervalMonths: Int?,
    )

    /**
     * A newly-overdue maintenance item that should fire a notification. [signature] is the crossing
     * signature the caller persists so this crossing fires exactly once. [tripped] names the interval
     * that is furthest past due and [overdueMagnitude] is the raw amount-overdue in that interval's
     * unit — kilometres for [Interval.KM], days for [Interval.MONTHS]. The human-readable copy (and
     * any km->miles unit conversion) is formatted downstream in [EventNotifier] so it can honour the
     * user's unit preference and locale grouping, rather than baking a fixed "km" string here.
     */
    data class DueResult(
        val entry: Entry,
        val signature: String,
        val tripped: Interval,
        val overdueMagnitude: Double,
    )

    /** Which configured interval pushed the entry overdue. */
    enum class Interval { KM, MONTHS }

    /**
     * Scores [entries] against the latest [odometerKm] (km; null when unknown) and [nowMs], skipping
     * any whose overdue crossing is already in [alreadyNotified]. Returns the newly-overdue entries
     * (one [DueResult] each). Entries with no interval, or not yet overdue, never appear.
     */
    fun evaluate(
        entries: List<Entry>,
        odometerKm: Double?,
        nowMs: Long,
        alreadyNotified: Set<String>,
    ): List<DueResult> {
        val out = ArrayList<DueResult>()
        for (entry in entries) {
            val result = evaluateEntry(entry, odometerKm, nowMs) ?: continue
            // Legacy compatibility: builds before the "<id>:overdue" unification persisted the crossing
            // as the interval-encoded "<id>:km" / "<id>:months". Treat either legacy form for this entry
            // as already-notified so a still-overdue item doesn't re-nag once after the upgrade.
            if (result.signature in alreadyNotified ||
                "${entry.id}:km" in alreadyNotified ||
                "${entry.id}:months" in alreadyNotified
            ) {
                continue
            }
            out.add(result)
        }
        return out
    }

    /**
     * The single-entry due decision, ported 1:1 from `storage-status.ts#maintenanceDue`. Returns the
     * worse-by-overdue-magnitude tripped interval, or null when the entry has no interval set or is
     * not yet overdue (the dashboard would render no "overdue" line for it).
     */
    private fun evaluateEntry(
        entry: Entry,
        odometerKm: Double?,
        nowMs: Long,
    ): DueResult? {
        val km = kmOverdue(entry, odometerKm)
        val months = monthsOverdue(entry, nowMs)
        // Both can be overdue at once; pick the larger-magnitude trip so the signature + copy name the
        // interval the user is furthest past, matching how the card's "overdue by …" reads worst-first.
        return when {
            km != null && (months == null || km.magnitude >= months.magnitude) -> km.toResult(entry)
            months != null -> months.toResult(entry)
            else -> null
        }
    }

    /** Distance overdue, or null when no distance interval is set or the entry is still ahead. */
    private fun kmOverdue(
        entry: Entry,
        odometerKm: Double?,
    ): Trip? {
        val intervalKm = entry.intervalKm ?: return null
        val loggedOdo = entry.odometerKm ?: return null
        if (intervalKm <= 0.0 || loggedOdo <= 0.0 || odometerKm == null) {
            return null
        }
        val dueAtKm = loggedOdo + intervalKm
        val remainingKm = dueAtKm - odometerKm
        if (remainingKm > 0.0) {
            return null
        }
        return Trip(Interval.KM, abs(remainingKm))
    }

    /** Time overdue, or null when no months interval is set or the entry is still ahead. */
    private fun monthsOverdue(
        entry: Entry,
        nowMs: Long,
    ): Trip? {
        val intervalMonths = entry.intervalMonths ?: return null
        if (intervalMonths <= 0 || entry.createdAtMs <= 0L) {
            return null
        }
        val dueAtMs = entry.createdAtMs + (intervalMonths * AVG_DAYS_PER_MONTH * MS_PER_DAY).toLong()
        val remainingDays = ((dueAtMs - nowMs).toDouble() / MS_PER_DAY).roundToLong()
        if (remainingDays > 0L) {
            return null
        }
        return Trip(Interval.MONTHS, abs(remainingDays).toDouble())
    }

    /** A tripped interval and how far past due it is (km, or days for the months interval). */
    private data class Trip(
        val interval: Interval,
        val magnitude: Double,
    ) {
        fun toResult(entry: Entry): DueResult =
            DueResult(
                entry = entry,
                signature = signatureFor(entry.id),
                tripped = interval,
                overdueMagnitude = magnitude,
            )
    }

    companion object {
        // Average days per month, matching the dashboard's AVG_DAYS_PER_MONTH so the time-overdue
        // threshold lands on the same day the card flips an entry to "overdue".
        private const val AVG_DAYS_PER_MONTH = 30.4375
        private const val MS_PER_DAY = 86_400_000L

        /**
         * Builds the crossing signature the caller persists: the entry id alone. Deliberately does
         * NOT encode which interval tripped, so a continuously-overdue entry keeps one stable
         * signature even when the dominant interval flips (km <-> months) and never re-nags.
         */
        fun signatureFor(entryId: Long): String = "$entryId:overdue"
    }
}
