# VoltTracker data model

One-page reference for the on-device SQLite schema. Source of truth is the DDL
in
[`app/src/main/kotlin/com/volttracker/obdpoc/data/VoltTrackerSchema.kt`](../app/src/main/kotlin/com/volttracker/obdpoc/data/VoltTrackerSchema.kt)
(the `CREATE TABLE` / `CREATE INDEX` statements) plus the migration orchestration
in `VoltTrackerDb`. If this doc and the DDL disagree, the DDL wins — update this
doc to match.

Current `VoltTrackerDb.DATABASE_VERSION` is **13**. Migrations are append-only and
non-destructive; v11 → v12 added the `maintenance_log` table and the
`trip_segments.label` column, and v12 → v13 added the nullable
`maintenance_log.interval_km` (`REAL`) and `interval_months` (`INTEGER`) service-interval
columns (M1/C4) via guarded `ALTER TABLE ADD COLUMN` (existing rows keep them `NULL`).

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
| `status_events` | raw | Session lifecycle / state-change events. |
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
| `trip_list_cache` | **derived/cache** | One row per finalized trip caching the exact trip JSON the Trips list renders. |
| `exports` | raw | Export job records (type, status, file, range, manifest). |
| `maintenance_log` | raw | User-authored service-log entries (M5) + optional service interval (M1/C4); FK-free, survives a data clear. |

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
- **`status_events`** — PK `_id`. `occurred_at_ms` (`NOT NULL`), `kind`, `state`,
  `detail`, `blocked`, `payload`.
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
  `label` (nullable; added v12 — see the trip-label note below), `summary_json`.
  FKs:
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

## Maintenance log + trip labels + favorites (schema v12–v13)

- **`maintenance_log`** (raw) — PK `_id`. User-authored service entries (M5):
  `created_at_ms` `NOT NULL`, `odometer_km` (nullable — the user may not know it),
  `type` (free-text category label), and `note` (free-text detail). v13 adds the
  optional service interval (M1/C4): `interval_km` (`REAL`, nullable) and
  `interval_months` (`INTEGER`, nullable) — both `NULL` for a plain history entry,
  and the dashboard computes a per-entry "next due / overdue" line from them against
  the latest logged odometer and/or elapsed months. Carries the index
  `idx_maintenance_log_created` on `created_at_ms DESC` for the newest-first read the
  dashboard renders.
  **No foreign keys.** This is deliberate (per the `VoltTrackerSchema.createMaintenanceLog`
  KDoc): an entry is independent of any session or vehicle — the user may log
  service performed entirely off-app — so it has nothing to cascade from and
  **survives a full data clear** (`clearStoredData`) that drops sessions and their
  raw children. Read via the bridge as `getMaintenanceLog()`; rows are
  `{id, createdAtMs, odometerKm, type, note, intervalKm, intervalMonths}`.

- **Trip labels** are **not** stored only in the `trip_segments.label` column.
  The user-facing labels the dashboard renders are persisted as `status_events`
  rows of `kind = "trip_label"`, keyed by the canonical route key
  (`sessionId:startedAt:endedAt`), by `ObdTripLabels`. The design note from its
  KDoc: the dashboard's trips come from drive-window detection and are keyed by
  route key — **not 1:1 with materialized `trip_segments` rows** — so a
  route-key-keyed event store (the same mechanism as trip exclusions in
  `ObdTripExclusions`) is what the trip JSON joins against. The latest event per
  route key wins; an empty-label event clears it. A label survives trip
  re-materialization and is dropped with its session's raw rows when that
  session's `status_events` are removed (the event's `session_id` FK
  set-nulls/cascades like every other status event). The on-disk
  `trip_segments.label` column exists for materialized-trip provenance, but is not
  the store the rendered labels read from.

- **Trip favorites** (M4 favorites half) are stored the same way as labels —
  **no schema change**: `status_events` rows of `kind = "trip_favorite"`, keyed by
  the canonical route key, by `ObdTripFavorites`. The payload carries a `favorite`
  boolean; the latest event per route key wins, so un-favoriting writes a
  `favorite=false` event that supersedes an earlier favorite. The resolved flag is
  stamped onto each trip's JSON (`trip.favorite`) at read time in
  `ObdStoreTrips.applyLabels`, alongside the label.

## Foreign-key delete behavior at a glance

- **CASCADE** (child removed with parent): `telemetry`, `pid_observations`,
  `location_samples`, `session_trip_rollups` (all → `sessions`); `cell_snapshots`
  → `battery_snapshots`.
- **SET NULL** (child survives, link nulled): `events` → `sessions`;
  `field_capabilities` → `vehicles`; and on `trip_segments`, `charge_sessions`,
  `battery_snapshots`, `exports`, every FK (session / vehicle / telemetry sample)
  is `ON DELETE SET NULL`.
- **No FK** (lives on regardless): `adapter_history` and `diagnostic_codes` carry
  only loose `last_session_id` references, and `maintenance_log` has no
  session/vehicle link at all — so a full data clear that drops sessions leaves
  the maintenance log intact.

## Indexes

Each table carries time-ordered indexes for its dominant read (e.g.
`idx_telemetry_session_time` on `(session_id, captured_at_ms DESC)`), plus
lookup indexes for the deduped tables (`idx_diagnostic_codes_lookup`,
`idx_field_capabilities_lookup`). Schema v7 added **time-only** prune indexes
(`idx_telemetry_captured_at`, `idx_location_samples_captured_at`,
`idx_events_occurred_at`, `idx_pid_observations_observed_at`) so the daily raw-
data prune in `ObdStoreMaintenance.pruneRawDataOlderThan` doesn't full-scan —
the composite `(session_id, …)` indexes can't help a query that doesn't
constrain `session_id`. Schema v12 added `idx_maintenance_log_created` on
`maintenance_log(created_at_ms DESC)` for the newest-first maintenance read.

## Test contract: dashboard handshake log string

Not a table, but a coupling that lives next to this data layer and is easy to
break by accident: the emulator runtime smoke
([`scripts/emulator-smoke.sh`](../scripts/emulator-smoke.sh)) proves the
dashboard JS came alive by grepping logcat for the **exact** line:

> `dashboard handshake received`

emitted by `MainActivity.onDashboardReady`. **This string is a TEST CONTRACT.**
If you rename or reword that log, you must update the `grep` in
`scripts/emulator-smoke.sh` (and the workflows
`.github/workflows/android.yml` / `.github/workflows/android-emulator-smoke.yml`)
in the same change, or the smoke
goes permanently green while testing nothing.
