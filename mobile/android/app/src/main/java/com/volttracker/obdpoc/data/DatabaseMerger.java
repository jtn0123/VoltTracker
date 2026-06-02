package com.volttracker.obdpoc.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Folds the rows of a donor VoltTracker database into a live one without losing either side's data
 * — the "I forgot to restore and now have two databases" recovery path. This is deliberately
 * additive: {@link com.volttracker.obdpoc.BackupController}'s restore path replaces the whole
 * database, while this merges.
 *
 * <p>Both databases are independent SQLite files that each counted their {@code _id} primary keys
 * from 1, so the donor's ids collide with the live ones. Every row is therefore re-inserted
 * <em>without</em> its {@code _id} (letting AUTOINCREMENT assign a fresh one) and every foreign key
 * is rewritten through an old&rarr;new map built as the merge walks the tables in dependency order:
 * parents before children, so the live connection's enabled foreign-key constraints stay satisfied
 * throughout.
 *
 * <h2>Dedup</h2>
 *
 * Sessions are deduplicated on {@code started_at_ms} — a millisecond-precise drive start time is
 * effectively unique per session. A donor session whose start time already exists live is
 * <em>not</em> added to the session map, and any donor row that references a non-mapped session is
 * dropped. That cleanly skips a duplicate drive's entire subtree (telemetry, GPS, events, trips,
 * charge sessions) instead of importing it a second time. Vehicles dedup differently: a matching
 * {@code vehicle_key} maps the donor vehicle onto the existing live one so the donor's trips attach
 * to it rather than creating a duplicate vehicle.
 *
 * <h2>Atomicity</h2>
 *
 * The whole merge runs in a single transaction on the target. Any failure rolls the transaction
 * back, leaving the live database byte-for-byte untouched — no half-merged state.
 *
 * <p>The {@code session_trip_rollups} cache is intentionally never copied: it is derived data the
 * app rebuilds lazily on the next Trips/Insights read for the newly-imported sessions.
 */
public final class DatabaseMerger {

    private DatabaseMerger() {}

    /** Outcome of a merge, surfaced to the user as a one-line status. */
    public static final class MergeResult {
        public final boolean ok;
        public final String error;
        public final int sessionsAdded;
        public final int sessionsSkipped;
        public final int vehiclesAdded;
        public final int vehiclesMerged;
        public final long rowsImported;

        private MergeResult(
                boolean ok,
                String error,
                int sessionsAdded,
                int sessionsSkipped,
                int vehiclesAdded,
                int vehiclesMerged,
                long rowsImported) {
            this.ok = ok;
            this.error = error;
            this.sessionsAdded = sessionsAdded;
            this.sessionsSkipped = sessionsSkipped;
            this.vehiclesAdded = vehiclesAdded;
            this.vehiclesMerged = vehiclesMerged;
            this.rowsImported = rowsImported;
        }

        static MergeResult failure(String error) {
            return new MergeResult(false, error, 0, 0, 0, 0, 0L);
        }

        /**
         * Human-readable summary for the dashboard status line. Reports sessions and vehicles
         * explicitly so a backup that only contributes vehicle/adapter/DTC history doesn't read as
         * "Merged 0 sessions" (i.e. as though nothing happened).
         */
        public String summary() {
            if (!ok) {
                return error == null ? "Merge failed." : error;
            }
            StringBuilder sb = new StringBuilder("Merged backup - ");
            if (sessionsAdded > 0) {
                sb.append(sessionsAdded)
                        .append(sessionsAdded == 1 ? " new session" : " new sessions");
            } else {
                sb.append("no new sessions");
            }
            if (vehiclesAdded > 0) {
                sb.append(", ")
                        .append(vehiclesAdded)
                        .append(vehiclesAdded == 1 ? " new vehicle" : " new vehicles");
            }
            if (sessionsSkipped > 0) {
                sb.append(" (")
                        .append(sessionsSkipped)
                        .append(
                                sessionsSkipped == 1
                                        ? " duplicate skipped)"
                                        : " duplicates skipped)");
            }
            sb.append('.');
            return sb.toString();
        }
    }

    /**
     * Merges every importable row from {@code donor} into {@code target} in one transaction.
     *
     * @param target the live, writable database (foreign keys enabled, owns the transaction)
     * @param donor a read-only handle on a verified VoltTracker backup at the same schema version
     */
    public static MergeResult merge(SQLiteDatabase target, SQLiteDatabase donor) {
        if (target == null || donor == null) {
            return MergeResult.failure("Merge failed - database handle unavailable.");
        }
        Map<Long, Long> sessionMap = new HashMap<>();
        Map<Long, Long> vehicleMap = new HashMap<>();
        Map<Long, Long> telemetryMap = new HashMap<>();
        Map<Long, Long> batteryMap = new HashMap<>();
        int[] vehicleCounts = new int[2]; // {added, merged}
        int[] sessionCounts = new int[2]; // {added, skipped}
        long rows = 0L;

        target.beginTransaction();
        try {
            // 1. Entities first: vehicles (dedup/merge on vehicle_key) seed the vehicle map.
            rows += copyVehicles(target, donor, vehicleMap, vehicleCounts);
            // 2. Sessions (dedup on started_at_ms) seed the session map; skipped = duplicates.
            rows += copySessions(target, donor, sessionMap, sessionCounts);
            // 3. Direct session children. telemetry seeds the telemetry map for sample pointers.
            rows +=
                    copyChildren(
                            target,
                            donor,
                            VoltTrackerDb.TABLE_TELEMETRY,
                            sessionMap,
                            null,
                            null,
                            telemetryMap);
            rows +=
                    copyChildren(
                            target,
                            donor,
                            VoltTrackerDb.TABLE_EVENTS,
                            sessionMap,
                            null,
                            null,
                            null);
            rows +=
                    copyChildren(
                            target,
                            donor,
                            VoltTrackerDb.TABLE_PID_OBSERVATIONS,
                            sessionMap,
                            null,
                            null,
                            null);
            rows +=
                    copyChildren(
                            target,
                            donor,
                            VoltTrackerDb.TABLE_LOCATION_SAMPLES,
                            sessionMap,
                            null,
                            null,
                            null);
            // 4. Vehicle children.
            rows +=
                    copyChildren(
                            target,
                            donor,
                            VoltTrackerDb.TABLE_FIELD_CAPABILITIES,
                            sessionMap,
                            vehicleMap,
                            null,
                            null);
            // 5. Mixed rows referencing session, vehicle and telemetry sample ids.
            rows +=
                    copyChildren(
                            target,
                            donor,
                            VoltTrackerDb.TABLE_TRIP_SEGMENTS,
                            sessionMap,
                            vehicleMap,
                            telemetryMap,
                            null);
            rows +=
                    copyChildren(
                            target,
                            donor,
                            VoltTrackerDb.TABLE_CHARGE_SESSIONS,
                            sessionMap,
                            vehicleMap,
                            telemetryMap,
                            null);
            rows +=
                    copyChildren(
                            target,
                            donor,
                            VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS,
                            sessionMap,
                            vehicleMap,
                            null,
                            batteryMap);
            rows += copyCellSnapshots(target, donor, batteryMap);
            rows +=
                    copyChildren(
                            target,
                            donor,
                            VoltTrackerDb.TABLE_EXPORTS,
                            sessionMap,
                            vehicleMap,
                            null,
                            null);
            // 6. Natural-key upsert tables.
            rows += copyAdapterHistory(target, donor, sessionMap);
            rows += copyDiagnosticCodes(target, donor, sessionMap);
            // session_trip_rollups is intentionally skipped (regenerable cache).

            target.setTransactionSuccessful();
        } catch (RuntimeException ex) {
            return MergeResult.failure(
                    "Merge failed - " + ex.getClass().getSimpleName() + ", no changes were made.");
        } finally {
            target.endTransaction();
        }
        return new MergeResult(
                true,
                null,
                sessionCounts[0],
                sessionCounts[1],
                vehicleCounts[0],
                vehicleCounts[1],
                rows);
    }

    /** Merges vehicles, deduplicating on the UNIQUE {@code vehicle_key}. */
    private static long copyVehicles(
            SQLiteDatabase target, SQLiteDatabase donor, Map<Long, Long> vehicleMap, int[] counts) {
        Map<String, Long> existingByKey = new HashMap<>();
        try (Cursor c =
                target.rawQuery(
                        "SELECT _id, vehicle_key FROM " + VoltTrackerDb.TABLE_VEHICLES, null)) {
            while (c.moveToNext()) {
                existingByKey.put(c.getString(1), c.getLong(0));
            }
        }
        long inserted = 0L;
        try (Cursor c = donor.rawQuery("SELECT * FROM " + VoltTrackerDb.TABLE_VEHICLES, null)) {
            while (c.moveToNext()) {
                ContentValues cv = readRow(c);
                Long oldId = cv.getAsLong("_id");
                String key = cv.getAsString("vehicle_key");
                if (oldId == null || key == null) {
                    continue;
                }
                Long liveId = existingByKey.get(key);
                if (liveId != null) {
                    vehicleMap.put(oldId, liveId);
                    counts[1]++;
                    continue;
                }
                cv.remove("_id");
                long newId = target.insertOrThrow(VoltTrackerDb.TABLE_VEHICLES, null, cv);
                vehicleMap.put(oldId, newId);
                existingByKey.put(key, newId);
                counts[0]++;
                inserted++;
            }
        }
        return inserted;
    }

    /** Merges sessions, deduplicating on {@code started_at_ms}. */
    private static long copySessions(
            SQLiteDatabase target, SQLiteDatabase donor, Map<Long, Long> sessionMap, int[] counts) {
        Set<Long> existingStartTimes = new HashSet<>();
        try (Cursor c =
                target.rawQuery(
                        "SELECT started_at_ms FROM " + VoltTrackerDb.TABLE_SESSIONS, null)) {
            while (c.moveToNext()) {
                existingStartTimes.add(c.getLong(0));
            }
        }
        long inserted = 0L;
        try (Cursor c = donor.rawQuery("SELECT * FROM " + VoltTrackerDb.TABLE_SESSIONS, null)) {
            while (c.moveToNext()) {
                ContentValues cv = readRow(c);
                Long oldId = cv.getAsLong("_id");
                Long startedAt = cv.getAsLong("started_at_ms");
                if (oldId == null || startedAt == null) {
                    continue;
                }
                if (existingStartTimes.contains(startedAt)) {
                    counts[1]++; // duplicate: leave out of the map so its subtree is dropped
                    continue;
                }
                cv.remove("_id");
                long newId = target.insertOrThrow(VoltTrackerDb.TABLE_SESSIONS, null, cv);
                sessionMap.put(oldId, newId);
                existingStartTimes.add(startedAt);
                counts[0]++;
                inserted++;
            }
        }
        return inserted;
    }

    /**
     * Generic copy for tables keyed by an AUTOINCREMENT {@code _id}. A row whose donor {@code
     * session_id} is non-null but absent from {@code sessionMap} belonged to a skipped (duplicate)
     * session and is dropped entirely. Foreign keys present in the table are remapped through the
     * supplied maps; {@code null} maps mean "this table has no such column".
     *
     * @param outIdMap if non-null, records old&rarr;new {@code _id} for downstream references
     */
    private static long copyChildren(
            SQLiteDatabase target,
            SQLiteDatabase donor,
            String table,
            Map<Long, Long> sessionMap,
            Map<Long, Long> vehicleMap,
            Map<Long, Long> telemetryMap,
            Map<Long, Long> outIdMap) {
        long inserted = 0L;
        try (Cursor c = donor.rawQuery("SELECT * FROM " + table, null)) {
            while (c.moveToNext()) {
                ContentValues cv = readRow(c);
                Long oldId = cv.getAsLong("_id");
                if (cv.containsKey("session_id")) {
                    Long donorSession = cv.getAsLong("session_id");
                    if (donorSession != null && !sessionMap.containsKey(donorSession)) {
                        continue; // parent session was a duplicate / not imported
                    }
                }
                cv.remove("_id");
                remap(cv, "session_id", sessionMap);
                if (vehicleMap != null) {
                    remap(cv, "vehicle_id", vehicleMap);
                }
                if (telemetryMap != null) {
                    remap(cv, "start_sample_id", telemetryMap);
                    remap(cv, "end_sample_id", telemetryMap);
                }
                long newId = target.insertOrThrow(table, null, cv);
                if (outIdMap != null && oldId != null) {
                    outIdMap.put(oldId, newId);
                }
                inserted++;
            }
        }
        return inserted;
    }

    /** Cell snapshots hang off battery snapshots via a NOT NULL CASCADE foreign key. */
    private static long copyCellSnapshots(
            SQLiteDatabase target, SQLiteDatabase donor, Map<Long, Long> batteryMap) {
        long inserted = 0L;
        try (Cursor c =
                donor.rawQuery("SELECT * FROM " + VoltTrackerDb.TABLE_CELL_SNAPSHOTS, null)) {
            while (c.moveToNext()) {
                ContentValues cv = readRow(c);
                Long parent = cv.getAsLong("battery_snapshot_id");
                if (parent == null || !batteryMap.containsKey(parent)) {
                    continue; // parent battery snapshot was not imported
                }
                cv.remove("_id");
                cv.put("battery_snapshot_id", batteryMap.get(parent));
                target.insertOrThrow(VoltTrackerDb.TABLE_CELL_SNAPSHOTS, null, cv);
                inserted++;
            }
        }
        return inserted;
    }

    /** Upserts adapter history on its {@code adapter_key} primary key, summing usage counters. */
    private static long copyAdapterHistory(
            SQLiteDatabase target, SQLiteDatabase donor, Map<Long, Long> sessionMap) {
        long touched = 0L;
        try (Cursor c =
                donor.rawQuery("SELECT * FROM " + VoltTrackerDb.TABLE_ADAPTER_HISTORY, null)) {
            while (c.moveToNext()) {
                ContentValues cv = readRow(c);
                String key = cv.getAsString("adapter_key");
                if (key == null) {
                    continue;
                }
                remap(cv, "last_session_id", sessionMap);
                ContentValues existing =
                        queryOne(
                                target,
                                VoltTrackerDb.TABLE_ADAPTER_HISTORY,
                                "adapter_key = ?",
                                new String[] {key});
                if (existing == null) {
                    target.insertOrThrow(VoltTrackerDb.TABLE_ADAPTER_HISTORY, null, cv);
                } else {
                    ContentValues merged = new ContentValues();
                    sumLong(merged, "connect_count", existing, cv);
                    sumLong(merged, "scan_count", existing, cv);
                    sumLong(merged, "demo_count", existing, cv);
                    sumLong(merged, "sample_count", existing, cv);
                    merged.put(
                            "first_seen_ms",
                            minLong(
                                    existing.getAsLong("first_seen_ms"),
                                    cv.getAsLong("first_seen_ms")));
                    long liveLast = orZero(existing.getAsLong("last_seen_ms"));
                    long donorLast = orZero(cv.getAsLong("last_seen_ms"));
                    merged.put("last_seen_ms", Math.max(liveLast, donorLast));
                    // Adopt the donor's "most recent" descriptive fields only if it saw the
                    // adapter more recently than the live row did.
                    if (donorLast >= liveLast) {
                        copyIfPresent(merged, cv, "address");
                        copyIfPresent(merged, cv, "name");
                        copyIfPresent(merged, cv, "last_session_id");
                        copyIfPresent(merged, cv, "last_mode");
                        copyIfPresent(merged, cv, "last_status");
                        copyIfPresent(merged, cv, "supported_pids");
                        copyIfPresent(merged, cv, "last_event_detail");
                    }
                    target.update(
                            VoltTrackerDb.TABLE_ADAPTER_HISTORY,
                            merged,
                            "adapter_key = ?",
                            new String[] {key});
                }
                touched++;
            }
        }
        return touched;
    }

    /** Upserts diagnostic codes on their UNIQUE {@code (module_key, dtc, status)} natural key. */
    private static long copyDiagnosticCodes(
            SQLiteDatabase target, SQLiteDatabase donor, Map<Long, Long> sessionMap) {
        long touched = 0L;
        try (Cursor c =
                donor.rawQuery("SELECT * FROM " + VoltTrackerDb.TABLE_DIAGNOSTIC_CODES, null)) {
            while (c.moveToNext()) {
                ContentValues cv = readRow(c);
                String moduleKey = cv.getAsString("module_key");
                String dtc = cv.getAsString("dtc");
                String status = cv.getAsString("status");
                if (moduleKey == null || dtc == null || status == null) {
                    continue;
                }
                cv.remove("_id");
                remap(cv, "last_session_id", sessionMap);
                String where = "module_key = ? AND dtc = ? AND status = ?";
                String[] args = {moduleKey, dtc, status};
                ContentValues existing =
                        queryOne(target, VoltTrackerDb.TABLE_DIAGNOSTIC_CODES, where, args);
                if (existing == null) {
                    target.insertOrThrow(VoltTrackerDb.TABLE_DIAGNOSTIC_CODES, null, cv);
                } else {
                    ContentValues merged = new ContentValues();
                    sumLong(merged, "seen_count", existing, cv);
                    merged.put(
                            "first_seen_ms",
                            minLong(
                                    existing.getAsLong("first_seen_ms"),
                                    cv.getAsLong("first_seen_ms")));
                    long liveLast = orZero(existing.getAsLong("last_seen_ms"));
                    long donorLast = orZero(cv.getAsLong("last_seen_ms"));
                    merged.put("last_seen_ms", Math.max(liveLast, donorLast));
                    if (donorLast >= liveLast) {
                        copyIfPresent(merged, cv, "status_label");
                        copyIfPresent(merged, cv, "module_name");
                        copyIfPresent(merged, cv, "header");
                        copyIfPresent(merged, cv, "last_session_id");
                        copyIfPresent(merged, cv, "raw_response");
                        copyIfPresent(merged, cv, "json");
                    }
                    target.update(VoltTrackerDb.TABLE_DIAGNOSTIC_CODES, merged, where, args);
                }
                touched++;
            }
        }
        return touched;
    }

    // ---- helpers --------------------------------------------------------------------

    /** Reads the cursor's current row into a {@link ContentValues}, preserving column types. */
    private static ContentValues readRow(Cursor c) {
        ContentValues cv = new ContentValues();
        int count = c.getColumnCount();
        for (int i = 0; i < count; i++) {
            String name = c.getColumnName(i);
            switch (c.getType(i)) {
                case Cursor.FIELD_TYPE_NULL:
                    cv.putNull(name);
                    break;
                case Cursor.FIELD_TYPE_INTEGER:
                    cv.put(name, c.getLong(i));
                    break;
                case Cursor.FIELD_TYPE_FLOAT:
                    cv.put(name, c.getDouble(i));
                    break;
                case Cursor.FIELD_TYPE_BLOB:
                    cv.put(name, c.getBlob(i));
                    break;
                default:
                    cv.put(name, c.getString(i));
                    break;
            }
        }
        return cv;
    }

    /**
     * Rewrites a foreign-key column in place: a non-null value present in {@code map} becomes its
     * new id; a value absent from the map becomes NULL (the column is always a SET NULL reference
     * when this fallback applies); a null stays null.
     */
    private static void remap(ContentValues cv, String column, Map<Long, Long> map) {
        if (!cv.containsKey(column)) {
            return;
        }
        Long old = cv.getAsLong(column);
        if (old == null) {
            return;
        }
        Long mapped = map.get(old);
        if (mapped == null) {
            cv.putNull(column);
        } else {
            cv.put(column, mapped);
        }
    }

    private static ContentValues queryOne(
            SQLiteDatabase db, String table, String where, String[] args) {
        try (Cursor c = db.query(table, null, where, args, null, null, null, "1")) {
            if (!c.moveToFirst()) {
                return null;
            }
            return readRow(c);
        }
    }

    private static void sumLong(
            ContentValues out, String column, ContentValues a, ContentValues b) {
        out.put(column, orZero(a.getAsLong(column)) + orZero(b.getAsLong(column)));
    }

    private static void copyIfPresent(ContentValues out, ContentValues src, String column) {
        if (!src.containsKey(column)) {
            return;
        }
        Object value = src.get(column);
        if (value == null) {
            out.putNull(column);
        } else if (value instanceof Long) {
            out.put(column, (Long) value);
        } else if (value instanceof Integer) {
            out.put(column, (Integer) value);
        } else if (value instanceof Double) {
            out.put(column, (Double) value);
        } else if (value instanceof byte[]) {
            out.put(column, (byte[]) value);
        } else {
            out.put(column, String.valueOf(value));
        }
    }

    private static long minLong(Long a, Long b) {
        if (a == null) {
            return orZero(b);
        }
        if (b == null) {
            return a;
        }
        return Math.min(a, b);
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }
}
