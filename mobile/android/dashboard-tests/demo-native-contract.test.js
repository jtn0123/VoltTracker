import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

// Demo↔native shape contract.
//
// The browser demo (VD.loadDemoScenario / loadSampleData) is what every
// dogfooding pass, Playwright render, and screenshot baseline runs against —
// so it MUST mirror the JSON the real Android layer hands the WebView. If the
// demo invents a field the app can never emit, we'd be polishing UI against
// fiction; if it drops a load-bearing field, dogfooding silently stops covering
// a real surface.
//
// Rather than hand-copy the native shape (which rots), this test reads the Java
// emitters at run time and extracts their `.put("key", …)` literals. The
// contract is therefore always whatever the source of truth currently emits —
// add a key in Java and this test starts requiring the demo to stay a subset of
// it; rename an emitter and the locator below fails loudly instead of passing
// vacuously.
const JAVA_ROOT = resolve('../app/src/main/java/com/volttracker/obdpoc');

function readJava(relPath) {
  return readFileSync(resolve(JAVA_ROOT, relPath), 'utf8');
}

// Captures one Java method body by brace-matching from a unique signature
// fragment. Throws if the fragment isn't found or the braces never balance, so
// a refactor that moves/renames an emitter surfaces as a hard failure here.
function methodBody(src, signatureFragment) {
  const start = src.indexOf(signatureFragment);
  if (start === -1) {
    throw new Error(`contract: could not locate method "${signatureFragment}" — did the Java emitter move/rename?`);
  }
  const open = src.indexOf('{', start);
  if (open === -1) throw new Error(`contract: no body brace after "${signatureFragment}"`);
  let depth = 0;
  for (let i = open; i < src.length; i += 1) {
    if (src[i] === '{') depth += 1;
    else if (src[i] === '}') {
      depth -= 1;
      if (depth === 0) return src.slice(open + 1, i);
    }
  }
  throw new Error(`contract: unbalanced braces for "${signatureFragment}"`);
}

// All distinct string-literal keys passed to `.put("…", …)` in a method body.
function putKeys(body) {
  const keys = new Set();
  const re = /\.put\(\s*"([^"]+)"/g;
  let m;
  while ((m = re.exec(body)) !== null) keys.add(m[1]);
  return keys;
}

function union(...sets) {
  const out = new Set();
  for (const s of sets) for (const k of s) out.add(k);
  return out;
}

// ---- Native contracts, derived live from the Java source of truth ----------
const storageJava = readJava('StorageSummaryJson.java');
const reportsJava = readJava('data/ObdStoreReports.java');
const appStateJava = readJava('AppStatePayload.java');
const dtcJava = readJava('data/DiagnosticCodeReport.java');

// state.storage top level = the keys build() puts directly, plus the flattened
// "last*" keys putLatestSession() merges onto the same payload object.
const NATIVE_STORAGE = union(
  putKeys(methodBody(storageJava, 'JSONObject build(StorageSummaryRecord record)')),
  putKeys(methodBody(storageJava, 'putLatestSession(JSONObject payload, ObdSessionRecord latest)')),
);
const NATIVE_RECENT_SESSION = putKeys(
  methodBody(storageJava, 'JSONArray recentSessionsJson(StorageSummaryRecord record)'),
);
const NATIVE_CHARGE_SUMMARY = putKeys(
  methodBody(reportsJava, 'JSONObject chargeSummaryJson(SQLiteDatabase db)'),
);
const NATIVE_CHARGE_ROW = putKeys(
  methodBody(reportsJava, 'JSONObject chargeSessionRowJson(Cursor cursor)'),
);
const NATIVE_DTC = putKeys(methodBody(dtcJava, 'JSONObject toJson()'));
// appState.vehicle = vehicleJson()'s own keys plus the latestVehicle row it
// merges in key-by-key (storage.optJSONObject("latestVehicle")).
const NATIVE_VEHICLE = union(
  putKeys(methodBody(appStateJava, 'JSONObject vehicleJson()')),
  putKeys(methodBody(reportsJava, 'JSONObject latestVehicleJson(SQLiteDatabase db)')),
);

// Fields the Vehicle card (panels.js#renderVehicleUi) renders but NO native
// builder emits yet: electric-mix % and battery-health %. The demo previews the
// intended card; until a PID/derivation feeds these the real app shows "--".
// Listed here so the gap is explicit and reviewed — a NEW demo-only field still
// fails the contract. Tracked separately to either compute these natively or
// drop them from the card.
const VEHICLE_FORWARD_LOOKING = new Set(['evSharePct', 'batteryHealthPct']);

function loadVD() {
  const VD = window.VoltDashboard;
  return VD;
}

describe('demo ↔ native shape contract', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  it('extracted native contracts are non-empty (parser sanity)', () => {
    // If an emitter is renamed the locator throws; this guards the subtler case
    // where the regex silently matches nothing.
    expect(NATIVE_STORAGE.size).toBeGreaterThan(10);
    expect(NATIVE_RECENT_SESSION.size).toBeGreaterThan(5);
    expect(NATIVE_CHARGE_SUMMARY.size).toBeGreaterThan(3);
    expect(NATIVE_CHARGE_ROW.size).toBeGreaterThan(5);
    expect(NATIVE_DTC.size).toBeGreaterThan(5);
    expect(NATIVE_VEHICLE.size).toBeGreaterThan(5);
  });

  // Every key the demo produces, across every scenario, must be a key the
  // native layer can produce — otherwise the UI is being exercised against a
  // field the real app never sends.
  for (const scenario of ['typical', 'power-user', 'fault', 'extreme', 'empty']) {
    it(`${scenario}: demo storage keys are a subset of the native contract`, () => {
      const VD = loadVD();
      VD.loadDemoScenario(scenario);
      const s = VD.state.storage || {};

      const fabricated = Object.keys(s).filter((k) => !NATIVE_STORAGE.has(k));
      expect(fabricated, `storage keys not emitted by StorageSummaryJson`).toEqual([]);

      if (Array.isArray(s.recentSessions) && s.recentSessions[0]) {
        const bad = Object.keys(s.recentSessions[0]).filter((k) => !NATIVE_RECENT_SESSION.has(k));
        expect(bad, 'recentSessions[] row keys not in native contract').toEqual([]);
      }

      const cs = s.chargeSummary || {};
      const csBad = Object.keys(cs).filter((k) => !NATIVE_CHARGE_SUMMARY.has(k));
      expect(csBad, 'chargeSummary keys not in native contract').toEqual([]);

      if (Array.isArray(cs.recentSessions) && cs.recentSessions[0]) {
        const bad = Object.keys(cs.recentSessions[0]).filter((k) => !NATIVE_CHARGE_ROW.has(k));
        expect(bad, 'charge session row keys not in native contract').toEqual([]);
      }

      if (Array.isArray(s.latestDiagnosticCodes) && s.latestDiagnosticCodes[0]) {
        const bad = Object.keys(s.latestDiagnosticCodes[0]).filter((k) => !NATIVE_DTC.has(k));
        expect(bad, 'DTC row keys not in native contract').toEqual([]);
      }
    });
  }

  it('demo vehicle keys are native, except documented forward-looking fields', () => {
    const VD = loadVD();
    VD.loadDemoScenario('typical');
    const vehicle = (VD.state.appState || {}).vehicle || {};
    const fabricated = Object.keys(vehicle).filter(
      (k) => !NATIVE_VEHICLE.has(k) && !VEHICLE_FORWARD_LOOKING.has(k),
    );
    expect(fabricated, 'vehicle keys neither native nor in the forward-looking allowlist').toEqual([]);
  });

  it('forward-looking vehicle allowlist stays minimal (no longer-needed entries)', () => {
    // If native ever starts emitting one of these, this fails so we delete the
    // allowlist entry instead of letting it mask a now-real field.
    const stale = [...VEHICLE_FORWARD_LOOKING].filter((k) => NATIVE_VEHICLE.has(k));
    expect(stale, 'allowlisted fields native now emits — remove from VEHICLE_FORWARD_LOOKING').toEqual([]);
  });

  // The other direction: the demo must keep exercising the load-bearing fields
  // the dashboard actually reads, so dogfooding doesn't quietly skip a surface.
  it('typical demo covers the load-bearing native fields the UI reads', () => {
    const VD = loadVD();
    VD.loadDemoScenario('typical');
    const s = VD.state.storage || {};

    const MUST_COVER_STORAGE = [
      'sessionCount', 'sampleCount', 'recentSessions', 'chargeSummary',
      'batterySummary', 'latestDiagnosticCodes', 'overview', 'enhancedCapabilities',
      'detailedSignalCatalog', 'recentRoutes',
    ];
    const missing = MUST_COVER_STORAGE.filter((k) => !(k in s));
    expect(missing, 'demo dropped a load-bearing storage field').toEqual([]);

    const MUST_COVER_CHARGE_ROW = ['id', 'startedAtMs', 'endSoc', 'powerKw', 'energyKwh'];
    const row = (s.chargeSummary && s.chargeSummary.recentSessions && s.chargeSummary.recentSessions[0]) || {};
    const rowMissing = MUST_COVER_CHARGE_ROW.filter((k) => !(k in row));
    expect(rowMissing, 'demo charge row dropped a field the Charge tab renders').toEqual([]);

    const MUST_COVER_DTC = ['dtc', 'status', 'statusLabel', 'seenCount'];
    const dtc = (Array.isArray(s.latestDiagnosticCodes) && s.latestDiagnosticCodes[0]) || {};
    const dtcMissing = MUST_COVER_DTC.filter((k) => !(k in dtc));
    expect(dtcMissing, 'demo DTC row dropped a field the Diag tab renders').toEqual([]);
  });
});
