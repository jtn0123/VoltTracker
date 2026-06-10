# Sensor expansion plan: new Volt PIDs to detect and track (2026-06-09)

## Why this exists

The app already polls a solid core set (speed/RPM/pedal, pack V/I/SOC, one battery
temp, charger AC/HV, motor A/B, thermals, fuel, oil). This document is a
**hand-off spec for an agent (or human)** to expand coverage: it explains the exact
mechanical recipe for adding a new sensor to the codebase, then lists researched
candidate PIDs — with headers, decode formulas, confidence, and sources — ordered by
value. It also records which metrics are **not** reachable over OBD so nobody wastes
time chasing them.

Research sources: OVMS `vehicle_voltampera.cpp` (working Gen 1 Volt code), the
community Volt PID sheet (`docs/volt-pids-community-sheet.csv`, Gen 1), the Bolt
extended PID list (allev.info/boltpids — same BECM family as Gen 2 Volt), iternio
`ev-obd-pids`, and gm-volt.com threads (327403, 45097, 333039, 304169). Per-row
citations below.

**Read first:** `volt-pid-research-2026-05-20.md` (decode conventions, prior
mistakes) and `enhanced-discovery-tracker.md` (what already failed on this car —
do not re-propose those without a new header/source).

## Ground rules (do not skip)

1. **Every new PID starts as `STATUS_CANDIDATE` with `pollLane = "diagnostic_only"`.**
   It is only probed during a Scan/Detail Probe, never live-polled, until the real
   car returns a positive `62xxxx` frame AND the decoded value passes a sanity check.
2. **Default diagnostic session only.** OVMS polls everything below (including 7E7
   cell voltages) with plain `22xxxx` ReadDataByIdentifier in the default session.
   **Never send `10 03` (extended session)** — one third-party profile uses it but
   Torque users confirm it is unnecessary, and it can suppress normal module behavior.
3. **A valid mode-22 command is exactly 6 hex digits** (`22` + 2-byte DID). The
   2026-05-20 research pass found 7-digit garbage commands; don't repeat that.
4. Promotion to live polling is **manual** (see recipe step 6) — there is no
   automatic promotion in the code.
5. After any real-car probe run, update `enhanced-discovery-tracker.md` (Update Log
   + move rows between Working/No-hit/Candidate).
6. Most Gen 2 rows below are inherited from the **Bolt** BECM list. Confirmed on an
   actual Volt: `2241A3`, `22434F`, `228334`, `222414`/`222429`, the charger block.
   Everything else is "unverified on Volt" until the Scan says otherwise.

## ECU header map (request → response)

| Request header | Responds | Module | Examples |
|---|---|---|---|
| `ATSH7E0` | 7E8 | ECM | oil temp `221154`, control-module V `220042` |
| `ATSH7E1` | 7E9 | HPCM (hybrid powertrain) | pack V `222429`, pack I `222414`, MGA/MGB, PRNDL `222889`, brake torque `22242C` |
| `ATSH7E2` | 7EA | TCM | trans temp `221940` (no-hit on this car) |
| `ATSH7E4` | 7EC | HPCM2/BECM summary + OBCM charger + thermal | `22434F`, charger block, capacity `2241A3` |
| `ATSH7E6` | 7EE | Brake module (documented on Bolt) | brake pedal position `224501`/`224502` |
| `ATSH7E7` | 7EF | BECM cell-level interface | individual cell voltages, 6 section temps |

`ATSH7E7` is **new to this app** — `PidSchedule.Header` has no entry for it yet
(add `CELL_BECM_7E7("ATSH7E7")`).

---

## The recipe: how to add one new sensor

Six files, in this order. Pattern-match existing entries in each.

### Step 1 — catalog the candidate

`app/src/main/kotlin/com/volttracker/obdpoc/EnhancedPidProfiles.kt` (init block):

```kotlin
add(
    profiles,
    "battery.capacity_ah.2241a3",        // unique key: category.metric.command
    "battery",                           // category
    HS_CAN,
    "ATSH7E4",                           // header
    "2241A3",                            // 6-hex-digit mode-22 command
    "HV battery capacity",               // human name
    "Ah",                                // unit
    "diagnostic_only",                   // pollLane — ALWAYS this at first
    STAGE_EXPERIMENTAL,                  // scanStage
    "low",                               // risk: safe|low|medium
    RETRY_REJECTED_MS,
    STATUS_CANDIDATE,                    // ALWAYS candidate at first
    "OVMS vehicle_voltampera.cpp; gm-volt thread 333039; Bolt list", // source
    "Confirmed returning data on 2018 Volt (raw 517 = 51.7 Ah). Promote after positive 62 frame + sanity 30-60 Ah.",
)
```

### Step 2 — add it to a probe array

`app/src/main/kotlin/com/volttracker/obdpoc/ObdProbes.kt`: append the command to the
matching header array (`VOLT_7E4_PROBES`, etc.), or create a new array + scan wiring
for a new header (7E7 needs this). The Scan/Detail Probe will then exercise it and
persist the result to the `field_capabilities` table via
`ObdStoreWriter.recordPidObservation()` — no extra code needed for that part.

### Step 3 — add the decode formula

`app/src/main/kotlin/com/volttracker/obdpoc/ObdProtocol.kt` → `parseKnownValue()`.
Helpers already exist:

```kotlin
// unsigned/signed word with divisor:
"2241A3" -> return voltWordValue(response, cleanCommand, 10.0, false)?.let {
    value("HV battery capacity", it, "Ah", 1)
}
// single byte with scale+offset (temps are usually A-40):
"224349" -> return voltByteValue(response, cleanCommand, 1.0, -40.0)?.let {
    value("HV battery max temp", it, "C", 0)
}
```

### Step 4 — unit-test the decode

`app/src/test/java/com/volttracker/obdpoc/ObdProtocolTest.kt`: one test per formula
with a hand-built `62xxxx...` frame and the expected value (e.g. `6241A30205` →
517/10 = 51.7 Ah). Also extend `EnhancedPidProfilesTest.kt` if you added a category.
JaCoCo coverage gates run in `./gradlew :app:check` — new code must be tested.

### Step 5 — probe on the real car (the gate)

Run a Scan/Detail Probe with the phone in the car. Check the Diagnostics capability
panel (backed by `field_capabilities`) or pull the DB. A metric passes when:
positive `62`+PID frame seen, AND the decoded value is physically plausible
(capacity 30–60 Ah, cell voltage 3.0–4.2 V, temps -30–60 °C, pressures 20–50 psi).
Record the outcome in `enhanced-discovery-tracker.md`.

### Step 6 — promote (only after step 5 passes)

1. `EnhancedPidProfiles.kt`: `STATUS_CANDIDATE` → `STATUS_CONFIRMED`, pick a real
   `pollLane`.
2. `PidSchedule.kt` → `SPECS`: add `PidSpec(command, Header.HV_PACK_7E4, periodCycles, phaseOffset)`.
   Cadence guide: drive-critical 1–2, state 6, thermal 12, slow context 24–48,
   once-per-drive 120–240. Pick a `phaseOffset` that doesn't collide with
   same-period specs on the same header.
3. `LiveSampleReader.kt`: `putNumeric(sample, "capacityAh", "2241A3", 1)` in the
   matching `appendXxxFields()` — the field name becomes the telemetry-JSON key.

### Step 7 — dashboard

Edit TypeScript sources in `app/src/main/dashboard-src/js/` (never the generated
bundle), then `npm --prefix dashboard-tests run build`. The telemetry sample JSON
already flows to the WebView; read `latest.<fieldName>`. Note `sohPct` and
`capacityAh` slots **already exist** (`storage-status.ts` `renderPackStats()` ~line
707, `map.ts` ~line 859) — feeding those keys lights them up with no UI work.

---

## Batch 1 — highest value, best evidence (do these first)

### 1a. Battery capacity + derived SOH ★ the headline feature

| Command | Header | Decode | Unit | Confidence | Source |
|---|---|---|---|---|---|
| `2241A3` | ATSH7E4 | `(A*256+B)/10` | Ah | **High — confirmed on 2018 Volt** (raw 517 → 51.7 Ah ≈ 18.4 kWh / 355 V) | OVMS; gm-volt 333039; Bolt list |
| `2245F9` | ATSH7E4 | `(A*256+B)/100` | Ah | Low for Volt (Bolt 2019+ moved here; probe as fallback) | Bolt list; iternio bolt19.json |

Derived (no PID, pure app code): `sohPct = capacityAh / 52.0 * 100` (Gen 2 nominal
~52 Ah new), `usableKwh = capacityAh * packVoltage / 1000`. Log capacity
once-per-drive into telemetry → the long-term **degradation trend chart** is the
payoff. There is **no direct SOH % PID** on Volt or Bolt; derivation is the answer.

### 1b. Pack health: min/max cell voltage + imbalance

All ATSH7E4, all from the Bolt list (same BECM family) — Medium confidence,
unverified on Volt:

| Command | Meaning | Decode | Unit |
|---|---|---|---|
| `224329` | min cell voltage | `(A*256+B)/1666.666` | V |
| `22432A` | min cell number | `A` | 1–96 |
| `22432B` | max cell voltage | `(A*256+B)/1666.666` | V |
| `22432C` | max cell number | `A` | 1–96 |
| `22435F` | SOC variation (balance proxy) | `A/2.55` | % |
| `2240E9` | pack resistance | `(A*256+B)/2` | Ω |
| `22433B` / `22433C` | pack min/max voltage | `(A*256+B)*0.52` | V |

Derived: `cellDeltaMv = (max - min) * 1000` — the single best pack-health signal
(healthy pack at rest: < ~50 mV).

### 1c. Multiple battery temps (min/max spread)

| Command | Header | Meaning | Decode | Confidence |
|---|---|---|---|---|
| `224349` / `22434A` | ATSH7E4 | max / min pack temp | `A-40` °C | Medium (Bolt) |
| `22434B` / `22434C` | ATSH7E4 | module # of max / min | `A` | Medium (Bolt) |
| `2240D7,2240D9,2240DB,2240DD,2240DF,2240E1` | **ATSH7E7** | section temps 1–6 | `A-40` °C | High (working OVMS Gen 1 code; identical on Bolt) |

Derived: pack temp spread `max - min` (thermal-health indicator).

### 1d. Power-electronics / motor temps

| Command | Header | Meaning | Decode | Confidence |
|---|---|---|---|---|
| `221C43` | ATSH7E4 | power-electronics coolant loop temp | `A-40` °C | **High** (Volt sheet + OVMS + Bolt all agree) |
| `221C26` / `221C28` / `221C2A` | ATSH7E1 | inverter temp sensors 1–3 | `A-40` °C | Medium (Bolt) |
| `2228CB` | ATSH7E1 | motor temperature | `A-40` °C | Medium (Bolt; single motor — MGA/MGB split unknown on Volt) |

## Batch 2 — fills real gaps, decent evidence

### 2a. HVAC electrical power (winter range-loss explainer) — ⚠ conflict to resolve first

The Bolt list maps `2241B1`/`2241B2` = AC compressor commanded/measured W and
`2241B3`/`2241B4` = cabin heater commanded/measured W (signed). **But the Gen 1
Volt sheet maps `2241B2` = battery coolant pump RPM and `2241B4` = coolant valve —
and this app already uses them that way** (`thermal.battery_coolant_pump_rpm` notes
aside, check what `2241B2`/`2241B4` decode as today). The `41Bx` block appears
remapped between platforms.

**Validation plan:** probe with HVAC forced on/off. If `2241B2` tracks compressor
duty (0 → thousands of W when AC max) it's the Bolt mapping; if it tracks pump RPM
it's the Gen 1 mapping. Do not ship either interpretation until this test is done.
Alternates: compressor speed/power `2282B5`/`2282B7` (W/RPM, Bolt), Bolt's battery
coolant pump is `22435A` `A*25` RPM @7E4 (already a candidate in the catalog).
`2241B5`/`2241B6` battery heater commanded/measured W is consistent across both
sources (High confidence).

### 2b. 12V system (real battery, not adapter proxy)

| Command | Header | Meaning | Decode | Confidence |
|---|---|---|---|---|
| `221141` | ATSH7E1 | ignition / 12V voltage | `A/10` V | Medium (Bolt) |
| `2241B0` | ATSH7E4 | APM (DC-DC) output power | `(A*256+B)/16` W | Medium (Bolt) |
| `221C47` | ATSH7E0 | 14V setpoint voltage | `A/10` V | Medium (Bolt) |
| `22437E` | ATSH7E4 | APM output current | `signed(A*256+B)/20` A | Low-Medium (Gen 1 sheet only) |

No PID exists for actual 12V *battery* current — the sensor exists on the car but
no identifier has been published.

### 2c. HV isolation resistance (safety; ties to P1AF0 DTC docs)

| Command | Header | Decode | Confidence |
|---|---|---|---|
| `2243A6` | ATSH7E4 | `A*25` kΩ | Medium (Bolt only) |
| `2241EC` | ATSH7E4 | `(A*256+B)` Ω | Low-Medium (Bolt only) |

### 2d. Brake pedal / regen context

| Command | Header | Meaning | Decode | Confidence |
|---|---|---|---|---|
| `22242C` | ATSH7E1 | brake torque demand | `signed(A*256+B)/4` Nm (Gen 1 sheet; Bolt says `/2` — validate scale on car) | Medium; a Gen 2 owner confirms it responds |
| `224501` / `224502` | **ATSH7E6** | brake pedal position 1/2 | `signed(A*256+B)/100` mm | Medium (Bolt; new header) |
| `2224B0` | ATSH7E1 | regen braking active | `A>7` bool | Low-Medium (Bolt) |

Note: `222434` ("BRAKE", Gen 1) returns constant 0 on Gen 2 — do not add.
True regen-vs-friction torque split is **not available** via OBD.

### 2e. Misc cheap probes

| Command | Header | Meaning | Decode | Confidence |
|---|---|---|---|---|
| `2241A4` | ATSH7E4 | battery coolant temp | `A-40` °C | Medium (Bolt) |
| `22433F` | ATSH7E4 | minimum SOC limit (possible Hold/Mountain proxy) | `A/2.55` % | Low — probe and watch while toggling Mountain mode |
| `0132` | broadcast | EVAP vapor pressure (sealed-tank proxy) | SAE standard | Worth one probe; Volt-specific tank PID not published |
| `2243AF` | ATSH7E4 | HD raw SOC 16-bit (already positive on this car) | `(A*256+B)*100/65535` % | High — already in catalog, ensure decode matches |

## Batch 3 — ambitious: full 96-cell map (new header ATSH7E7)

OVMS reads individual cells on Gen 1 with a **non-contiguous** PID layout; Bolt is
contiguous. Probe both layouts on the Gen 2 car (a handful of probes each, not all
96, to determine which layout answers):

| Layout | Commands | Decode | Confidence |
|---|---|---|---|
| Gen 1 (OVMS, working code) | cells 1–31: `224181`–`22419F`; cells 32–96: `224200`–`224240` | `(A*256+B)*5/65535` V | High for Gen 1 |
| Bolt (contiguous) | cells 1–96: `224181`–`2241E0` | same | High for Bolt |
| Avg cell voltage | `22C218` @7E7 | same | Medium (Bolt) |
| HD pack current | `2240D4` @7E7 | `signed(A*256+B)/20` A | Medium (Bolt) |

Both generations have **96 series cell groups**. Cell polling should be a dedicated
low-cadence "battery snapshot" pass (e.g. once per charge session), not the live
loop — 96 sequential requests take seconds. The empty `cell_snapshots` /
`battery_snapshots` DB tables look purpose-built for this.

## TPMS — already rejected once; needs a different angle

The 2026-06-03 real-car scan returned `7F2231` for `22248E/F`, `222490/91`,
`22C901/02` @7E0 and `NO DATA` for `224051`–`224054` @760 (see
`enhanced-discovery-tracker.md`). **Do not re-probe those.** Remaining leads, all
weak:

1. Try the wheel PIDs (`22248E`–`222491`, `A` psi) on **other headers** — the GM
   truck source uses 29-bit extended addressing (`6C A1 F1`), which needs
   `ATSP7`-style config the app doesn't do today; the Volt RCDLR/BCM header for
   D2XX is unpublished. Research the BCM diag address first (ATSH 241/641 is BCM
   on GMLAN for device control).
2. Passive CAN monitor for TPMS broadcast frames (pairs with the deferred odometer
   `0x120` idea; needs a CAN-sniffing-capable adapter such as OBDLink MX+).
3. No tire-temperature PID is documented anywhere for the Volt.

## Confirmed dead ends — do not chase over OBD

| Wanted metric | Finding |
|---|---|
| Engine Maintenance Mode countdown | No PID published anywhere (gm-volt 289481 is behavior-only) |
| Fuel age / Fuel Maintenance Mode | Readable by GDS2/Tech2 only; identifier never published (gm-volt 238889) |
| Lifetime EV miles / fuel / kWh counters | Cluster/OnStar-internal, not OBD-exposed (gm-volt 304169). Use trip EV distance `222487` @7E1 `(A*256+B)/100` km + own accumulation |
| Drive mode (Normal/Sport/Mountain/Hold) | No PID; community infers from trip-EV-km behavior. `22433F` min-SOC-limit is the only testable lead |
| Regen vs friction torque split | Explicitly asked on gm-volt; nobody has a PID |
| Hilltop/target-SOC setting | No direct PID; infer from charge-termination SOC |

## Derived metrics needing no new PID (free wins)

- **Capacity-fade trend**: log `2241A3` once per drive → chart over months (the
  core "tracker" feature).
- **SOH %**: `capacityAh / 52.0` (Gen 2 nominal). Feeds the existing `sohPct`
  dashboard slot immediately.
- **Cell imbalance trend**: delta mV at rest, per session (after Batch 1b).
- **Charge-curve** (power vs SOC) and per-session energy: from already-live
  `packPowerKw` + SOC.
- 2016+ usable-window constants used by the community for efficiency math:
  14.2 kWh window, SOC 19–89 %.

## Suggested execution order

1. **Batch 1a** end-to-end (catalog → probe → decode → promote → `capacityAh`/`sohPct`
   on dashboard). Smallest scope, biggest payoff, only High-confidence row.
2. **Batch 1b + 1c** as one probe wave (single 7E4 pass + new 7E7 header support).
3. **Batch 1d + 2e** cheap probes piggybacking the same scan.
4. **2a HVAC** only after the `41Bx` conflict experiment.
5. **Batch 3** cell map as its own feature (snapshot pass + heatmap UI).
6. TPMS passive-CAN research last — weakest leads, hardware-dependent.

Steps 1–3 can be coded in one PR each: every PID lands as a candidate (zero risk to
the live loop), and a single real-car Scan validates the whole wave at once.
