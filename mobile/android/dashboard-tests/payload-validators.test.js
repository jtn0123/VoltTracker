// payload-validators.test.js — pins the warn-only native-payload shape checks
// (payload-validators.ts, shapes from docs/bridge-abi.md): mistyped/missing
// fields produce exactly one console.warn per distinct issue, valid payloads
// are silent, and validation can never throw or block the setter render path.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

async function freshLoad(bridge) {
  document.body.innerHTML = '';
  window.localStorage.clear();
  delete window.VoltDashboard;
  delete window.VoltTrackerNative;
  delete window.VoltTrackerAndroid;
  await loadDashboard({ bridge });
  return window.VoltDashboard;
}

const payloadWarns = (warnSpy) =>
  warnSpy.mock.calls
    .map((call) => String(call[0]))
    .filter((message) => message.startsWith('payload check:'));

describe('payload validators', () => {
  let VD;
  let warnSpy;

  beforeEach(async () => {
    VD = await freshLoad(createVoltBridgeFixture());
    warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('stays silent for well-shaped payloads', () => {
    VD.setStatus(JSON.stringify({ state: 'connected', detail: 'OK', blocked: false, bluetoothReady: true }));
    VD.setStorage(JSON.stringify({ sessionCount: 3, sampleCount: 120 }));
    VD.setAppState(JSON.stringify({ permissions: { bluetooth: true }, session: { state: 'idle' } }));

    expect(payloadWarns(warnSpy)).toEqual([]);
  });

  it('warns once per distinct mistyped field on the setStatus path', () => {
    VD.setStatus(JSON.stringify({ state: 'ready', detail: 42 }));
    VD.setStatus(JSON.stringify({ state: 'ready', detail: 43 }));

    const warns = payloadWarns(warnSpy);
    expect(warns).toEqual(['payload check: setStatus.detail expected string, got number']);
    // Rendering was not blocked: the status still landed in state.
    expect(VD.state.status.state).toBe('ready');
  });

  it('warns when a required setStatus field is missing', () => {
    VD.validatePayload('setStatus', { detail: 'no state field' });

    expect(payloadWarns(warnSpy)).toEqual(['payload check: setStatus.state is missing']);
  });

  it('warns when a payload is not an object at all', () => {
    VD.validatePayload('setStatus', 'definitely-not-json-object');
    VD.validatePayload('setStatus', 'definitely-not-json-object');

    expect(payloadWarns(warnSpy)).toEqual([
      'payload check: setStatus payload is not an object (got string)',
    ]);
  });

  it('warns for mistyped storage counts on the setStorage path', () => {
    VD.setStorage(JSON.stringify({ sessionCount: '5', sampleCount: 12 }));

    expect(payloadWarns(warnSpy)).toEqual([
      'payload check: setStorage.sessionCount expected number, got string',
    ]);
  });

  it('warns for mistyped setAppState sections, including arrays', () => {
    VD.setAppState(JSON.stringify({ permissions: 'nope', session: ['not', 'an', 'object'] }));

    expect(payloadWarns(warnSpy)).toEqual([
      'payload check: setAppState.permissions expected object, got string',
      'payload check: setAppState.session expected object, got array',
    ]);
  });

  it('treats native error envelopes as in-contract (no warning)', () => {
    VD.setStorage(JSON.stringify({ ok: false, error: 'storage_summary_failed', message: 'nope' }));

    expect(payloadWarns(warnSpy)).toEqual([]);
  });

  it('routes each warning once through bridge.logClientError for the rolling log', async () => {
    const bridge = createVoltBridgeFixture();
    const logSpy = vi.spyOn(bridge, 'logClientError');
    VD = await freshLoad(bridge);
    warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    VD.setStatus(JSON.stringify({ state: 'ready', detail: 42 }));
    VD.setStatus(JSON.stringify({ state: 'ready', detail: 43 }));

    const shapeCalls = logSpy.mock.calls.filter((call) => call[0] === 'payload.shape');
    expect(shapeCalls).toEqual([
      ['payload.shape', 'payload check: setStatus.detail expected string, got number'],
    ]);
  });

  it('ignores unknown payload kinds and never throws', () => {
    expect(() => {
      VD.validatePayload('setSomethingElse', { whatever: 1 });
      VD.validatePayload('setStatus', null);
      VD.validatePayload('setStatus', [1, 2, 3]);
    }).not.toThrow();
  });

  // The warn-once dedupe Set must stay bounded for the life of the WebView.
  // The spec-driven public surface can only mint a few dozen distinct keys, so
  // the eviction path is exercised through the test-only exports.
  it('caps the warn-once cache and starts a fresh dedupe cycle when full', async () => {
    const { WARNED_PAYLOAD_ISSUES_MAX, warnPayloadIssueOnce, warnedPayloadIssues } = await import(
      '../app/src/main/dashboard-src/js/payload-validators.ts'
    );
    // Start from a known-empty cache so the boundary arithmetic below is
    // deterministic regardless of what bootstrap may have minted.
    warnedPayloadIssues.clear();

    // Fill to exactly the cap: every distinct key warns once...
    for (let i = 0; i < WARNED_PAYLOAD_ISSUES_MAX; i += 1) {
      warnPayloadIssueOnce(`cap-test:${i}`, `cap-test message ${i}`);
    }
    expect(warnSpy).toHaveBeenCalledTimes(WARNED_PAYLOAD_ISSUES_MAX);

    // ...and repeats are still deduped at the boundary.
    warnPayloadIssueOnce('cap-test:0', 'cap-test message 0');
    expect(warnSpy).toHaveBeenCalledTimes(WARNED_PAYLOAD_ISSUES_MAX);

    // The next NEW key evicts (clears) the full cache and is recorded.
    warnPayloadIssueOnce('cap-test:overflow', 'cap-test overflow message');
    expect(warnSpy).toHaveBeenCalledTimes(WARNED_PAYLOAD_ISSUES_MAX + 1);

    // Post-eviction the old keys re-warn once (acceptable worst case), and
    // the cache keeps deduping from there.
    warnPayloadIssueOnce('cap-test:0', 'cap-test message 0');
    warnPayloadIssueOnce('cap-test:0', 'cap-test message 0');
    expect(warnSpy).toHaveBeenCalledTimes(WARNED_PAYLOAD_ISSUES_MAX + 2);
  });
});
