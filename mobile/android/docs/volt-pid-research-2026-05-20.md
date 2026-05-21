# Volt PID research and on-phone data comparison (2026-05-20)

## Why this exists

The dashboard has shown `Volt PID needed` for SOC, kWh, pack voltage/current and
charger data since the project started. This pass researched the Chevy Volt
battery/charger PIDs and compared them against the data actually captured on the
test phone (`R5CX73C92JR`).

## What the phone database actually contains

Pulled `databases/volttracker_obd_poc.db` (20.9 MB) via `adb run-as` into
`docs/db-pulls/pull-20260520-pidcheck.db`.

| Table | Rows |
|---|---|
| obd_sessions | 5 (all mode `obd`; no `scan` session) |
| telemetry_samples | 13,069 |
| status_events | 37,266 |
| pid_observations | 16,533 |
| location_samples | 2,750 |
| cell_snapshots / battery_snapshots | 0 / 0 |

Distinct commands ever recorded in `pid_observations`: `0100 0104 0105 010C 010D
0111 ATRV` plus ELM init (`ATZ ATE0 ATL0 ATS0 ATH0 ATAT1 ATST64 ATSP0`).

**No mode-22 (`22xxxx`) command, no `ATSH7E4`, and no `62`-prefixed response
exists anywhere in the database.** The diagnostic scan has never been run against
the car — every session is standard live polling. So there is zero Volt-specific
battery data captured to date; nothing to validate the old PID guesses against.

### Standard-PID behaviour observed

- `010C` engine RPM: always `0`. This is **correct** for a Volt driving on
  electric — the gas engine is off, so SAE RPM reads zero. It is not a logging
  bug. Real RPM only appears in gas/hold mode, or via a Volt-specific motor PID.
- `0104` engine load: always `0` — same reason (engine off).
- `0105` coolant, `0111` throttle, `010D` speed, `ATRV` (~14.9 V) all logged
  plausible values.

## The old PID targets were malformed

The previous probes (`2243AF1`, `228334`, `2241A31`, `2234B2`, `2241811`...) were
mostly 7-hex-digit strings. A valid mode-22 request is `22` + a 2-byte PID =
6 hex digits (`22XXXX`). The old strings could not have returned valid data even
if the scan had been run.

## Validated Volt PID set (now in the code)

Primary source: the community "Volt PIDs" Torque sheet (compiled by *wosk*),
exported to `docs/volt-pids-community-sheet.csv`. Cross-checked against the
gm-volt.com forum threads. `A` = first data byte after the positive-response
marker (`62`+PID for mode 22, `41`+PID for mode 01); `B` = next byte. Signed
words are two's-complement (discharge positive, charge negative).

### State of charge — `015B` (standard mode 01, no header)

| PID | Meaning | Decode |
|---|---|---|
| `015B` | State of charge | `A * 100 / 255` % |

`015B` is the SAE hybrid-battery PID; it answers on the normal OBD bus with no
`ATSH`, so it is the simplest SOC source and can go straight into live polling.
(The mode-22 equivalent `22005B` exists on header 7E0 but is not needed.)

### Header `ATSH7E1` — HV pack

| PID | Meaning | Decode |
|---|---|---|
| `222429` | HV pack voltage | signed `(A*256+B) / 64` V |
| `222414` | HV pack current | signed `(A*256+B) / 20` A |

Pack power: `power_kW = 222429 * 222414 / 1000` (the sheet's "Inst. kPower").
Note: `222885` is **MGA motor-generator voltage**, not the pack — the first pass
mistakenly used it; corrected to `222429`.

### Header `ATSH7E4` — battery / charger controller

| PID | Meaning | Decode |
|---|---|---|
| `22434F` | HV battery temperature | `A - 40` °C |
| `224368` | Charger AC input voltage | `A * 2` V |
| `224369` | Charger AC input current | `A * 0.2` A |
| `22436B` | Charger HV output voltage | signed `(A*256+B) / 2` V |
| `22436C` | Charger HV output current | signed `(A*256+B) / 20` A |
| `224373` | Charger HV output power | signed `(A*256+B)` W |
| `22437D` | Last charge AC energy | `(A*256+B) * 10` Wh |

## Not yet resolved

- **Individual cell voltages.** The old `7E7` cell probes were unvalidated and
  removed. The community sheet is **pack-level only** — it contains no per-cell
  voltage PIDs, so cell-level monitoring still has no validated source.
- **Capacity (Ah) and odometer** PIDs were guesses and were dropped; no
  confirmed replacement found in the sheet.

## Next step

Run a **diagnostic scan** (Diagnostics screen, Scan) once with the car on. The
scan now sends `015B` plus the `ATSH7E1` / `ATSH7E4` groups; raw responses land
in `pid_observations` and the scan `raw` blob. Pull the database again and
confirm each PID returns a `41`/`62` response with a sane value. Once confirmed,
promote SOC, pack voltage and pack current to the live polling loop in
`readObdSample()` so the Drive screen shows real battery data and derived kW.
