// dataset-state.ts — typed setters for the dashboard's `data-state` / `data-tone`
// attribute state machines.
//
// dataset.state / dataset.tone used to be written as free-form strings from a
// dozen call sites, so a typo ("need-review", "wran") silently produced an
// unstyled element. These helpers pin the value space as string-literal unions:
// literal call sites are checked at COMPILE time, and extrinsic values (native
// status payloads, stored session outcomes) are funnelled through asDataState /
// asDataTone so unexpected values console.warn at runtime in dev/test while the
// raw string still lands on the element (CSS keyed to a genuinely-new native
// state keeps working).
//
// The unions are derived from every `dataset.state` / `dataset.tone` /
// `data-state` / `data-tone` producer and consumer across the TS sources, the
// HTML partials, and the CSS files. When adding a new value, add it here AND to
// the stylesheet that styles it.

/** Every data-state value produced or styled across TS/HTML/CSS. */
export const DATA_STATE_VALUES = [
  // Connection/status badge states (StatusPayload `state` + base.css badges).
  "idle",
  "ready",
  "connected",
  "connecting",
  "initializing",
  "scanning",
  "scan-complete",
  "demo",
  "error",
  "blocked",
  "failed",
  // Drive live-rate chip (telemetry.ts updateRateChip).
  "waiting",
  "live",
  "stale",
  // Power state pill (telemetry.ts updateLiveUi / components.css .power-state).
  "drive",
  "regen",
  "coast",
  // Charge status badge (storage-status.ts / base.css).
  "charging",
  "recorded",
  "needs-review",
  // Last-connected badge outcome (connection-status.ts / status-tools.css).
  "success",
  "unknown",
  // Enhanced-signals badge tones (signals-panel.ts setEnhancedBadge).
  "working",
  "saved"
] as const;

export type DataStateValue = (typeof DATA_STATE_VALUES)[number];

/** Every data-tone value produced or styled across TS/HTML/CSS. */
export const DATA_TONE_VALUES = [
  // Generic severity tones (validation rows, hints, micro tags).
  "idle",
  "ok",
  "warn",
  "bad",
  // Restore-progress dialog tones (core.ts setRestoreProgress).
  "busy",
  "blocked",
  // Drive now-chip tones (drive.ts deriveLiveChip / screens.css).
  "live",
  "demo",
  // Power micro-tag tones (drive.ts renderPowerMicroHeader / screens.css).
  "drive",
  "regen",
  "coast",
  // Kicker color accents (partials + base.css .kicker[data-tone=…]).
  "volt",
  "ev",
  "mixed",
  "accent",
  "muted",
  // Adapter-health pill (status-tools.css .connection-status__pill).
  "good"
] as const;

export type DataToneValue = (typeof DATA_TONE_VALUES)[number];

// Warn once per attr:value pair — these setters run on every render tick, so an
// unguarded warn for a persistent unexpected value would flood the console.
const warnedUnexpected = new Set<string>();

function warnUnexpected(attr: string, value: string, known: readonly string[]): void {
  if (known.indexOf(value) >= 0) return;
  const key = `${attr}:${value}`;
  if (warnedUnexpected.has(key)) return;
  warnedUnexpected.add(key);
  try {
    if (typeof console !== "undefined" && console && console.warn) {
      console.warn(`dataset-state: unexpected ${attr} value "${value}"`);
    }
  } catch (_err) {
    /* console is best-effort on legacy WebViews */
  }
}

/**
 * Funnel for extrinsic state strings (native payload `status.state`, stored
 * session outcomes). The raw value is preserved — setDataState warns at runtime
 * if it falls outside the known union — so a genuinely-new native state still
 * reaches the CSS while showing up loudly in dev/test.
 */
export function asDataState(value: unknown): DataStateValue {
  return String(value == null ? "" : value) as DataStateValue;
}

/** Extrinsic-tone funnel; see asDataState. */
export function asDataTone(value: unknown): DataToneValue {
  return String(value == null ? "" : value) as DataToneValue;
}

/** Set `data-state` on an element (null-safe). Returns whether it was applied. */
export function setDataState(el: HTMLElement | null, value: DataStateValue): boolean {
  if (!el) return false;
  warnUnexpected("data-state", String(value), DATA_STATE_VALUES);
  el.dataset.state = String(value);
  return true;
}

/** Set `data-tone` on an element (null-safe). Returns whether it was applied. */
export function setDataTone(el: HTMLElement | null, value: DataToneValue): boolean {
  if (!el) return false;
  warnUnexpected("data-tone", String(value), DATA_TONE_VALUES);
  el.dataset.tone = String(value);
  return true;
}
