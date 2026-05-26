package com.volttracker.obdpoc.data;

import com.volttracker.obdpoc.materialize.ChargeSession;
import com.volttracker.obdpoc.materialize.Trip;
import java.io.Closeable;
import java.util.List;
import org.json.JSONObject;

/**
 * Write- and lifecycle-side of the on-device SQLite store. New callers that only need to write (or
 * only need to drive the session lifecycle) should depend on this narrower interface rather than
 * the full {@link ObdLocalStore} facade — it shrinks the surface they have to understand and makes
 * JVM-side fakes practical to write.
 *
 * <p>Both this and {@link ObdQueryStore} are implemented by {@link ObdLocalStore}; existing callers
 * can keep depending on the concrete facade. The split is additive; nothing was moved out of {@link
 * ObdLocalStore}.
 *
 * <p>Maintenance methods ({@link #clearAllData}, {@link #checkpoint}, {@link
 * #pruneRawDataOlderThan}) belong on the write side because they mutate state.
 */
public interface ObdSessionStore extends Closeable {

    // ---- session lifecycle ---------------------------------------------------------

    long startSession(String mode, String adapterAddress, String adapterName);

    long startSession(String mode, String adapterAddress, String adapterName, long startedAtMs);

    void finishSession(long sessionId, String status);

    void finishSession(long sessionId, String status, long endedAtMs, String supportedPids);

    void finalizeSession(
            long sessionId,
            String status,
            long endedAtMs,
            String supportedPids,
            String address,
            String adapterName,
            String mode,
            int sampleCount,
            String lastEventDetail);

    // ---- per-sample writes ---------------------------------------------------------

    long recordTelemetry(long sessionId, JSONObject sample);

    long recordTelemetry(long sessionId, JSONObject sample, long capturedAtMs);

    long recordPidObservation(long sessionId, JSONObject observation);

    long recordPidObservation(long sessionId, JSONObject observation, long observedAtMs);

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
            String rawResponse);

    long recordDiagnosticCode(long sessionId, JSONObject diagnosticCode);

    long recordLocationSample(long sessionId, JSONObject sample);

    long recordLocationSample(long sessionId, JSONObject sample, long capturedAtMs);

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
            Long elapsedRealtimeNanos);

    long recordStatus(
            long sessionId, String state, String detail, boolean blocked, JSONObject payload);

    long recordEvent(
            long sessionId,
            String kind,
            String state,
            String detail,
            boolean blocked,
            JSONObject payload);

    long upsertVehicleFromVin(String vin);

    void recordAdapterSummary(
            String address,
            String name,
            String mode,
            long sessionId,
            String status,
            int samples,
            String supportedPids,
            String lastEventDetail);

    // ---- materializer outputs ------------------------------------------------------

    void persistTrips(long sessionId, List<Trip> trips);

    void persistChargeSessions(long sessionId, List<ChargeSession> sessions);

    // ---- maintenance ---------------------------------------------------------------

    void clearAllData();

    void checkpoint();

    int pruneRawDataOlderThan(int keepDays);

    @Override
    void close();
}
