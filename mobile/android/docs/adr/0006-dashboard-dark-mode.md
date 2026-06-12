# ADR 0006 - Dashboard remains dark-only

- **Status:** Amended (recorded 2026-06-09; dark stays the default and primary
  scheme, but a complete `prefers-color-scheme: light` theme shipped
  2026-06-12 — see the updates below).
- **Deciders:** Project author.
- **Supersedes:** -
- **Superseded by:** -

## Context

The dashboard is primarily used in a car, often at night or in mixed cabin
lighting. Its tokens, screenshots, visual baselines, and contrast checks all
assume a dark surface. Adding a light palette would not be just a token swap:
maps, alert colors, charts, and in-car glare all need fresh visual validation.

The root CSS currently declares `color-scheme: dark`, and Playwright visual
tests run with `colorScheme: 'dark'`.

## Decision

VoltTracker intentionally ships a dark-only dashboard for now. We will not add
an automatic `prefers-color-scheme: light` token set until there is a concrete
driving use case and visual regression coverage for the light palette.

## Consequences

- The app keeps one tested visual surface across WebView, Playwright, and field
  screenshots.
- Accessibility work focuses on dark-mode contrast, focus state, touch targets,
  and readable warning states.
- A future light mode should arrive as a deliberate feature with screenshots and
  contrast proof, not as an opportunistic media query.

## Update (2026-06-12): base light palette added

A `prefers-color-scheme: light` token palette now ships in `base.css` (plus a
floating-nav companion override in `screens.css`). Scope is deliberately
partial:

- The CSS custom properties (background, surfaces, text, borders, tones) get
  light values darkened to hold WCAG AA contrast, and `color-scheme` flips to
  `light` under the media query; dark remains the default and primary scheme.
- Component-level surfaces with hardcoded dark-tuned colors (map overlays,
  route scrubber, troubleshooter modal, hero/tile gradients and accent text
  tints in `components.css` / `screens.css`) intentionally keep their dark
  styling — each stays an internally consistent dark card.
- Visual-regression coverage (Playwright `colorScheme: 'light'` baselines) and
  a full component-level color migration remain future work; until then this
  ADR's caution about treating light mode as a deliberate feature still
  applies to everything beyond the base palette.

## Update (2026-06-12, later): light theme completed across component surfaces

The component-level color migration above is done. The hardcoded dark colors
in `components.css`, `screens.css`, `troubleshooter.css` and `status-tools.css`
were grouped by role and replaced with theme tokens defined in `base.css`
(`--surface-*`, `--fill-*`, `--line-*`, `--inset-*`, `--scrim*`, `--shadow*`,
`--sheen*`, plus per-tone `*-soft` text tokens). Dark mode keeps the exact
shipped values (the dark Playwright visual baselines are unchanged); the light
block swaps every token, so map chrome, drive/charge/insights cards, the
diagnostics and signals consoles, the route scrubber, trip chips, the bottom
nav, dialogs, toasts, the status popover and the troubleshooter modal all
render as light surfaces with AA-contrast text under
`prefers-color-scheme: light`.

Deliberate exceptions that stay hardcoded:

- Map tiles remain provider-styled (light OSM tiles in both schemes), so SVG
  route halos/markers keep their white strokes — they are drawn over tiles,
  not over app surfaces. Leaflet's vendored CSS is untouched; our overrides
  re-skin its controls via tokens.
- Brand-hue alpha tints (e.g. `rgba(255,122,69,0.08)` washes, tone glow
  box-shadows) read correctly on both schemes and stay literal; only the text
  placed on them moved to tokens.
- The backup dialog's primary button (`#2f7fbc` + white) and the restore
  progress meter fills are fixed mid-tone colors that hold contrast on both
  schemes.
- `--accent` stays undefined in the dark scheme (the focus ring uses its
  `#4cc4ff` fallback) so legacy inherit behavior — and the pinned dark
  pixels — do not change; the light scheme defines it as `#0b6dc2`.

Functional light-mode coverage lives in `dashboard-e2e/light-mode.spec.js`
(launches with `colorScheme: 'light'`, asserts light surfaces, dark text and
>= 4.5:1 contrast spot-checks). Pixel baselines remain dark-only.

## References

- `app/src/main/assets/dashboard/css/base.css`
- `dashboard-e2e/playwright.config.js`
- `docs/privacy-data-handling.md` for map tile behavior and local data handling
