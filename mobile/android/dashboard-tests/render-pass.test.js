// render-pass.test.js — the shared render pass (Phase 2).
//
// The pass replaced the hand-written render clusters that used to follow every state write
// (updateStorageUi(); renderRealV2Ui(); renderMapIfLoaded(); …), which is where the
// "forgot to repaint" and "repainted twice" bugs came from. These tests pin the properties
// that make it safe to rely on:
//
//   - a state change schedules exactly ONE frame no matter how many writes it took
//   - a synchronous flush consumes the queued frame instead of leaving it to fire again
//   - a renderer that throws does not take the rest of the frame down with it
//   - signature memoisation skips redundant repaints but never skips a real change
//   - a renderer that writes state on every pass is capped, not allowed to spin forever
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

describe('shared render pass', () => {
  let VD;

  beforeEach(async () => {
    await loadDashboard();
    VD = window.VoltDashboard;
    // Boot queues a frame (lazy chunks hydrate and write state). Settle it so each test
    // starts from a painted, non-pending baseline.
    VD.flushRender();
  });

  it('coalesces many state writes into a single frame', () => {
    const raf = vi.spyOn(window, 'requestAnimationFrame').mockReturnValue(7);
    try {
      VD.setState({ view: 'map' });
      VD.setState({ mode: 'gas' });
      VD.setState({ signalProbeStage: 'brakes' });
      expect(raf).toHaveBeenCalledTimes(1);
    } finally {
      raf.mockRestore();
    }
  });

  it('does not schedule a frame when a patch changes nothing', () => {
    VD.setState({ view: 'drive' });
    VD.flushRender();
    const raf = vi.spyOn(window, 'requestAnimationFrame').mockReturnValue(7);
    try {
      // Same value again — re-broadcasting an unchanged payload is the common native case.
      VD.setState({ view: 'drive' });
      expect(raf).not.toHaveBeenCalled();
    } finally {
      raf.mockRestore();
    }
  });

  it('a synchronous flush consumes the queued frame rather than leaving it armed', () => {
    const cancel = vi.spyOn(window, 'cancelAnimationFrame');
    try {
      VD.setState({ view: 'insights' });
      const queued = VD.state.rafPending;
      expect(queued).not.toBe(0);

      VD.flushRender();
      // The flush did the work now, so the pending frame is cancelled rather than left to
      // fire and repaint everything a second time — the double-render this pass removes.
      expect(cancel).toHaveBeenCalledWith(queued);
      expect(VD.state.rafPending).toBe(0);
    } finally {
      cancel.mockRestore();
    }
  });

  it('keeps painting the remaining renderers when one throws', () => {
    const order = [];
    VD.registerRenderer('test:before', () => order.push('before'));
    VD.registerRenderer('test:boom', () => { throw new Error('renderer exploded'); });
    VD.registerRenderer('test:after', () => order.push('after'));

    expect(() => VD.flushRender()).not.toThrow();
    expect(order).toContain('before');
    expect(order).toContain('after');
  });

  it('skips a repaint when the signature is unchanged and resumes when it moves', () => {
    let painted = 0;
    VD.registerRenderer('test:signed', () => { painted += 1; }, () => [VD.state.selectedMapSessionId]);

    VD.flushRender();
    expect(painted).toBe(1);

    // Unrelated state moves; this renderer's signature does not.
    VD.setState({ mode: 'ev' });
    VD.flushRender();
    expect(painted).toBe(1);

    // Its own slice moves.
    VD.setState({ selectedMapSessionId: 'session-42' });
    VD.flushRender();
    expect(painted).toBe(2);
  });

  it('repaints unconditionally after the render cache is invalidated', () => {
    let painted = 0;
    VD.registerRenderer('test:cached', () => { painted += 1; }, () => [VD.state.mapLayer]);
    VD.flushRender();
    expect(painted).toBe(1);

    VD.flushRender();
    expect(painted).toBe(1);

    // Demo re-seed / restore replaces the DOM under an identical signature.
    VD.invalidateRenderCache();
    VD.flushRender();
    expect(painted).toBe(2);
  });

  it('replaces a renderer registered again under the same name instead of duplicating it', () => {
    let first = 0;
    let second = 0;
    VD.registerRenderer('test:dupe', () => { first += 1; });
    VD.registerRenderer('test:dupe', () => { second += 1; });

    VD.flushRender();
    expect(first).toBe(0);
    expect(second).toBe(1);
  });

  it('caps a renderer that re-arms the pass on every frame instead of spinning forever', () => {
    let painted = 0;
    // Writes a genuinely new value every pass — the shape of the getLastDevice() defect this
    // guard caught during the migration.
    VD.registerRenderer('test:greedy', () => {
      painted += 1;
      VD.setState({ signalProbeStage: 'stage-' + painted });
    });

    // A pass never recurses synchronously: the re-arm goes through a fresh frame, so one
    // flush paints exactly once and leaves a frame queued. That is what keeps a greedy
    // renderer from blocking the current frame.
    VD.flushRender();
    expect(painted).toBe(1);
    expect(VD.state.rafPending).not.toBe(0);

    // Driving it by hand, the cascade counter eventually gives up instead of re-arming
    // forever: within a chain of consecutive flushes at least one must decline to queue
    // another frame. (It then starts a fresh chain, so the END state depends on where you
    // stop counting — the bound is per chain, which is what stops a runaway.)
    let declinedToRearm = 0;
    for (let i = 0; i < 12; i += 1) {
      VD.flushRender();
      if (VD.state.rafPending === 0) declinedToRearm += 1;
    }
    expect(declinedToRearm).toBeGreaterThan(0);
  });
});
