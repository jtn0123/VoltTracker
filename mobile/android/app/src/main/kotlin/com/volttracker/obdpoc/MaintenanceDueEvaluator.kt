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
 * A "crossing signature" pins a *specific* overdue crossing: the entry id plus which interval
 * tripped (`km` or `months`). An entry overdue on both distance and time yields one signature for
 * the worse-by-magnitude trip, so re-logging the service (a new entry id) re-arms a fresh crossing
 * while a still-overdue old entry stays quiet across app-opens.
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
     * signature the caller persists so this crossing fires exactly once; [overdueByText] is a short,
     * human-readable amount-overdue ("1,000 km", "12 days") for the notification copy.
     */
    data class DueResult(
        val entry: Entry,
        val signature: String,
        val tripped: Interval,
        val overdueByText: String,
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
            if (result.signature in alreadyNotified) {
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
                signature = signatureFor(entry.id, interval),
                tripped = interval,
                overdueByText =
                    when (interval) {
                        Interval.KM -> "${magnitude.roundToLong()} km"
                        Interval.MONTHS -> "${magnitude.roundToLong()} days"
                    },
            )
    }

    companion object {
        // Average days per month, matching the dashboard's AVG_DAYS_PER_MONTH so the time-overdue
        // threshold lands on the same day the card flips an entry to "overdue".
        private const val AVG_DAYS_PER_MONTH = 30.4375
        private const val MS_PER_DAY = 86_400_000L

        /** Builds the crossing signature the caller persists: entry id + which interval tripped. */
        fun signatureFor(
            entryId: Long,
            interval: Interval,
        ): String =
            when (interval) {
                Interval.KM -> "$entryId:km"
                Interval.MONTHS -> "$entryId:months"
            }
    }
}
