# ADR 0010 — Native launcher and classic tools

- **Status:** Accepted, documenting the current implementation on 2026-10-03.
- **Supersedes:** The launcher/UI choice in [ADR 0001](0001-webview-dashboard.md).
- **Superseded by:** —

## Context

`ComposeDashboardActivity` is the manifest launcher. The native Drive, Charge,
Trips, Insights, Car, Health and Settings screens are the everyday interface.
`MainActivity` retains the local WebView dashboard and its advanced tools.

## Decision

Keep Compose as the launcher. `LiveUiStateStore` folds the service's telemetry and
status into immutable `VoltAppUiState`; `ComposeHistoryLoader` reads saved data
off the main thread. Native actions use the shared foreground service, settings,
diagnostics, export and backup helpers. The service owns Bluetooth polling and
persistence independently of either activity.

Keep classic tools reachable through Settings → Advanced and explicit deep links
(trip editing, maintenance and Troubleshooter). `VoltBridge` and the allowlisted
`DashboardPublisher` remain the boundary for that surface. Its HTML and JS are
generated from partials and TypeScript; they are not a second backend.

## Consequences

Both surfaces share the same database, settings and status contract. Changes to
telemetry, recording health or diagnostics must be checked on both. Native JVM /
Robolectric behavior and visual tests cover the launcher; Vitest and Playwright
cover classic tools. Demo and emulator evidence do not establish physical-car
PID accuracy. ADR 0001 remains useful historical context for the retained WebView,
but its recommendation to defer Compose no longer describes the product.
