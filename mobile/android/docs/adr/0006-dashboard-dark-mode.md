# ADR 0006 - Dashboard remains dark-only

- **Status:** Accepted (recorded 2026-06-09).
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

## References

- `app/src/main/assets/dashboard/css/base.css`
- `dashboard-e2e/playwright.config.js`
- `docs/privacy-data-handling.md` for map tile behavior and local data handling
