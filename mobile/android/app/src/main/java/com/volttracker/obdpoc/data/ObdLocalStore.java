package com.volttracker.obdpoc.data;

import static com.volttracker.obdpoc.data.ObdStatementCache.SQL_UPDATE_SESSION_AFTER_TELEMETRY;
import static com.volttracker.obdpoc.data.ObdStoreSupport.adapterKey;
import static com.volttracker.obdpoc.data.ObdStoreSupport.clean;
import static com.volttracker.obdpoc.data.ObdStoreSupport.cleanMode;
import static com.volttracker.obdpoc.data.ObdStoreSupport.cleanStatus;
import static com.volttracker.obdpoc.data.ObdStoreSupport.isUsefulTelemetry;
import static com.volttracker.obdpoc.data.ObdStoreSupport.optTimestamp;
import static com.volttracker.obdpoc.data.ObdStoreSupport.updateSessionLastEvent;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import com.volttracker.obdpoc.materialize.ChargeSession;
import com.volttracker.obdpoc.materialize.LocationSample;
import com.volttracker.obdpoc.materialize.MaterializerData;
import com.volttracker.obdpoc.materialize.PidObservation;
import com.volttracker.obdpoc.materialize.TelemetrySample;
import com.volttracker.obdpoc.materialize.Trip;
import java.io.Closeable;
import java.io.File;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * On-device SQLite store for OBD sessions, telemetry, GPS, events and adapter history.
 *
 * <p>This class is the stable public facade. It owns transaction control and session lifecycle;
 * read-side JSON projections are delegated to {@link ObdStoreReports} and {@link ObdStoreTrips},
 * the prepared telemetry statement + bind helpers live in {@link ObdStatementCache}, write-side
 * payload construction lives in {@link ObdStoreSnapshots}, maintenance lives in {@link
 * ObdStoreMaintenance}, and stateless helpers are in {@link ObdStoreSupport}.
 */
public class ObdLocalStore implements Closeable, MaterializerData {
    public static final String MODE_OBD = "obd";
    public static final String MODE_SCAN = "scan";
    public static final String MODE_DEMO = "demo";

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_COMPLETE = "complete";
    public static final String STATUS_ERROR = "error";
    public static final String STATUS_DISCONNECTED = "disconnected";

    private final VoltTrackerDb helper;
    private final ObdStoreTrips trips;
    private final ObdStoreReports reports;
    private final ObdStoreMaintenance maintenance;
    private final ObdStoreSnapshots snapshots;
    private final ObdStatementCache statementCache;
    private final ObdStoreMaterialize materialize;

    public ObdLocalStore(Context context) {
        Context appContext = context.getApplicationContext();
        helper = new VoltTrackerDb(appContext);
        trips = new ObdStoreTrips(helper);
        reports = new ObdStoreReports(helper, trips);
        maintenance = new ObdStoreMaintenance(appContext, helper);
        snapshots = new ObdStoreSnapshots();
        statementCache = new ObdStatementCache();
        materialize = new ObdStoreMaterialize(helper);
    }

    // ---- session lifecycle ---------------------------------------------------------

    public long startSession(String mode, String adapterAddress, String adapterName) {
        return startSession(mode, adapterAddress, adapterName, System.currentTimeMillis());
    }

    public long startSession(
            String mode, String adapterAddress, String adapterName, long startedAtMs) {
        ContentValues values = new ContentValues();
        values.put("mode", cleanMode(mode));
        values.put("adapter_address", clean(adapterAddress));
        values.put("adapter_name", clean(adapterName));
        values.put("started_at_ms", startedAtMs);
        values.put("status", STATUS_ACTIVE);
        values.put("created_at_ms", System.currentTimeMillis());
        return helper.getWritableDatabase()
                .insertOrThrow(VoltTrackerDb.TABLE_SESSIONS, null, values);
    }

    public void finishSession(long sessionId, String status) {
        finishSession(sessionId, status, System.currentTimeMillis(), null);
    }

    public void finishSession(long sessionId, String status, long endedAtMs, String supportedPids) {
        ContentValues values = new ContentValues();
        values.put("ended_at_ms", endedAtMs);
        values.put("status", cleanStatus(status));
        if (supportedPids != null) {
            values.put("supported_pids", supportedPids);
        }
        helper.getWritableDatabase()
                .update(
                        VoltTrackerDb.TABLE_SESSIONS,
                        values,
                        "_id = ?",
                        new String[] {String.valueOf(sessionId)});
    }

    /**
     * Atomically finalises a session: marks the session row as ended AND upserts the
     * adapter-history summary in a single transaction. A crash between the two writes used to leave
     * the session ended but the adapter row stale (or vice versa); the transaction here keeps the
     * two rows consistent.
     *
     * <p>{@link #finishSession} and {@link #recordAdapterSummary} are still public so other callers
     * (tests, future tooling) can use them independently — this method just wraps both inside one
     * {@code beginTransaction()} block.
     */
    public void finalizeSession(
            long sessionId,
            String status,
            long endedAtMs,
            String supportedPids,
            String address,
            String adapterName,
            String mode,
            int sampleCount,
            String lastEventDetail) {
        String cleanedStatus = cleanStatus(status);
        String cleanMode = cleanMode(mode);
        String key = adapterKey(address, cleanMode);
        long now = System.currentTimeMillis();
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues sessionValues = new ContentValues();
            sessionValues.put("ended_at_ms", endedAtMs);
            sessionValues.put("status", cleanedStatus);
            if (supportedPids != null) {
                sessionValues.put("supported_pids", supportedPids);
            }
            db.update(
                    VoltTrackerDb.TABLE_SESSIONS,
                    sessionValues,
                    "_id = ?",
                    new String[] {String.valueOf(sessionId)});

            AdapterHistoryRecord existing = snapshots.findAdapterHistory(db, key);
            ContentValues adapterValues =
                    snapshots.adapterHistoryValues(
                            existing,
                            key,
                            address,
                            adapterName,
                            cleanMode,
                            cleanedStatus,
                            sampleCount,
                            supportedPids,
                            lastEventDetail,
                            sessionId,
                            now);
            db.insertWithOnConflict(
                    VoltTrackerDb.TABLE_ADAPTER_HISTORY,
                    null,
                    adapterValues,
                    SQLiteDatabase.CONFLICT_REPLACE);

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ---- telemetry / observations / location --------------------------------------

    public long recordTelemetry(long sessionId, JSONObject sample) {
        long capturedAtMs =
                sample == null
                        ? System.currentTimeMillis()
                        : sample.optLong("updatedAt", System.currentTimeMillis());
        return recordTelemetry(sessionId, sample, capturedAtMs);
    }

    public long recordTelemetry(long sessionId, JSONObject sample, long capturedAtMs) {
        JSONObject safeSample = sample == null ? new JSONObject() : sample;
        if (!isUsefulTelemetry(safeSample)) {
            return -1L;
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            long id =
                    statementCache.bindAndInsertTelemetry(db, sessionId, capturedAtMs, safeSample);
            String supportedPids = clean(safeSample.optString("supportedPids", ""));
            db.execSQL(
                    SQL_UPDATE_SESSION_AFTER_TELEMETRY,
                    new Object[] {capturedAtMs, supportedPids, supportedPids, sessionId});
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    public long recordPidObservation(long sessionId, JSONObject observation) {
        JSONObject safeObservation = observation == null ? new JSONObject() : observation;
        long observedAtMs =
                optTimestamp(
                        safeObservation,
                        "observedAtMs",
                        optTimestamp(
                                safeObservation,
                                "observedAt",
                                safeObservation.optLong("updatedAt", System.currentTimeMillis())));
        return recordPidObservation(sessionId, safeObservation, observedAtMs);
    }

    public long recordPidObservation(long sessionId, JSONObject observation, long observedAtMs) {
        JSONObject safeObservation = observation == null ? new JSONObject() : observation;
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values =
                    snapshots.pidObservationValues(sessionId, safeObservation, observedAtMs);
            long id = db.insertOrThrow(VoltTrackerDb.TABLE_PID_OBSERVATIONS, null, values);
            updateSessionLastEvent(db, sessionId, observedAtMs);
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    public long recordPidObservation(
            long sessionId,
            long observedAtMs,
            String command,
            String header,
            String pid,
            String name,
            String valueText,
            Double valueNumeric,
            String unit,
            String rawRequest,
            String rawResponse) {
        JSONObject payload =
                snapshots.pidObservationPayload(
                        observedAtMs,
                        command,
                        header,
                        pid,
                        name,
                        valueText,
                        valueNumeric,
                        unit,
                        rawRequest,
                        rawResponse);
        return recordPidObservation(sessionId, payload, observedAtMs);
    }

    public long recordDiagnosticCode(long sessionId, JSONObject diagnosticCode) {
        JSONObject safeCode = diagnosticCode == null ? new JSONObject() : diagnosticCode;
        long seenAtMs =
                optTimestamp(
                        safeCode,
                        "seenAtMs",
                        optTimestamp(safeCode, "observedAtMs", System.currentTimeMillis()));
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            long id = snapshots.upsertDiagnosticCode(db, sessionId, safeCode, seenAtMs);
            if (id >= 0) {
                updateSessionLastEvent(db, sessionId, seenAtMs);
            }
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    public long recordLocationSample(long sessionId, JSONObject sample) {
        JSONObject safeSample = sample == null ? new JSONObject() : sample;
        long capturedAtMs =
                optTimestamp(
                        safeSample,
                        "capturedAtMs",
                        optTimestamp(
                                safeSample,
                                "timestampMs",
                                safeSample.optLong("updatedAt", System.currentTimeMillis())));
        return recordLocationSample(sessionId, safeSample, capturedAtMs);
    }

    public long recordLocationSample(long sessionId, JSONObject sample, long capturedAtMs) {
        JSONObject safeSample = sample == null ? new JSONObject() : sample;
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values =
                    snapshots.locationSampleValues(sessionId, safeSample, capturedAtMs);
            long id = db.insertOrThrow(VoltTrackerDb.TABLE_LOCATION_SAMPLES, null, values);
            updateSessionLastEvent(db, sessionId, capturedAtMs);
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    public long recordLocationSample(
            long sessionId,
            long capturedAtMs,
            String provider,
            double latitude,
            double longitude,
            Double accuracyM,
            Double altitudeM,
            Double speedMps,
            Double bearingDeg,
            Long locationAgeMs,
            Long elapsedRealtimeNanos) {
        JSONObject payload =
                snapshots.locationSamplePayload(
                        capturedAtMs,
                        provider,
                        latitude,
                        longitude,
                        accuracyM,
                        altitudeM,
                        speedMps,
                        bearingDeg,
                        locationAgeMs,
                        elapsedRealtimeNanos);
        return recordLocationSample(sessionId, payload, capturedAtMs);
    }

    public long recordStatus(
            long sessionId, String state, String detail, boolean blocked, JSONObject payload) {
        return recordEvent(sessionId, "status", state, detail, blocked, payload);
    }

    public long recordEvent(
            long sessionId,
            String kind,
            String state,
            String detail,
            boolean blocked,
            JSONObject payload) {
        long occurredAtMs =
                payload == null
                        ? System.currentTimeMillis()
                        : payload.optLong("updatedAt", System.currentTimeMillis());
        JSONObject safePayload = payload == null ? new JSONObject() : payload;
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            if (sessionId > 0) {
                values.put("session_id", sessionId);
            }
            values.put("occurred_at_ms", occurredAtMs);
            values.put("kind", clean(kind).isEmpty() ? "event" : clean(kind));
            values.put("state", clean(state));
            values.put("detail", clean(detail));
            values.put("blocked", blocked ? 1 : 0);
            values.put("payload", safePayload.toString());
            long id = db.insertOrThrow(VoltTrackerDb.TABLE_EVENTS, null, values);
            updateSessionLastEvent(db, sessionId, occurredAtMs);
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    public void recordAdapterSummary(
            String address,
            String name,
            String mode,
            long sessionId,
            String status,
            int samples,
            String supportedPids,
            String lastEventDetail) {
        long now = System.currentTimeMillis();
        String cleanMode = cleanMode(mode);
        String key = adapterKey(address, cleanMode);
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            AdapterHistoryRecord existing = snapshots.findAdapterHistory(db, key);
            ContentValues values =
                    snapshots.adapterHistoryValues(
                            existing,
                            key,
                            address,
                            name,
                            cleanMode,
                            cleanStatus(status),
                            samples,
                            supportedPids,
                            lastEventDetail,
                            sessionId,
                            now);
            db.insertWithOnConflict(
                    VoltTrackerDb.TABLE_ADAPTER_HISTORY,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ---- typed-record reads (delegated) --------------------------------------------

    public ObdSessionRecord getSession(long sessionId) {
        return reports.getSession(sessionId);
    }

    public List<ObdSessionRecord> getRecentSessions(int limit) {
        return reports.getRecentSessions(limit);
    }

    public List<TelemetrySampleRecord> getRecentTelemetry(long sessionId, int limit) {
        return reports.getRecentTelemetry(sessionId, limit);
    }

    public List<StatusEventRecord> getRecentEvents(long sessionId, int limit) {
        return reports.getRecentEvents(sessionId, limit);
    }

    public List<AdapterHistoryRecord> getAdapterHistory(int limit) {
        return reports.getAdapterHistory(limit);
    }

    // ---- JSON projections (delegated) ----------------------------------------------

    public JSONObject getStorageSummary() {
        return reports.storageSummary(getDatabaseFile());
    }

    public JSONArray getRecentSessionsJson(int limit) {
        return reports.recentSessionsJson(limit);
    }

    public JSONArray getAdapterHistoryJson(int limit) {
        return reports.adapterHistoryJson(limit);
    }

    public JSONArray getTripsJson(int limit) {
        return trips.tripsJson(limit);
    }

    public JSONObject getInsightsJson() {
        return trips.insightsJson();
    }

    // ---- materializer plumbing (delegated) -----------------------------------------

    @Override
    public List<LocationSample> readLocationSamples(long sessionId) {
        return materialize.readLocationSamples(sessionId);
    }

    @Override
    public List<PidObservation> readPidObservations(long sessionId) {
        return materialize.readPidObservations(sessionId);
    }

    @Override
    public List<TelemetrySample> readTelemetrySamples(long sessionId) {
        return materialize.readTelemetrySamples(sessionId);
    }

    /** Inserts every {@link Trip} as a {@code trip_segments} row inside one transaction. */
    public void persistTrips(long sessionId, List<Trip> tripsToPersist) {
        materialize.persistTrips(sessionId, tripsToPersist);
    }

    /** Inserts every {@link ChargeSession} as a {@code charge_sessions} row in one transaction. */
    public void persistChargeSessions(long sessionId, List<ChargeSession> sessions) {
        materialize.persistChargeSessions(sessionId, sessions);
    }

    // ---- maintenance (delegated) ---------------------------------------------------

    public void clearAllData() {
        maintenance.clearAllData();
    }

    public File getDatabaseFile() {
        return maintenance.getDatabaseFile();
    }

    /** Flushes the write-ahead log into the main DB file so a file copy is a complete backup. */
    public void checkpoint() {
        maintenance.checkpoint();
    }

    /** Default raw-data retention in days (telemetry / location / events / pid_observations). */
    public static final int DEFAULT_RAW_RETENTION_DAYS =
            ObdStoreMaintenance.DEFAULT_RAW_RETENTION_DAYS;

    /**
     * Prunes raw telemetry, location samples, status events, and PID observations older than {@code
     * keepDays} days. Per-session summaries and derived rows are preserved. Returns the total
     * number of rows deleted. See {@link ObdStoreMaintenance#pruneRawDataOlderThan(int)} for the
     * full contract.
     */
    public int pruneRawDataOlderThan(int keepDays) {
        return maintenance.pruneRawDataOlderThan(keepDays);
    }

    @Override
    public void close() {
        statementCache.close();
        helper.close();
    }
}
