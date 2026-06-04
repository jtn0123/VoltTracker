package com.volttracker.obdpoc.data;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class StorageSummaryRecord {
    public final String database;
    public final long databaseBytes;
    public final long sessionCount;
    public final long rawTelemetryCount;
    public final long sampleCount;
    public final long emptyTelemetryCount;
    public final long eventCount;
    public final long adapterCount;
    public final long pidObservationCount;
    public final long diagnosticCodeCount;
    public final Map<String, Long> diagnosticCodeStatusCounts;
    public final long locationSampleCount;
    public final long vehicleCount;
    public final long fieldCapabilityCount;
    public final long tripSegmentCount;
    public final long chargeSessionCount;
    public final long batterySnapshotCount;
    public final long cellSnapshotCount;
    public final long exportCount;
    public final ObdSessionRecord latestSession;
    public final List<RecentSessionSummaryRecord> recentSessions;
    public final List<AdapterHistoryRecord> adapters;
    public final List<DiagnosticCodeReport> latestDiagnosticCodes;
    public final JSONObject latestReview;
    public final JSONObject latestRoute;
    public final JSONArray recentRoutes;
    public final JSONObject overview;
    public final JSONObject chargeSummary;
    public final JSONObject batterySummary;
    public final JSONObject latestVehicle;
    public final JSONArray enhancedCapabilities;

    StorageSummaryRecord(
            String database,
            long databaseBytes,
            long sessionCount,
            long rawTelemetryCount,
            long sampleCount,
            long emptyTelemetryCount,
            long eventCount,
            long adapterCount,
            long pidObservationCount,
            long diagnosticCodeCount,
            Map<String, Long> diagnosticCodeStatusCounts,
            long locationSampleCount,
            long vehicleCount,
            long fieldCapabilityCount,
            long tripSegmentCount,
            long chargeSessionCount,
            long batterySnapshotCount,
            long cellSnapshotCount,
            long exportCount,
            ObdSessionRecord latestSession,
            List<RecentSessionSummaryRecord> recentSessions,
            List<AdapterHistoryRecord> adapters,
            List<DiagnosticCodeReport> latestDiagnosticCodes,
            JSONObject latestReview,
            JSONObject latestRoute,
            JSONArray recentRoutes,
            JSONObject overview,
            JSONObject chargeSummary,
            JSONObject batterySummary,
            JSONObject latestVehicle,
            JSONArray enhancedCapabilities) {
        this.database = database == null ? "" : database;
        this.databaseBytes = databaseBytes;
        this.sessionCount = sessionCount;
        this.rawTelemetryCount = rawTelemetryCount;
        this.sampleCount = sampleCount;
        this.emptyTelemetryCount = emptyTelemetryCount;
        this.eventCount = eventCount;
        this.adapterCount = adapterCount;
        this.pidObservationCount = pidObservationCount;
        this.diagnosticCodeCount = diagnosticCodeCount;
        this.diagnosticCodeStatusCounts =
                diagnosticCodeStatusCounts == null
                        ? Collections.emptyMap()
                        : Map.copyOf(diagnosticCodeStatusCounts);
        this.locationSampleCount = locationSampleCount;
        this.vehicleCount = vehicleCount;
        this.fieldCapabilityCount = fieldCapabilityCount;
        this.tripSegmentCount = tripSegmentCount;
        this.chargeSessionCount = chargeSessionCount;
        this.batterySnapshotCount = batterySnapshotCount;
        this.cellSnapshotCount = cellSnapshotCount;
        this.exportCount = exportCount;
        this.latestSession = latestSession;
        this.recentSessions =
                recentSessions == null ? Collections.emptyList() : List.copyOf(recentSessions);
        this.adapters = adapters == null ? Collections.emptyList() : List.copyOf(adapters);
        this.latestDiagnosticCodes =
                latestDiagnosticCodes == null
                        ? Collections.emptyList()
                        : List.copyOf(latestDiagnosticCodes);
        this.latestReview = copy(latestReview);
        this.latestRoute = copy(latestRoute);
        this.recentRoutes = copy(recentRoutes);
        this.overview = copy(overview);
        this.chargeSummary = copy(chargeSummary);
        this.batterySummary = copy(batterySummary);
        this.latestVehicle = copy(latestVehicle);
        this.enhancedCapabilities = copy(enhancedCapabilities);
    }

    private static JSONObject copy(JSONObject source) {
        if (source == null) {
            return new JSONObject();
        }
        try {
            return new JSONObject(source.toString());
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private static JSONArray copy(JSONArray source) {
        if (source == null) {
            return new JSONArray();
        }
        try {
            return new JSONArray(source.toString());
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }
}
