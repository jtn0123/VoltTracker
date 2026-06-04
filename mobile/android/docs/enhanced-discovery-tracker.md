# Detailed Signal Discovery Tracker

This file tracks the app's enhanced/detailed OBD discovery work over time. Keep it updated after
each real-car Scan or Detail Probe so a new debugging session can quickly see what worked, what did
not, and what is still only a candidate.

## Status Meanings

- Working: positive response seen, parser exists or the raw value is already useful.
- No hit: probed on the user's car and returned `NO DATA`, `7F...`, or another unusable response.
- Candidate: plausible source exists, but this car has not returned a useful response yet.
- Deferred: likely requires a different collection method, such as a short passive CAN monitor.
- Derived: calculated from fields we already collect; no new PID needed.

## Current Working Signals

| Signal | Source | Polling/Use | Evidence | Notes |
| --- | --- | --- | --- | --- |
| Speed | `010D` | Hot live lane | Standard OBD-II | High-rate dashboard item. |
| RPM | `010C` | Hot live lane | Standard OBD-II | High-rate dashboard item. |
| Coolant temperature | `0105` | Live lane | Standard OBD-II | Useful for ICE/generator behavior. |
| Engine load | `0104` | Live lane | Standard OBD-II | Useful for ICE load context. |
| Accelerator pedal | `0149` | Live lane | Standard OBD-II | Driver-demand context. |
| State of charge | `015B` | Live lane | Standard OBD-II | Generic SOC signal. |
| Control module voltage | `0142` | Slow context lane | Real-car scan positive | Useful 12V/adapter sanity check. |
| Engine run time | `011F` | Slow context lane | Real-car scan positive | Helps split and explain ICE-active periods. |
| Fuel level | `012F` | Slow context lane | Real-car scan positive | Useful for Volt ICE context. |
| Engine oil temperature | `015C`, `221154` | Thermal/detail lane | Standard PID plus `221154` confirmed | `221154` is in enhanced capability cache. |
| HV battery voltage/current | `222429`, `222414` | Slow context lane | Real-car scan positive | Used to derive pack power. |
| HV battery temperature | `22434F` | Slow context lane | Real-car scan positive | Thermal health signal. |
| Charger AC voltage/current | `224368`, `224369` | Scan/detail context | OBDb-backed profile | Useful when plug charging. |
| Charger HV voltage/current | `22436B`, `22436C` | Slow context lane | Real-car scan positive | Used to derive charger power. |
| Charging mode/level | `224373`, `224531` | Slow context lane | Real-car scan positive | `224373` is charging mode, not HV output power. |
| HV raw/displayed SOC | `2243AF`, `228334` | Slow/detail context | `2243AF` positive; `228334` questionable | Keep `228334` scan-only until another capture proves meaning. |
| HV battery charge count | `2243A5` | Slow context lane | Real-car scan positive | Useful long-term battery history signal. |

## Current Derived Signals

| Signal | Inputs | Status | Notes |
| --- | --- | --- | --- |
| Pack power | `222429 * 222414 / 1000` | Derived | Sign shows drive vs regen/charging direction. |
| Drive kWh / regen kWh / net kWh | Integrated signed `powerKw` | Derived | Needs clean sample cadence and trip bounds. |
| Rolling efficiency | Integrated energy plus trip distance | Derived | Best for complete GPS-backed trips. |
| SOC rate | SOC slope over time | Derived | Useful during charge/discharge windows. |
| Poll-loop health | Command timing and sample timestamps | Derived | Already visible through session/debug logs. |

## No-Hit Or Rejected Attempts

| Signal | Header/Command | Result | Evidence | Next Step |
| --- | --- | --- | --- | --- |
| Odometer, optional Mode 01 | `01A6` | No hit | Real car returned `NO DATA` | Do not poll live; try passive CAN idea instead. |
| Engine oil life | `ATSH7E0` / `22119F` | No hit | Real car returned negative response | Try only if a better GM/Volt-specific source appears. |
| Engine oil life selector variant | `ATSH7E0` / `22119F01` | No hit | Real car returned negative response | Same as above. |
| Transmission temperature | `ATSH7E2` / `221940` | No hit | Real car returned `NO DATA` | Do not promote without a positive response. |
| Transmission temperature selector variant | `ATSH7E2` / `22194001` | No hit | Real car returned `NO DATA` | Do not promote without a positive response. |
| Tire pressure wheel candidates | `ATSH7E0` / `22248E`, `22248F`, `222490`, `222491`, `22C901`, `22C902` | No hit | Real car returned `7F2231` | Look for BCM/receiver-oriented sources before probing again. |
| Tire receiver slot candidates | `ATSH760` / `224051`-`224054` | No hit | Real car returned `NO DATA` | Keep cached unsupported rows; skip repeated probes unless catalog changes. |

## Deferred Or Candidate Ideas

| Signal | Candidate Source | Status | Safe Validation Plan |
| --- | --- | --- | --- |
| Odometer | Passive CAN frame `0x120` | Deferred | Add a short passive monitor path; never send broad requests just for odometer. |
| Tire pressure | Alternate BCM/RCDLR headers or passive receiver frames | Candidate | Research exact Volt module/header first; run a narrow Detail Probe only. |
| Battery coolant pump RPM | GM enhanced battery thermal PIDs | Candidate | Add to catalog only with a source and diagnostic-only first. |
| Battery coolant valve state | GM enhanced battery thermal PIDs | Candidate | Same as pump RPM; record raw frames before parsing. |
| Outside temperature | Standard/GM body signal | Candidate | Prefer standard or already-broadcast source if available. |
| Intake air temperature | `010F` standard OBD-II | Candidate | Low-risk standard PID; add to warm lane if useful. |
| Torque | GM/SAE calculated engine or motor torque PID | Candidate | Needs clear source; likely diagnostic-only first. |
| EV miles/km | Vehicle lifetime/trip counters | Candidate | Prefer derived trip distance unless a real odometer/EV counter is proven. |
| Cell group voltages | GM battery module enhanced PIDs | Candidate | High value, but only probe with exact source and low cadence. |

## Update Log

| Date | App/Build | Test | Result |
| --- | --- | --- | --- |
| 2026-06-03 | `0.6.2-dev` | Broad Scan on user's Volt | Battery/charger/SOC fields positive; tire-pressure candidates negative. |
| 2026-06-03 | `0.6.2-dev` | Detail Probe UI and persisted capability panel | App now shows Working/No hit/Candidate/Deferred counts from SQLite `field_capabilities`. |

## After Each Scan

1. Export or inspect the debug log/session.
2. Add a row to `Update Log`.
3. Move each probed item into Working, No-Hit, Candidate, or Deferred.
4. Only promote a signal to live polling after a positive response and a parser sanity check.
