# VoltTracker data model

One-page reference for the on-device SQLite schema. Source of truth is the DDL
in
[`app/src/main/kotlin/com/volttracker/obdpoc/data/VoltTrackerSchema.kt`](../app/src/main/kotlin/com/volttracker/obdpoc/data/VoltTrackerSchema.kt)
(the `CREATE TABLE` / `CREATE INDEX` statements) plus the migration orchestration
in `VoltTrackerDb`. If this doc and the DDL disagree, the DDL wins — update this
doc to match.

## Table overview

Tables fall into two buckets:

- **Raw** — written directly from capture (sessions, telemetry, observations,
  GPS, DTCs, adapter history). These are the system of record.
- **Derived / cache** — computed from raw rows and safe to rebuild. Dropping and
  recomputing them changes performance, not correctness.

| Table | Kind | What it holds |
|-------|------|----------------|
| `sessions` | raw | One row per capture session (mode, adapter, start/end, status, sample count). |
| `telemetry` | raw | Per-sample decoded telemetry (speed, rpm, SOC, power, pack V/A, GPS, `json`). |
| `events` | raw | Session lifecycle / state-change events. |
| `adapter_history` | raw | Per-adapter rollup keyed by `adapter_key` (seen counts, last session/mode/status). |
| `pid_observations` | raw | Raw OBD PID request/response observations (command, header, value, `json`). |
| `location_samples` | raw | Raw GPS fixes (lat/lng/alt/speed/bearing/accuracy, `json`). |
| `diagnostic_codes` | raw | Deduped DTCs keyed by `(module_key, dtc, status)` with seen counts. |
| `vehicles` | raw | Distinct vehicles keyed by `vehicle_key` (make/model/year, redacted+hashed VIN). |
| `field_capabilities` | raw | Per-(adapter/vehicle) PID support map (which commands respond). |
| `trip_segments` | **derived/cache** | Detected trips (distance, speeds, energy, classification). |
| `charge_sessions` | **derived/cache** | Detected charge sessions (SOC delta, V/A/kW, energy, interrupted). |
| `battery_snapshots` | derived/cache | Point-in-time battery state (SOC, SOH, pack V/A/kW, temp, odometer). |
| `cell_snapshots` | derived/cache | Per-cell detail attached to a battery snapshot. |
| `session_trip_rollups` | **derived/cache** | Per-session scalar trip rollup (distance/duration/max speed/route flag). |
| `exports` | raw | Export job records (type, status, file, range, manifest). |

## Raw base tables (schema v1)

- **`sessions`** — PK `_id`. Columns include `mode`, `adapter_address`,
  `adapter_name`, `started_at_ms`, `ended_at_ms`, `status`, `supported_pids`,
  `sample_count`, `last_event_at_ms`, `created_at_ms`. Parent of nearly
  everything; most child FKs point here.
- **`telemetry`** — PK `_id`. The decoded per-sample stream: `speed_kph`, `rpm`,
  `coolant_c`, `load_pct`, `throttle_pct`, `voltage`, `soc`, `battery_temp`,
  `power_kw`, `pack_voltage`, `pack_current_a`, GPS fields, `sample_number`,
  `session_ms`, plus `raw` and `json` (the latter `NOT NULL`).
  FK `session_id → sessions(_id) ON DELETE CASCADE`.
- **`events`** — PK `_id`. `kind`, `state`, `detail`, `blocked`, `payload`.
  FK `session_id → sessions(_id) ON DELETE SET NULL` (events survive their
  session being deleted, with the link nulled).
- **`adapter_history`** — PK is `adapter_key` (TEXT). Lifetime per-adapter
  counters; no FK (it references the last session by id without a constraint).

## Observation + diagnostic tables

- **`pid_observations`** — PK `_id`. Raw PID exchanges (`command`, `header`,
  `pid`, `value_text`, `value_numeric`, `unit`, `raw_request`, `raw_response`,
  `json`). FK `session_id → sessions(_id) ON DELETE CASCADE`.
- **`location_samples`** — PK `_id`. Raw GPS fixes; `latitude`/`longitude`
  `NOT NULL`. FK `session_id → sessions(_id) ON DELETE CASCADE`.
- **`diagnostic_codes`** — PK `_id`, with `UNIQUE(module_key, dtc, status)` —
  one row per distinct code/state, `seen_count` incremented on re-observation.
  No FK; `last_session_id` is a loose reference.

## Roadmap tables (vehicles, trips, charging, battery, exports)

- **`vehicles`** — PK `_id`, `vehicle_key` `UNIQUE`. VIN stored only as
  `vin_redacted` + `vin_hash` (privacy). Referenced by capability/trip/charge/
  battery/export rows.
- **`field_capabilities`** — PK `_id`. PID support per adapter/vehicle.
  FK `vehicle_id → vehicles(_id) ON DELETE SET NULL`.
- **`trip_segments`** (derived) — PK `_id`. Detected trips with `distance_m`,
  `max_speed_kph`, `avg_speed_kph`, `energy_kwh`, `classification`, `confidence`,
  `summary_json`. FKs:
  `session_id → sessions(_id) ON DELETE SET NULL`,
  `vehicle_id → vehicles(_id) ON DELETE SET NULL`,
  `start_sample_id`/`end_sample_id → telemetry(_id) ON DELETE SET NULL`.
- **`charge_sessions`** (derived) — PK `_id`. Detected charges with
  `start_soc`/`end_soc`, `voltage`, `current_a`, `power_kw`, `energy_kwh`,
  `interrupted`, `confidence`, `summary_json`. Same four FKs as `trip_segments`
  (session, vehicle, start/end telemetry sample), all `ON DELETE SET NULL`.
- **`battery_snapshots`** (derived) — PK `_id`. `soc`, `capacity_ah`, `soh_pct`,
  pack V/A/kW, `battery_temp_c`, `odometer_km`, `json`. FKs
  `session_id → sessions(_id) ON DELETE SET NULL`,
  `vehicle_id → vehicles(_id) ON DELETE SET NULL`.
- **`cell_snapshots`** (derived) — PK `_id`. Per-cell `voltage`,
  `temperature_c`, `resistance_mohm`, `balance_mv`.
  FK `battery_snapshot_id → battery_snapshots(_id) ON DELETE CASCADE` — cells die
  with their parent snapshot.
- **`exports`** — PK `_id`. Export jobs (`export_type`, `status`, `file_name`,
  `mime_type`, `bytes`, range, `include_precise_location`, `include_raw_logs`,
  `manifest_json`). FKs `session_id` / `vehicle_id` both `ON DELETE SET NULL`.

## Per-session trip rollup cache (schema v9)

- **`session_trip_rollups`** (derived/cache) — PK is `session_id`. Stores the
  scalars a Trips/Insights read would otherwise recompute by walking every
  session's GPS track on each load: `counted`, `distance_m`, `duration_ms`,
  `max_speed_kph`, `has_route`, `started_at_ms`. `counted = 1` when the session
  produced a real trip (useful telemetry) and `0` for a failed connection, so
  Insights can sum trips while the storage summary still sums distance over all
  sessions. Lazily populated on read in `ObdStoreTrips` for closed sessions; the
  active session is always computed live.
  FK `session_id → sessions(_id) ON DELETE CASCADE`.

## Foreign-key delete behavior at a glance

- **CASCADE** (child removed with parent): `telemetry`, `pid_observations`,
  `location_samples`, `session_trip_rollups` (all → `sessions`); `cell_snapshots`
  → `battery_snapshots`.
- **SET NULL** (child survives, link nulled): `events` → `sessions`;
  `field_capabilities` → `vehicles`; and on `trip_segments`, `charge_sessions`,
  `battery_snapshots`, `exports`, every FK (session / vehicle / telemetry sample)
  is `ON DELETE SET NULL`.

## Indexes

Each table carries time-ordered indexes for its dominant read (e.g.
`idx_telemetry_session_time` on `(session_id, captured_at_ms DESC)`), plus
lookup indexes for the deduped tables (`idx_diagnostic_codes_lookup`,
`idx_field_capabilities_lookup`). Schema v7 added **time-only** prune indexes
(`idx_telemetry_captured_at`, `idx_location_samples_captured_at`,
`idx_events_occurred_at`, `idx_pid_observations_observed_at`) so the daily raw-
data prune in `ObdStoreMaintenance.pruneRawDataOlderThan` doesn't full-scan —
the composite `(session_id, …)` indexes can't help a query that doesn't
constrain `session_id`.

## Test contract: dashboard handshake log string

Not a table, but a coupling that lives next to this data layer and is easy to
break by accident: the emulator startup smoke
([`scripts/emulator-smoke.sh`](../scripts/emulator-smoke.sh)) proves the
dashboard JS came alive by grepping logcat for the **exact** line:

> `dashboard handshake received`

emitted by `MainActivity.onDashboardReady`. **This string is a TEST CONTRACT.**
If you rename or reword that log, you must update the `grep` in
`scripts/emulator-smoke.sh` (and the workflow
`.github/workflows/android-emulator-smoke.yml`) in the same change, or the smoke
goes permanently green while testing nothing.
