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
 * adapter-history upserts, and VIN-derived vehicle identity. Split out of {@link ObdLocalStore} so
 * the facade is no longer one class owning both the wide write surface and every read projection;
 * {@code ObdLocalStore} now delegates its {@code ObdSessionStore} methods here. Owns the prepared-
 * statement cache used by the hot telemetry-insert path.
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

    // ---- vehicle identity (VIN) ----------------------------------------------------

    /**
     * Upserts a vehicle row keyed by the SHA-256 hash of the VIN. We deliberately do not store the
     * raw VIN — only {@code vin_redacted} (last 4 chars, the most useful for "is this the same
     * car?" without being PII) and the hash for stable lookup across sessions. {@code make} is
     * derived from the WMI (world manufacturer identifier, first 3 chars of the VIN), {@code
     * model_year} from position 10 per ISO-3779.
     *
     * <p>Returns the vehicle row id, or 0 if the VIN was rejected (wrong length, unrecognized
     * format). Idempotent on the {@code vin_hash} unique key — repeated calls update {@code
     * last_seen_ms} only.
     */
    long upsertVehicleFromVin(String vin) {
        if (vin == null || vin.length() != 17) {
            return 0L;
        }
        long now = System.currentTimeMillis();
        String hash = sha256Hex(vin);
        String last4 = vin.substring(13);
        String wmi = vin.substring(0, 3);
        String make = guessMakeFromWmi(wmi);
        Integer year = decodeModelYear(vin.charAt(9));
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            try (android.database.Cursor cursor =
                    db.rawQuery(
                            "SELECT _id FROM "
                                    + VoltTrackerDb.TABLE_VEHICLES
                                    + " WHERE vehicle_key = ?",
                            new String[] {hash})) {
                if (cursor.moveToFirst()) {
                    long existingId = cursor.getLong(0);
                    ContentValues update = new ContentValues();
                    update.put("last_seen_ms", now);
                    update.put("updated_at_ms", now);
                    db.update(
                            VoltTrackerDb.TABLE_VEHICLES,
                            update,
                            "_id = ?",
                            new String[] {String.valueOf(existingId)});
                    db.setTransactionSuccessful();
                    return existingId;
                }
            }
            ContentValues values = new ContentValues();
            values.put("vehicle_key", hash);
            values.put("vin_redacted", last4);
            values.put("vin_hash", hash);
            values.put("vin_source", "obd_0902");
            if (make != null) {
                values.put("make", make);
                values.put("display_name", make);
            }
            if (year != null) {
                values.put("model_year", year);
            }
            values.put("first_seen_ms", now);
            values.put("last_seen_ms", now);
            values.put("created_at_ms", now);
            values.put("updated_at_ms", now);
            long id = db.insertOrThrow(VoltTrackerDb.TABLE_VEHICLES, null, values);
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    private static String sha256Hex(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            // SHA-256 is mandatory on every Android version we support; falling back to the
            // raw value would defeat the redaction so we deliberately let this fail loudly.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /**
     * Maps a 3-char WMI to a coarse manufacturer name. We only carry entries the app is likely to
     * actually encounter — primarily GM's Chevy Volt prefixes — and fall back to {@code null} for
     * anything else so the column stays unset rather than wrong.
     */
    private static String guessMakeFromWmi(String wmi) {
        if (wmi == null || wmi.length() < 3) {
            return null;
        }
        String upper = wmi.toUpperCase(java.util.Locale.US);
        if (upper.startsWith("1G1")
                || upper.startsWith("1G6")
                || upper.startsWith("1GC")
                || upper.startsWith("1GT")
                || upper.startsWith("2G1")
                || upper.startsWith("3G1")) {
            return "Chevrolet";
        }
        if (upper.startsWith("1FT") || upper.startsWith("1FA") || upper.startsWith("3FA")) {
            return "Ford";
        }
        if (upper.startsWith("1HG") || upper.startsWith("2HG") || upper.startsWith("JHM")) {
            return "Honda";
        }
        if (upper.startsWith("4T1") || upper.startsWith("JT2") || upper.startsWith("5TD")) {
            return "Toyota";
        }
        return null;
    }

    /**
     * ISO-3779 model-year code in VIN position 10. Returns null for the ambiguous {@code
     * I/O/Q/U/Z/0} positions or unsupported codes. The 30-year cycle wrapped in 2010 (the same code
     * maps to 1980 and 2010); we resolve it by snapping into the current 30-year window centred on
     * today.
     */
    private static Integer decodeModelYear(char code) {
        String alphabet = "ABCDEFGHJKLMNPRSTVWXY123456789";
        int index = alphabet.indexOf(Character.toUpperCase(code));
        if (index < 0) {
            return null;
        }
        int baseYear = 1980 + index; // A=1980, B=1981 ...
        int currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        // Snap forward by 30 years until the candidate is within 30 years of today.
        while (baseYear + 30 <= currentYear + 1) {
            baseYear += 30;
        }
        return baseYear;
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
