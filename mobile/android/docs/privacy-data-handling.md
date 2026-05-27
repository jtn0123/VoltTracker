# Privacy and Data Handling

Volt Tracker is designed as an on-phone OBD companion. The app does not need a
server for normal use, and OBD/GPS history is stored in app-private Android
storage.

## Data Stored On Device

The app may store:

- Live OBD telemetry samples and raw adapter replies.
- GPS route samples for logged drives.
- Status/debug events that help diagnose adapter connection failures.
- Bluetooth adapter names and addresses for remembered devices.
- Vehicle identity rows when the car reports a VIN. The dashboard shows only a
  redacted VIN suffix; the full VIN is not displayed.

Structured history lives in the app SQLite database. Field-test JSONL logs live
under app-private `files/obd-logs/`.

## Backups and Diagnostics

The backup action shares a full copy of the on-device SQLite database. That file
can include precise GPS routes, raw OBD samples, Bluetooth adapter addresses,
and redacted vehicle records. Share it only with people or storage providers you
trust.

Diagnostics exports are meant for troubleshooting. They can include recent
session logs, rolling app logs, and environment details useful for debugging an
adapter or phone issue.

## Network Use

Normal OBD logging, local history, and dashboard rendering work from on-device
data. The Map tab starts with remote basemap tiles off and can render stored
routes on a blank offline canvas. If the user enables the Tiles control, the app
can request remote basemap tiles from CARTO or OpenStreetMap so routes have
geographic context. Route points and OBD samples are still rendered from local
storage, but tile providers can see tile coordinates requested by the device.

## Unusual Permissions

The app declares `KILL_BACKGROUND_PROCESSES` so the troubleshooter can offer a
user-triggered force-stop button for known Bluetooth OBD apps that may be holding
the adapter connection. The bridge rejects packages outside the curated
competing-app allowlist and never force-stops apps automatically.

## Delete or Export Data

Use the dashboard Settings and backup tools to export, restore, or clear local
history. Android app uninstall also removes app-private storage.
