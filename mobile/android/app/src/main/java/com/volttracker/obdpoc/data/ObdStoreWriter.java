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
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;

/**
 * The write half of the local store (A2): session lifecycle, telemetry/PID/location/event inserts,
 * and adapter-history upserts. Split out of {@link ObdLocalStore} so the facade is no longer one
 * class owning both the wide write surface and every read projection; {@code ObdLocalStore} now
 * delegates its {@code ObdSessionStore} methods here. Owns the prepared-statement cache used by the
 * hot telemetry-insert path. (VIN-derived vehicle identity lives in {@link ObdStoreVehicles}.)
 */
final class ObdStoreWriter {

    private final VoltTrackerDb helper;
    private final ObdStoreSnapshots snapshots;
    private final ObdStatementCache statementCache = new ObdStatementCache();

    ObdStoreWriter(VoltTrackerDb helper, ObdStoreSnapshots snapshots) {
        this.helper = helper;
        this.snapshots = snapshots;
    }

    // ---- session lifecycle ---------------------------------------------------------

    long startSession(String mode, String adapterAddress, String adapterName) {
        return startSession(mode, adapterAddress, adapterName, System.currentTimeMillis());
    }

    long startSession(String mode, String adapterAddress, String adapterName, long startedAtMs) {
        ContentValues values = new ContentValues();
        values.put("mode", cleanMode(mode));
        values.put("adapter_address", clean(adapterAddress));
        values.put("adapter_name", clean(adapterName));
        values.put("started_at_ms", startedAtMs);
        values.put("status", ObdLocalStore.STATUS_ACTIVE);
        values.put("created_at_ms", System.currentTimeMillis());
        return helper.getWritableDatabase()
                .insertOrThrow(VoltTrackerDb.TABLE_SESSIONS, null, values);
    }

    void finishSession(long sessionId, String status) {
        finishSession(sessionId, status, System.currentTimeMillis(), null);
    }

    void finishSession(long sessionId, String status, long endedAtMs, String supportedPids) {
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

    void finalizeSession(
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

    long recordTelemetry(long sessionId, JSONObject sample) {
        long capturedAtMs =
                sample == null
                        ? System.currentTimeMillis()
                        : sample.optLong("updatedAt", System.currentTimeMillis());
        return recordTelemetry(sessionId, sample, capturedAtMs);
    }

    long recordTelemetry(long sessionId, JSONObject sample, long capturedAtMs) {
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

    long recordPidObservation(long sessionId, JSONObject observation) {
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

    long recordPidObservation(long sessionId, JSONObject observation, long observedAtMs) {
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

    long recordPidObservation(
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

    long recordDiagnosticCode(long sessionId, JSONObject diagnosticCode) {
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

    long recordLocationSample(long sessionId, JSONObject sample) {
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

    long recordLocationSample(long sessionId, JSONObject sample, long capturedAtMs) {
        JSONObject safeSample = sample == null ? new JSONObject() : sample;
        // Reject samples missing lat/lng — without this, ObdStoreSnapshots.locationSampleValues
        // would default both to 0.0, leaving a fake "Null Island" row off the African coast that
        // would later show up on the map and pollute distance computations. Today's caller
        // (LocationManagerTracker) always provides coordinates, but the early-return makes the
        // helper safe to call from any future producer.
        if (!safeSample.has("latitude")
                || safeSample.isNull("latitude")
                || !safeSample.has("longitude")
                || safeSample.isNull("longitude")) {
            return -1L;
        }
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

    long recordLocationSample(
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

    long recordStatus(
            long sessionId, String state, String detail, boolean blocked, JSONObject payload) {
        return recordEvent(sessionId, "status", state, detail, blocked, payload);
    }

    long recordEvent(
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

    // ---- adapter history -----------------------------------------------------------

    void recordAdapterSummary(
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

    void close() {
        statementCache.close();
    }
}
