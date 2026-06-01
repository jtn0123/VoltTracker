package com.volttracker.obdpoc.data;

import android.content.Context;
import com.volttracker.obdpoc.materialize.ChargeSession;
import com.volttracker.obdpoc.materialize.ChargeSessionMaterializer;
import com.volttracker.obdpoc.materialize.LocationSample;
import com.volttracker.obdpoc.materialize.MaterializerData;
import com.volttracker.obdpoc.materialize.MaterializerInput;
import com.volttracker.obdpoc.materialize.PidObservation;
import com.volttracker.obdpoc.materialize.TelemetrySample;
import com.volttracker.obdpoc.materialize.Trip;
import com.volttracker.obdpoc.materialize.TripMaterializer;
import java.io.Closeable;
import java.io.File;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * On-device SQLite store for OBD sessions, telemetry, GPS, events and adapter history.
 *
 * <p>This class is the stable public facade and delegates: the write surface (session lifecycle,
 * telemetry/PID/location/event inserts, adapter history, VIN-derived vehicle identity) to {@link
 * ObdStoreWriter}; read-side JSON projections to {@link ObdStoreReports} and {@link ObdStoreTrips};
 * materialization to {@link ObdStoreMaterialize}; maintenance to {@link ObdStoreMaintenance}; and
 * stateless helpers live in {@link ObdStoreSupport}.
 */
public class ObdLocalStore implements Closeable, MaterializerData, ObdSessionStore, ObdQueryStore {
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
    private final ObdStoreWriter writer;
    private final ObdStoreVehicles vehicles;
    private final ObdStoreMaterialize materialize;

    public ObdLocalStore(Context context) {
        Context appContext = context.getApplicationContext();
        helper = new VoltTrackerDb(appContext);
        trips = new ObdStoreTrips(helper);
        reports = new ObdStoreReports(helper, trips);
        maintenance = new ObdStoreMaintenance(appContext, helper);
        writer = new ObdStoreWriter(helper, new ObdStoreSnapshots());
        vehicles = new ObdStoreVehicles(helper);
        materialize = new ObdStoreMaterialize(helper);
    }

    // ---- session lifecycle + writes (delegated to ObdStoreWriter) ------------------

    public long startSession(String mode, String adapterAddress, String adapterName) {
        return writer.startSession(mode, adapterAddress, adapterName);
    }

    public long startSession(
            String mode, String adapterAddress, String adapterName, long startedAtMs) {
        return writer.startSession(mode, adapterAddress, adapterName, startedAtMs);
    }

    public void finishSession(long sessionId, String status) {
        writer.finishSession(sessionId, status);
    }

    public void finishSession(long sessionId, String status, long endedAtMs, String supportedPids) {
        writer.finishSession(sessionId, status, endedAtMs, supportedPids);
    }

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
        writer.finalizeSession(
                sessionId,
                status,
                endedAtMs,
                supportedPids,
                address,
                adapterName,
                mode,
                sampleCount,
                lastEventDetail);
    }

    public long recordTelemetry(long sessionId, JSONObject sample) {
        return writer.recordTelemetry(sessionId, sample);
    }

    public long recordTelemetry(long sessionId, JSONObject sample, long capturedAtMs) {
        return writer.recordTelemetry(sessionId, sample, capturedAtMs);
    }

    public long recordPidObservation(long sessionId, JSONObject observation) {
        return writer.recordPidObservation(sessionId, observation);
    }

    public long recordPidObservation(long sessionId, JSONObject observation, long observedAtMs) {
        return writer.recordPidObservation(sessionId, observation, observedAtMs);
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
        return writer.recordPidObservation(
                sessionId,
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
    }

    public long recordDiagnosticCode(long sessionId, JSONObject diagnosticCode) {
        return writer.recordDiagnosticCode(sessionId, diagnosticCode);
    }

    public long recordLocationSample(long sessionId, JSONObject sample) {
        return writer.recordLocationSample(sessionId, sample);
    }

    public long recordLocationSample(long sessionId, JSONObject sample, long capturedAtMs) {
        return writer.recordLocationSample(sessionId, sample, capturedAtMs);
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
        return writer.recordLocationSample(
                sessionId,
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
    }

    public long recordStatus(
            long sessionId, String state, String detail, boolean blocked, JSONObject payload) {
        return writer.recordStatus(sessionId, state, detail, blocked, payload);
    }

    public long recordEvent(
            long sessionId,
            String kind,
            String state,
            String detail,
            boolean blocked,
            JSONObject payload) {
        return writer.recordEvent(sessionId, kind, state, detail, blocked, payload);
    }

    public long upsertVehicleFromVin(String vin) {
        return vehicles.upsertVehicleFromVin(vin);
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
        writer.recordAdapterSummary(
                address, name, mode, sessionId, status, samples, supportedPids, lastEventDetail);
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

    public StorageSummaryRecord getStorageSummaryRecord() {
        return reports.storageSummaryRecord(getDatabaseFile());
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

    /**
     * Runs the trip and charge-session materializers for a just-closed session and persists their
     * output — the whole orchestration lives in the data layer, which already owns the {@code
     * materialize} types. Engine callers (e.g. SessionRecorder) invoke this by name rather than
     * reaching across into the materialize package themselves. Exceptions propagate so the caller
     * can record a materialize_failure; each persist call is individually transactional.
     */
    public void materializeSession(long sessionId, long startedAtMs, long closedAtMs) {
        MaterializerInput input = new MaterializerInput(sessionId, startedAtMs, closedAtMs);
        persistTrips(sessionId, TripMaterializer.materialize(input, this));
        persistChargeSessions(sessionId, ChargeSessionMaterializer.materialize(input, this));
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
        writer.close();
        helper.close();
    }
}
