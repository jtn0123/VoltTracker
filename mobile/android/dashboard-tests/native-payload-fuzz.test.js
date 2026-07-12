import { beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

// Fixed-seed bridge-boundary fuzzing. Native normally sends valid JSON, but partial upgrades,
// corrupt rows, and producer/consumer schema drift can still deliver the wrong JSON root/field
// types. The payload validator is warn-only by contract, so every native setter must degrade
// without throwing and taking down the WebView render loop.
describe('native payload fuzzing', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
  });

  it('all JSON callback roots and field types fail soft', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    await loadDashboard({ bridge: createVoltBridgeFixture() });
    const native = window.VoltTrackerNative;
    const random = mulberry32(0x564f4c54);
    const callbacks = [
      'setDevices',
      'setHistory',
      'setStatus',
      'setStorage',
      'setTrips',
      'setInsights',
      'setTripRoute',
      'setBatterySohHistory',
      'setCurrentSessionRoute',
      'setAppState',
      'setRestoreProgress',
      'updateTelemetry',
      'backfillTelemetry',
    ];

    for (let iteration = 0; iteration < 2_000; iteration += 1) {
      const callback = callbacks[iteration % callbacks.length];
      const value = randomJsonValue(random, 0);
      const payload = JSON.stringify(value);
      invokeFuzzCallback(native, callback, payload, iteration, '0x564f4c54');
    }

    expect(callbacks.every((name) => typeof native[name] === 'function')).toBe(true);
  });

  it('truncated and syntactically invalid JSON callbacks fail soft', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    await loadDashboard({ bridge: createVoltBridgeFixture() });
    const native = window.VoltTrackerNative;
    const random = mulberry32(0x42524944);
    const callbacks = Object.keys(native);
    const prefixes = ['{', '[', '"', '{"state":', '[null,', '{"points":[', '{"ok":false,'];

    for (let iteration = 0; iteration < 1_000; iteration += 1) {
      const callback = callbacks[iteration % callbacks.length];
      const payload = `${prefixes[Math.floor(random() * prefixes.length)]}${randomString(random)}`;
      invokeFuzzCallback(native, callback, payload, iteration, '0x42524944');
    }
  });
});

function invokeFuzzCallback(native, callback, payload, iteration, seed) {
  try {
    native[callback](payload);
  } catch (error) {
    throw new Error(
      `native payload fuzz failure seed=${seed} iteration=${iteration} callback=${callback} payload=${payload}`,
      { cause: error },
    );
  }
}

function mulberry32(seed) {
  let state = seed >>> 0;
  return () => {
    state += 0x6d2b79f5;
    let value = state;
    value = Math.imul(value ^ (value >>> 15), value | 1);
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61);
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296;
  };
}

function randomJsonValue(random, depth) {
  const choice = Math.floor(random() * (depth >= 3 ? 6 : 8));
  if (choice === 0) return null;
  if (choice === 1) return random() < 0.5;
  if (choice === 2) return Math.floor(random() * 2_000_000) - 1_000_000;
  if (choice === 3) return random() < 0.1 ? Number.MAX_VALUE : randomString(random);
  if (choice === 4) return '';
  if (choice === 5) return [randomJsonValue(random, depth + 1), randomJsonValue(random, depth + 1)];
  const object = {};
  const fields = ['state', 'detail', 'points', 'storage', 'latestTelemetry', 'session', 'ok', 'error'];
  for (let index = 0; index < 1 + Math.floor(random() * 5); index += 1) {
    object[fields[Math.floor(random() * fields.length)]] = randomJsonValue(random, depth + 1);
  }
  return object;
}

function randomString(random) {
  const alphabet = '0123456789ABCDEF xyz!?\r\n\t\u0000éß';
  let value = '';
  const length = Math.floor(random() * 80);
  for (let index = 0; index < length; index += 1) {
    value += alphabet[Math.floor(random() * alphabet.length)];
  }
  return value;
}
