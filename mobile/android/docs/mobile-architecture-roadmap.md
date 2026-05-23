# Volt Tracker Android Mobile Architecture Roadmap

## Product Direction

The Android app should become the primary car companion and field logger. The phone UI should answer three questions quickly:

1. Is the car connected and logging correctly?
2. What state is the car in right now?
3. What did we learn from the current and previous sessions?

The UI should not mix real data, demo data, and future/placeholder data. Demo remains useful, but it should be an explicit sandbox.

## Current Progress

Last updated: 2026-05-20

Current Android database schema: v4

Completed:

- App-state bridge from Android to the WebView, with a single `VoltTrackerNative.setAppState` payload.
- Drive screen centered on connection, logging, GPS, database health, and source state.
- Diagnostics tab separated from the driver-facing Drive screen.
- Demo mode separated from real data states, with a clear stop path.
- Remembered adapter flow for reconnect/resume.
- Append-only `pid_observations` storage for command/response capture.
- GPS sample persistence in `location_samples`.
- Long-term schema tables: `vehicles`, `field_capabilities`, `trip_segments`, `charge_sessions`, `battery_snapshots`, `cell_snapshots`, and `exports`.
- VIN redaction for raw scan/log output.
- Initial typed parser layer for standard OBD values, adapter voltage, Volt SOC/capacity/odometer commands, sampled cell-voltage commands, and charging transition hints.
- Database status counts surfaced in Diagnostics.

In progress:

- Real-car validation of exact Volt PID response formats and units.
- Mapping parsed PID values into battery, cell, charge, vehicle-state, and capability tables.
- Turning stored GPS/PID observations into trip and charge-session records.
- Replacing remaining placeholder product views with database-backed empty, loading, and real-data states.

Remaining:

- Exportable diagnostic bundles with explicit privacy controls for VIN and location.
- Long-run background logging tests for reconnect, GPS, screen-off, and app-minimized behavior.
- Query-backed Trips, Charge, Map, and Insights screens.
- Confidence labels for every derived classifier so weak inference is visible.
- Migration tests and fixture-backed parser tests for every PID we decide to support.

## UI/UX Model

### Drive

Primary purpose: live operation.

- Show connection state, logging state, GPS state, and vehicle state.
- Show speed, 12V voltage, RPM/engine state, throttle/load, and the most recent real Volt fields as they become available.
- Collapse adapter controls into one compact connection row.
- Use one primary action at a time: Connect, Stop, Resume, or Retry.
- Move Scan, Demo, Permissions, Refresh, and Export into secondary controls.

### Trips

Primary purpose: real stored drive sessions.

- Back this screen from trip/session tables only.
- Start with route, distance, duration, speed range, EV/gas state transitions, and GPS confidence.
- Show empty states based on missing real data, not generic future content.

### Charge

Primary purpose: plugged-in and charging sessions.

- Detect plugged/charging state from Volt PIDs and observed transition patterns.
- Store charge sessions separately from trips.
- Show charger type, start/end SOC, estimated kWh, charge rate, duration, and interruptions when real data exists.

### Insights

Primary purpose: derived, queryable facts.

- Use materialized or computed summaries from the database.
- Never use demo records in real insight calculations.
- Show confidence labels when data is incomplete.

### Diagnostics

Primary purpose: field debugging.

- Raw frames, scan results, PID response summaries, adapter history, DB status, permissions, export tools, and log collection live here.
- This tab should be powerful, but not the first thing a driver sees.

## Backend/UI Contract

The Android layer should publish one clear app-state payload to the WebView. The UI should render that state rather than infer too much from scattered telemetry objects.

Suggested shape:

```json
{
  "app": {
    "version": "0.1.0",
    "schemaVersion": 4
  },
  "permissions": {
    "bluetooth": true,
    "location": true,
    "notifications": true
  },
  "adapter": {
    "name": "OBDLink MX+ 54242",
    "address": "redacted",
    "connected": true,
    "lastSeenAtMs": 1770000000000
  },
  "session": {
    "id": 12,
    "mode": "obd",
    "state": "logging",
    "startedAtMs": 1770000000000,
    "sampleCount": 540
  },
  "vehicle": {
    "state": "driving_ev",
    "confidence": "observed",
    "vinStored": false
  },
  "gps": {
    "state": "locked",
    "accuracyM": 8,
    "ageMs": 400
  },
  "latestTelemetry": {
    "speedKph": 48,
    "voltage": 14.1,
    "rpm": 0
  },
  "availableFields": {
    "standardObd": ["speedKph", "rpm", "coolantC", "loadPct", "throttlePct"],
    "volt": ["rawSoc", "displaySoc", "capacityAh"],
    "gps": ["latitude", "longitude", "accuracyM"]
  }
}
```

## Layering Rule

The Android code is organized in four layers. **Calls flow downward only** — a
higher layer may depend on a lower layer, never the reverse.

```text
UI / WebView         MainActivity, VoltBridge, dashboard/*       (Activity, JS bridge)
       ↓
Service              ObdService, ObdNotifications, PermissionGate (foreground lifecycle)
       ↓
Engine               ObdPollingEngine, SessionRecorder, ObdProtocol, ElmConnection,
                     ObdElmDecode, ObdProbes, location/* (Bluetooth IO, parsing, GPS)
       ↓
Data                 data/* (ObdLocalStore, VoltTrackerDb, ObdStore*) — SQLite only
```

Rules:

- `data/*` must not import anything outside `com.volttracker.obdpoc.data` and the
  Android SDK. It is the only layer allowed to call `SQLiteDatabase` /
  `SQLiteOpenHelper` / `ContentValues`.
- Engine code may use `data/*` but must not import `MainActivity`, the WebView,
  or the JS bridge.
- The service layer orchestrates engine work and publishes status broadcasts; it
  must not touch the WebView directly.
- `MainActivity` and `VoltBridge` may call into the service via Intents and into
  `data/*` for read-only queries (e.g. storage summary). They must not call
  `getWritableDatabase()` directly — go through an `ObdLocalStore` method.

When in doubt: if a change would require a `data/*` class to import from the
engine or above, the abstraction is in the wrong file. Extract a small
read-only DTO into `data/*` instead.

## Database Strategy

The database should be the source of truth for real app history. JSONL field logs are still useful as black-box debug artifacts, but product screens should read from SQLite.

### Principles

- Store raw observations append-only.
- Store normalized derived facts in queryable tables.
- Keep demo data out of real tables or mark it with `source = demo` and exclude it by default.
- Version every schema change with real migrations.
- Preserve unknown PID responses instead of throwing them away.
- Treat VIN and precise location as private data by default.

### Tables

Base tables:

- `obd_sessions`
- `telemetry_samples`
- `status_events`
- `adapter_history`

Roadmap tables now present in schema v4:

- `vehicles`: one row per car, with VIN redacted or hashed by default.
- `pid_observations`: every command/response pair with command, header, raw response, parsed value, parser version, and error state.
- `location_samples`: GPS samples linked to session/sample time, with accuracy and provider.
- `trip_segments`: inferred drives with start/end time, route availability, distance, max speed, EV/gas classification, and confidence.
- `charge_sessions`: plugged/charging sessions with charger type guess, start/end SOC, voltage/current/power, and interruption markers.
- `battery_snapshots`: pack SOC, capacity Ah, pack voltage/current/power, battery temp, and derived health fields.
- `cell_snapshots`: per-cell readings linked to a battery snapshot.
- `field_capabilities`: what PIDs responded for this adapter/car/protocol/header.
- `exports`: records of exported diagnostic bundles.

Remaining database work:

- Populate `vehicles` from verified identity signals without storing a full VIN by default.
- Populate `field_capabilities` from scan results and parser confidence.
- Materialize `trip_segments` and `charge_sessions` from observation windows.
- Persist parsed pack and cell values into `battery_snapshots` and `cell_snapshots`.
- Track diagnostic exports in `exports` once the export bundle format is defined.

### Raw-to-Useful Pipeline

1. Capture raw command responses into `pid_observations`.
2. Parse known values into typed columns and JSON payloads.
3. Classify session state: parked, plugged, charging, ready, driving EV, driving gas, unknown.
4. Build trips and charge sessions from time windows.
5. Render UI from typed tables and a compact app-state payload.

### Privacy Defaults

- VIN: do not store full VIN by default. Store redacted response metadata and optionally a salted hash later.
- Location: store locally for maps/trips, but make export explicit.
- Raw logs: private app storage only unless user exports.

## Implementation Order

Done:

1. Build the app-state bridge and make the UI render from it.
2. Simplify Drive around real connection/logging state.
3. Move raw/debug features into Diagnostics.
4. Add `pid_observations` and `location_samples`.
5. Add schema v4 roadmap tables.
6. Add the first parser layer for standard OBD, selected Volt commands, cell-voltage samples, and charge-transition hints.

Next:

1. Validate parser output against the newest field logs from the car.
2. Save parsed battery, cell, capability, and vehicle-state facts into normalized tables.
3. Build trip and charge-session materializers from stored PID and GPS samples.
4. Wire Trips, Charge, Map, and Insights to database queries.
5. Add export/privacy controls and migration/parser tests.

## Near-Term Definition Of Done

Done:

- Phone Drive screen clearly shows whether data is real or demo.
- One-tap connect/resume is available with the remembered adapter.
- GPS lock and DB write status are visible without opening raw logs.
- Scan mode stores PID observations in a queryable table.
- No product screen should depend on fake/demo data unless Demo is explicitly active.

Still open:

- Charge/driving state classifiers need to be stored with confidence after real-log validation.
- Trips, Charge, Map, and Insights still need full database-backed product states.
- Parser and migration tests need to be added around the Android database layer.
