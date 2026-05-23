# ADR 0003 — JSONL field logs as black-box debug, SQLite as source of truth

- **Status:** Accepted (recorded 2026-05-22).
- **Deciders:** Project author.
- **Supersedes:** —
- **Superseded by:** —

## Context

VoltTracker writes two kinds of records during an OBD session: structured rows
into SQLite (`telemetry_samples`, `pid_observations`, `location_samples`,
`status_events`, etc.) and append-only JSON-lines field logs into private app
storage. Both have always existed; what wasn't written down is which one the
product UI is allowed to read.

The roadmap (`mobile/android/docs/mobile-architecture-roadmap.md`, "Database
Strategy") says: "The database should be the source of truth for real app
history. JSONL field logs are still useful as black-box debug artifacts, but
product screens should read from SQLite." This ADR records that decision
explicitly so it survives future refactors.

## Decision

- **SQLite is the source of truth for product UI.** Every Drive/Trips/Charge/
  Insights/Diagnostics screen reads from a table or a derived view of one.
  Nothing in the dashboard JS reads JSONL.
- **JSONL is an append-only black box for offline analysis.** It captures the
  raw protocol behavior — every line/response, including parser failures,
  intermittent PIDs, and Volt-specific fields the app doesn't yet decode. It
  is read by the developer with `adb pull` and a text editor, not by the app.
- The JSONL files live in private app storage and are never user-visible until
  an explicit export action.

## Consequences

### Positive

- Product data is queryable: SQL over `telemetry_samples` instead of grepping
  log lines.
- Clean privacy boundary: SQLite holds normalized, redacted state; JSONL holds
  raw protocol bytes for debugging. The two can be exported with different
  consent treatments (see grade item E1).
- Schema migrations have a clear scope — they apply to SQLite only. JSONL
  format can evolve independently because nothing in the app parses it.

### Negative

- Double-write cost on the IO thread: every telemetry row pays the SQLite
  insert *and* the JSONL append.
- Potential consistency drift: a row that makes it to JSONL but not to SQLite
  produces a session whose raw log exists but whose product view is empty.
  Most acute for lifecycle events (session open/close) where a missed write
  leaves the next launch seeing a stale "still-open" session.

### Mitigations

- `SessionRecorder` was split into a telemetry executor (silent on failure,
  per the diagnostic-only contract) and a lifecycle executor that surfaces
  failures via `Log.e` and a `status_events` row (see grade item B4). This
  bounds the drift to telemetry rows, where the cost is "one missing sample"
  rather than "one orphaned session".
- JSONL stays in private app storage. It cannot be exfiltrated by another app
  on the device and is not part of the user-facing backup until an explicit
  export step adds it.

## Alternatives considered

### JSONL-only (drop SQLite for product data)

- ✅ Single source, no double-write.
- ❌ Not queryable. Trips/Charge/Insights screens would need to scan and
  re-parse the entire log on every view, with no indexes. Untenable as
  history grows.

### SQLite-only (drop JSONL)

- ✅ One write path, simpler reasoning about consistency.
- ❌ Schema can't evolve fast enough to preserve raw unknown PIDs. When a
  parser fails or a new Volt-specific field appears, the only place we'd
  notice is logcat at runtime — and logcat is bounded and ephemeral. The
  black-box log is what makes "why did the app stop reporting SOC at 3:47?"
  answerable hours after the fact.

## Revisit triggers

Revisit this decision if any of these hold:

- JSONL files grow large enough to be a problem on low-storage devices and
  the existing retention prune isn't keeping up.
- A product feature actually needs to read raw JSONL data (e.g. exposing the
  "unknown PID stream" inside the app rather than only via export). At that
  point JSONL stops being a black box and becomes an addressable surface,
  and the rule needs rewriting.
- The double-write cost ever shows up in IO-thread profiling.
