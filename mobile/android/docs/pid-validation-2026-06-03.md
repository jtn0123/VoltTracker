# PID Validation Notes - 2026-06-03

See also `enhanced-discovery-tracker.md` for the running detailed-signal ledger that should be
updated after each Scan or Detail Probe.

## Confirmed In App

These fields are either standard OBD-II PIDs, parsed by existing tests, or backed by Volt-specific
OBDb test cases and diagnostic scan probes.

- Speed: `010D`
- RPM: `010C`
- Coolant temperature: `0105`
- Engine load: `0104`
- Accelerator pedal: `0149`
- State of charge: `015B`
- Control module voltage: `0142`
- Engine run time: `011F`
- Fuel level: `012F`
- Engine oil temperature: `015C`
- HV pack voltage/current: `222429`, `222414`
- HV battery temperature: `22434F`
- Charger AC voltage/current: `224368`, `224369`
- Charger HV voltage/current: `22436B`, `22436C`
- Charging mode/level: `224373`, `224531`
- HV battery raw/displayed SOC: `2243AF`, `228334`
- HV battery charge count: `2243A5`

`224373` was corrected from "charger HV power" to charging mode after checking OBDb test cases.
Charger power should be derived from `22436B * 22436C / 1000`, not read from `224373`.

## Derived From Existing Data

These do not need new PIDs:

- Pack power: `222429 * 222414 / 1000`
- Drive/regen direction: sign of pack current or pack power
- Drive kWh, regen kWh, and net kWh: integrate signed `powerKw`
- Rolling efficiency: combine integrated `powerKw` with GPS/trip distance
- SOC rate: slope of `soc`/`2243AF` over time
- Poll-loop health: command durations and sample cadence already flow through command logs and
  sample timestamps

## Real-Car Scan - 2026-06-03

Scan session `session-1780532623362-scan.jsonl` on the user's Volt confirmed the app can collect
and persist parsed values for the added battery/charger PIDs.

- Car state during scan: READY, stationary, ICE running (`010C` = `410C14B4`), coolant 56 C,
  fuel about 67%, HV battery temp 32 C, SOC about 14%.
- The pack-current sign showed current flowing into the HV pack (`222414` = `622414FE87`) while
  charger output was zero (`22436B` = `62436B02A0`, `22436C` = `62436C0000`), so this looked like
  engine/generator charging rather than plug charging.
- Confirmed useful responses: `0142`, `011F`, `012F`, `22434F`, `22436B`, `22436C`, `224373`,
  `22437D`, `2243A5`, `2243AF`, `224531`, `222429`, `222414`.
- `228334` responded with `62833400`, but that value did not match the known SOC, so keep it as
  scan-only until another capture proves it useful.

Normal live telemetry now promotes the confirmed slow-context fields on deep polling lanes:

- `controlModuleVoltage`, `engineRunTimeSec`, `fuelLevelPct`
- `hvBatteryRawSoc`, `hvBatteryChargeCount`, `lastChargeEnergyWh`
- `chargingMode`, `chargingLevel`, `chargerHvVoltage`, `chargerHvCurrent`, `chargerPowerKw`

## Tire Pressure Discovery

Tire pressure is not yet live-tracked. The diagnostic scan now probes candidate Chevrolet/Volt
tire-pressure commands without adding them to the live poll loop:

- Header `7E0`: `22248E`, `22248F`, `222490`, `222491`, `22C901`, `22C902`
- Header `760`: `224051`, `224052`, `224053`, `224054`

OBDb marks the `7E0` tire-pressure candidates unsupported in the 2018 Volt test data, so the next
validation step is a real diagnostic scan on the user's car. If any candidate returns a positive
`62...` response with plausible pressure/temperature bytes, add a parser and only then consider
slow-lane live tracking.

The real-car scan rejected the current tire-pressure candidates:

- Header `7E0` candidates returned `7F2231` (`request out of range` for service 22).
- Header `760` candidates returned `NO DATA`.

Do not promote tire pressure to live telemetry until a different header/PID set returns positive
frames.
