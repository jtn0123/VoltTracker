# Gen 2 parity: what's ported, what needs the car, what's left

The target car is a 2017 Volt (gen 2, before the 2019 gateway) with an OBDLink MX+. This page
tracks how the app compares to an established Volt app's gen 2 reads. That app's reads are the
reference for the DIDs and their layouts. Nothing from it is copied; only the facts are used.

Every read added by this work is `Evidence.GUESS` in the virtual Volt until this car answers it.
When a read is confirmed, add the car's layout with a made-up value to `VirtualVoltCatalog`, mark it
`Evidence.REAL`, and add a row to the [discovery tracker](enhanced-discovery-tracker.md) update
log. Logs from the car hold VIN and GPS frames, so they stay off the repo.

## On-car test checklist

The stack is #179 (decode fixes), then #180 (status reads), then #181 (more reads and polish).
Install the top of the stack and run these in one or two sessions.
Most of the checks only need the phone screen; the session log is the backup.

The [October 15 field-run review](field-run-review-2026-10-15.md) records the first target-car
results for this stack: 35 previously unproven request/header pairs answered. It also identifies
three confirmed integration issues (old tire broadcasts overriding newer BCM reads, redundant
tire hunts, and shutdown mistaken for plugged in), plus slow initial Health reads. Charging
transitions, charge-record order, and the twelve Detailed signals candidates remain untested.
Use that review for the measured results; the unchecked items below remain the acceptance list.

### Follow-up fixes and recorded signal history

The field-run follow-up implements F1–F6 from the review:

- Tires choose the newest valid source per wheel, with per-wheel ages. A complete fresh set of
  valid BCM replies also suppresses the long broadcast hunt; ordinary body-bus listening remains active.
- The first body-bus capture while driving is bounded to the regular listen duration. Parked
  captures retain their longer window.
- The classifier uses a fresh dedicated charger state. `010D=FF` alone no longer asserts plugged;
  moving regeneration cannot become a charging state.
- Gear is requested on cycle 1 and every 4 cycles. Capacity, pack resistance and insulation
  are first requested on cycles 4, 6 and 8 respectively, then keep their slow trend refresh.
- Controller voltage skips implausible near-zero shutdown values and tries another responding
  module; adapter and battery-sensor voltage remain separate readings.
- **Health → Live signals → Signal history & CSV** opens saved recordings, even offline.
  Fourteen numeric signals have charts; CSV also includes engine-start, conditioning, charger
  and remote-start status. The viewer lists the latest 50 OBD recordings with retained samples.
  Charts use the selected display units and retain extrema and breaks. CSV streams every
  original sample in fixed units with UTC timestamps and source ages, without GPS/VIN/raw frames.
  Demo charts are isolated from real history and cannot be exported.

The capture supports regression tests; a new drive is still needed to measure actual startup
times and confirm the changed integration on the car. The plugged-in acceptance list still applies.

### Parked, car on (about 5 minutes)

- [ ] Drive shows P in Park. (#179)
- [ ] The session log has a `bcm_tires_first_read` event, and its four pressures match the
      SW-CAN tire frame on the Car tab. The right side should read lower. (#179)
- [ ] Health's thermal card shows a coolant pump speed from `22435A`. (#179)
- [ ] Health's battery card shows "Insulation … kΩ · good". A reading under 400 kΩ on a healthy
      car means the thresholds are wrong, not the car.
- [ ] Health's thermal card shows a conditioning line such as "Not conditioning · battery coolant
      68°F". After the car has sat a while, the coolant should be close to the pack sections.
- [ ] Live signals › 12 V & cabin shows "12 V capacity" with a plausible value in Ah (`22CB31`).
      In the raw log, `ATFCSH241`, `ATFCSD300000` and `ATFCSM1` answer `OK` (not `?`), and
      `ATFCSM0` follows the BCM block.
- [ ] Live signals shows "Last started by: Ignition switch" (`22900D`), and the remote start
      rows from #180.
- [ ] The Car tab's controls show ready, not the low 12 V message, while the 12 V battery is
      healthy. Don't run the battery down to test the guard; the unit tests cover it.

### Drive

- [ ] Drive shows D in Drive and L in Low. (#179)
- [ ] Live signals shows a transmission temperature from 7E1. (#179)
- [ ] Drive in EV until the engine starts, or start with a low battery. Drive's gas row shows the
      reason, and the session log has an `engine_start_reason` event with the same words. (#180)
- [ ] With the engine running, Live signals shows "EGR command" and both EGR cooler
      temperatures. Note whether the cooler temperatures track engine coolant (coolant side) or
      run much hotter (gas side), so the range can be narrowed.
- [ ] Run Detailed signals while driving on battery. `2240D4` should be positive, like `222414`.
      If it reads negative, undo the negation in `ObdVoltMode22ParserRegistry`.

### Plugged in

- [ ] Live signals › Charging shows the charge state, the self-checks and the timetable. (#180)
- [ ] With a delayed charge set in the car, the Charge tab shows "Plugged in · waiting" and "The
      car starts charging …". The time should match the car's own screen.
- [ ] While charging, "Charger input duty" and "Charger output duty" show values.
- [ ] After a full charge, still plugged in, the Charge tab shows "Charge complete".
- [ ] Unplug at the car, reconnect, and check the charge records. Record 1 should be the most
      recent charge ("Unplugged at the car" or "Full charge"). If record 5 changes instead, the
      order is reversed. (#180)

### Detailed signals scan (parked once, then plugged in once)

These candidates are decoded but not polled. Note which answer and whether the values make sense:

- [ ] Energy used `224470`, fuel used `2241AD`, and the EV and gas distance counters `2241C5`
      and `2241C6`. Compare them against a trip, and don't write the absolute values into the
      repo.
- [ ] The BCM's 12 V current, voltage and charge (`2290FB`, `2290B1`, `2290F8`). `2290B1` should
      be within about 0.1 V of the adapter's 12 V reading.
- [ ] Module supply voltage `220042`. It should match the standard module voltage `0142`.
- [ ] Blower speed `22451E`. It should follow the SW-CAN blower %.
- [ ] Plugged in: charger current command `224533`, and pilot voltage and duty (`224545`,
      `224546`).

Each candidate that answers sensibly can move to live polling. Each one that is refused goes in
the tracker's no-hit table.

## What's ported

Decode fixes (#179):

- Gear codes follow the lever (8 P, 7 R, 6 N, 5 D, 3 L).
- Transmission temperature is read on 7E1.
- `2241B2` and `2241B4` are A/C and cabin heater power. The coolant pump speed comes from
  `22435A`.
- BCM tires are read one wheel at a time.

Status reads (#180):

- Engine start reason, with a log event.
- The charger's state, self-checks and timetable.
- How the last five charges ended.
- Battery conditioning and 12 V top-up.
- Pump duties.
- Remote start countdown and starts left.

More reads and polish (#181):

- New reads: remote start reason, 12 V battery capacity, battery coolant temperature, charger
  input and output duty, EGR command and cooler temperatures.
- BCM flow control, so multi-frame body-module replies come back whole.
- Health shows HV insulation, graded against the car's 400 kΩ and 300 kΩ limits, and how the car
  is conditioning the battery.
- The Charge tab speaks for the car's own charger when it's waiting on a schedule, complete or in
  error.
- Wake commands are refused when the 12 V battery reads 11.6 V or less. Remote stop still works.
- `2240D4` reads with the same sign as `222414`.
- Twelve gen 2 candidates are added to Detailed signals.

Already in the app before this work:

- Pack voltage, current and power.
- Raw and displayed SOC, and capacity and health.
- Cell min/max, internal resistance and the six section temperatures.
- Charge count and lifetime charge energy, and the odometer.
- The SW-CAN body bus: locks, doors, windows, tires, climate and the energy screen.
- DTC scan and clear, and freeze frames.
- Lock, unlock, flash, locate, remote start and stop, and windows.

## What's left

These are possible on a 2017 but not done yet:

- **More car controls:** charge limit, charge level (8 A / 12 A on 120 V) with departure time,
  engine hold, trunk release, and cabin preheat with automatic cut-offs. Each one writes to the
  car, so each needs its own on-car check behind the same gate as the current controls. The
  read side they depend on (charge state, timetable) is in now.
- **HVAC over SW-CAN:** setting climate from the app. This is also a write; it goes with the
  controls.
- **Weak-cell load test:** a guided test that loads the pack and watches the cell spread. It
  needs the 96-cell probe confirmed on this car first.
- **Module voltages:** reading the supply voltage at each module to find a weak 12 V feed. Only
  7E4's `220042` is a scan candidate so far.
- **Promotions:** moving scan candidates to live polling, and sim entries from GUESS to REAL, as
  the car confirms them.

## Skipped, and why

- **`2245FF` and `2224AC`:** model-year 2019+ reads, or absent on a 2017.
- **`2245F9` capacity fallback:** doesn't apply to a 2017.
- **Gen 1 reads:** the 7E2 header and the 751 tire module. A gen 2 keeps these elsewhere, and the
  7E2 header never answered on this car.
- **`228005` (keys learned):** immobilizer-adjacent, so it is not read.
- **Security access (mode 27) and anything tied to the reference app's licensing or paid
  features:** out of scope.
- **BCM door, window and lock DIDs:** the SW-CAN broadcasts already give these on this car without
  polling the body module.
- **The reference app's 0.976 scale for `2290B1`:** the app uses plain /1000 until the car shows
  whether the adjustment is needed.
