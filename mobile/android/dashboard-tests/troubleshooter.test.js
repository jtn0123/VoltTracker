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
  return loadDashboard({ bridge });
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
    expect(bannerTitle()).toBe("Couldn't reach the adapter");
    expect(bannerHint().hidden).toBe(true);
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
      { status: 'failed' },
      { status: 'error' },
      { status: 'disconnected' },
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
      { status: 'failed' },
      { status: 'success' },
      { status: 'failed' },
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
        { status: 'failed' },
        { status: 'failed' },
        { status: 'failed' },
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
      scheduleAdapterReadyNotify: vi.fn(),
      cancelAdapterReadyNotify: vi.fn(),
    });
    await freshLoad(bridge);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('Test connection probes the adapter and re-enables the button after the cooldown', () => {
    const button = document.getElementById('testConnectionBtn');
    const original = button.textContent;
    button.click();

    expect(bridge.startTestConnection).toHaveBeenCalledTimes(1);
    expect(button.disabled).toBe(true);
    expect(button.textContent).toBe('Probing...');

    // Re-enables roughly when the Android-side probe (25 s) stops itself.
    vi.advanceTimersByTime(25_500);
    expect(button.disabled).toBe(false);
    expect(button.textContent).toBe(original);
  });

  it('Send diagnostics funnels both the primary and settings-mirror buttons to the bridge', () => {
    document.getElementById('sendDiagnosticsBtn').click();
    expect(bridge.shareDiagnostics).toHaveBeenCalledTimes(1);

    const mirror = document.getElementById('sendDiagnosticsSettingsBtn');
    if (mirror) {
      mirror.click();
      expect(bridge.shareDiagnostics).toHaveBeenCalledTimes(2);
    }
  });

  it('Notify-when-ready schedules the clamped minutes when toggled on', () => {
    const toggle = document.getElementById('notifyWhenReadyToggle');
    const mins = document.getElementById('notifyWhenReadyMinutes');
    const status = document.getElementById('notifyWhenReadyStatus');
    mins.value = '15';

    toggle.checked = true;
    toggle.dispatchEvent(new window.Event('change'));

    expect(bridge.scheduleAdapterReadyNotify).toHaveBeenCalledWith(15);
    expect(status.textContent).toMatch(/next 15 min/i);
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
  });

  it('re-arms the schedule when the minutes selector changes while toggled on', () => {
    const toggle = document.getElementById('notifyWhenReadyToggle');
    const mins = document.getElementById('notifyWhenReadyMinutes');

    toggle.checked = true;
    toggle.dispatchEvent(new window.Event('change'));
    expect(bridge.scheduleAdapterReadyNotify).toHaveBeenCalledTimes(1);

    mins.value = '5';
    mins.dispatchEvent(new window.Event('change'));
    expect(bridge.scheduleAdapterReadyNotify).toHaveBeenLastCalledWith(5);
  });
});
