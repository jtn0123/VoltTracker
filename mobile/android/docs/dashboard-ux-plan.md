# Dashboard UX plan — 2026-07-04

Outcome of the design/UX deep dive (screens driven live via Demo / Testing at a
Pixel-class viewport plus the committed visual baselines). Companion to
`code-audit-findings-2026-07-04.md`, which tracks the non-UI findings.

Design grades at time of review: visual design **C+**, information
architecture **C**, first-run/empty states **B-**, data-viz **B-**
(frontend *engineering* remains **A** — the gap is presentation, not plumbing).

## Root-cause diagnosis

The app reads as its own debug console. Five systemic causes:

1. **One card pattern wallpapers everything** — kicker + big value + caption in
   identical chrome for vehicle speed and for trivia like sample counts, so
   nothing reads as important.
2. **The dead state is the default texture** — pre-connect, up to seven
   placeholder vocalizations were visible above the fold on Drive.
3. **Internal ontology leaks into user copy** — "Live validation 2/5 ok",
   "Possible plugged-in transitions", "91 PTS", "DB 5738 r…".
4. **No unified control language** — four button styles in one DTC row; orange
   pill / blue circle / blue outline all meaning "active"; native blue
   checkboxes against an orange palette.
5. **Insights is a kitchen sink** — battery latest + lifetime + vehicle card +
   full DTC scanner + scatter + maintenance in one scroll, while Diag hosts the
   probe engineering panel.

## Shipped in this pass (branch `claude/app-grading-feedback-xh3cw4`)

- First-run onboarding card moved to the top of Drive; the session/health/
  overview dead tiles collapse behind a page-level `is-prelive` state until the
  first sample ever exists (`drive.ts#renderDriveSourceBadge`, `screens.css`).
- Unknown view names fall back to Drive instead of a blank page
  (`core.ts#setView`).
- Removed the duplicate "Stop demo" button in Settings (the morphing Connect
  button is the single control; the banner is informational).
- Speed hero: units now hug the digits; removed the fixed-position radial halo
  that read as a stray orange smudge at 1–2 digit readings.
- RPM tile collapses while the range extender is off instead of pinning a
  permanent "0" (`telemetry.ts`).
- Copy: "Possible plugged-in transitions" → "Times charging likely started";
  DTC search links → "Search the web".
- Native checkboxes/radios tinted with the app accent (`accent-color`).
- Deleted 7 stale `trips-*` visual baselines (the Trips tab was folded into
  Insights/Map; the snapshots no longer corresponded to any test).

**X1 (shipped):** chromeless state-reactive Drive hero — one `--hero-accent`
keyed off `data-power-state` tints the speed trace, power readout, state label,
and meter fill together (orange drive / green regen / neutral coast); adaptive
±40→±80→±120 kW power track with session-sticky ratchet.

**X2 (shipped):** `--tab-accent` token system on `body[data-active-view]`
replaces five hand-copied nav-active blocks plus the one-off map/insights
actives — and fixed the Diag tab active, which still targeted the removed
"signals" view name and fell back to orange.

**X3 (shipped, scoped):** DTC scanner moved from Insights to Diagnostics
(markup + ids verbatim; renderers address ids, not views) with a saved-codes
badge on the Diag nav icon; Insights leads with trends and the vehicle card
moved to the end. Deferred: relocating vehicle+maintenance to a "Car" block
outside Insights — it needs `loadInsights` data-flow rework, tracked below.

**X4 (shipped, scoped):** the Drive session counters and health checklist
dropped their card chrome (footnote tier). A full three-tier re-tag of every
surface remains open.

**X5 (started):** drive chart empty-state copy migrated into the typed i18n
catalog; an `i18n-ratchet` vitest pins the catalog as grow-only with no dead
keys; "Live validation" → "Data health"; Settings DB chip renders compact
counts ("5.7k rows") via `VD.formatRowCount`. The full copy sweep continues
module by module behind the ratchet.

## X-factor proposals (bigger swings, in recommended order)

### X1. Build the identity outward from the live Drive hero
The live state (big white digits, glowing power wave, orange kW readout with
DRIVE/COAST direction) is the app's best screen and should define the whole
product's look. Concretely:
- Let the hero bleed: drop the card border/background around speed+power so
  the top third of Drive is gauge, not card. Secondary readouts become a
  chromeless label:value strip under it.
- Make the hero state-reactive: the power trace/readout already knows
  drive/coast/regen — tint the hero's single accent (orange drive, green
  regen, neutral coast) so the screen *feels* like the car. One accent at a
  time, driven by `vehicleState`, replaces today's static per-widget colors.
- Adaptive power meter bounds (±40 kW city typical, expand to ±120 only when
  exceeded) instead of the fixed −80/+80 that wastes bar resolution.

### X2. Commit to the per-tab accent system — or collapse it
Drive=orange, Map=blue, Charge=green, Insights=purple can become a signature
if applied *consistently*: nav icon, active pill, header accent, hero tint,
and chart primary all shift together per tab, from one CSS custom property
(`--tab-accent`) set on `body[data-active-view]`. Everything else uses the
neutral scale. Delete the remaining one-off active styles (blue EFF circle,
blue chip outline) in favor of one active-state shape.

### X3. Two-tier information architecture
- Insights = trends only (efficiency chart, lifetime totals, savings).
- DTC scanner moves to Diag, which gets a user-facing top half (codes, scan,
  clear) and a collapsed "Engineering" bottom half (probes, live validation,
  debug logs). Nav badge on Diag when codes exist.
- Vehicle card + maintenance become a compact "Car" block (Settings top or
  Charge bottom).
- Kill the remaining `setView("trips")` references if any linger (fallback now
  covers them).

### X4. Hierarchy pass on cards
Adopt three explicit tiers and re-tag every surface: hero (no chrome),
standard card (current panel), footnote row (no border, muted, single line —
sample counts, runtime, PIDs). Rule: if a value doesn't change a driving or
charging decision, it doesn't get a card.

### X5. Copy system
One pass over all user-facing strings with the rule "describe the car, not
the pipeline", routed through the existing typed `i18n.ts` catalog (it's
already built — only ~7 strings use it). Ratchet-test literal counts in the
highest-churn modules so new copy lands in the catalog.

## Deferred smaller items (validated, not yet done)

- Route chip strips clip mid-word with no scroll affordance (Map/Trips
  pickers) — add an edge-fade or next-chip peek.
- Prose paragraphs inside Charge stat cards → move explainers behind an ⓘ
  affordance; empty-state cards should be one line + one action.
- "DB 5738 r…" truncation in the Settings state grid — allow the value to
  wrap or shorten to "5.7k rows".
- Dual mph/km/h always both shown — show preferred unit only, conversion on
  tap (prefs already track the unit).
- Em-dash/hyphen inconsistency in generated headlines ("- about 5.7 mi/kWh").
- Map attribution overlaps the efficiency legend row on narrow widths.
- Header status line can truncate ("60 samples · …") — drop the sample count
  from the header (it lives in the session card).

## Validation notes

- The status toast is *not* a mid-screen popup — it's a bottom snackbar with a
  3.2 s auto-dismiss; full-page screenshots just freeze it at viewport
  position. No change needed.
- `.app` already reserves `--nav-safe` clearance; mid-scroll content passing
  under the floating nav is inherent to the pattern, not a bug.
- The "Drive summary" decorative bars only exist in the stale trips baselines;
  the live UI no longer renders them.

## Revision 2 — 2026-07-04, post X1–X5 re-audit

Eight-lens multi-agent audit over fresh captures (all tabs × dark/light ×
live/demo/first-run states); 29 findings confirmed by adversarial
verification, 0 refuted.

**Grades now:** Drive **B** (was C+ overall), Map **B**, Charge **B-**,
Insights **B-**, Diagnostics **B-**, Settings **B-**, cross-screen
consistency **B**, light mode **B**. The hero, the IA moves, and the token
groundwork landed. Not at the x-factor bar yet — the gap has consolidated
into four themes:

1. **The accent system stops at the nav (~9 of 29 findings).** Charts paint
   Drive orange on the green Charge and purple Insights tabs; `.primary`
   buttons and checkbox tints are hardwired orange, colliding with teal Diag
   and amber Settings; the light-theme nav pill is still hardcoded orange
   (predates the token collapse); the `--*-rgb` alpha companions are never
   redefined for light, so every tinted glow uses dark-mode values. Fix wave:
   push `--tab-accent` into chart palettes, `.primary`, `accent-color`, the
   light nav pill, and add light-mode `--*-rgb` values.
2. **The data voice contradicts itself.** Topbar says "0% SOC" beside a 77%
   hero (Insights + demo warm-up); the Insights hero leads with "vehicle
   state unknown"; trips are titled by adapter model ("OBDLink MX+" + "91
   pts"); the Status tile says "charging" three times; "PIDS unknown" sits in
   the Drive footnote. One state source + a copy pass on identity lines.
3. **The tier system is half-finished.** The Drive footnote tier dropped its
   card but kept 20px bold chip-boxed values (reads unstyled, not quiet);
   the page gets LOUDER again after it (26px overview cards); the light-mode
   battery-ring label smears (dark text-shadow under dark ink); micro-chart
   header chips wrap.
4. **Settings still speaks web-form.** Notched fieldset legends, square
   checkboxes, raw selects — a second design language no other tab uses; the
   loudest button on the tab is "Test connection" rather than Connect.

Also confirmed at lower impact: no charging-now hero on Charge, coast state
indistinguishable from disconnected, Leaflet attribution collides with the
legend, ASCII "->" axis captions, ⛶/◎ raw-glyph buttons, mono-vs-sans value
typeface split between tabs, radius/padding tokens bypassed ~30 times.
Full finding list: workflow run wf_17ef1e3c-075.

## Revision 2 fixes — shipped (same day)

All 29 confirmed findings addressed except two scoped-down items:
- Accent plumbing: per-view `--view-accent` tokens; charts, `.primary`,
  `.scan`, checkboxes, tile-toggles, and db-actions all ride their tab's
  accent; light-mode nav pill tokenized; light `--*-rgb` companions added;
  demo tone is purple everywhere; DTC quick-scan is a ghost; icon-link
  buttons quieted; one selection grammar (soft-tint hairline).
- Voice: topbar SOC chip only renders for a real reading (no fabricated
  "0%"); Insights hero is a verdict ("Pack at 77%"), never "vehicle state
  unknown"; trips titled by daypart ("Morning drive"), adapter demoted to
  the kicker; Charge KPI debug right-labels removed; scatter axis uses a
  real arrow and a data-fit floor.
- Tier finish: session footnote is 13px muted with placeholder cells
  suppressed; overview grid moved above it (monotonic decrescendo); coast
  has a warm living tint (gray = no data only); light-mode ring label
  pinned white; charging hero card moved to the top of Charge; empty
  prose cards compacted to one line.
- Components: Settings fieldsets de-natived; Clear codes demoted to a
  destructive text link; Debug logs opens the session-review block; Test
  connection quieted; Leaflet attribution restyled as a legend pill; nav
  badge exposes its count to AT and escalates red for current/permanent
  faults; STOPS zero-count bubble hides.

Scoped down (tracked): demo stream does not feed charger power (the
charging hero renders on real cars; wiring demo requires reworking the
demo contract tests), and checkbox rows keep the native control (now
per-tab tinted) rather than a custom switch component.

## Revision 3 — design elevation pass (shipped)

Five competing redlines (typography, surfaces, rhythm, micro-components,
signature moments) scored by a three-persona judge panel; the merged winner
shipped as one CSS-only pass, byte-funded by deleting the dead `.energy-rows`
block and ~15 label `ui-monospace` declarations:

- **Voice**: one quiet sans label recipe app-wide (kickers, KPI headers,
  badges, state flags, map overlays); mono is reserved for tabular numerals.
  Value tier gained real scale contrast: KPI 29→32px, SOC 30→40px, power
  20→26px, chart headlines 20→24px/760, ring label 26→34px.
- **Planes over outlines**: --line 0.10→0.06, --line-soft 0.07→0.04, darker
  page (--bg #050609) against a lighter matte card (--surface-raised
  0.96 opaque, gloss gradient deleted), lifted fill ramp, deeper insets.
  State-grid / live-readout / DTC tiles dissolved into label-over-value
  columns with hairline dividers; static badges are dot+text (the capsule
  lives only on the interactive topbar pill).
- **Two-tier rhythm**: topbar 8→22px, hero gap 14→22px, card-pad-lg 16→20px,
  page-end +16px, speed-row/power-block air, scoped link-btn line-box fix.
- **Signatures**: Charge sessions render as decaying green energy bars;
  Insights' battery ring scaled to a 96×156 hero; Diag recovery is a
  6px-stripe monolith with a 24px headline; Settings leads with a 24px
  connection headline; the map earns min(62vh, 600px).

Judge-vetoed and deliberately NOT shipped: kicker size drop below 11px
(in-car legibility), SOC strip thinning (kept 5px; secondary meters 3px),
global link-btn negative margins (scoped instead), view-gap +2px (imperceptible).

## Revision 4 — motion, typeface, demo charging (shipped)

The three levers Revision 3 left on the table, shipped together:

- **Motion design**: a shared enter vocabulary (`--motion-enter` 300ms,
  `--motion-ease-out` decelerate) — every tab switch settles in (fade +
  10px rise, retriggered by the display flip on `.view.is-active`), and the
  live charge hero stages itself when charging starts instead of popping.
  Hero accent flips (drive/regen/coast) now crossfade end to end: the
  power-state flag joined the value's color transition and the meter fill
  glow rides `box-shadow` in the same 250ms envelope. All of it collapses
  to instant under the global reduced-motion reset; the a11y axe scan
  audits the settled state via `reducedMotion: reduce`.
- **Display typeface**: Space Grotesk (OFL, variable wght 300–700, latin
  subset, ~22 KB woff2 shipped from app assets, `font-display: swap`,
  preloaded). One grouped rule in base.css gives it to headings and the
  hero-tier numerals (speed, SOC, KPIs, ring, chart headlines); labels and
  body stay on the system stack, raw OBD text stays mono. Its `tnum`
  feature keeps live values jitter-free under the existing
  `font-variant-numeric` rules. Startup budget ratcheted 360k→364k to fund
  the @font-face + motion CSS (the woff2 itself is non-render-blocking and
  outside the bucket — see docs/bundle-budget.md).
- **Demo charging hero**: both demo paths (browser stream and native
  DemoPollingLoop.kt) now run a compressed 60s-drive / 30s-charge cycle:
  parked at 0 km/h on a 7.2 kW Level-2 draw, SOC visibly climbing as a
  continuous periodic sawtooth (drive loss exactly offsets charge gain, so
  it never drifts into a cap or above the hero's target — the original
  cumulative form plateaued at 95% after ~9 min), `vehicleState: "charging"`,
  route clock frozen so the map marker doesn't orbit an unplugged charger.
  Driving samples explicitly zero `chargerPowerKw` — samples merge into
  `state.telemetry`, so omission would pin the hero open forever. Pinned by
  dashboard-tests/demo-stream.test.js and DemoPollingLoopTest.kt.
