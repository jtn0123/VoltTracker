# Field-Test Checklist

Use this before a real-car or adapter test so runtime findings become useful
regression evidence instead of one-off notes.

## Before The Drive

- Record app version, branch/commit, Android version, WebView version, phone model,
  adapter model, and car state.
- Confirm Bluetooth adapter is paired in Android settings.
- Run the app once without the car and confirm the dashboard opens.
- If testing GPS/routes, confirm location permission is granted.
- If testing background logging, confirm notification permission is granted.

## During The Test

- Start with the Drive tab visible and record whether status reaches connected.
- Capture one normal live logging segment of at least two minutes.
- Tap Trips, Map, Charge, Insights, Diagnostics, and Signals once during or after
  logging; note any blank screens, clipped controls, or stale values.
- If testing recovery, unplug/replug the adapter or sleep/wake the car and record
  the visible status copy plus timing.
- If testing detail probes or DTC flows, write down the exact button/path used.

## After The Test

- Pull the latest JSONL session log from `files/obd-logs/latest.txt`.
- Pull the SQLite database when the finding involves Trips, Map, Charge,
  Insights, backups, or stored history.
- Capture screenshots for any visual issue.
- Record whether the issue reproduced once or repeatedly.
- Convert any protocol/runtime anomaly into a sanitized fixture or note why it
  cannot safely be stored.

## Artifact Names

Prefer names that include date, short commit, and scenario:

```text
field-test-YYYY-MM-DD-<short-sha>-<scenario>.jsonl
field-test-YYYY-MM-DD-<short-sha>-<scenario>.db
field-test-YYYY-MM-DD-<short-sha>-<scenario>-notes.md
```
