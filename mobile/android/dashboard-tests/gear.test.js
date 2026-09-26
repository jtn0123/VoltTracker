// Gear selector display (gear.ts): native decodes the PRNDL PID (VoltGear.kt) into a letter,
// the raw code and a confidence. An unseen code must read as unknown, never as a guess.
import { describe, expect, it } from 'vitest';

import { gearDisplayText } from '../app/src/main/dashboard-src/js/gear.ts';

describe('gearDisplayText', () => {
  it('shows the decoded letter for confirmed and tentative codes', () => {
    expect(gearDisplayText({ prndlState: 'P', prndlRaw: 8, gearConfidence: 'confirmed' })).toBe('P');
    expect(gearDisplayText({ prndlState: 'R', prndlRaw: 7, gearConfidence: 'tentative' })).toBe('R');
  });

  it('shows an unseen code as unknown with its raw number', () => {
    expect(gearDisplayText({ prndlState: '?', prndlRaw: 13, gearConfidence: 'unknown' })).toBe('? (code 13)');
    expect(gearDisplayText({ prndlState: '?', gearConfidence: 'unknown' })).toBe('?');
    expect(gearDisplayText({ prndlState: '?', prndlRaw: '' })).toBe('?');
  });

  it('is empty when there is no gear reading', () => {
    expect(gearDisplayText(null)).toBeNull();
    expect(gearDisplayText({})).toBeNull();
    expect(gearDisplayText({ prndlState: '' })).toBeNull();
  });
});
