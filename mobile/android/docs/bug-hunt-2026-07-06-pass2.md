# Bug hunt — 2026-07-06 (second pass)

A deeper follow-up to the first-pass audit (`bug-hunt-2026-07-06.md`), weighted
toward the areas a quick sweep skips: Kotlin OBD/PID decode math, crypto/backup,
the JS↔native bridge, route geometry, insight/SoH calculations, notification and
polling state machines, the home-screen widget, and cross-partial a11y.

Method: 19 parallel finder lanes → per-lane adversarial verification → synthesis
deduped against the first pass. 53 new verified defects; 49 fixed here, 4 left
(with reasons). All dashboard fixes ship with regression tests (dashboard unit
617/617, e2e 53/53, visual 36/36); Kotlin fixes carry Robolectric/JVM tests.

## High severity

| # | Area | File | Defect → fix |
|---|------|------|-------------|
| 1 | crypto | `BackupCrypto.kt` | GCM decrypt via `CipherInputStream` can swallow `AEADBadTagException` at EOF, writing unauthenticated plaintext on a wrong passphrase / tampered backup. Rewrote to buffer + explicit `doFinal` so a bad tag → `DECRYPT_FAILED`. |
| 2 | correctness | `prefs.ts` | `bindNumericPref` clamped below-min digit prefixes on every keystroke, making charge-target 60/70/80/90 (and MPG 25–45) untypeable. Only cap over-max on input; min-clamp + persist moved to `change`. |
| 3 | a11y | `base.css` | `:root[data-contrast="high"]` shipped white-on-white on light-scheme + high-contrast (~1.1:1), blanking the UI for the exact user who needs it. Added a light-scheme high-contrast token override. |

## Medium severity (selected)

- `DiagnosticScanRunner.kt` — manual scan fed Mode 07/0A codes into the new-DTC
  notification baseline that the on-connect scan writes with Mode 03 only, causing
  a permanent-only code to re-fire a false alert forever. Now Mode-03-only baseline.
- `ObdProtocol.kt` — `mode01PayloadBytes` undercounted signed 2-byte PID 32, so a
  truncated batched frame was accepted and the value silently blanked.
- `map.ts` — live-position stats admitted `(0,0)` fixes the drawn polyline filters,
  inflating distance/avg-speed by a ~13,000 km phantom leg; `null` altitude read as
  0 m sea-level in the elevation/grade traces. Now filtered / `numOrNaN`.
- `storage-status.ts` / `insights-panel.ts` — `Number(null) === 0` planted false 0%
  SoH points and false 0.8 mi/kWh highway scatter dots. Coerce null → NaN first.
- `telemetry.ts` — `resetTelemetry()` fired on every inactive→active edge, so a
  mid-drive Bluetooth blip zeroed session distance/SoC. Gated on a genuine stop.
- `widget/WidgetUpdater.kt` — `onStatus` advanced the freshness clock, so a frozen
  SOC read "Updated just now" forever. Only telemetry samples advance it now.
- `PidPollingState.kt` — carry-forward max-age ignored per-cycle I/O, aging out slow
  7E4 PIDs (cell balance / SoC) between polls; batch-miss streak not reset on reconnect.
- `TroubleshooterBridge.kt` — "adapter ready" alert posted on an IMPORTANCE_LOW
  channel (silent); moved to the DEFAULT alerts channel.
- `status-tools.css` — status toast (z-index 900) painted above modal dialogs (220);
  lowered to 210.

## Low severity

~30 further fixes: DTC category/terminology corrections (P1259 VTEC→GM, P34xx
cylinder-deactivation, P0D aux vs P0C charging), MAC redaction in the WebView
payload, U+2028/2029 escaping before `evaluateJavascript`, indexed `isHidden`
query, notification-id space widened against collisions, per-row export double-tap
guard, demo-preview isolation race, several new singular/plural + null-render
fixes, heading-order (h1→h2) on Charge/Settings tabs, dialog `autocomplete`
per-use, adaptive-icon monochrome safe-zone, widget cell sizing, and more.

## Not fixed (with reason)

- **Session JSONL retention/pruning** — REJECTED by owner (see
  `code-audit-findings-2026-07-04.md` / ADR 0003): raw logs are intentionally kept.
- **One-shot reconnect attempt budget** (`ObdPollingEngine.kt`) — assessed
  intentional (tighter combined bound for one-shot runners); documented in-code.
- **Map layer-switcher ARIA** (`map.html`) — the tab/tabpanel nesting is invalid,
  but a correct radiogroup conversion needs coordinated `aria-checked` emission in
  `map.ts`; deferred to avoid a partial-conversion a11y regression.
- **Status popover under bottom-nav** (`status-tools.css`) — the popover is trapped
  in `.app`'s `translateZ(0)` stacking context; the clean fix needs a DOM-layer or
  `.bottom-nav` change outside the touched files.
