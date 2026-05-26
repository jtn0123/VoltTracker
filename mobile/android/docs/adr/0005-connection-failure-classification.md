# ADR 0005 — Connection-failure classifier priority for the troubleshooter

- **Status:** Accepted (recorded 2026-05-25; reflects the
  `ConnectionFailureClassifier` shipped in PR #131).
- **Deciders:** Project author.
- **Supersedes:** —
- **Superseded by:** —

## Context

When the OBD adapter fails to connect, the underlying failure can be one of
several distinct root causes that take different remedies. Lumping them all
into a generic "Connection failed" status forces the user to guess which fix
to try; the wrong guess wastes the next 10–15 seconds of retry attempts.

Field-test logs surfaced six recurring failure modes:

| Class             | Symptom in the IOException                                          | Fix the user should try                          |
|-------------------|---------------------------------------------------------------------|--------------------------------------------------|
| `BT_OFF`          | Bluetooth adapter null / disabled                                   | Turn Bluetooth on                                |
| `SDP_FAILURE`     | "service discovery failed" in the message                           | Forget + re-pair the adapter                     |
| `REMOTE_REFUSED`  | "connection refused" — adapter answered, then said no               | Power-cycle the adapter                          |
| `BOND_LOST`       | "permission denied" / "not bonded" / bond-state changed             | Re-pair in Bluetooth settings                    |
| `CONNECT_TIMEOUT` | Phase = `rfcomm_connect`/`get_streams`, or generic timeout message  | Check adapter is plugged in, LED on              |
| `INSTANT_DROP`    | Phase = `first_read`, duration < 500 ms, "read failed / closed"     | Unplug adapter for 10s; OBDLink wedged-bond bug  |
| `UNKNOWN`         | Anything else                                                       | Show generic "couldn't reach the adapter" copy   |

The `INSTANT_DROP` class is the most operationally important one: two
consecutive INSTANT_DROPs in < 500 ms each is the wedged-OBDLink signature,
and the engine's adaptive-retry logic uses it to switch to a long-backoff
schedule (`LONG_BACKOFFS_MS = {8s, 12s}`) that gives the adapter time to
self-recover rather than burning through all 6 retries in 25 s.

## Decision

`ConnectionFailureClassifier.classify(Input)` evaluates the heuristics in this
strict priority order. Earlier matches win — they are MORE specific than later
ones and should not be re-evaluated.

1. **`BT_OFF`** — `input.bluetoothEnabled == false`. Highest priority because
   it's the only class that's true even before we touch the adapter; nothing
   else can be inferred until Bluetooth is on.
2. **`SDP_FAILURE`** — message contains `"service discovery"`. Distinct
   remedy (re-pair) and distinct from refused/bond-lost, so it must be
   classified before those would also match.
3. **`REMOTE_REFUSED`** — message contains `"connection refused"`. Adapter
   answered SDP but rejected RFCOMM; power-cycle is the right fix.
4. **`BOND_LOST`** — message contains any of `"permission denied"`,
   `"not bonded"`, `"bond state"`, `"bond-state"`, `"bond_bonded"`. The
   bond-state phrasing varies by Android version, so the multi-string match
   is intentional.
5. **`CONNECT_TIMEOUT`** — phase is `rfcomm_connect` or `get_streams`. These
   phases are themselves the timeout signal; we don't need to scan the
   message for "timeout".
6. **`INSTANT_DROP`** — phase is `first_read` AND `durationMs <
   INSTANT_DROP_THRESHOLD_MS` (500 ms) AND the message contains
   `"read ret: -1"` / `"socket might closed"` / `"read failed"`. All three
   conditions must hold; just a first-read failure isn't enough, and just a
   short duration isn't enough.
7. **Generic timeout-flavored messages** — `"timeout"` / `"timed out"` /
   `"socket might closed"` / `"socket closed"` → fall back to
   `CONNECT_TIMEOUT` rather than `UNKNOWN` so the dashboard still has useful
   copy to show.
8. **`UNKNOWN`** — everything else.

## Consequences

- The dashboard troubleshooter modal uses the classified value to pick the
  right help copy from `FAILURE_CLASS_COPY` in `troubleshooter.js`. A user
  seeing the wrong copy is worse than a generic message — so when in doubt,
  classify as `UNKNOWN` and let the generic path render.
- Every `reconnect` / `reconnect_exhausted` / `wedged_suspected` JSONL event
  carries `failureClass` so post-hoc log triage can distinguish "adapter
  wedged" from "car asleep" from "stale bond" without re-reading stack
  traces.
- The classifier is **pure** (no I/O, no Android imports, no mutable state)
  so it can be unit-tested on the host JVM. Adding a new heuristic means
  adding a `Test` case to `ConnectionFailureClassifierTest` that pins both
  the new class AND the order — moving an earlier match below a later one
  would silently break existing classifications.
- All message matching is case-insensitive (`Input.of` lowercases on the way
  in) so future Android version changes that recapitalize the messages don't
  silently miss.

## References

- `app/src/main/java/com/volttracker/obdpoc/ConnectionFailureClassifier.java`
- `app/src/test/java/com/volttracker/obdpoc/ConnectionFailureClassifierTest.java`
- `app/src/main/java/com/volttracker/obdpoc/ObdPollingEngine.java`
  (`LONG_BACKOFFS_MS` activation on consecutive INSTANT_DROP)
- `app/src/main/assets/dashboard/js/troubleshooter.js`
  (`FAILURE_CLASS_COPY` — the user-facing mapping)
