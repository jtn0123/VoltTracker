package com.volttracker.obdpoc.data;

import com.volttracker.obdpoc.materialize.LocationSample;
import com.volttracker.obdpoc.materialize.MaterializerData;
import com.volttracker.obdpoc.materialize.PidObservation;
import com.volttracker.obdpoc.materialize.TelemetrySample;
import java.io.File;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Read-side of the on-device SQLite store. New callers that only need to read (typed records or
 * JSON projections) should depend on this narrower interface rather than the full {@link
 * ObdLocalStore} facade.
 *
 * <p>Both this and {@link ObdSessionStore} are implemented by {@link ObdLocalStore}; existing
 * callers can keep depending on the concrete facade. The split is additive; nothing was moved out
 * of {@link ObdLocalStore}.
 *
 * <p>Extends {@link MaterializerData} because the three {@code read*Samples} methods the
 * materializers consume are pure reads and belong on the query side.
 */
public interface ObdQueryStore extends MaterializerData {

    // ---- typed-record reads --------------------------------------------------------

    ObdSessionRecord getSession(long sessionId);

    List<ObdSessionRecord> getRecentSessions(int limit);

    List<TelemetrySampleRecord> getRecentTelemetry(long sessionId, int limit);

    List<StatusEventRecord> getRecentEvents(long sessionId, int limit);

    List<AdapterHistoryRecord> getAdapterHistory(int limit);

    // ---- JSON projections ----------------------------------------------------------

    StorageSummaryRecord getStorageSummaryRecord();

    JSONArray getRecentSessionsJson(int limit);

    JSONArray getAdapterHistoryJson(int limit);

    JSONArray getTripsJson(int limit);

    JSONObject getInsightsJson();

    // ---- materializer reads (from MaterializerData) --------------------------------

    @Override
    List<LocationSample> readLocationSamples(long sessionId);

    @Override
    List<PidObservation> readPidObservations(long sessionId);

    @Override
    List<TelemetrySample> readTelemetrySamples(long sessionId);

    // ---- DB file location (read-only metadata) -------------------------------------

    File getDatabaseFile();
}
