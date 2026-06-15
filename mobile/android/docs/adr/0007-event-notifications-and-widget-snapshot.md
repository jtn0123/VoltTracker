# ADR 0007 — Event notifications and the widget-snapshot decoupling

- **Status:** Accepted (recorded 2026-06-14; reflects the event-notification +
  home-screen-widget features shipped in the v0.15.0 feature wave, report items
  M1 and M10a).
- **Deciders:** Project author.
- **Supersedes:** —
- **Superseded by:** —

## Context

The v0.15.0 feature wave added two surfaces that both need to react to live
vehicle state from *outside* the WebView dashboard:

1. **Event notifications** (M1) — one-shot alerts for charge-complete, a new
   diagnostic code, low SOC, and high pack temperature. These must fire while the
   app is backgrounded (a charge finishes hours after the user put the phone
   down), and they must not spam: a value hovering at a threshold, or a single
   glitchy sample mid-charge, cannot be allowed to post a flurry of identical
   notifications.

2. **Home-screen widget** (M10a) — an at-a-glance SOC / charging / freshness
   snapshot. AppWidgets are hosted by a **separate system process** (the launcher)
   on their own update schedule; the OS delivers `APPWIDGET_UPDATE` to the
   provider's `BroadcastReceiver`, which may run when our process is not even
   alive. The widget therefore cannot assume the `ObdService` is running, let
   alone bind to it.

Two design questions fell out of this:

- How does the alert logic stay correct (fire exactly once per real event) and
  fully unit-testable, given it lives in the Android service layer?
- How does an out-of-process widget read "the latest vehicle state" without
  binding the foreground service or reaching into the SQLite store on the UI
  path of a launcher redraw?

Both also had to respect the existing strict UI → Service → Engine → Data
layering rule (ADR 0002): neither surface may touch the WebView, and the alert
notifications live in the same service/notification layer as `ObdNotifications`.

## Decision

### Event notifications: dedicated channel + hysteresis-armed pure decider

- **Separate "alerts" notification channel.** `EventNotifier` owns its own
  `DEFAULT`-importance "alerts" channel, distinct from the low-importance,
  ongoing foreground-service channel in `ObdNotifications`. One-shot alerts
  deserve attention; the persistent "logging is running" notification does not.
- **The firing logic is a pure, Android-free core.** `EventNotificationDecider`
  is fed `Sample`s (and scan results) and returns the `Event`s that should fire,
  holding only in-memory per-connection state. It has no `Context`, so every edge
  case is unit-tested directly.
- **Per-crossing firing with hysteresis arming.** Low-SOC and high-pack-temp
  alerts fire once when the reading *crosses* the configured threshold, then
  **re-arm only after the reading recovers past the threshold by a hysteresis
  band** (`SOC_HYSTERESIS_PCT = 3`, `TEMP_HYSTERESIS_C = 3`). A value oscillating
  at the line therefore cannot spam. The decider starts armed so the first
  crossing in a session alerts.
- **Charge-complete is edge-triggered and sample-gated.** It fires once on the
  charging → not-charging transition, only after at least `MIN_CHARGE_SAMPLES = 3`
  charging samples, so a single glitchy sample never raises a phantom
  "charge complete". It reuses the same pack-current-into-pack-at-low-speed signal
  as `ChargeSessionMaterializer` (ADR 0004).
- **New-DTC is baseline-diffed in prefs.** A scan's code set is diffed against the
  previous scan's set, which is persisted in `EventNotificationPrefs`. The very
  first scan only establishes the baseline (pre-existing months-old codes are not
  "new"), so onboarding a car with an old pending code stays quiet.
- **Settings are mutable without losing arming state.** The user toggles/thresholds
  are pushed in via `updateSettings(...)` rather than rebuilding the decider, so a
  mid-session preference change takes effect on the next sample without discarding
  the per-connection accumulation/arming state.
- **Runtime permission degrades gracefully.** On Android 13+ a post without
  `POST_NOTIFICATIONS` is logged and dropped, never thrown.

### Widget: read a compact SharedPreferences snapshot, never bind the service

- **The widget reads a persisted snapshot, not the live service.** The service
  writes a compact `WidgetSnapshot` (SOC, charging, connected, vehicle state, two
  timestamps) into the app's existing shared-prefs file (`AppPrefs.FILE`, under a
  dedicated `widget_snapshot_*` key namespace) via `WidgetSnapshotStore`.
  `VoltWidgetProvider.onUpdate` reads that snapshot and binds `RemoteViews`. It
  never binds `ObdService` and never opens the database on the redraw path — the
  out-of-process host just reads a handful of primitive prefs keys.
- **The write is cheap, crash-safe, and debounced.** Writes use `apply()` (never
  `commit()`), and every store method swallows storage failures so a snapshot write
  can never break a live OBD session. `writeIfChanged` only persists the display
  fields and asks for a redraw when a *meaningful* field actually changed, so the
  1 Hz telemetry stream does not thrash prefs or redraw on every identical sample.
  The freshness timestamp, however, is bumped on *every* sample so a steady
  (flat-but-live) charge does not age out as falsely "stale".
- **The "what to show" decision is also pure.** `WidgetStateFormatter` turns a
  snapshot + "now" into display strings/freshness, keeping the provider thin and
  the formatting unit-testable.
- **Manifest exposure is minimal.** The provider receiver is `exported="true"`
  (required — the system widget host is a separate process) but gated by
  `android:permission="android.permission.BIND_APPWIDGET"`, so only the system
  widget host can deliver `APPWIDGET_UPDATE`.

## Consequences

- The widget keeps showing the last known state even when the app process is dead,
  with no service binding and no DB read on the launcher's redraw path — at the
  cost of being eventually-consistent (a snapshot, not a live feed) and bounded by
  the AppWidget `updatePeriodMillis` plus the service's push on change.
- Alert correctness lives in two pure classes (`EventNotificationDecider`,
  `WidgetStateFormatter`) with full unit coverage; the Android edges
  (`EventNotifier`, `WidgetSnapshotStore`, `VoltWidgetProvider`) stay thin.
- Reusing the existing prefs file for the widget snapshot avoids a second prefs
  file but couples the widget to `AppPrefs.FILE`; the dedicated key prefix keeps it
  from colliding with the event-notification settings stored in the same file (the
  prefix-disjointness is asserted by a unit test — report item A4).
- The hysteresis bands and sample/crossing gates are tuned constants pinned in
  `EventNotificationDecider`; changing them changes alert sensitivity and is
  covered by regression tests.

### Still open: encrypted-backup crypto/format has no ADR

The encrypted-backup path (passphrase-protected portable backup, see
`docs/privacy-data-handling.md`) makes a real cryptographic decision — KDF,
cipher, and on-disk container format — that is **not yet captured in any ADR**.
This ADR does not close that gap; the backup crypto/format decision remains an
open ADR to write. It is called out here so the omission is tracked rather than
silently assumed.

## References

- `app/src/main/kotlin/com/volttracker/obdpoc/EventNotificationDecider.kt`
- `app/src/main/kotlin/com/volttracker/obdpoc/EventNotifier.kt`
- `app/src/main/kotlin/com/volttracker/obdpoc/EventNotificationPrefs.kt`
- `app/src/main/kotlin/com/volttracker/obdpoc/widget/WidgetSnapshotStore.kt`
- `app/src/main/kotlin/com/volttracker/obdpoc/widget/VoltWidgetProvider.kt`
- `app/src/main/kotlin/com/volttracker/obdpoc/widget/WidgetStateFormatter.kt`
- `app/src/main/AndroidManifest.xml` (widget receiver + `POST_NOTIFICATIONS`)
- `docs/adr/0002-strict-layering-rule.md` (UI → Service → Engine → Data)
- `docs/adr/0004-charge-detection-heuristics.md` (the charging signal reused here)
