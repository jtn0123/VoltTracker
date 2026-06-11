// [data-nav-jump] navigation contract: actions.ts delegates clicks at the
// document level (same pattern as [data-map-session]) so link chips that
// drive.ts builds at render time — long after bindListeners() ran — navigate
// exactly like the static partial buttons. One mechanism, no double-fire.
import { describe, it, expect, beforeEach, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

describe('[data-nav-jump] delegation', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  it('navigates when a chip created AFTER boot is clicked', () => {
    const VD = window.VoltDashboard;
    expect(VD.state.view).not.toBe('map');

    // Mirror drive.ts buildDriveNowChip()'s link-chip shape: a button created
    // at render time with dataset.navJump, never seen by any boot-time
    // querySelectorAll binding.
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.className = 'map-drive-chip drive-now-chip';
    chip.dataset.navJump = 'map';
    chip.textContent = 'Recording';
    document.body.append(chip);

    chip.click();

    expect(VD.state.view).toBe('map');
  });

  it('navigates from a click on a child element inside the chip', () => {
    const VD = window.VoltDashboard;
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.dataset.navJump = 'insights';
    const inner = document.createElement('span');
    inner.textContent = 'details';
    chip.append(inner);
    document.body.append(chip);

    inner.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));

    expect(VD.state.view).toBe('insights');
  });

  it('fires setView exactly once per click on a static boot-time element', () => {
    const VD = window.VoltDashboard;
    const staticButton = document.querySelector('[data-nav-jump]');
    expect(staticButton, 'generated DOM should ship at least one [data-nav-jump] button').not.toBeNull();

    const original = VD.setView;
    const spy = vi.fn((view) => original(view));
    VD.setView = spy;
    try {
      staticButton.click();
    } finally {
      VD.setView = original;
    }

    expect(spy).toHaveBeenCalledTimes(1);
    expect(spy).toHaveBeenCalledWith(staticButton.dataset.navJump);
    expect(VD.state.view).toBe(staticButton.dataset.navJump);
  });
});
