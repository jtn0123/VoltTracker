package com.volttracker.obdpoc.data;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.volttracker.obdpoc.VehicleActivityThresholds;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Detects drive windows inside one longer OBD session using GPS stops and OBD activity. */
final class DriveWindowDetector {

    private static final long MAX_INACTIVE_MS = 5L * 60_000L;
    private static final long MIN_STOP_SPLIT_MS = 3L * 60_000L;
    private static final double STOP_SPEED_MPS = 2.5;
    private static final double MAX_STOP_DRIFT_METERS = 300.0;

    private DriveWindowDetector() {}

    static List<DriveWindow> windowsForSession(SQLiteDatabase db, ObdSessionRecord session) {
        if (db == null || session == null) {
            return Collections.emptyList();
        }
        DataBounds bounds = dataBounds(db, session.id);
        long fallbackStartMs = session.startedAtMs;
        long fallbackEndMs = session.endedAtMs > 0 ? session.endedAtMs : session.lastEventAtMs;
        if (bounds != null) {
            if (fallbackStartMs <= 0L
                    || fallbackStartMs > bounds.lastMs
                    || fallbackStartMs < bounds.firstMs) {
                fallbackStartMs = bounds.firstMs;
            }
            fallbackEndMs = bounds.lastMs;
        }
        if (fallbackEndMs <= fallbackStartMs) {
            return Collections.emptyList();
        }
        List<SplitSpan> spans = splitSpans(db, session.id);
        if (spans.isEmpty()) {
            return List.of(new DriveWindow(session.id, 0, fallbackStartMs, fallbackEndMs));
        }
        List<DriveWindow> windows = new ArrayList<>();
        long windowStartMs = fallbackStartMs;
        int index = 0;
        for (SplitSpan span : mergeSpans(spans)) {
            if (span.endMs <= windowStartMs) {
                continue;
            }
            if (span.startMs > windowStartMs) {
                windows.add(new DriveWindow(session.id, index++, windowStartMs, span.startMs));
            }
            windowStartMs = Math.max(windowStartMs, span.endMs + 1L);
        }
        if (windowStartMs < fallbackEndMs) {
            windows.add(new DriveWindow(session.id, index, windowStartMs, fallbackEndMs));
        }
        return windows;
    }

    static RouteKey parseRouteKey(String raw) {
        String clean = raw == null ? "" : raw.trim();
        if (clean.isEmpty()) {
            return null;
        }
        String[] parts = clean.split(":", -1);
        try {
            if (parts.length == 1) {
                return new RouteKey(Long.parseLong(parts[0]), null, null);
            }
            if (parts.length == 3) {
                return new RouteKey(
                        Long.parseLong(parts[0]),
                        Long.parseLong(parts[1]),
                        Long.parseLong(parts[2]));
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    private static List<SplitSpan> splitSpans(SQLiteDatabase db, long sessionId) {
        List<SplitSpan> spans = new ArrayList<>();
        spans.addAll(gpsStopSpans(db, sessionId));
        spans.addAll(inactiveTelemetrySpans(db, sessionId));
        return spans;
    }

    private static List<SplitSpan> gpsStopSpans(SQLiteDatabase db, long sessionId) {
        List<RouteSample> samples = readRouteSamples(db, sessionId);
        List<SplitSpan> spans = new ArrayList<>();
        if (samples.size() < 2) {
            return spans;
        }
        int runStart = -1;
        for (int i = 1; i < samples.size(); i++) {
            RouteSample previous = samples.get(i - 1);
            RouteSample sample = samples.get(i);
            double seconds = Math.max(1.0, (sample.atMs - previous.atMs) / 1000.0);
            boolean slow = haversineMeters(previous, sample) / seconds < STOP_SPEED_MPS;
            if (slow) {
                if (runStart < 0) {
                    runStart = i - 1;
                }
            } else if (runStart >= 0) {
                addStopSpan(spans, samples, runStart, i - 1);
                runStart = -1;
            }
        }
        if (runStart >= 0) {
            addStopSpan(spans, samples, runStart, samples.size() - 1);
        }
        return spans;
    }

    private static void addStopSpan(
            List<SplitSpan> spans, List<RouteSample> samples, int startIndex, int endIndex) {
        long startMs = samples.get(startIndex).atMs;
        long endMs = samples.get(endIndex).atMs;
        long stoppedAtMs = samples.get(Math.min(startIndex + 1, endIndex)).atMs;
        if (endMs - stoppedAtMs >= MIN_STOP_SPLIT_MS
                && pathMeters(samples, startIndex, endIndex) <= MAX_STOP_DRIFT_METERS) {
            spans.add(new SplitSpan(startMs, endMs));
        }
    }

    private static DataBounds dataBounds(SQLiteDatabase db, long sessionId) {
        DataBounds route =
                boundsFor(db, VoltTrackerDb.TABLE_LOCATION_SAMPLES, "session_id = ?", sessionId);
        if (route != null) {
            return route;
        }
        DataBounds geoTelemetry =
                boundsFor(
                        db,
                        VoltTrackerDb.TABLE_TELEMETRY,
                        "session_id = ? AND latitude IS NOT NULL AND longitude IS NOT NULL",
                        sessionId);
        if (geoTelemetry != null) {
            return geoTelemetry;
        }
        return boundsFor(db, VoltTrackerDb.TABLE_TELEMETRY, "session_id = ?", sessionId);
    }

    private static DataBounds boundsFor(
            SQLiteDatabase db, String table, String where, long sessionId) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT MIN(captured_at_ms), MAX(captured_at_ms) FROM "
                                + table
                                + " WHERE "
                                + where,
                        new String[] {String.valueOf(sessionId)})) {
            if (!cursor.moveToFirst() || cursor.isNull(0) || cursor.isNull(1)) {
                return null;
            }
            return new DataBounds(cursor.getLong(0), cursor.getLong(1));
        }
    }

    private static double pathMeters(List<RouteSample> samples, int startIndex, int endIndex) {
        double meters = 0.0;
        for (int i = startIndex + 1; i <= endIndex; i++) {
            meters += haversineMeters(samples.get(i - 1), samples.get(i));
        }
        return meters;
    }

    private static List<RouteSample> readRouteSamples(SQLiteDatabase db, long sessionId) {
        List<RouteSample> samples =
                readRouteSamplesFromTable(
                        db,
                        VoltTrackerDb.TABLE_LOCATION_SAMPLES,
                        "session_id = ?",
                        new String[] {String.valueOf(sessionId)});
        if (!samples.isEmpty()) {
            return samples;
        }
        return readRouteSamplesFromTable(
                db,
                VoltTrackerDb.TABLE_TELEMETRY,
                "session_id = ? AND latitude IS NOT NULL AND longitude IS NOT NULL",
                new String[] {String.valueOf(sessionId)});
    }

    private static List<RouteSample> readRouteSamplesFromTable(
            SQLiteDatabase db, String table, String where, String[] args) {
        List<RouteSample> samples = new ArrayList<>();
        try (Cursor cursor =
                db.query(
                        table,
                        new String[] {"captured_at_ms", "latitude", "longitude"},
                        where,
                        args,
                        null,
                        null,
                        "captured_at_ms ASC")) {
            while (cursor.moveToNext()) {
                samples.add(
                        new RouteSample(
                                cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")),
                                cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")),
                                cursor.getDouble(cursor.getColumnIndexOrThrow("longitude"))));
            }
        }
        return samples;
    }

    private static List<SplitSpan> inactiveTelemetrySpans(SQLiteDatabase db, long sessionId) {
        List<SplitSpan> spans = new ArrayList<>();
        long inactiveStartMs = -1L;
        long inactiveEndMs = -1L;
        try (Cursor cursor =
                db.query(
                        VoltTrackerDb.TABLE_TELEMETRY,
                        new String[] {
                            "captured_at_ms",
                            "speed_kph",
                            "rpm",
                            "voltage",
                            "power_kw",
                            "pack_current_a"
                        },
                        "session_id = ?",
                        new String[] {String.valueOf(sessionId)},
                        null,
                        null,
                        "captured_at_ms ASC")) {
            while (cursor.moveToNext()) {
                long atMs = cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms"));
                if (isActiveVehicleSample(cursor)) {
                    addInactiveSpan(spans, inactiveStartMs, inactiveEndMs);
                    inactiveStartMs = -1L;
                    inactiveEndMs = -1L;
                } else {
                    if (inactiveStartMs < 0L) {
                        inactiveStartMs = atMs;
                    }
                    inactiveEndMs = atMs;
                }
            }
        }
        addInactiveSpan(spans, inactiveStartMs, inactiveEndMs);
        return spans;
    }

    private static void addInactiveSpan(List<SplitSpan> spans, long startMs, long endMs) {
        if (startMs >= 0L && endMs >= startMs && endMs - startMs > MAX_INACTIVE_MS) {
            spans.add(new SplitSpan(startMs, endMs));
        }
    }

    private static List<SplitSpan> mergeSpans(List<SplitSpan> raw) {
        if (raw.isEmpty()) {
            return raw;
        }
        List<SplitSpan> sorted = new ArrayList<>(raw);
        Collections.sort(
                sorted,
                new Comparator<SplitSpan>() {
                    @Override
                    public int compare(SplitSpan left, SplitSpan right) {
                        return Long.compare(left.startMs, right.startMs);
                    }
                });
        List<SplitSpan> merged = new ArrayList<>();
        for (SplitSpan span : sorted) {
            if (merged.isEmpty()) {
                merged.add(span);
                continue;
            }
            SplitSpan last = merged.get(merged.size() - 1);
            if (span.startMs <= last.endMs) {
                merged.set(
                        merged.size() - 1,
                        new SplitSpan(last.startMs, Math.max(last.endMs, span.endMs)));
            } else {
                merged.add(span);
            }
        }
        return merged;
    }

    private static boolean isActiveVehicleSample(Cursor cursor) {
        Integer speedKph = nullableInt(cursor, "speed_kph");
        if (speedKph != null && speedKph > VehicleActivityThresholds.MOVING_SPEED_KPH) {
            return true;
        }
        Integer rpm = nullableInt(cursor, "rpm");
        if (rpm != null && rpm > VehicleActivityThresholds.ENGINE_READY_RPM) {
            return true;
        }
        Double voltage = nullableDouble(cursor, "voltage");
        if (voltage != null && voltage > VehicleActivityThresholds.READY_VOLTAGE) {
            return true;
        }
        Double powerKw = nullableDouble(cursor, "power_kw");
        if (powerKw != null && Math.abs(powerKw) > VehicleActivityThresholds.ACTIVE_POWER_KW) {
            return true;
        }
        Double packCurrentA = nullableDouble(cursor, "pack_current_a");
        return packCurrentA != null
                && Math.abs(packCurrentA) > VehicleActivityThresholds.ACTIVE_PACK_CURRENT_A;
    }

    private static Integer nullableInt(Cursor cursor, String column) {
        int idx = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(idx) ? null : cursor.getInt(idx);
    }

    private static Double nullableDouble(Cursor cursor, String column) {
        int idx = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(idx) ? null : cursor.getDouble(idx);
    }

    private static double haversineMeters(RouteSample a, RouteSample b) {
        double earthMeters = 6_371_000.0;
        double dLat = Math.toRadians(b.lat - a.lat);
        double dLng = Math.toRadians(b.lng - a.lng);
        double x =
                Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                        + Math.cos(Math.toRadians(a.lat))
                                * Math.cos(Math.toRadians(b.lat))
                                * Math.sin(dLng / 2.0)
                                * Math.sin(dLng / 2.0);
        return earthMeters * 2.0 * Math.atan2(Math.sqrt(x), Math.sqrt(1.0 - x));
    }

    static final class DriveWindow {
        final long sessionId;
        final int index;
        final long startedAtMs;
        final long endedAtMs;

        DriveWindow(long sessionId, int index, long startedAtMs, long endedAtMs) {
            this.sessionId = sessionId;
            this.index = index;
            this.startedAtMs = startedAtMs;
            this.endedAtMs = endedAtMs;
        }

        long durationMs() {
            return Math.max(0L, endedAtMs - startedAtMs);
        }

        String routeKey() {
            return String.format(Locale.US, "%d:%d:%d", sessionId, startedAtMs, endedAtMs);
        }
    }

    static final class RouteKey {
        final long sessionId;
        final Long startedAtMs;
        final Long endedAtMs;

        RouteKey(long sessionId, Long startedAtMs, Long endedAtMs) {
            this.sessionId = sessionId;
            this.startedAtMs = startedAtMs;
            this.endedAtMs = endedAtMs;
        }
    }

    private static final class SplitSpan {
        final long startMs;
        final long endMs;

        SplitSpan(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    private static final class RouteSample {
        final long atMs;
        final double lat;
        final double lng;

        RouteSample(long atMs, double lat, double lng) {
            this.atMs = atMs;
            this.lat = lat;
            this.lng = lng;
        }
    }

    private static final class DataBounds {
        final long firstMs;
        final long lastMs;

        DataBounds(long firstMs, long lastMs) {
            this.firstMs = firstMs;
            this.lastMs = lastMs;
        }
    }
}
