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
