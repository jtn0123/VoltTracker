// Gear selector display (gear.ts): native decodes the PRNDL PID (VoltGear.kt) into a letter,
// the raw code and a confidence. An unseen code must read as unknown, never as a guess.
import { describe, expect, it } from 'vitest';

import { GEAR_FRESH_MS, driveGear, gearDisplayText } from '../app/src/main/dashboard-src/js/gear.ts';

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

describe('driveGear (Drive tab gear chip)', () => {
  it('shows a confirmed letter as-is', () => {
    expect(driveGear({ prndlState: 'D', prndlRaw: 3, gearConfidence: 'confirmed', prndlStateStaleMs: 1000 })).toEqual({
      text: 'D',
      label: 'Gear D',
      confidence: 'confirmed',
    });
  });

  it('shows a tentative letter and says it is unconfirmed', () => {
    const gear = driveGear({ prndlState: 'L', prndlRaw: 5, gearConfidence: 'tentative' });
    expect(gear.text).toBe('L');
    expect(gear.confidence).toBe('tentative');
    expect(gear.label).toContain('not yet confirmed');
  });

  it('shows an unseen code as a dash, never a guessed letter', () => {
    expect(driveGear({ prndlState: '?', prndlRaw: 13, gearConfidence: 'unknown' }).text).toBe('—');
  });

  it('hides with no reading or a stale one', () => {
    expect(driveGear(null)).toBeNull();
    expect(driveGear({})).toBeNull();
    expect(driveGear({ prndlState: '' })).toBeNull();
    expect(driveGear({ prndlState: 'P', prndlStateStaleMs: GEAR_FRESH_MS })).not.toBeNull();
    expect(driveGear({ prndlState: 'P', prndlStateStaleMs: GEAR_FRESH_MS + 1 })).toBeNull();
    expect(driveGear({ prndlState: 'P', prndlStateStaleMs: 'junk' })).toBeNull();
  });
});
