# Troubleshooting

This guide maps common field symptoms to the in-app tools that already collect
evidence. Keep Bluetooth pairing and vehicle power state visible while testing;
most connection failures come from one of those two surfaces.

## Connection fails

Check:

- Android Bluetooth is on and the adapter is paired in system settings.
- The adapter LED is on and the car is awake or in Ready.
- No other OBD app is connected. VoltTracker detects likely competitors in the
  Diagnostics tab when Android exposes package data.
- The selected adapter address matches the paired device you expect.

Try:

1. Tap Reconnect once from the dashboard.
2. If the copy says the bond may be stale, forget and re-pair the adapter in
   Android Bluetooth settings.
3. If it says the adapter dropped immediately after connect, unplug the adapter
   for 10 seconds before retrying.
4. Open Diagnostics and export detailed signal logs if the failure repeats.

## No live telemetry

Check the Signals tab first. It shows whether the app is connected, whether the
adapter is answering, and which PIDs are stale. Some Volt-specific Mode-22 values
require an adapter that supports header switching; standard Mode-01 speed/RPM
should still appear on a healthy connection.

## No GPS route

Android location permission is separate from Bluetooth permission. Confirm the
app has location access and that the phone can see GPS outdoors. A session can
still record OBD telemetry without a route; Trips will show a lower-confidence
summary when route samples are absent.

## Map tiles missing

Route history is local, but basemap tiles are requested from the configured tile
provider. Missing tiles usually mean the phone is offline, the tile provider is
blocked, or the provider is timing out. The route line can still render over an
empty map background.

## Restore or backup errors

Encrypted backups require a passphrase of at least 8 characters. Restore performs
schema, integrity, and foreign-key checks before merging data, so an incompatible
or damaged file should fail before touching the live database. When restore fails,
use the Settings status message and export a debug bundle if the cause is unclear.

## Useful artifacts

- JSONL session logs: `files/obd-logs/session-*.jsonl`
- Latest log pointer: `files/obd-logs/latest.txt`
- Dashboard Diagnostics tab: connection classifier, competing-app hints, and
  detailed signal log export
- Settings storage summary: database size, session count, sample count, and
  backup/restore state
