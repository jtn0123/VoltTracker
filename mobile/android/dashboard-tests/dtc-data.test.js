import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const DASHBOARD_TS = resolve(HERE, '../app/src/main/dashboard-src/js');
const CODE_RE = /^[PBCU][0-9A-F]{4}$/;
const VALID_SEVERITIES = new Set(['info', 'warning', 'critical']);

function sourceFor(file) {
  const base = file.replace(/\.(js|ts)$/u, '');
  const candidate = resolve(DASHBOARD_TS, `${base}.ts`);
  if (existsSync(candidate)) return readFileSync(candidate, 'utf8');
  throw new Error(`Missing dashboard DTC TypeScript source for ${file}`);
}

function extractTopLevelDtcKeys(source) {
  return source
    .split('\n')
    .map((line) => line.match(/^\s*"?([PBCU][0-3][0-9A-F]{3})"?\s*:/)?.[1])
    .filter(Boolean);
}

function duplicates(values) {
  const seen = new Set();
  const dupes = new Set();
  for (const value of values) {
    if (seen.has(value)) dupes.add(value);
    seen.add(value);
  }
  return [...dupes].sort();
}

async function freshLoad() {
  document.body.innerHTML = '';
  delete window.VoltDashboard;
  delete window.VoltTrackerNative;
  delete window.VoltTrackerAndroid;
  await loadDashboard();
  await window.VoltDashboard.ensureDtcData();
  return window.VoltDashboard;
}

describe('DTC data integrity', () => {
  let VD;

  it('does not load the large dictionaries during the default dashboard bootstrap', async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();

    expect(window.VoltDashboard.dtcInfo).toBeUndefined();
    expect(window.VoltDashboard.dtcLookupCodes).toBeUndefined();
    expect(typeof window.VoltDashboard.ensureDtcData).toBe('function');
  });

  beforeEach(async () => {
    VD = await freshLoad();
  });

  it('keeps lookup keys unique, shaped like DTCs, and counted by the runtime', () => {
    const keys = VD.dtcLookupCodes || [];
    expect(keys.length).toBeGreaterThan(3000);
    expect(duplicates(keys)).toEqual([]);
    expect(keys.every((key) => CODE_RE.test(key))).toBe(true);
    expect(VD.dtcLookupSize).toBe(keys.length);
  });

  it('builds a prefix-family index for future chunking', () => {
    const keys = VD.dtcLookupCodes || [];
    const families = VD.dtcLookupFamilyCounts || {};
    const counted = Object.values(families).reduce((sum, count) => sum + count, 0);

    expect(counted).toBe(keys.length);
    expect(families.P04).toBeGreaterThan(0);
    expect(families.U00).toBeGreaterThan(0);
    expect(Object.keys(families).every((family) => /^[PBCU][0-9A-F]{2}$/.test(family))).toBe(true);
  });

  it('keeps the lazy DTC source tables within the documented scale budget', () => {
    const lookupLines = sourceFor('dtc-lookup.js').split('\n').length;
    const causeLines = sourceFor('dtc-causes.js').split('\n').length;

    expect(lookupLines + causeLines).toBeLessThan(8000);
  });

  it('keeps cause entries unique and valid', () => {
    const sourceKeys = extractTopLevelDtcKeys(sourceFor('dtc-causes.js'));
    expect(sourceKeys.length).toBeGreaterThan(300);
    expect(duplicates(sourceKeys)).toEqual([]);

    const causeEntries = Object.entries(VD.DTC_CAUSES || {});
    expect(causeEntries.length).toBe(sourceKeys.length);
    for (const [code, info] of causeEntries) {
      expect(code, 'code shape').toMatch(CODE_RE);
      expect(Array.isArray(info.causes), `${code} causes`).toBe(true);
      expect(info.causes.length, `${code} cause count`).toBeGreaterThan(0);
      expect(info.causes.every((cause) => typeof cause === 'string' && cause.trim().length > 0)).toBe(true);
      expect(VALID_SEVERITIES.has(info.severity), `${code} severity`).toBe(true);
      expect(typeof info.category, `${code} category`).toBe('string');
      expect(info.category.trim().length, `${code} category`).toBeGreaterThan(0);
    }
  });

  it('preserves known Volt and generic sample lookups', () => {
    expect(VD.dtcInfo('p0aa6')).toMatchObject({
      code: 'P0AA6',
      known: true,
      severity: 'critical',
    });
    expect(VD.dtcInfo('P0420')).toMatchObject({
      code: 'P0420',
      known: true,
      severity: 'warning',
    });
    expect(VD.dtcInfo('U0073')).toMatchObject({
      code: 'U0073',
      known: true,
    });
    expect(VD.dtcInfo('C07B0')).toMatchObject({
      code: 'C07B0',
      category: 'TPMS',
      severity: 'info',
    });
  });
});
