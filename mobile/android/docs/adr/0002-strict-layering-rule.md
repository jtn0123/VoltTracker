# ADR 0002 — Strict UI → Service → Engine → Data layering rule

- **Status:** Accepted (recorded 2026-05-22).
- **Deciders:** Project author.
- **Supersedes:** —
- **Superseded by:** —

## Context

VoltTracker's Android code spans four very different concerns: Bluetooth IO
against an ELM327, OBD-II protocol parsing, on-device SQLite persistence, and a
WebView dashboard that renders the result. Without a written rule, those
concerns interleave quickly — Bluetooth read callbacks reach into the WebView,
SQLite helpers grow knowledge of the Activity, and the dashboard ends up coupled
to internal data classes that should never have crossed the bridge.

The roadmap (`mobile/android/docs/mobile-architecture-roadmap.md`, "Layering
Rule") sketches the layout:

```text
UI / WebView   MainActivity, VoltBridge, dashboard/*
       ↓
Service        ObdService, ObdNotifications, PermissionGate
       ↓
Engine         ObdPollingEngine, SessionRecorder, ObdProtocol, ElmConnection,
               ObdElmDecode, ObdProbes, location/*
       ↓
Data           data/* — ObdLocalStore, VoltTrackerDb, ObdStore*
```

"Calls flow downward only" means a higher layer may import and call a lower
one, but the lower layer must never reference the higher one — not by import,
not by reflection, not by a back-pointer field.

## Decision

The four layers above are normative. The rules are:

- `data/*` may import only itself and the Android SDK. It is the only layer
  allowed to touch `SQLiteDatabase` / `SQLiteOpenHelper` / `ContentValues`.
- Engine code may use `data/*` but must never import `MainActivity`, the
  WebView, or the `VoltBridge`.
- The service layer (`ObdService` and friends) orchestrates engine work and
  publishes broadcasts; it must not call `webView.evaluateJavascript` or hold a
  reference to any UI object.
- `MainActivity` / `VoltBridge` may call into the service via Intents and into
  `data/*` for read-only DTO queries (storage summary, trip list, insights
  JSON). They must not call `getWritableDatabase()` directly — every write goes
  through an `ObdLocalStore` method.

## Consequences

### Positive

- The engine and data layers stay testable with plain JVM + Robolectric — no
  Activity or WebView in the test classpath.
- File splits stay easy: each layer's files share a purpose, so cutting a
  large class apart doesn't drag dependencies across the boundary.
- Code review has one obvious question to ask any PR: "does this import
  upward?" If yes, the change is in the wrong file.

### Negative

- Occasional verbosity: passing a small DTO out of `data/*` instead of letting
  the caller see the raw `Cursor` or schema-shaped class.
- No shortcut from `MainActivity` to write a row directly; every write
  round-trips through a recorder/store method even when the call site already
  knows the SQL it wants.

### Mitigations

- For now the rule is human-enforced in code review. A linter
  (`dependency-analysis-gradle-plugin`, or a custom `archunit` test) is a
  reasonable follow-up if the project ever grows enough to feel the lack.
- DTOs are kept small and JSON-shaped so the verbosity cost is bounded.

## Alternatives considered

### Flat single-package layout

- ✅ Less ceremony for a small app; no DTO boilerplate.
- ❌ The codebase already exceeds the size where a flat layout is comfortable
  (data layer alone has six files >500 LOC). Without a rule, the WebView and
  the SQLite helpers would already be entangled.

### Gradle multi-module enforcement

- ✅ Compiler-enforced rather than human-enforced.
- ❌ Overkill for a single-app project: extra Gradle build files, slower
  incremental builds, and the modules would each contain only a handful of
  classes. Revisit if the codebase grows past ~15k LOC.

## Revisit triggers

Revisit this decision if any of these hold:

- The project crosses ~15k LOC and code review can no longer reliably catch
  layering violations.
- A feature requires crossing layers and the workaround feels worse than the
  refactor (e.g. `MainActivity` needing to subscribe to a fine-grained engine
  event the broadcast surface can't express).
- A new contributor consistently misses the rule, signaling that documentation
  alone is not enough and a Gradle/archunit check is warranted.
