// Gear selector (PRNDL) display text.
//
// Native decodes the Volt's PRNDL PID (222889, VoltGear.kt) into three sample fields:
//   prndlState     - the gear letter ("P", "R", "N", "D", "L"), or "?" for a code never seen
//   prndlRaw       - the raw code, kept so the decode table can be confirmed from logs
//   gearConfidence - "confirmed" | "tentative" | "unknown"
// An unseen code is shown as unknown with its raw number, never as a guessed letter.

export const UNKNOWN_GEAR = "?";

export function gearDisplayText(t: Record<string, unknown> | null | undefined): string | null {
  if (!t) return null;
  const state = t.prndlState;
  if (state == null || state === "") return null;
  const letter = String(state);
  if (letter !== UNKNOWN_GEAR && t.gearConfidence !== "unknown") return letter;
  const raw = t.prndlRaw;
  const code = raw == null || raw === "" ? Number.NaN : Number(raw);
  return Number.isFinite(code) ? `${UNKNOWN_GEAR} (code ${code})` : UNKNOWN_GEAR;
}

/** A gear reading older than this is not shown on Drive (the PID is polled about every 37 s).
 *  Mirrors VoltGear.FRESH_MS. */
export const GEAR_FRESH_MS = 120_000;

export type DriveGear = { text: string; label: string; confidence: string };

/**
 * The Drive tab's gear chip, or null (chip hidden) when there is no reading or it is older
 * than GEAR_FRESH_MS. A code never seen shows "—", never a guessed letter; a tentative decode
 * shows its letter and says in its label that it is not yet confirmed on the car.
 */
export function driveGear(t: Record<string, unknown> | null | undefined): DriveGear | null {
  if (!t) return null;
  const state = t.prndlState;
  if (state == null || state === "") return null;
  const ageMs = t.prndlStateStaleMs == null || t.prndlStateStaleMs === "" ? 0 : Number(t.prndlStateStaleMs);
  if (!(ageMs <= GEAR_FRESH_MS)) return null;
  const letter = String(state);
  if (letter === UNKNOWN_GEAR || t.gearConfidence === "unknown") {
    return { text: "—", label: "Gear unknown", confidence: "unknown" };
  }
  if (t.gearConfidence === "tentative") {
    return { text: letter, label: `Gear ${letter}, not yet confirmed on the car`, confidence: "tentative" };
  }
  return { text: letter, label: `Gear ${letter}`, confidence: "confirmed" };
}
