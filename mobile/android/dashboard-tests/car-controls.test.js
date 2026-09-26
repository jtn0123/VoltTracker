// car-controls.ts — the experimental Controls card on Drive + its Settings opt-in.
//
// The dashboard may only read native state and ASK for a command by name; these tests pin that
// it never does more (no PIN, no frames), that the card follows the engine's gate from the live
// sample, and that Demo / Testing simulates without touching the bridge.
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

const OFF = { available: true, enabled: false, unlocked: false, unlockedRemainingMs: 0, pinLockedOut: false };
const ON = { ...OFF, enabled: true };

function click(node) {
  node.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
}

describe('car controls', () => {
  let bridge;
  let native;
  let VD;

  async function boot(overrides = {}) {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    bridge = createVoltBridgeFixture({
      getCarControlState: vi.fn(() => JSON.stringify(native)),
      setCarControlsEnabled: vi.fn(),
      requestCarControl: vi.fn(),
      lockCarControls: vi.fn(),
      ...overrides,
    });
    await loadDashboard({ bridge });
    VD = window.VoltDashboard;
    await VD.ensureCarControlsModule();
    // The chunk paints through the render pass (next frame); paint now for the assertions.
    VD.renderCarControls();
  }

  function live(fields) {
    VD.updateTelemetry({ source: 'obd', updatedAt: Date.now(), speedKph: 0, ...fields });
    VD.renderCarControls();
  }

  const card = () => document.getElementById('carControlsCard');
  const gate = () => document.getElementById('carControlsGate');
  const buttons = () => [...document.querySelectorAll('#carControlsCard [data-car-control]')];
  const button = (name) => document.querySelector(`#carControlsCard [data-car-control="${name}"]`);

  beforeEach(async () => {
    native = { ...OFF };
    await boot();
  });

  it('is off by default: card hidden, toggle Off', () => {
    expect(card().hidden).toBe(true);
    const toggle = document.getElementById('carControlsToggle');
    expect(toggle.getAttribute('aria-pressed')).toBe('false');
    expect(toggle.textContent).toBe('Off');
    expect(document.getElementById('carControlsWarning').textContent).toContain('not been tested on your car');
  });

  it('the Settings toggle only asks native to start the opt-in, and turns off directly', () => {
    const toggle = document.getElementById('carControlsToggle');
    click(toggle);
    expect(bridge.setCarControlsEnabled).toHaveBeenCalledWith(true);
    expect(document.getElementById('carControlsSettingsStatus').textContent).toContain('set a PIN');
    // Nothing flips until native says so.
    VD.renderCarControls();
    expect(toggle.getAttribute('aria-pressed')).toBe('false');

    native = { ...ON };
    VD.renderCarControls();
    expect(toggle.getAttribute('aria-pressed')).toBe('true');
    expect(card().hidden).toBe(false);
    click(toggle);
    expect(bridge.setCarControlsEnabled).toHaveBeenLastCalledWith(false);
  });

  it('shows every command and keeps them disabled without a live gate', () => {
    native = { ...ON };
    VD.renderCarControls();
    expect(buttons().map((b) => b.getAttribute('data-car-control'))).toEqual([
      'lock',
      'unlock',
      'flash',
      'locate',
      'remote_start',
      'remote_stop',
      'windows_down',
      'windows_up',
    ]);
    expect(gate().textContent).toContain('Connect to the car');
    expect(buttons().every((b) => b.disabled)).toBe(true);
  });

  it('follows the engine gate and only sends the command name', () => {
    native = { ...ON };
    live({ carControlGate: 'ready', carControlGateDetail: '' });
    expect(gate().getAttribute('data-tone')).toBe('ok');
    expect(buttons().every((b) => !b.disabled)).toBe(true);
    click(button('unlock'));
    expect(bridge.requestCarControl).toHaveBeenCalledTimes(1);
    expect(bridge.requestCarControl).toHaveBeenCalledWith('unlock');

    live({ carControlGate: 'moving', carControlGateDetail: 'The car is moving.' });
    expect(gate().textContent).toBe('The car is moving.');
    expect(gate().getAttribute('data-tone')).toBe('warn');
    expect(buttons().every((b) => b.disabled)).toBe(true);

    live({ carControlGate: 'busy', carControlGateDetail: 'Another command is still running.' });
    expect(gate().textContent).toContain('Sending');
  });

  it('reports the last outcome from the live sample', () => {
    native = { ...ON };
    const result = document.getElementById('carControlsResult');
    live({ carControlGate: 'ready' });
    expect(result.hidden).toBe(true);
    live({
      carControlGate: 'ready',
      carControlLastCommand: 'lock',
      carControlLastOutcome: 'confirmed',
      carControlLastDetail: 'Car reported doors locked.',
      carControlLastAtMs: 1,
    });
    expect(result.hidden).toBe(false);
    expect(result.textContent).toBe('Lock: confirmed by the car. Car reported doors locked.');
    expect(result.getAttribute('data-tone')).toBe('ok');

    live({ carControlGate: 'ready', carControlLastCommand: 'windows_up', carControlLastOutcome: 'sent_unconfirmed' });
    expect(result.textContent).toBe('Windows up: sent, not confirmed.');
    expect(result.hasAttribute('data-tone')).toBe(false);

    live({ carControlGate: 'rate_limited', carControlLastCommand: 'flash', carControlLastOutcome: 'refused', carControlLastDetail: 'Wait.' });
    expect(result.textContent).toBe('Flash lights: not sent. Wait.');
    expect(result.getAttribute('data-tone')).toBe('warn');
  });

  it('shows the PIN window and can end it', () => {
    native = { ...ON, unlocked: true, unlockedRemainingMs: 125000 };
    VD.renderCarControls();
    expect(document.getElementById('carControlsPinState').textContent).toContain('about 3 min');
    const relock = document.getElementById('carControlsRelockBtn');
    expect(relock.hidden).toBe(false);
    click(relock);
    expect(bridge.lockCarControls).toHaveBeenCalledTimes(1);

    native = { ...ON, pinLockedOut: true };
    VD.renderCarControls();
    expect(document.getElementById('carControlsPinState').textContent).toContain('wrong attempts');
    expect(relock.hidden).toBe(true);
  });

  it('demo mode simulates success without touching the bridge', async () => {
    VD.setDemoActive(true);
    VD.renderCarControls();
    expect(card().hidden).toBe(false);
    expect(gate().textContent).toContain('simulated');
    expect(buttons().every((b) => !b.disabled)).toBe(true);
    const confirm = vi.fn(async () => true);
    VD.confirmAppDialog = confirm;
    click(button('remote_start'));
    await vi.waitFor(() => {
      VD.renderCarControls();
      expect(document.getElementById('carControlsResult').hidden).toBe(false);
    });
    expect(confirm.mock.calls[0][0].title).toBe('Remote start?');
    expect(document.getElementById('carControlsResult').textContent).toContain('Remote start: confirmed by the car. Demo');
    expect(bridge.requestCarControl).not.toHaveBeenCalled();

    VD.confirmAppDialog = vi.fn(async () => false);
    click(button('lock'));
    await Promise.resolve();
    expect(document.getElementById('carControlsResult').textContent).toContain('Remote start');
    expect(bridge.requestCarControl).not.toHaveBeenCalled();
    expect(document.getElementById('carControlsPinState').textContent).toContain('Demo');
  });

  it('an older native without car controls disables the toggle', async () => {
    await boot({ getCarControlState: undefined });
    const toggle = document.getElementById('carControlsToggle');
    expect(toggle.disabled).toBe(true);
    expect(card().hidden).toBe(true);
  });

  it('tolerates a bridge that returns junk', async () => {
    await boot({ getCarControlState: vi.fn(() => 'not json') });
    expect(card().hidden).toBe(true);
    await boot({ getCarControlState: vi.fn(() => { throw new Error('boom'); }) });
    expect(document.getElementById('carControlsToggle').disabled).toBe(true);
  });
});
