# ADR 0004 — Charge-detection heuristic priority for ChargeSessionMaterializer

- **Status:** Accepted (recorded 2026-05-25; reflects the "smarter charge detection"
  shipped in PR #130).
- **Deciders:** Project author.
- **Supersedes:** —
- **Superseded by:** —

## Context

The Android app needs to identify "the car was plugged in and charging" windows
inside a recorded session so the dashboard's Charge tab can render a session
list, the Insights tab can compute kWh totals, and Trips can be cleanly
separated from non-driving time.

The materializer has two classes of signal available per telemetry sample, both
nullable:

1. **`adapterVoltage`** (PID `ATRV` — the 12 V auxiliary battery, **not** the HV
   pack). When the car is plugged in and the on-board charger is awake, the
   DC-DC converter holds the aux at ~14.0–14.5 V; when parked unplugged it
   floats at ~12.2–12.4 V. The 14 V threshold is the legacy heuristic — cheap
   and works with any ELM327 adapter, but noisy: a recently-driven car with a
   warm alternator will also read 14 V for the first few minutes after
   shutdown.
2. **`packCurrentA`** (PID `222414` — the HV pack ammeter, Volt-specific). The
   Volt's sign convention: **discharge is positive, charge is negative.** A
   sustained negative current at zero speed is the high-confidence signal that
   energy is flowing into the pack from an external source. Requires a Volt and
   a mode-22-capable adapter.

The pre-PR-#130 implementation looked only at `adapterVoltage > 13.5 V` and
`speedKph == 0`. That bucket captured real charge sessions but also produced
~10% false positives in field-test data (a post-drive cool-down window with
high aux voltage + stationary car).

## Decision

ChargeSessionMaterializer consults the available signals in this priority
order:

1. **Pack current dominates.** If `packCurrentA` is present on any sample in
   the window:
   - `packCurrentA < 0` AND `speedKph == 0` → **plugged** with `OBSERVED`
     confidence. Ignore `adapterVoltage` even if it disagrees.
   - `packCurrentA >= 0` (discharging or at rest) AND `speedKph == 0` → **not
     plugged**, even if `adapterVoltage > 13.5 V`. Suppresses the aux-voltage
     false positive.
2. **Aux voltage as fallback.** If `packCurrentA` is absent for the whole
   window (e.g. adapter doesn't support mode-22), the legacy heuristic
   applies: `adapterVoltage > 13.5 V` AND `speedKph == 0` → **plugged** with
   `WEAK` confidence.
3. **Null speed never infers plugged.** If `speedKph` is `null` (adapter
   dropped the column) even with high voltage, the window is rejected. The car
   could be moving with a warm alternator and we can't tell.
4. **Splitting:** windows with a continuous-plug signal longer than
   `SPLIT_GAP_MS = 30 min` are split into separate sessions. Gaps shorter than
   that are counted as `interruptionCount` inside one session.
5. **Transient-break debounce (added 2026-06-13).** A movement/discharge sample
   no longer splits an active charge on its own. A break only splits once it is
   *sustained* for `BREAK_DEBOUNCE_MS = 60 s`; a shorter break that resumes
   charging is folded back in as an `interruptionCount`. This was the main cause
   of over-counting — a single glitchy speed/power sample mid-charge logged one
   physical charge as several sessions. A genuinely sustained drive still splits,
   so a drive is never stitched into a charge.
6. **Minimum sample count (added 2026-06-13).** A finalized window needs at least
   `MIN_SAMPLES = 3` plugged samples. Real charges stream hundreds of samples;
   this rejects the sparse two-sample windows a transient break can leave behind
   (which a duration-only check would otherwise accept as a tiny "charge").

## Consequences

- **Volt + mode-22 adapter (the target)** gets `OBSERVED`-confidence sessions
  and the aux-voltage false positive is eliminated.
- **Other vehicles / cheap adapters** still get sessions via the aux-voltage
  fallback, marked `WEAK` so the Insights tab can treat them with appropriate
  uncertainty (or hide them entirely).
- **The null-speed rule** is a deliberate sensitivity reduction: it would be
  trivial to assume "no speed = stationary," but doing so in field-test data
  surfaced a phantom 4-hour "charge session" during a highway drive where the
  speed PID had stopped responding while the alternator stayed up.
- Threshold values (`13.5 V` aux, `< 0` pack current, `30 min` split gap,
  `> 5 min` interruption-vs-split boundary, `60 s` break debounce, `3`-sample
  minimum) are pinned in `ChargeSessionMaterializer.Tunables` with regression
  tests in `ChargeSessionMaterializerTest`. Round-6 added single-sample,
  all-zero, and all-null cases for the conservative rejection paths; the
  2026-06-13 over-counting fix added transient-break, sustained-drive-split, and
  sparse-window cases.

## References

- `app/src/main/kotlin/com/volttracker/obdpoc/materialize/ChargeSessionMaterializer.kt`
- `app/src/test/java/com/volttracker/obdpoc/materialize/ChargeSessionMaterializerTest.kt`
- `docs/volt-pid-research-2026-05-20.md` (PID `222414` decode + sign convention)
- `docs/field-test-2026-05-19.md` (capture session that surfaced the
  aux-voltage false positive)
