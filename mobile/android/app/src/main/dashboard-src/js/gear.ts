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
