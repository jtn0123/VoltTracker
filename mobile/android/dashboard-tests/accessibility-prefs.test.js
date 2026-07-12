// M9 — Preferences "Accessibility" section. prefs.ts persists a font-scale
// multiplier (--font-scale on :root) and a high-contrast theme (data-contrast
// on :root), swapping the centralized CSS tokens (base.css). This suite drives
// the real bootPrefsUi wiring (loaded on import) against the a11y controls.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// The a11y controls as they appear in the generated Preferences partial.
const A11Y_DOM = `
  <div class="pref-card">
    <div class="card-head"><div></div></div>
    <fieldset class="pref-row pref-fieldset">
      <legend class="pref-label"><strong>Text size</strong></legend>
      <div class="pref-seg" role="group" aria-label="Text size">
        <button type="button" data-pref-font-scale="1" class="is-active" aria-pressed="true"><span>Default</span></button>
        <button type="button" data-pref-font-scale="1.25" aria-pressed="false"><span>Large</span></button>
        <button type="button" data-pref-font-scale="1.5" aria-pressed="false"><span>Largest</span></button>
      </div>
    </fieldset>
    <div class="pref-row">
      <div class="pref-label"><strong>High contrast</strong></div>
      <button type="button" id="prefContrastToggle" class="tile-toggle pref-toggle"
        data-pref-contrast-toggle data-on="false" aria-pressed="false"
        aria-label="High-contrast theme is off">Off</button>
    </div>
  </div>`;

async function loadPrefs({ clear = true } = {}) {
  if (clear) window.localStorage.clear();
  document.body.innerHTML = A11Y_DOM;
  // prefs.ts boots its UI at most once per document (C1 lazy-chunk guard);
  // this helper re-executes the module against a rebuilt DOM, so reset the
  // once-flag or the fresh elements would never get their listeners.
  delete document.__voltPrefsUiBooted;
  vi.resetModules();
  await import('../app/src/main/dashboard-src/js/prefs.ts');
  return window.VoltDashboard.prefs;
}

function rootScale() {
  return document.documentElement.style.getPropertyValue('--font-scale').trim();
}

function fontScaleButton(value) {
  return document.querySelector(`[data-pref-font-scale="${value}"]`);
}

describe('accessibility prefs (M9)', () => {
  beforeEach(() => {
    window.localStorage.clear();
    document.documentElement.style.removeProperty('--font-scale');
    document.documentElement.removeAttribute('data-contrast');
  });
  afterEach(() => {
    window.localStorage.clear();
    document.body.innerHTML = '';
    document.documentElement.style.removeProperty('--font-scale');
    document.documentElement.removeAttribute('data-contrast');
  });

  it('applies a default scale of 1 and no high-contrast at boot when unset', async () => {
    await loadPrefs();
    expect(rootScale()).toBe('1');
    expect(document.documentElement.hasAttribute('data-contrast')).toBe(false);
    expect(fontScaleButton('1').getAttribute('aria-pressed')).toBe('true');
    expect(fontScaleButton('1.25').getAttribute('aria-pressed')).toBe('false');
  });

  it('persists and applies a chosen font scale, and reflects it on the control', async () => {
    // One load → one click, so the single document-level click listener from
    // this module's import fires exactly once (re-importing prefs.ts stacks a
    // fresh global listener, so multi-load + click in one test is avoided).
    const prefs = await loadPrefs();
    fontScaleButton('1.25').click();
    expect(prefs.get('fontScale', 1)).toBe(1.25);
    expect(rootScale()).toBe('1.25');
    expect(fontScaleButton('1.25').getAttribute('aria-pressed')).toBe('true');
    expect(fontScaleButton('1').getAttribute('aria-pressed')).toBe('false');
  });

  it('reapplies a persisted font scale at boot', async () => {
    // Seed the stored pref directly, then boot once: the boot path must apply
    // and reflect it without a click.
    window.localStorage.setItem('vt.pref.fontScale', '1.5');
    await loadPrefs({ clear: false });
    expect(rootScale()).toBe('1.5');
    expect(fontScaleButton('1.5').getAttribute('aria-pressed')).toBe('true');
    expect(fontScaleButton('1').getAttribute('aria-pressed')).toBe('false');
  });

  it('turns the high-contrast theme on from a single toggle click', async () => {
    const prefs = await loadPrefs();
    const toggle = document.getElementById('prefContrastToggle');
    toggle.click();
    expect(prefs.get('highContrast', false)).toBe(true);
    expect(document.documentElement.getAttribute('data-contrast')).toBe('high');
    expect(toggle.getAttribute('aria-pressed')).toBe('true');
    expect(toggle.dataset.on).toBe('true');
    expect(toggle.textContent).toBe('On');
  });

  it('reapplies a persisted high-contrast attr at boot', async () => {
    window.localStorage.setItem('vt.pref.highContrast', 'true');
    await loadPrefs({ clear: false });
    expect(document.documentElement.getAttribute('data-contrast')).toBe('high');
    const toggle = document.getElementById('prefContrastToggle');
    expect(toggle.getAttribute('aria-pressed')).toBe('true');
    expect(toggle.textContent).toBe('On');
  });

  it('clamps a corrupt/out-of-range stored scale into the 1.0–1.5 range', async () => {
    window.localStorage.setItem('vt.pref.fontScale', '9');
    await loadPrefs({ clear: false });
    expect(rootScale()).toBe('1.5');
  });
});

describe('accessibility CSS tokens (M9)', () => {
  it('defines a scale-aware font token and a high-contrast override block', async () => {
    const { readFileSync } = await import('node:fs');
    const { dirname, resolve } = await import('node:path');
    const { fileURLToPath } = await import('node:url');
    const here = dirname(fileURLToPath(import.meta.url));
    const baseCss = readFileSync(
      resolve(here, '../app/src/main/assets/dashboard/css/base.css'),
      'utf8',
    );
    // --font-scale exists and the heading tokens multiply through it.
    expect(baseCss).toMatch(/--font-scale\s*:\s*1\s*;/);
    expect(baseCss).toMatch(/--font-h1\s*:\s*calc\(\s*30px\s*\*\s*var\(--font-scale\)\s*\)/);
    // The root font-size scales so relative text grows too.
    expect(baseCss).toMatch(/font-size\s*:\s*calc\(100%\s*\*\s*var\(--font-scale\)\)/);
    // A high-contrast token override block keyed off data-contrast="high".
    expect(baseCss).toMatch(/:root\[data-contrast="high"\]\s*\{/);
  });
});
