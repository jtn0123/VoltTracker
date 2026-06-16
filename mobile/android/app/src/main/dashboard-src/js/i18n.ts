// i18n.ts — minimal, dependency-free string lookup for dashboard copy.
//
// This is the FIRST STEP of internationalizing the WebView UI, not the whole job.
// Today the vast majority of user-facing copy is still inlined as English literals
// in the TS sources and HTML partials. Extracting all of it into this table is a
// large, mechanical follow-up. What lives here proves the path end-to-end on a small,
// representative slice: a typed default (English) catalog, a `t()` lookup with
// `{placeholder}` interpolation, a partial per-locale override mechanism, and locale
// resolution from `navigator.language`.
//
// Design notes:
//   - The English catalog (`EN`) is the source of truth and the type. `MessageKey`
//     is derived from it, so every `t()` call site is checked at COMPILE time and a
//     typo'd key fails `tsc` rather than silently rendering the key string.
//   - Lookups fall back: active-locale override -> English -> the key itself. A
//     locale override is intentionally PARTIAL (a translator can ship a subset);
//     anything it omits falls through to English, never to an empty string.
//   - `t()` returns a plain string. It performs NO HTML — callers assign the result
//     to `textContent` (never `innerHTML`), so interpolated values cannot inject
//     markup. This keeps the surface XSS-safe by construction.
//   - No `window`/`VD` coupling: this is a pure ES module other dashboard modules
//     import (like dataset-state.ts), so it is unit-testable in isolation.

/** The default (English) message catalog. Its keys define the valid `MessageKey` set. */
export const EN = {
  // connection-status.ts popover copy (the demo slice migrated in this pass).
  "status.detail.ready": "Ready.",
  "status.logging.waitingForData": "Waiting for data",
  "status.logging.notLogging": "Not logging",
  "status.adapter.fallbackName": "OBD adapter",
  "trip.empty.noTripYet": "No trip yet — connect to start logging.",
  "trip.waitingForFirstSamples": "Waiting for the first samples…",
  // Count-bearing example proving `{placeholder}` interpolation end-to-end.
  "status.logging.samples": "{count} samples",
} as const;

/** Every translatable key. Derived from `EN` so call sites are compile-checked. */
export type MessageKey = keyof typeof EN;

/** Values substituted into `{placeholder}` tokens. Stringified at interpolation time. */
export type MessageParams = Readonly<Record<string, string | number>>;

/** A locale override: a PARTIAL map of keys to translated copy. Missing keys fall back to English. */
export type Catalog = Readonly<Partial<Record<MessageKey, string>>>;

// Registered per-locale overrides, keyed by lowercase BCP-47 base tag ("es", "de", …).
const overrides = new Map<string, Catalog>();

// The active base tag. "en" is the built-in default and is always satisfiable by EN.
let activeBase = "en";

/** Normalize a BCP-47 tag ("es-MX", "EN_us") to its lowercase base ("es", "en"). */
export function baseTag(tag: string | null | undefined): string {
  const raw = (tag || "").trim().toLowerCase();
  if (!raw) return "";
  // Split on either separator; the region/script suffix is dropped for catalog lookup.
  const base = raw.split(/[-_]/)[0];
  return base || "";
}

/**
 * Register (or replace) a partial catalog for a locale. The tag is normalized to its
 * base, so `registerLocale("es-ES", …)` and `registerLocale("es", …)` target the same
 * bucket. Returns the normalized base tag that was registered.
 */
export function registerLocale(tag: string, catalog: Catalog): string {
  const base = baseTag(tag);
  if (base) overrides.set(base, catalog);
  return base;
}

/**
 * Choose the active locale. A tag whose base has no registered override (and is not
 * "en") leaves the active locale unchanged so lookups keep resolving to English.
 * Returns the base tag now active.
 */
export function setLocale(tag: string | null | undefined): string {
  const base = baseTag(tag);
  if (base === "en" || overrides.has(base)) activeBase = base;
  return activeBase;
}

/** The currently active base locale tag. */
export function getLocale(): string {
  return activeBase;
}

/** Reset to the built-in English default and forget all registered overrides (test hook). */
export function resetI18n(): void {
  overrides.clear();
  activeBase = "en";
}

/**
 * Resolve the device locale from `navigator.language` and activate it if a matching
 * override is registered. Safe to call where `navigator` is absent (returns "en").
 */
export function resolveDeviceLocale(): string {
  try {
    const nav = typeof navigator !== "undefined" ? navigator : null;
    return setLocale(nav ? nav.language : null);
  } catch (_err) {
    // `navigator` access can throw in exotic embeddings; default to English.
    return activeBase;
  }
}

// Substitute `{key}` tokens with stringified params. A token whose param is absent —
// or present but null/undefined — is left intact so a missing param is visible in dev
// rather than silently blanked.
function interpolate(template: string, params?: MessageParams): string {
  if (!params) return template;
  return template.replace(/\{(\w+)\}/g, (match, name: string) => {
    const value = params[name];
    return value == null ? match : String(value);
  });
}

/**
 * Translate `key` for the active locale, interpolating any `{placeholder}` tokens.
 * Resolution order: active-locale override -> English -> the raw key (never empty).
 */
export function t(key: MessageKey, params?: MessageParams): string {
  const override = activeBase === "en" ? undefined : overrides.get(activeBase);
  const template = (override && override[key]) || EN[key] || key;
  return interpolate(template, params);
}
