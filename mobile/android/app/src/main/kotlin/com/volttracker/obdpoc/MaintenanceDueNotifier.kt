package com.volttracker.obdpoc

import org.json.JSONArray

/**
 * App-open coordinator for the maintenance-overdue notification (M2). It reads the maintenance log +
 * latest odometer, runs the pure [MaintenanceDueEvaluator] against the already-notified crossing
 * signatures, posts a one-shot [EventNotifier] alert per newly-overdue item, and persists the new
 * signatures so each crossing fires once.
 *
 * The body lives here (not on [MainActivity]) so the Activity adds a single call; every collaborator
 * is a lambda so this is exercisable without a Context. The maintenance check is NOT a per-sample
 * event — it runs on foreground entry — so it deliberately sits outside [EventNotificationDecider].
 *
 * Honours the opt-in [EventNotificationPrefs.maintenanceDueEnabled] toggle: a disabled toggle is a
 * no-op (no read, no post, no persist).
 */
class MaintenanceDueNotifier(
    private val isEnabled: () -> Boolean,
    private val readMaintenanceLogJson: () -> JSONArray?,
    private val readLatestOdometerKm: () -> Double?,
    private val readNotifiedSignatures: () -> Set<String>,
    private val persistNotifiedSignatures: (Set<String>) -> Unit,
    private val notify: (EventNotificationDecider.Event) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val evaluator: MaintenanceDueEvaluator = MaintenanceDueEvaluator(),
) {
    /**
     * Runs the check. Returns the number of alerts posted (0 when disabled, nothing overdue, or every
     * overdue crossing already fired). Safe to call on any thread the caller controls; the DB reads
     * and the notification post happen on whatever thread invokes this, so callers run it off the UI
     * thread (it touches SQLite).
     */
    fun checkOnAppOpen(): Int {
        if (!isEnabled()) {
            return 0
        }
        val entries = parseEntries(readMaintenanceLogJson())
        if (entries.isEmpty()) {
            return 0
        }
        val alreadyNotified = readNotifiedSignatures()
        val due = evaluator.evaluate(entries, readLatestOdometerKm(), now(), alreadyNotified)
        if (due.isEmpty()) {
            return 0
        }
        for (result in due) {
            notify(EventNotificationDecider.Event.MaintenanceDue(result.entry.type, result.overdueByText))
        }
        // Union the freshly-fired signatures into the persisted set so each crossing stays quiet on
        // the next app-open. Pruning to live entry ids is unnecessary: a deleted/re-logged entry gets
        // a new id, so its stale signature can never match a future crossing and is harmless.
        persistNotifiedSignatures(alreadyNotified + due.map { it.signature })
        return due.size
    }

    private fun parseEntries(log: JSONArray?): List<MaintenanceDueEvaluator.Entry> {
        if (log == null) {
            return emptyList()
        }
        val out = ArrayList<MaintenanceDueEvaluator.Entry>(log.length())
        for (i in 0 until log.length()) {
            val row = log.optJSONObject(i) ?: continue
            out.add(
                MaintenanceDueEvaluator.Entry(
                    id = row.optLong("id", -1L),
                    type = row.optString("type", ""),
                    odometerKm = optDoubleOrNull(row, "odometerKm"),
                    createdAtMs = row.optLong("createdAtMs", 0L),
                    intervalKm = optDoubleOrNull(row, "intervalKm"),
                    intervalMonths = optIntOrNull(row, "intervalMonths"),
                ),
            )
        }
        return out
    }

    companion object {
        /** Cap on maintenance rows scanned per app-open check; matches the dashboard read cap. */
        const val MAX_MAINTENANCE_ROWS = 200

        // optDouble/optInt return their default for JSON null too, so test isNull explicitly to keep
        // an unset interval as null (the evaluator treats it as "never fires") rather than 0.
        private fun optDoubleOrNull(
            row: org.json.JSONObject,
            key: String,
        ): Double? {
            if (row.isNull(key)) {
                return null
            }
            val value = row.optDouble(key, Double.NaN)
            return if (value.isNaN()) null else value
        }

        private fun optIntOrNull(
            row: org.json.JSONObject,
            key: String,
        ): Int? {
            if (row.isNull(key)) {
                return null
            }
            return if (row.has(key)) row.optInt(key) else null
        }
    }
}
