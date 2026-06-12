// error-banner-dedupe.test.js — pins the reportClientError banner dedupe in
// core.ts: identical messages inside the dedupe window must not re-render
// (or re-show a dismissed banner), while a different message — or the same
// one after the window has passed — renders normally. Every call still flows
// to bridge.logClientError regardless of banner dedupe.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

// Must stay >= ERROR_BANNER_DEDUPE_MS in core.ts (2500ms).
const PAST_DEDUPE_WINDOW_MS = 3000;

async function freshLoad(bridge) {
  document.body.innerHTML = '';
  window.localStorage.clear();
  delete window.VoltDashboard;
  delete window.VoltTrackerNative;
  delete window.VoltTrackerAndroid;
  await loadDashboard({ bridge });
  return window.VoltDashboard;
}

describe('error banner dedupe', () => {
  let bridge;
  let VD;
  let now;

  beforeEach(async () => {
    bridge = createVoltBridgeFixture({ logClientError: vi.fn() });
    VD = await freshLoad(bridge);
    now = 1_720_000_000_000;
    vi.spyOn(Date, 'now').mockImplementation(() => now);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  const banner = () => document.getElementById('errorBanner');
  const detail = () => document.getElementById('errorBannerDetail');

  it('shows the first occurrence of an error', () => {
    VD.reportClientError('window.error', 'Boom: render failed');

    expect(banner().hidden).toBe(false);
    expect(detail().textContent).toBe('Boom: render failed');
  });

  it('suppresses an identical message inside the dedupe window', () => {
    VD.reportClientError('window.error', 'Boom: render failed');
    // User dismisses the banner...
    banner().hidden = true;

    now += 1000;
    VD.reportClientError('window.error', 'Boom: render failed');

    // ...and the duplicate must not re-show it.
    expect(banner().hidden).toBe(true);
    // The full stream still reaches the native logger on every call.
    expect(bridge.logClientError).toHaveBeenCalledTimes(2);
  });

  it('keeps a visible banner counter fresh for in-window duplicates', () => {
    VD.reportClientError('window.error', 'Boom: render failed');
    now += 500;
    VD.reportClientError('window.error', 'Boom: render failed');
    now += 500;
    VD.reportClientError('window.error', 'Boom: render failed');

    expect(banner().hidden).toBe(false);
    expect(detail().textContent).toBe('Boom: render failed (x3)');
  });

  it('renders a different message arriving inside the window', () => {
    VD.reportClientError('window.error', 'Boom: render failed');
    banner().hidden = true;

    now += 1000;
    VD.reportClientError('window.error', 'A different failure');

    expect(banner().hidden).toBe(false);
    expect(detail().textContent).toBe('A different failure');
  });

  it('renders the same message again once the window has passed', () => {
    VD.reportClientError('window.error', 'Boom: render failed');
    banner().hidden = true;

    now += PAST_DEDUPE_WINDOW_MS;
    VD.reportClientError('window.error', 'Boom: render failed');

    expect(banner().hidden).toBe(false);
    expect(detail().textContent).toBe('Boom: render failed');
  });

  // parsePayload used to swallow JSON.parse failures silently — a garbled
  // native payload read as "no data" with no signal anywhere. It must now
  // route through reportClientError (banner + bridge.logClientError, with the
  // dedupe above) while still returning the fallback and never throwing.
  describe('parsePayload malformed-JSON surfacing', () => {
    it('returns the fallback and reports the failure with a payload snippet', () => {
      const fallback = { fromFallback: true };

      expect(VD.parsePayload('{not json', fallback)).toBe(fallback);

      expect(banner().hidden).toBe(false);
      expect(detail().textContent).toContain('Malformed JSON payload');
      expect(bridge.logClientError).toHaveBeenCalledTimes(1);
      const [label, message] = bridge.logClientError.mock.calls[0];
      expect(label).toBe('parsePayload');
      expect(message).toContain('{not json');
    });

    it('stays silent for valid JSON, objects, and empty payloads', () => {
      expect(VD.parsePayload('{"ok":true}', null)).toEqual({ ok: true });
      expect(VD.parsePayload({ already: 'parsed' }, null)).toEqual({ already: 'parsed' });
      expect(VD.parsePayload('', 'fallback')).toBe('fallback');
      expect(VD.parsePayload(null, 'fallback')).toBe('fallback');

      expect(banner().hidden).toBe(true);
      expect(bridge.logClientError).not.toHaveBeenCalled();
    });

    it('dedupes a storm of identical malformed payloads into one visible render', () => {
      VD.parsePayload('{not json', null);
      banner().hidden = true;
      now += 1000;
      VD.parsePayload('{not json', null);

      // Banner stays dismissed for the in-window duplicate, but the native
      // log still received both.
      expect(banner().hidden).toBe(true);
      expect(bridge.logClientError).toHaveBeenCalledTimes(2);
    });
  });
});
