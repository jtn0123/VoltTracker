// i18n.ts — minimal string-lookup module. These tests pin the contract the
// dashboard relies on: keys resolve from the English default, registered locale
// overrides take precedence, partial overrides fall back to English (never to an
// empty string), `{placeholder}` interpolation substitutes params, and locale
// resolution from navigator.language only activates a locale that was registered.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

describe('i18n string lookup', () => {
  // Re-import per test: the module keeps module-level active-locale + override
  // state, so a shared instance would leak registrations between cases.
  let EN, t, registerLocale, setLocale, getLocale, baseTag, resolveDeviceLocale, resetI18n;

  beforeEach(async () => {
    vi.resetModules();
    ({ EN, t, registerLocale, setLocale, getLocale, baseTag, resolveDeviceLocale, resetI18n } =
      await import('../app/src/main/dashboard-src/js/i18n.ts'));
  });

  afterEach(() => {
    resetI18n();
    vi.restoreAllMocks();
  });

  it('returns the English default for a known key', () => {
    expect(t('status.detail.ready')).toBe('Ready.');
    expect(t('status.logging.notLogging')).toBe('Not logging');
    expect(getLocale()).toBe('en');
  });

  it('interpolates {placeholder} tokens from params', () => {
    expect(t('status.logging.samples', { count: 1234 })).toBe('1234 samples');
    expect(t('status.logging.samples', { count: '1,234' })).toBe('1,234 samples');
  });

  it('leaves an unmatched placeholder intact when a param is missing', () => {
    // Visible-in-dev behavior: a missing param surfaces as the literal token
    // rather than silently blanking, so the gap is obvious.
    expect(t('status.logging.samples')).toBe('{count} samples');
    expect(t('status.logging.samples', {})).toBe('{count} samples');
  });

  it('prefers a registered locale override over English', () => {
    registerLocale('es', {
      'status.detail.ready': 'Listo.',
      'status.logging.samples': '{count} muestras',
    });
    setLocale('es');
    expect(getLocale()).toBe('es');
    expect(t('status.detail.ready')).toBe('Listo.');
    expect(t('status.logging.samples', { count: 5 })).toBe('5 muestras');
  });

  it('falls back to English for keys a partial override omits', () => {
    registerLocale('es', { 'status.detail.ready': 'Listo.' });
    setLocale('es');
    // Not in the es override -> English, never an empty string.
    expect(t('status.logging.notLogging')).toBe('Not logging');
    expect(t('status.logging.notLogging')).not.toBe('');
  });

  it('normalizes region/script suffixes to a base tag', () => {
    expect(baseTag('es-MX')).toBe('es');
    expect(baseTag('EN_us')).toBe('en');
    expect(baseTag('')).toBe('');
    expect(baseTag(null)).toBe('');
    expect(baseTag(undefined)).toBe('');
  });

  it('matches a region-qualified locale to a base override', () => {
    registerLocale('es', { 'status.detail.ready': 'Listo.' });
    setLocale('es-419');
    expect(getLocale()).toBe('es');
    expect(t('status.detail.ready')).toBe('Listo.');
  });

  it('ignores an unregistered locale and keeps resolving to English', () => {
    setLocale('de-DE');
    expect(getLocale()).toBe('en');
    expect(t('status.detail.ready')).toBe('Ready.');
  });

  it('resolves the device locale from navigator.language when registered', () => {
    registerLocale('es', { 'status.detail.ready': 'Listo.' });
    vi.spyOn(navigator, 'language', 'get').mockReturnValue('es-ES');
    expect(resolveDeviceLocale()).toBe('es');
    expect(t('status.detail.ready')).toBe('Listo.');
  });

  it('keeps every English key non-empty (catalog integrity)', () => {
    for (const key of Object.keys(EN)) {
      expect(typeof EN[key]).toBe('string');
      expect(EN[key].length).toBeGreaterThan(0);
    }
  });
});
