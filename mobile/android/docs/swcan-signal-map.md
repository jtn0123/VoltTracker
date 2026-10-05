# SW-CAN signal map

What each single-wire CAN (GMLAN low-speed, OBD pin 1, 33.3 kbit/s) frame on our 2017 Volt carries,
as far as we know. The list itself is [`swcan-signal-map.csv`](swcan-signal-map.csv), one row per
field. This page explains how to read it and how to grow it.

**Rule:** whenever you learn what a frame or byte means, add or update its row in the CSV in the same
PR. Don't leave it only in a PR description or a chat. The next agent starts from the CSV.

## Quick start

1. **Capture.** Debug builds log every SW-CAN listen window as a `swcan_raw` event in the session log
   (`SwcanListenRunner.Policy(logRawWindows = BuildConfig.DEBUG)`). Drive with a debug build. Car →
   "Body test" listens for 60 s straight, which helps with events like doors and windows.
2. **Pull the logs** from the phone (debug app only, no root):

   ```bash
   adb exec-out run-as com.volttracker.obdpoc.debug ls files/obd-logs/
   adb exec-out run-as com.volttracker.obdpoc.debug cat files/obd-logs/session-<epochMs>-obd.jsonl > /tmp/session.jsonl
   ```

   These logs are **not redacted**: they hold VIN, GPS and the OnStar Wi-Fi details. Keep them out of
   the repo.
3. **Match frames to telemetry:**

   ```bash
   cd mobile/android
   python3 tools/swcan_correlate.py /tmp/session-*.jsonl              # ids the map doesn't name yet
   python3 tools/swcan_correlate.py --id 106B8040 /tmp/session-*.jsonl  # one id, even if mapped
   python3 tools/swcan_correlate.py --id 1062C040 --changes /tmp/session-*.jsonl  # payload timeline
   python3 tools/swcan_correlate.py --self-test
   ```

   For every frame id it prints how often it was heard, what each byte does (`const`, `counter`,
   `flag`, `enum`, `analog`), and which telemetry signals the moving bytes track:

   ```text
   ## 106B8040  arb 35C  src 40  frames 144  windows 22  distinct 144  len 8
     bytes: b0 analog x8, b1 analog x139, ...
     b2-3    ~ speedKph r=+0.995  speedKph ≈ 0.0312456·raw - 0.008959  (n=56, sessions=2)
   ```

   Read that as: bytes 2–3, big-endian, times 1/32 is speed in km/h. `n` is the number of listen
   windows behind the fit and `sessions` the number of separate drives or parked sessions.
4. **Write it down** in the CSV, using the status legend below.

## How the tool decides

- Each listen window is one data point. Its frames are reduced to a median and paired with the
  nearest telemetry sample within 15 s.
- Fits are computed **within each session** (windows more than 20 minutes apart start a new one).
  Otherwise anything that differs between a parked day and a driving day "tracks" everything else that
  differs.
- For each moving byte it tries 8, 16 and 24-bit big-endian fields and keeps the best one.
- `[app reads this from SW-CAN]` after a fit means the telemetry key comes from SW-CAN itself (the
  `app_key` column). That only shows two frames carry the same thing, not that the decode is right.
- A 20-minute drive gives about 20 windows. Treat one-session fits as leads. Several drives make a fit
  trustworthy.
- The listen windows are short: about 2.5 s every 45 s while driving. Frames sent once per drive (like
  tire pressures) or on events (doors, windows) are easy to miss.

## Columns

| Column | Meaning |
| --- | --- |
| `id` | 29-bit frame id in hex, or `arb:XXX` to match the 13-bit GMLAN parameter id from any source |
| `arb` | GMLAN parameter id: `(id >> 13) & 0x1FFF` |
| `src` | Sending module: `id & 0xFF` (see the table below) |
| `bytes` | 0-based byte indexes, ranges inclusive, big-endian; `*` = whole frame. Bit fields say so. |
| `name` | What the field is |
| `scale`, `unit` | value = scale applied to raw, in that unit |
| `status` | See below |
| `app_key` | Telemetry key(s) the app fills from this field, `;`-separated. Empty = the app doesn't read it |
| `evidence` | How we know, with dates of the drives |

Lines starting with `#` are comments.

## Status legend

| Status | Meaning |
| --- | --- |
| `decoded` | The app reads it, and a real-car capture agreed with the car or a polled value |
| `decoded-unchecked` | The app reads it (mostly OVMS layouts), but it's not matched on our car yet |
| `strong` | Fits the car's own readings across sessions, or reads plainly by eye. The app doesn't use it yet |
| `candidate` | A lead: a weaker or single-session fit. Needs more drives |
| `counter` | Rolling or free-running counter |
| `constant` | Same payload in every frame on every drive so far |
| `opaque` | Every frame differs with no pattern (likely a MAC or encrypted) |
| `heartbeat` | Empty-payload "I'm alive" frame |
| `sensitive` | Personal data. The tool never prints its payload. Never log, paste or commit it |
| `unknown` | Heard, not understood yet |

## Sensitive frames

Some frames carry personal data. The CSV marks them `sensitive`, and the tool hides their payloads
(including the per-byte summary). Unmapped frames whose payloads look like text are hidden too.

- `arb:762`, `arb:764`: VIN.
- `arb:155`, `arb:156`: GPS position.
- `arb:160`, `arb:182`–`arb:184`: immobilizer id and its learned environment id (anti-theft pairing).
- `arb:474`, `arb:478`–`arb:482` from OnStar (`0x97`): the car's Wi-Fi settings, hotspot name and
  password, in plain text.

If you find another one, add a `sensitive` row **before** pasting any tool output anywhere.

## Source addresses

| Source | Module | Evidence |
| --- | --- | --- |
| `40` | Body control module | Locks, doors, 12 V, tires, wheel speeds, VIN |
| `60` | Instrument cluster | Gas range, distance counters |
| `80` | Radio | Date frame, heartbeat |
| `97` | OnStar / telematics | GPS, date and time, Wi-Fi hotspot |
| `99` | HVAC | A/C state, blower, cabin temperature |
| `CB` | Hybrid powertrain control | A/C compressor, PE coolant, SOC, drive-cycle frames |
| `58`, `59`, `5B`, `66`, `68`, `81`, `AF`, `B9`, `BB`, `BC` | Unknown | Heartbeats plus a few frames each |

Every module also sends an empty heartbeat on `13FFE0xx`, where `xx` is its source address.

## What we know (as of 2026-10-04)

From two sessions: a 5-minute parked capture on 2026-09-29 and a 21-minute drive on 2026-10-04.
That's 56 windows, 40,710 frames and 105 frame ids.

- **In the app and confirmed:** door lock, 12 V voltage, tire pressures, A/C state, A/C evaporator
  and compressor, blower, cabin temperature, PE coolant and drive-cycle EV distance.
- **Ready to use (strong):** four wheel speeds (`106B8040`), vehicle speed (three frames), live
  odometer (`106C0040`), displayed SOC (`107840CB`), engine coolant and intake air (`102E0040`), UTC
  and local date/time, and a free-running minute counter (`1062C040`).
- **Open:** tire corner order (check against the cluster tire page), doors, hood, trunk, windows and
  alarm (event-only; use the Body test), and about 30 unknown movers.
