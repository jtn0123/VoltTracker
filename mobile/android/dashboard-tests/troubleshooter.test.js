// troubleshooter.ts + connection-tools.ts — the recovery surfaces a user
// leans on when an adapter is flaky.
//
// These were the two least-covered dashboard modules (troubleshooter ~38% stmts,
// connection-tools ~44%) yet they are the lifeline path. This spec drives
// synthetic native status/telemetry payloads through the real observer wiring
// (the troubleshooter IIFE wraps VD.setStatus / VoltTrackerNative.updateTelemetry
// on load) and asserts the observable DOM/state — never the module internals.
//
// Setup mirrors connection-status.test.js / actions.test.js: loadDashboard()
// mounts the production DOM (sourced from the generated index.html, which
// includes the troubleshooter + connection-tools partials) and executes the
// real modules, returning the bridge fixture so we can assert bridge dispatch.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

// Reset every global the dashboard IIFEs touch so loadDashboard() can
// re-bootstrap cleanly between tests.
async function freshLoad(bridge) {
  document.body.innerHTML = '';
  delete window.VoltDashboard;
  delete window.VoltTrackerNative;
  delete window.VoltTrackerAndroid;
  const result = await loadDashboard({ bridge });
  await window.VoltDashboard.ensureTroubleshooterModule();
  return result;
}

// Push a status payload through the same seam the Android side uses
// (VoltTrackerNative.setStatus), which the troubleshooter observer wraps.
function pushStatus(status) {
  window.VoltTrackerNative.setStatus(status);
}

function modal() {
  return document.getElementById('troubleshooterModal');
}

function isModalOpen() {
  const node = modal();
  return Boolean(node && !node.hidden);
}

describe('troubleshooter.ts — auto-open triggers', () => {
  let VD;

  beforeEach(async () => {
    await freshLoad(createVoltBridgeFixture());
    VD = window.VoltDashboard;
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('starts hidden and opens after the 3rd retry in a connect burst', () => {
    expect(isModalOpen()).toBe(false);

    // First two retries stay under RETRY_OPEN_THRESHOLD (3).
    pushStatus({ state: 'connecting', detail: 'retrying (1/5)' });
    expect(isModalOpen()).toBe(false);
    pushStatus({ state: 'connecting', detail: 'retrying (2/5)' });
    expect(isModalOpen()).toBe(false);

    // The third retry crosses the threshold and auto-opens the modal.
    pushStatus({ state: 'connecting', detail: 'retrying (3/5)' });
    expect(isModalOpen()).toBe(true);
    expect(VD.state.troubleshooter.autoOpened).toBe(true);
  });

  it('opens after two consecutive failed sessions and resets on a clean connect', () => {
    // First session terminates from connecting -> failed.
    pushStatus({ state: 'connecting', detail: 'connecting...' });
    pushStatus({ state: 'failed', detail: 'handshake failed' });
    expect(isModalOpen()).toBe(false);
    expect(VD.state.troubleshooter.consecutiveFailedSessions).toBe(1);

    // Second consecutive failed session crosses FAILED_SESSION_OPEN_THRESHOLD (2).
    pushStatus({ state: 'connecting', detail: 'connecting...' });
    pushStatus({ state: 'failed', detail: 'handshake failed' });
    expect(isModalOpen()).toBe(true);

    // Close it, then a clean connect must reset the failure counter so the
    // modal doesn't immediately re-trigger on the next blip.
    VD.troubleshooter.close();
    pushStatus({ state: 'connected', detail: 'OBDLink MX+' });
    expect(VD.state.troubleshooter.consecutiveFailedSessions).toBe(0);
    expect(VD.state.troubleshooter.retriesThisBurst).toBe(0);
  });

  it('a successful scan resets the retry burst before it can trip the modal', () => {
    pushStatus({ state: 'connecting', detail: 'retrying (1/5)' });
    pushStatus({ state: 'connecting', detail: 'retrying (2/5)' });
    expect(VD.state.troubleshooter.retriesThisBurst).toBe(2);

    pushStatus({ state: 'scan-complete', detail: 'found 3 devices' });
    expect(VD.state.troubleshooter.retriesThisBurst).toBe(0);

    // A subsequent lone retry must not immediately reopen — the burst restarts.
    pushStatus({ state: 'connecting', detail: 'retrying (1/5)' });
    expect(isModalOpen()).toBe(false);
  });

  it('a dismissed modal stays closed for the rest of the retry burst, then re-arms', () => {
    pushStatus({ state: 'connecting', detail: 'retrying (1/5)' });
    pushStatus({ state: 'connecting', detail: 'retrying (2/5)' });
    pushStatus({ state: 'connecting', detail: 'retrying (3/5)' });
    expect(isModalOpen()).toBe(true);

    // User dismisses mid-burst. The very next retry status must NOT reopen it.
    VD.troubleshooter.close();
    expect(isModalOpen()).toBe(false);
    pushStatus({ state: 'connecting', detail: 'retrying (4/5)' });
    expect(isModalOpen()).toBe(false);
    pushStatus({ state: 'connecting', detail: 'retrying (5/5)' });
    expect(isModalOpen()).toBe(false);

    // A clean connect ends the burst and re-arms the auto-open for the next one.
    pushStatus({ state: 'connected', detail: 'OBDLink MX+' });
    pushStatus({ state: 'connecting', detail: 'retrying (1/5)' });
    pushStatus({ state: 'connecting', detail: 'retrying (2/5)' });
    pushStatus({ state: 'connecting', detail: 'retrying (3/5)' });
    expect(isModalOpen()).toBe(true);
  });

  it('dismissing after consecutive failures suppresses reopen until a clean connect', () => {
    pushStatus({ state: 'connecting', detail: 'connecting...' });
    pushStatus({ state: 'failed', detail: 'handshake failed' });
    pushStatus({ state: 'connecting', detail: 'connecting...' });
    pushStatus({ state: 'failed', detail: 'handshake failed' });
    expect(isModalOpen()).toBe(true);

    // Dismiss, then a third failed session: it must not pop straight back.
    VD.troubleshooter.close();
    pushStatus({ state: 'connecting', detail: 'connecting...' });
    pushStatus({ state: 'failed', detail: 'handshake failed' });
    expect(isModalOpen()).toBe(false);

    // After a clean connect the trigger is live again.
    pushStatus({ state: 'connected', detail: 'OBDLink MX+' });
    pushStatus({ state: 'connecting', detail: 'connecting...' });
    pushStatus({ state: 'failed', detail: 'handshake failed' });
    pushStatus({ state: 'connecting', detail: 'connecting...' });
    pushStatus({ state: 'failed', detail: 'handshake failed' });
    expect(isModalOpen()).toBe(true);
  });

  it('reopens manually via the error-banner "Get help" affordance', () => {
    const help = document.getElementById('errorBannerHelp');
    help.click();
    expect(isModalOpen()).toBe(true);
  });

  it('restores the modal hidden + clears autoOpened on close', () => {
    VD.troubleshooter.open();
    expect(isModalOpen()).toBe(true);
    VD.troubleshooter.close();
    expect(isModalOpen()).toBe(false);
    expect(VD.state.troubleshooter.autoOpened).toBe(false);
  });

  it('closes on Escape and restores the inert background', () => {
    const app = document.querySelector('main.app');
    VD.troubleshooter.open();
    expect(isModalOpen()).toBe(true);
    expect(app.getAttribute('aria-hidden')).toBe('true');
    expect(app.inert).toBe(true);

    document.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));

    expect(isModalOpen()).toBe(false);
    expect(app.getAttribute('aria-hidden')).toBeNull();
    expect(app.inert).toBe(false);
  });

  it('does not stack focus traps when open() is called twice', () => {
    const app = document.querySelector('main.app');
    VD.troubleshooter.open();
    // A second programmatic open() must be a no-op, not a second stacked trap.
    // A stacked trap snapshots the background AFTER the first inerted it, then
    // restores inert=true on close — permanently freezing the whole app shell.
    VD.troubleshooter.open();
    expect(app.inert).toBe(true);

    VD.troubleshooter.close();

    expect(isModalOpen()).toBe(false);
    expect(app.inert).toBe(false);
    expect(app.getAttribute('aria-hidden')).toBeNull();
  });

  it('traps Tab focus inside the modal', () => {
    VD.troubleshooter.open();
    const first = modal().querySelector('.troubleshooter-close');
    const last = modal().querySelector('.troubleshooter-secondary');
    last.focus();

    document.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Tab', bubbles: true }));

    expect(document.activeElement).toBe(first);
  });
});

describe('troubleshooter.ts — failure-class banner copy', () => {
  let VD;

  beforeEach(async () => {
    await freshLoad(createVoltBridgeFixture());
    VD = window.VoltDashboard;
  });

  function bannerTitle() {
    return document.getElementById('errorBannerTitle').textContent;
  }
  function bannerHint() {
    return document.getElementById('errorBannerHint');
  }

  it('renders the SDP_FAILURE copy + hint and shows the help affordance', () => {
    pushStatus({ state: 'failed', failureClass: 'SDP_FAILURE', detail: 'sdp lookup failed' });

    const copy = VD.troubleshooter.FAILURE_CLASS_COPY.SDP_FAILURE;
    expect(bannerTitle()).toBe(copy.title);
    expect(bannerHint().textContent).toBe(copy.hint);
    expect(bannerHint().hidden).toBe(false);
    expect(document.getElementById('errorBannerHelp').hidden).toBe(false);
    expect(document.getElementById('errorBannerActions').hidden).toBe(false);
    // Not a live retry, so the in-flight Cancel button stays hidden.
    expect(document.getElementById('errorBannerCancelRetry').hidden).toBe(true);
  });

  it('renders BT_OFF copy on a blocked status', () => {
    pushStatus({ state: 'blocked', failureClass: 'BT_OFF', detail: 'bluetooth disabled' });
    const copy = VD.troubleshooter.FAILURE_CLASS_COPY.BT_OFF;
    expect(bannerTitle()).toBe(copy.title);
    expect(bannerHint().textContent).toBe(copy.hint);
  });

  it('shows the retry Cancel button while a failure-class retry is in flight', () => {
    pushStatus({ state: 'connecting', failureClass: 'CONNECT_TIMEOUT', detail: 'retry 1' });
    const copy = VD.troubleshooter.FAILURE_CLASS_COPY.CONNECT_TIMEOUT;
    expect(bannerTitle()).toBe(copy.title);
    expect(document.getElementById('errorBannerCancelRetry').hidden).toBe(false);
    expect(document.getElementById('errorBannerHelp').hidden).toBe(false);
  });

  it('falls back to the generic failure title for an unknown failure class', () => {
    pushStatus({ state: 'failed', failureClass: 'TOTALLY_UNKNOWN', detail: 'mystery' });
    expect(bannerTitle()).toBe("Can't reach the adapter");
    expect(bannerHint().hidden).toBe(true);
  });

  it('keeps the action row visible across a non-retry interstitial in a connect burst', () => {
    const actions = () => document.getElementById('errorBannerActions');
    const cancel = () => document.getElementById('errorBannerCancelRetry');
    const help = () => document.getElementById('errorBannerHelp');

    // The action row lives inside the error banner, so the latch only matters
    // (and only holds) while the banner is actually visible — mirror that here.
    document.getElementById('errorBanner').hidden = false;

    // Retry tick — the action row appears.
    pushStatus({ state: 'connecting', failureClass: 'CONNECT_TIMEOUT', detail: 'retry 1' });
    expect(actions().hidden).toBe(false);
    expect(cancel().hidden).toBe(false);
    expect(help().hidden).toBe(false);

    // Interstitial connecting tick (native alternates this with retry ticks): no
    // "retry" in the detail, no failureClass. The row must stay latched, not
    // flash out then back in on the next retry.
    pushStatus({ state: 'connecting', detail: 'opening serial connection to volt…' });
    expect(actions().hidden).toBe(false);
    expect(cancel().hidden).toBe(false);

    // Next retry tick — still visible.
    pushStatus({ state: 'connecting', failureClass: 'CONNECT_TIMEOUT', detail: 'retry 2' });
    expect(actions().hidden).toBe(false);

    // A terminal non-connecting state clears the latch and hides the row.
    pushStatus({ state: 'idle', detail: 'idle' });
    expect(actions().hidden).toBe(true);
  });

  it('resets the banner to the default label when there is no live failure', () => {
    // Prime a failure, then send a benign idle status.
    pushStatus({ state: 'failed', failureClass: 'BOND_LOST', detail: 'bond lost' });
    expect(bannerTitle()).toBe(VD.troubleshooter.FAILURE_CLASS_COPY.BOND_LOST.title);

    pushStatus({ state: 'idle', detail: 'idle' });
    expect(bannerTitle()).toBe('Dashboard error');
    expect(document.getElementById('errorBannerActions').hidden).toBe(true);
  });
});

describe('troubleshooter.ts — stuck-bond "Forget & re-pair" swap', () => {
  function loadWithRecent(sessions) {
    return freshLoad(createVoltBridgeFixture({
      getRecentSessions: () => JSON.stringify(sessions),
    }));
  }

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('swaps the primary action to Forget & re-pair when the last 3 sessions all failed', async () => {
    await loadWithRecent([
      { outcome: 'failed' },
      { outcome: 'aborted' },
      { outcome: 'failed' },
    ]);
    const VD = window.VoltDashboard;

    VD.troubleshooter.open();

    expect(VD.state.troubleshooter.forgetMode).toBe(true);
    const primary = document.getElementById('troubleshooterPrimary');
    expect(primary.textContent).toBe('Forget adapter & re-pair');
    expect(document.getElementById('troubleshooterTitle').textContent)
      .toMatch(/pairing looks stuck/i);
    expect(document.getElementById('troubleshooterIntro').textContent)
      .toMatch(/forget the adapter/i);
  });

  it('keeps the normal "Try again" primary when not all 3 recent sessions failed', async () => {
    await loadWithRecent([
      { outcome: 'failed' },
      { outcome: 'success' },
      { outcome: 'failed' },
    ]);
    const VD = window.VoltDashboard;

    VD.troubleshooter.open();

    expect(VD.state.troubleshooter.forgetMode).toBe(false);
    expect(document.getElementById('troubleshooterPrimary').textContent).toBe('Try again');
  });

  it('the Forget-mode primary button deep-links to Bluetooth settings', async () => {
    const openBluetoothSettings = vi.fn();
    await freshLoad(createVoltBridgeFixture({
      getRecentSessions: () => JSON.stringify([
        { outcome: 'failed' },
        { outcome: 'failed' },
        { outcome: 'failed' },
      ]),
      openBluetoothSettings,
    }));

    window.VoltDashboard.troubleshooter.open();
    const primary = document.getElementById('troubleshooterPrimary');
    expect(primary.textContent).toBe('Forget adapter & re-pair');

    primary.click();
    expect(openBluetoothSettings).toHaveBeenCalledTimes(1);
  });

  it('the normal primary button asks the bridge to reconnect, then closes', async () => {
    const tryReconnectNow = vi.fn();
    await freshLoad(createVoltBridgeFixture({ tryReconnectNow }));

    window.VoltDashboard.troubleshooter.open();
    const primary = document.getElementById('troubleshooterPrimary');
    expect(primary.textContent).toBe('Try again');

    primary.click();
    expect(tryReconnectNow).toHaveBeenCalledTimes(1);
    expect(isModalOpen()).toBe(false);
  });

  it('stays dormant (no forget swap) when getRecentSessions returns []', async () => {
    await loadWithRecent([]);
    const VD = window.VoltDashboard;
    VD.troubleshooter.open();
    expect(VD.state.troubleshooter.forgetMode).toBe(false);
    expect(document.getElementById('troubleshooterPrimary').textContent).toBe('Try again');
  });
});

describe('troubleshooter.ts — competing-apps force-stop step', () => {
  let bridge;

  beforeEach(async () => {
    bridge = createVoltBridgeFixture({ forceStopPackage: vi.fn(() => true) });
    await freshLoad(bridge);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  function competingRows() {
    return Array.from(
      document.getElementById('troubleshooterCompetingList').querySelectorAll('.troubleshooter-competing-row'),
    );
  }

  it('keeps the competing step hidden when no competing apps are reported', () => {
    window.VoltDashboard.troubleshooter.open();
    expect(document.getElementById('troubleshooterStepCompeting').hidden).toBe(true);
    expect(competingRows()).toHaveLength(0);
  });

  it('renders one de-duplicated Force-stop row per competing package', () => {
    pushStatus({
      state: 'failed',
      competingApps: 'com.torque.full, com.obd.app, com.torque.full',
    });
    window.VoltDashboard.troubleshooter.open();

    const step = document.getElementById('troubleshooterStepCompeting');
    expect(step.hidden).toBe(false);
    const rows = competingRows();
    expect(rows).toHaveLength(2);
    expect(rows.map((r) => r.querySelector('.troubleshooter-competing-pkg').textContent))
      .toEqual(['com.torque.full', 'com.obd.app']);
  });

  it('the Force-stop button calls the bridge and shows immediate "Sent" feedback', () => {
    pushStatus({ state: 'failed', competingApps: 'com.torque.full' });
    window.VoltDashboard.troubleshooter.open();

    const button = competingRows()[0].querySelector('.troubleshooter-force-stop');
    expect(button.textContent).toBe('Force-stop');
    button.click();

    expect(bridge.forceStopPackage).toHaveBeenCalledWith('com.torque.full');
    expect(button.disabled).toBe(true);
    expect(button.textContent).toBe('Sent');
  });

  it('preserves the Force-stop "Sent" state across an identical competing-apps status', () => {
    pushStatus({ state: 'failed', competingApps: 'com.torque.full', detail: 'a' });
    window.VoltDashboard.troubleshooter.open();
    const button = competingRows()[0].querySelector('.troubleshooter-force-stop');
    button.click();
    expect(button.disabled).toBe(true);
    expect(button.textContent).toBe('Sent');

    // Same package set on the next tick (vary detail so the status isn't deduped,
    // guaranteeing renderCompeting runs) must NOT rebuild the row — the tapped
    // button stays disabled/"Sent" instead of reverting to an enabled "Force-stop".
    pushStatus({ state: 'failed', competingApps: 'com.torque.full', detail: 'b' });
    const after = competingRows();
    expect(after).toHaveLength(1);
    expect(after[0].querySelector('.troubleshooter-force-stop')).toBe(button);
    expect(button.disabled).toBe(true);
    expect(button.textContent).toBe('Sent');
  });

  it('does not re-expand a manually collapsed competing step until the package set changes', () => {
    pushStatus({ state: 'failed', competingApps: 'com.torque.full', detail: 'a' });
    window.VoltDashboard.troubleshooter.open();
    const body = document.getElementById('troubleshooterStepCompetingBody');
    const head = document
      .getElementById('troubleshooterStepCompeting')
      .querySelector('.troubleshooter-step-head');
    // Simulate the user collapsing the step.
    body.hidden = true;
    head.setAttribute('aria-expanded', 'false');

    // Identical package set — stays collapsed.
    pushStatus({ state: 'failed', competingApps: 'com.torque.full', detail: 'b' });
    expect(body.hidden).toBe(true);
    expect(head.getAttribute('aria-expanded')).toBe('false');

    // A changed package set rebuilds and re-expands.
    pushStatus({ state: 'failed', competingApps: 'com.torque.full, com.obd.app', detail: 'c' });
    expect(body.hidden).toBe(false);
    expect(head.getAttribute('aria-expanded')).toBe('true');
    expect(competingRows()).toHaveLength(2);
  });

  it('reverts the Force-stop button when the bridge throws so the user can retry', async () => {
    const throwingBridge = createVoltBridgeFixture({
      forceStopPackage: vi.fn(() => { throw new Error('boom'); }),
    });
    await freshLoad(throwingBridge);
    pushStatus({ state: 'failed', competingApps: 'com.torque.full' });
    window.VoltDashboard.troubleshooter.open();

    const button = competingRows()[0].querySelector('.troubleshooter-force-stop');
    button.click();
    expect(throwingBridge.forceStopPackage).toHaveBeenCalledTimes(1);
    // Reverted, not stuck on "Sent".
    expect(button.disabled).toBe(false);
    expect(button.textContent).toBe('Force-stop');
  });
});

describe('troubleshooter.ts — stale-telemetry step', () => {
  beforeEach(async () => {
    await freshLoad(createVoltBridgeFixture());
  });

  function staleRows() {
    return Array.from(
      document.getElementById('troubleshooterStaleList').querySelectorAll('.troubleshooter-stale-row'),
    );
  }

  it('keeps the stale step hidden when no slow-tier PID is overdue', () => {
    window.VoltTrackerNative.updateTelemetry({
      source: 'demo',
      voltageStaleMs: 500,
      socStaleMs: 1200,
    });
    window.VoltDashboard.troubleshooter.open();
    expect(document.getElementById('troubleshooterStepStale').hidden).toBe(true);
    expect(staleRows()).toHaveLength(0);
  });

  it('lists each PID whose staleMs exceeds the 4s threshold with a humanized age', () => {
    window.VoltTrackerNative.updateTelemetry({
      source: 'demo',
      voltageStaleMs: 9000, // > 4000 → stale
      socStaleMs: 1000, // fresh
      coolantCStaleMs: 6000, // > 4000 → stale
      batteryTempStaleMs: 2000, // fresh
    });
    window.VoltDashboard.troubleshooter.open();

    const step = document.getElementById('troubleshooterStepStale');
    expect(step.hidden).toBe(false);
    const rows = staleRows();
    expect(rows).toHaveLength(2);
    const labels = rows.map((r) => r.querySelector('.troubleshooter-stale-label').textContent);
    expect(labels).toEqual(['Adapter voltage (ATRV)', 'Coolant temperature']);
    // 9000 ms rounds to "9s ago".
    expect(rows[0].querySelector('.troubleshooter-stale-age').textContent)
      .toBe('last update 9s ago');
  });

  it('re-renders the stale step live while the modal is open', () => {
    window.VoltDashboard.troubleshooter.open();
    expect(document.getElementById('troubleshooterStepStale').hidden).toBe(true);

    // A later telemetry sample with an overdue field must surface the step
    // without re-opening the modal.
    window.VoltTrackerNative.updateTelemetry({
      source: 'demo',
      socStaleMs: 8000,
    });
    expect(document.getElementById('troubleshooterStepStale').hidden).toBe(false);
    expect(staleRows()).toHaveLength(1);
  });

  it('pickStaleFields ignores non-finite / missing staleMs values', () => {
    const VD = window.VoltDashboard;
    expect(VD.troubleshooter.pickStaleFields(null)).toEqual([]);
    expect(VD.troubleshooter.pickStaleFields({ voltageStaleMs: 'nope' })).toEqual([]);
    expect(VD.troubleshooter.pickStaleFields({ voltageStaleMs: 5000 }))
      .toEqual([{ label: 'Adapter voltage (ATRV)', staleMs: 5000 }]);
  });
});

describe('connection-tools.ts — proactive adapter checks', () => {
  let bridge;

  beforeEach(async () => {
    vi.useFakeTimers();
    bridge = createVoltBridgeFixture({
      startTestConnection: vi.fn(),
      shareDiagnostics: vi.fn(),
      shareDiagnosticsDigest: vi.fn(),
      getAutoConnectState: vi.fn(() => '{"enabled":true,"available":true,"lastName":"Volt OBD","lastAddress":"AA:BB:CC:DD:EE:FF"}'),
      setAutoConnectEnabled: vi.fn(),
      scheduleAdapterReadyNotify: vi.fn(),
      cancelAdapterReadyNotify: vi.fn(),
    });
    await freshLoad(bridge);
    window.VoltDashboard.setView('settings');
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('Test connection probes the adapter and re-enables the button after the cooldown', () => {
    const button = document.getElementById('testConnectionBtn');
    const original = button.textContent;
    button.click();
    button.click();

    expect(bridge.startTestConnection).toHaveBeenCalledTimes(1);
    expect(button.disabled).toBe(true);
    expect(button.getAttribute('aria-busy')).toBe('true');
    expect(button.textContent).toBe('Probing…');

    // Re-enables roughly when the Android-side probe (25 s) stops itself.
    vi.advanceTimersByTime(25_500);
    expect(button.disabled).toBe(false);
    expect(button.getAttribute('aria-busy')).toBe('false');
    expect(button.textContent).toBe(original);
  });

  it('Send diagnostics funnels both the primary and settings-mirror buttons to the bridge', () => {
    const primary = document.getElementById('sendDiagnosticsBtn');
    primary.click();
    primary.click();
    expect(bridge.shareDiagnostics).toHaveBeenCalledTimes(1);
    expect(primary.disabled).toBe(true);
    expect(primary.getAttribute('aria-busy')).toBe('true');
    vi.advanceTimersByTime(1500);
    expect(primary.disabled).toBe(false);

    const mirror = document.getElementById('sendDiagnosticsSettingsBtn');
    if (mirror) {
      mirror.click();
      expect(bridge.shareDiagnostics).toHaveBeenCalledTimes(2);
    }
  });

  it('Send AI digest funnels to shareDiagnosticsDigest and re-enables after the cooldown', () => {
    const btn = document.getElementById('sendAiDigestBtn');
    const original = btn.textContent;
    btn.click();
    btn.click(); // second click while busy must be ignored
    expect(bridge.shareDiagnosticsDigest).toHaveBeenCalledTimes(1);
    expect(btn.disabled).toBe(true);
    expect(btn.getAttribute('aria-busy')).toBe('true');
    expect(btn.textContent).toBe('Preparing…');

    vi.advanceTimersByTime(1500);
    expect(btn.disabled).toBe(false);
    expect(btn.getAttribute('aria-busy')).toBe('false');
    expect(btn.textContent).toBe(original);
  });

  it('Auto-connect reflects native state and toggles the bridge preference', () => {
    const toggle = document.getElementById('autoConnectToggle');
    const status = document.getElementById('autoConnectStatus');

    expect(bridge.getAutoConnectState).toHaveBeenCalledTimes(1);
    expect(toggle.checked).toBe(true);
    expect(status.textContent).toMatch(/Volt OBD/i);
    expect(status.textContent).toMatch(/without Bluetooth discovery/i);

    toggle.checked = false;
    toggle.dispatchEvent(new window.Event('change'));

    expect(bridge.setAutoConnectEnabled).toHaveBeenCalledWith(false);
    expect(status.textContent).toMatch(/manual connect/i);
  });

  it('the change handler re-polls auto-connect state instead of reusing the bind-time snapshot', async () => {
    const getAutoConnectState = vi.fn(
      () => '{"enabled":true,"available":true,"lastName":"New Adapter","lastAddress":"11:22:33:44:55:66"}'
    );
    getAutoConnectState.mockReturnValueOnce(
      '{"enabled":true,"available":true,"lastName":"Old Adapter","lastAddress":"AA:BB:CC:DD:EE:FF"}'
    );
    await freshLoad(createVoltBridgeFixture({
      getAutoConnectState,
      setAutoConnectEnabled: vi.fn(),
    }));
    window.VoltDashboard.setView('settings');
    const toggle = document.getElementById('autoConnectToggle');
    const status = document.getElementById('autoConnectStatus');
    expect(status.textContent).toMatch(/Old Adapter/);

    // Re-toggling on must repaint from a FRESH bridge poll, not the snapshot
    // captured when the handler was bound.
    toggle.checked = true;
    toggle.dispatchEvent(new window.Event('change'));

    expect(getAutoConnectState.mock.calls.length).toBeGreaterThanOrEqual(2);
    expect(status.textContent).toMatch(/New Adapter/);
    expect(status.textContent).not.toMatch(/Old Adapter/);
  });

  it('Auto-connect surfaces the post-failure cooldown', async () => {
    await freshLoad(createVoltBridgeFixture({
      getAutoConnectState: vi.fn(
        () => '{"enabled":true,"available":true,"lastName":"Volt OBD","lastAddress":"AA:BB:CC:DD:EE:FF","cooldownRemainingMs":12000}'
      ),
      setAutoConnectEnabled: vi.fn(),
    }));
    window.VoltDashboard.setView('settings');
    const status = document.getElementById('autoConnectStatus');
    expect(status.textContent).toMatch(/cooling down/i);
    expect(status.textContent).toMatch(/12s/);
  });

  it('Notify-when-ready schedules the clamped minutes when toggled on', () => {
    const toggle = document.getElementById('notifyWhenReadyToggle');
    const mins = document.getElementById('notifyWhenReadyMinutes');
    const status = document.getElementById('notifyWhenReadyStatus');
    mins.value = '15';

    toggle.checked = true;
    toggle.dispatchEvent(new window.Event('change'));

    expect(bridge.scheduleAdapterReadyNotify).toHaveBeenCalledWith(15);
    expect(toggle.closest('fieldset').getAttribute('aria-busy')).toBe('true');
    expect(status.textContent).toMatch(/next 15 min/i);
    vi.advanceTimersByTime(600);
    expect(toggle.closest('fieldset').getAttribute('aria-busy')).toBe('false');
  });

  it('Notify-when-ready honors the longest (30 min) selectable duration', () => {
    const toggle = document.getElementById('notifyWhenReadyToggle');
    const mins = document.getElementById('notifyWhenReadyMinutes');
    mins.value = '30';

    toggle.checked = true;
    toggle.dispatchEvent(new window.Event('change'));

    expect(bridge.scheduleAdapterReadyNotify).toHaveBeenCalledWith(30);
    // The UI mirrors the (already in-range) value back unchanged.
    expect(mins.value).toBe('30');
  });

  it('toggling Notify-when-ready off cancels the schedule on the bridge', () => {
    const toggle = document.getElementById('notifyWhenReadyToggle');
    toggle.checked = false;
    toggle.dispatchEvent(new window.Event('change'));

    expect(bridge.cancelAdapterReadyNotify).toHaveBeenCalledTimes(1);
    expect(document.getElementById('notifyWhenReadyStatus').textContent)
      .toMatch(/Probes the last-used adapter/i);
    expect(toggle.closest('fieldset').getAttribute('aria-busy')).toBe('true');
    vi.advanceTimersByTime(600);
    expect(toggle.closest('fieldset').getAttribute('aria-busy')).toBe('false');
  });

  it('re-arms the schedule when the minutes selector changes while toggled on', () => {
    const toggle = document.getElementById('notifyWhenReadyToggle');
    const mins = document.getElementById('notifyWhenReadyMinutes');

    toggle.checked = true;
    toggle.dispatchEvent(new window.Event('change'));
    expect(bridge.scheduleAdapterReadyNotify).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(600);

    mins.value = '5';
    mins.dispatchEvent(new window.Event('change'));
    expect(bridge.scheduleAdapterReadyNotify).toHaveBeenLastCalledWith(5);
  });
});
