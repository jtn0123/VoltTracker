# Language Modernization Plan & Tracker

Living plan for two parallel, independent modernizations:

- **Part A — Kotlin** for the Android app (currently Java).
- **Part B — TypeScript / build** for the dashboard WebView JS.

This file is the single source of truth for *what's planned, what's done, and what's
next*. Update it in the same PR as the work (see [How to update](#how-to-update)).

> Status at a glance — update the counters when a wave item lands.
>
> | Track | Done | In progress | Planned | Notes |
> |---|---:|---:|---:|---|
> | Kotlin files converted | 17 | 0 | ~14 candidates | K0 canary + K1 (constants/enums) + K2 (11 DTOs) |
> | Kotlin waves complete | K0–K2 | — | K3–K4 | K3 (opportunistic) or T1 (strictNullChecks) next |
> | Dashboard JS type-safety | checkJs ✅ | — | strictNullChecks, full TS | `checkJs:true`, whole dir checked |
> | Dashboard build step | none | — | esbuild/vite (proposed) | zero-build today |

**Status legend:** `[ ]` not started · `[~]` in progress · `[x]` done · `[-]` won't do / deferred indefinitely.

---

## Current state (baseline)

- **Android:** 98 Java files (~35k LOC) + 1 Kotlin file (`BuildFlags.kt`). AGP 9.2.1
  built-in Kotlin is enabled; Spotless/ktlint formats `.kt`; JaCoCo measures Kotlin
  (`built_in_kotlinc` output). Policy is documented in `CONTRIBUTING.md` and the repo
  `CLAUDE.md`: **new code is Kotlin; existing Java stays Java.**
- **Dashboard:** 13 first-party JS files (~14k LOC), classic IIFE pattern sharing
  `window.VoltDashboard`, loaded by ordered `<script>` tags. Type-checked by
  `tsc --checkJs` (`checkJs:true`, `noImplicitAny:true`, `strictNullChecks:false`),
  linted by ESLint, tested by Vitest (jsdom) + Playwright (e2e + visual). **No build
  step** — files ship as-is into the APK.

---

## End state (where each language lives)

Kotlin and TypeScript are **not blended** — they live in two separate runtimes that
communicate only across the WebView bridge (a string/JSON ABI, not a code boundary). A
`.kt` file never imports a `.ts` file.

| Layer | Runs | Source: today → end state | Tests: today → end state |
|---|---|---|---|
| **Native app** | on device (APK) | Java → **Kotlin** (new) + Java (legacy) | `app/src/test/java/` Java → **new tests in Kotlin**, existing stay Java |
| **Dashboard** | inside the WebView | JS → **TS** (Wave T2) *or* JS + checkJs/strict | `dashboard-tests/` `.js` → **`.ts` iff source goes TS (T2)** |

**Test-language rule:** tests are authored in whatever language their layer uses.
- Native: **write new tests in Kotlin** (the build already compiles `src/test/**/*.kt`;
  Spotless/ktlint already targets it). Don't rewrite passing Java tests.
- Dashboard: tests stay `.js` at State 0/1; they become `.ts` only if/when the source
  migrates to TS in Wave T2 (Vitest + Playwright run TS natively once esbuild is in play).

---

# Part A — Kotlin adoption

## Why Kotlin (benefits)

- **Null-safety** at the type level — the biggest correctness win for a codebase full
  of nullable OBD/telemetry values.
- **Data classes** — `equals`/`hashCode`/`toString`/`copy`/destructuring for free,
  replacing hand-written value classes.
- **Coroutines** — a cleaner model than the current threads/`Worker`s for the OBD
  polling loops (longer-term, not a near-term wave).
- **Conciseness** — far less boilerplate; sealed classes/`when` exhaustiveness for the
  classifier/state-machine code.
- It's the Android-first language; new SDK/AndroidX guidance assumes it.

## Interop rules (read before converting anything)

These are the facts that make conversions safe/unsafe here:

1. **No Java `record`s exist** — so there's no `field()`→`getField()` accessor trap.
2. **Public-field DTOs need `@JvmField`.** Java callers read `obj.session` directly. A
   Kotlin `val session` exposes `getSession()` instead and would break those callers.
   Annotate each property `@JvmField val session` to keep field access, *or* update the
   call sites to `getSession()`. Pick per file. **Prefer a plain `class` over `data
   class`** for these: the existing DTOs have no `equals`/`hashCode` (identity equality)
   and often normalize/validate in their constructors, so a `data class` would change
   equality semantics and add uncovered generated methods. See the Wave K2 decision note.
3. **Constants holders use `const val`** inside an `object` → compiles to
   `public static final`, so `Foo.BAR` keeps working and stays a compile-time constant
   (branch-folding preserved). This is the `BuildFlags.kt` pattern. `const val` only
   works for primitives + `String`.
4. **Enums convert cleanly.** Java callers (`FailureClass.INSTANT_DROP`, `.wireName()`)
   are unaffected; keep methods as methods (not `@JvmStatic`-needing) for instance
   members.
5. **Coverage:** new Kotlin is measured by JaCoCo and held to the same ratcheting
   floors (project 71% LINE, `data` package 89%). Write tests with the conversion.
6. **Wire formats are load-bearing.** `FailureClass.wireName()` snake-case strings and
   any JSON field names are an ABI with the dashboard — do not rename during a
   conversion. See `docs/bridge-abi.md`.

## Waves (order of attack)

Each wave is independently shippable. Recommended order is K1 → K2, with K3/K4
opportunistic. Tick items as they land and bump the counters at the top.

### Wave K0 — Toolchain + first file ✅ done
- [x] AGP built-in Kotlin enabled, Java 17 / Kotlin JVM 17 pinned (`app/build.gradle`)
- [x] Spotless ktlint lane for `.kt`
- [x] JaCoCo measures Kotlin classes (`app/jacoco.gradle`)
- [x] `BuildFlags.java` → `BuildFlags.kt` (constants-holder canary, 9/9 lines covered)
- [x] Policy documented (`CONTRIBUTING.md`, `CLAUDE.md`)

### Wave K1 — Constants + pure enums (clean, zero call-site churn) ✅ done
Trivial, low-risk, no Java caller changes. All converted with no call-site edits; full
build + tests + lint + coverage green, all five now measured from `.kt` by JaCoCo.

| # | File | Type | Lines | Notes |
|---|---|---|---:|---|
| [x] | `VehicleActivityThresholds.kt` | `object`+`const val` | 22 | `Foo.BAR` static access preserved |
| [x] | `FailureClass.kt` | enum | 73 | `wireName()` ABI strings kept verbatim |
| [x] | `classify/VehicleState.kt` | enum | 40 | `asPayloadKey()` → exhaustive `when` (no `else`) |
| [x] | `classify/Confidence.kt` | enum | 23 | `name.lowercase(Locale.ROOT)` |
| [x] | `materialize/Confidence.kt` | enum | 28 | distinct from `classify/Confidence` — both left in place |

### Wave K2 — Value/DTO classes → plain Kotlin class + `@JvmField` ✅ done
**Decision: plain `class`, not `data class`.** During conversion the original Java proved
this out: 8 of the 11 carry constructor logic (`nonNull`/`clean` normalization, validation,
`toJson()`, overloaded constructors) that a `data class` primary constructor can't express,
and none of the Java originals defined `equals`/`hashCode` — they used identity equality. A
`data class` would have (a) silently switched them to value equality (a behavior change for
any future map/set use) and (b) added generated `equals`/`hashCode`/`toString`/`copy` lines
that risk the `data` package's tight 89% LINE floor. Plain classes with `@JvmField val`
preserve the exact semantics: identity equality, `obj.field` Java access, normalization, and
all methods — and add accurate nullability types. Upgrading a specific class to `data class`
later is a separate, per-class decision after auditing its equality usage.

Result: all 11 converted with **zero call-site changes**; full suite green; `data` package
held at **90.5%** (floor 89%), project **76.7%** (floor 71%).

| # | File | Fields | Notes |
|---|---|---:|---|
| [x] | `data/RecentSessionSummaryRecord.kt` | 3 | ctor widened package-private → public |
| [x] | `classify/ClassifierResult.kt` | 3 | non-null `state`/`confidence` (replaces `requireNonNull`); `reasons` immutable-copied |
| [x] | `materialize/LocationSample.kt` | 5 | boxed cols → nullable |
| [x] | `materialize/MaterializerInput.kt` | 3 | `require(...)` validation preserved |
| [x] | `location/FilteredLocation.kt` | 10 | boxed cols → nullable |
| [x] | `data/ObdSessionRecord.kt` | 10 | `nonNull` normalization via property initializers |
| [x] | `materialize/PidObservation.kt` | 6 | 3 strings null→`""`; `parserKey`/`parsedNumeric` stay nullable |
| [x] | `data/StatusEventRecord.kt` | 8 | `toJson()` method ported |
| [x] | `materialize/TelemetrySample.kt` | 8 | back-compat secondary constructor kept |
| [x] | `data/AdapterHistoryRecord.kt` | 14 | `nonNull` normalization |
| [x] | `EnhancedPidProfile.kt` | 16 | `clean` (trim + null→`""`) via file-private helper |

### Wave K3 — Class-with-nested-enum + mid-size logic (opportunistic)
Convert when you're already reworking these; more surface than K1/K2.

| # | File | Lines | Notes |
|---|---|---:|---|
| [ ] | `SessionStateMachine.java` | 90 | enum + transition logic; `when` exhaustiveness wins |
| [ ] | `location/LocationFilter.java` | 152 | enum + filtering logic |
| [ ] | `data/BackupMigrator.java` | 134 | enum + migration steps; `data` floor |
| [ ] | `PidSchedule.java` | 223 | enum + scheduling; check threading |
| [ ] | `materialize/ChargeSessionMaterializer.java` | 278 | enum + heuristics; `materialize` is well-tested |
| [ ] | `BackupController.java` | 444 | large; only if substantially reworked |

### Wave K4 — Large stateful core (defer; convert only mid-rework) `[-]`
High interop surface (threads, listeners, the WebView bridge). Language-only churn here
is not worth the risk.
- [-] `ObdPollingEngine.java`, `ObdService.java`, `SessionRecorder.java`,
  `ObdProtocol.java`, `MainActivity.java`, `VoltBridge.java`

---

# Part B — TypeScript / dashboard build

## The three states (differences & benefits)

The dashboard can sit at one of three levels of type-safety/tooling. We're at **State 0**
today. Here's what each adds and costs.

| | **State 0 — checkJs (today)** | **State 1 — strictNullChecks** | **State 2 — full TS + bundler** |
|---|---|---|---|
| Source files | `.js` + JSDoc | `.js` + JSDoc | `.ts` |
| Build step | **none** | **none** | **yes** (esbuild/vite) |
| Catches wrong types / bad arity | ✅ | ✅ | ✅ |
| Catches **null/undefined** misuse | ❌ | ✅ | ✅ |
| Real interfaces / enums / generics | awkward (JSDoc) | awkward (JSDoc) | ✅ native |
| ES modules (`import`/`export`) | ❌ (file:// blocks them) | ❌ | ✅ (bundler emits classic IIFE) |
| Source maps for debugging | n/a (ships source) | n/a | ✅ |
| Tree-shake / minify | ❌ | ❌ | ✅ (can *help* the 400 KB budget) |
| HMR / live reload in browser preview | ❌ | ❌ | ✅ (vite dev server) |
| Effort to reach | done | **low** (flip a flag, fix errors) | **high** (migrate source + tests + Gradle + CI) |

**Plain-English summary:**

- **State 0 → 1** is the cheapest real *correctness* win. `strictNullChecks` makes the
  compiler force every possibly-`null`/`undefined` access to be handled. No build, no
  new tooling — just turn it on and clear the errors. This is the recommended immediate
  step (**Wave T1**).
- **State 1 → 2** is an ergonomics/DX leap, not mainly a "catch more bugs" leap (State 1
  already catches the high-value class). You get real modules (kill the global-namespace
  juggling), proper TS syntax, source maps, minification, and **live reload in the
  preview loop**. It costs a build pipeline and a migration (**Wave T2**).

## The file:// constraint (why a build is the *enabler*, not just a cost)

The dashboard is served from `file:///android_asset/` in the WebView.
`<script type="module">` is fetched with CORS semantics that `file://` can't satisfy, so
**ES modules silently never execute on-device** (see the comment block in
`app/src/main/assets/dashboard/index.html` and `docs/dashboard-script-contract.md`).
That's why every file is a self-contained IIFE sharing `window.VoltDashboard`.

Consequence: you **cannot** write `import`/`export` source without a bundler. A bundler
(esbuild/vite) compiles modules down to a **single classic IIFE script** that loads fine
from `file://`. So "TS requires a build" is the same statement as "modules require a
build" — the build is what unlocks both.

**Lazy chunks must stay classic.** `dtc-lookup.js` / `dtc-causes.js` (and `demo-data.js`)
are loaded on demand by injecting a classic `<script>` (`core.js` `loadDashboardScript`).
A bundler's dynamic `import()` code-splitting would emit *module* chunks and reintroduce
the file:// problem. Keep lazy data as **separate IIFE entry points** the app injects the
same way — not auto-split chunks.

## Waves

### Wave T1 — `strictNullChecks` (zero build) — **recommended next**
- [ ] Flip `strictNullChecks: true` in `dashboard-tests/tsconfig.json`
- [ ] Run `npm --prefix dashboard-tests run typecheck`; triage the error list
- [ ] Fix file-by-file (guards, `?.`, JSDoc `@type` narrowing); large files can be
      temporarily excluded and ratcheted in, but **track exclusions here** — no silent skips
- [ ] Update `CONTRIBUTING.md` "Dashboard JS type-checking" once fully on
- [ ] CI already gates `typecheck`, so no workflow change needed

### Wave T2 — Full `.ts` + bundler (proposed; pending go-ahead `[ ]`)
Tooling decision: **esbuild** for the production bundle (fast, trivial config, emits
classic IIFE, supports multiple entry points for the lazy chunks); **vite** layered on
top *only* for the dev server / HMR against the browser preview. Sub-steps:

- [ ] Add `esbuild` to `dashboard-tests` (or a new `dashboard-build/`) toolchain; pin version
- [ ] Configure entry points: one **core** bundle (the 10 ordered scripts) + one per lazy
      chunk (`dtc`, `demo-data`); output classic IIFE with `globalName`/global preservation
      for the `window.VoltDashboard` / `window.VoltTrackerAndroid` / `VoltTrackerNative` ABI
- [ ] Convert `js/*.js` → `src/*.ts` with real `import`/`export`; delete the
      `window.VoltDashboard` namespace shim and `script-order.test.js` ordering once modules
      express the deps
- [ ] Emit source maps; verify they resolve in Playwright + on-device WebView
- [ ] Rewire `generateDashboardHtml` + `index.html` to reference built outputs, and make
      the build a `preBuild` dependency
- [ ] Update the budget gates: `verifyDashboardBundleSize` (root `build.gradle`) and the
      CI bundle-size step measure **built outputs**, not hand-written sources; re-baseline
      the 400 KB / 380 KB budgets against minified output
- [ ] Rework the Vitest harness: `setup/load-dashboard.js`, the `readFileSync('js/..')`
      content tests (`demo-data.test.js`, `csp.test.js`), and `script-order.test.js`
- [ ] Update `docs/dashboard-script-contract.md`, `docs/bundle-budget.md`, and
      `CONTRIBUTING.md`
- [ ] Confirm CSP (`script-src 'self'`) still satisfied by the bundled output

### Wave T3 — On-device live reload (optional, after T2) `[ ]`
- [ ] Debug-build-only hook: point the WebView at the vite dev server
      (`http://10.0.2.2:5173` for the emulator) behind a build flag, so on-device edits
      hot-reload instead of needing a reinstall. Release builds always load file:// assets.

---

## How to update

- **When you finish a wave item:** flip its `[ ]` → `[x]`, add the PR link in Notes, and
  update the at-a-glance counters at the top.
- **When you start one:** mark it `[~]` and put your name/PR so two people don't collide.
- **When you defer/drop one:** mark it `[-]` with a one-line reason.
- **Keep it in the same PR as the code.** This doc drifting from reality is worse than no
  doc. CI doesn't enforce it — treat it like the coverage floors: a discipline, not a gate.
- New conversions should follow the [Interop rules](#interop-rules-read-before-converting-anything);
  if you discover a new gotcha, add it there.

## Decision log

| Date | Decision |
|---|---|
| 2026-06-04 | Enabled AGP built-in Kotlin; `BuildFlags` converted as canary. Policy: new code Kotlin, no bulk migration. |
| 2026-06-04 | Dashboard `checkJs` rollout completed (`checkJs:true`). Next: `strictNullChecks` (T1); full TS+bundler (T2) proposed, pending decision. |
| 2026-06-04 | Wave K1 landed: `VehicleActivityThresholds` + 4 enums (`FailureClass`, `classify/VehicleState`, `classify/Confidence`, `materialize/Confidence`) converted to Kotlin. Zero call-site changes; all gates green. Next: K2 (DTOs) or T1. |
| 2026-06-04 | Wave K2 landed: all 11 DTO/value classes converted. Chose **plain `class` + `@JvmField`** over `data class` (ctor normalization + identity-equality preservation + `data` coverage floor). Zero call-site changes; `data` 90.5%/project 76.7%. Java main files now 82, Kotlin 17. Next: K3 (opportunistic) or T1. |
