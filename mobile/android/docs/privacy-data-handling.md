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

Encrypted backup is the primary export path. It creates a passphrase-protected
portable backup before handing the file to the Android share sheet, which is the
right choice for cloud storage, email, shared computers, or support handoffs.
Use a strong, unique passphrase for encrypted backups. Volt Tracker cannot
recover the backup if that passphrase is lost, and weak or reused passphrases
make the encrypted file easier to attack offline.

An encrypted backup is protected with AES-256-GCM, and the key is derived from
your passphrase with PBKDF2-HMAC-SHA256 at 600,000 iterations (`BackupCrypto.kt`).
The current backup format (v3) records that iteration count in its header; older
backups written before this change still decrypt at the legacy count of 150,000.
Because the key is stretched per-passphrase rather than stored, the strength of
the encryption rests on the passphrase you choose.

Plaintext backup remains available as an advanced compatibility option when a
trusted tool specifically needs the raw SQLite database. Plaintext files can
include precise GPS routes, raw OBD samples, Bluetooth adapter addresses, and
redacted vehicle records. Share them only with people or storage providers you
trust.

Diagnostics exports are meant for troubleshooting. They can include recent
session logs, rolling app logs, and environment details useful for debugging an
adapter or phone issue. Before writing a debug summary or diagnostics share zip,
Volt Tracker redacts Bluetooth MAC addresses, VIN-like identifiers, and precise
coordinate fields from the included log tails.

The user-authored maintenance log (your service-history entries) is deliberately
kept separate from drive data: the `maintenance_log` table has no foreign keys
(see `data-model.md`), so it intentionally **survives a full data clear** that
drops sessions, telemetry, and GPS routes. It is also part of the on-device
SQLite database, so it **is included in encrypted backups** and travels with
them. Keep this in mind if you would not expect logged service history to persist
after clearing data or to leave the device inside a shared backup file.

### Per-trip GPX / CSV exports contain full-precision location

The per-trip **GPX** and **CSV** exports (the "export this drive" action on a
logged trip) are **unredacted by design**: they contain **full-precision GPS
coordinates and timestamps in plaintext**, written by `TripTrackFormatter`. This
is deliberate — the whole point of a GPX/CSV is to hand a faithful route to a
mapping tool (Strava, Garmin, a spreadsheet), so coordinates are emitted at full
decimal precision and per-point UTC times are included. This is **unlike the
in-app VIN** (shown only redacted) and **unlike the diagnostics share zip**
(where coordinates are scrubbed from the log tails).

These exports are only produced when **you initiate the export** for a specific
drive, and they leave the device only through the **Android share sheet** (the OS
chooser), to whatever app or destination you pick. Share a trip export only with
people, apps, or storage providers you trust, the same way you would treat a
plaintext backup.

## Network Use

Normal OBD logging, local history, and dashboard rendering work from on-device
data. The Map and Trips route views use remote basemap tiles by default so
stored routes have geographic context. Route points and OBD samples are still
rendered from local storage, but tile providers can see tile coordinates
requested by the device.

### Map Tiles

The Map and Trips basemap is fetched at view time from the CARTO basemap CDN
(`basemaps.cartocdn.com`), with `tile.openstreetmap.org` as an automatic
fallback when CARTO is unreachable. Tile requests are the only routine network
traffic the app generates.

**What leaves the device:** a tile request contains the tile's map
coordinates (zoom level plus x/y tile indices) and standard HTTP metadata
such as your IP address. Tile coordinates identify the approximate
geographic area shown on screen — at typical zoom levels a tile covers
roughly a neighborhood-to-city-sized area — so the tile provider can infer
the coarse region you are looking at (which often correlates with where you
drive). **What does not leave the device:** your GPS route points, OBD
telemetry, drive history, vehicle identity, and account-free app state are
never sent to tile providers or anywhere else; routes are drawn locally on
top of the fetched imagery. No telemetry, analytics, or identifiers beyond
the plain HTTP request are attached.

**Timing:** no tiles are fetched at app startup. Tile requests begin only
when a map actually renders on screen — opening the Map tab or opening a
trip onto the map (real or demo); the Leaflet map and its tile layer are not
even created while the map view is hidden. The first time the map
renders on an install, the app shows a one-time dismissible notice stating
the above; a permanent one-line restatement lives at the bottom of Settings
next to the open-source licenses.

Tiles are deliberately **not persistently cached** by the app. There is no
offline tile store: caching tiles to disk would accumulate a
location-revealing archive of everywhere the map has been viewed, and an
unbounded one as zoom levels and areas pile up. Aside from the WebView's
standard transient HTTP caching (app-private and removed with app data),
viewed tile imagery is not written to device storage.

Offline behavior: routes, OBD history, and GPS traces always render from local
storage, with or without a basemap. When tiles repeatedly fail to load, the
map shows a "Map tiles are not loading" banner with a retry control; after a
sustained run of CARTO failures the map switches itself to the OSM fallback
layer (see `dashboard-src/js/map.ts`). If the fallback also fails, the banner
notes that routes still work without basemap tiles.

## Unusual Permissions

The app declares `KILL_BACKGROUND_PROCESSES` so the troubleshooter can offer a
user-triggered force-stop button for known Bluetooth OBD apps that may be holding
the adapter connection. The bridge rejects packages outside the curated
competing-app allowlist and never force-stops apps automatically.

## Delete or Export Data

Use the dashboard Settings and backup tools to export, restore, or clear local
history. Android app uninstall also removes app-private storage.
