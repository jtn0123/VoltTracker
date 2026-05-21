/**
 * Tests for core.ts utility functions
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const { escapeHtml, sanitizeNumber, sanitizeDate, formatDate, formatChartDate, formatDateTime, formatTime, formatDuration, getElapsedTime } = await import('../core');

describe('escapeHtml', () => {
  it('escapes HTML special characters', () => {
    expect(escapeHtml('<script>alert("xss")</script>')).toBe('&lt;script&gt;alert("xss")&lt;/script&gt;');
  });

  it('returns empty string for null/undefined', () => {
    expect(escapeHtml(null)).toBe('');
    expect(escapeHtml(undefined)).toBe('');
  });

  it('converts non-strings to string', () => {
    expect(escapeHtml(42)).toBe('42');
    expect(escapeHtml(true)).toBe('true');
  });

  it('handles ampersands', () => {
    expect(escapeHtml('a & b')).toBe('a &amp; b');
  });
});

describe('sanitizeNumber', () => {
  it('formats valid numbers', () => {
    expect(sanitizeNumber(3.14159, 2)).toBe('3.14');
    expect(sanitizeNumber(42)).toBe('42.0');
  });

  it('returns -- for null/undefined/NaN', () => {
    expect(sanitizeNumber(null)).toBe('--');
    expect(sanitizeNumber(undefined)).toBe('--');
    expect(sanitizeNumber(NaN)).toBe('--');
  });

  it('respects decimals parameter', () => {
    expect(sanitizeNumber(1.5, 0)).toBe('2');
    expect(sanitizeNumber(1.5, 3)).toBe('1.500');
  });
});

describe('sanitizeDate', () => {
  it('returns -- for falsy values', () => {
    expect(sanitizeDate(null)).toBe('--');
    expect(sanitizeDate('')).toBe('--');
    expect(sanitizeDate(undefined)).toBe('--');
  });

  it('returns -- for invalid dates', () => {
    expect(sanitizeDate('not-a-date')).toBe('--');
  });

  it('formats valid date strings', () => {
    const result = sanitizeDate('2024-06-15T10:30:00Z');
    expect(result).not.toBe('--');
    expect(result.length).toBeGreaterThan(0);
  });
});

describe('formatDate', () => {
  it('formats date with month and day', () => {
    const date = new Date('2024-06-15T12:00:00Z');
    const result = formatDate(date);
    expect(result).toContain('Jun');
    expect(result).toContain('15');
  });

  it('includes year for different year', () => {
    const date = new Date('2020-01-01T12:00:00Z');
    const result = formatDate(date);
    expect(result).toMatch(/20/);
  });
});

describe('formatChartDate', () => {
  it('always includes year', () => {
    const date = new Date('2024-06-15T12:00:00Z');
    const result = formatChartDate(date);
    expect(result).toContain('24');
  });
});

describe('formatDateTime', () => {
  it('includes time component', () => {
    const date = new Date('2024-06-15T14:30:00Z');
    const result = formatDateTime(date);
    expect(result).toContain('Jun');
    expect(result.length).toBeGreaterThan(6);
  });
});

describe('formatTime', () => {
  it('returns time string', () => {
    const date = new Date('2024-06-15T14:30:00Z');
    const result = formatTime(date);
    expect(result.length).toBeGreaterThan(0);
  });
});

describe('formatDuration', () => {
  it('formats minutes only', () => {
    const start = new Date('2024-01-01T10:00:00Z');
    const end = new Date('2024-01-01T10:45:00Z');
    expect(formatDuration(start, end)).toBe('45 min');
  });

  it('formats hours and minutes', () => {
    const start = new Date('2024-01-01T10:00:00Z');
    const end = new Date('2024-01-01T12:30:00Z');
    expect(formatDuration(start, end)).toBe('2h 30m');
  });

  it('formats exact hours', () => {
    const start = new Date('2024-01-01T10:00:00Z');
    const end = new Date('2024-01-01T12:00:00Z');
    expect(formatDuration(start, end)).toBe('2h');
  });
});

describe('getElapsedTime', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2024-06-15T12:00:00Z'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('returns minutes for short durations', () => {
    expect(getElapsedTime('2024-06-15T11:45:00Z')).toBe('15m');
  });

  it('returns hours and minutes for longer durations', () => {
    expect(getElapsedTime('2024-06-15T09:30:00Z')).toBe('2h 30m');
  });
});
