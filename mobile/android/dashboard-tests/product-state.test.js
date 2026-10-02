import { describe, expect, it } from 'vitest';

import {
  applyDataAvailability,
  deriveDataAvailability,
  deriveProductState,
  renderProductStatusBadge,
} from '../app/src/main/dashboard-src/js/product-state.ts';

function dashboardState(overrides = {}) {
  return {
    appState: {},
    status: {},
    telemetry: {},
    lastSampleAt: 0,
    demoActive: false,
    ...overrides,
  };
}

describe('product-state — one owner-facing vocabulary', () => {
  it('does not expose raw demo/waiting combinations', () => {
    const view = deriveProductState(dashboardState({
      demoActive: true,
      status: { state: 'demo' },
      telemetry: { sampleCount: 0 },
    }));
    expect(view).toMatchObject({
      key: 'preview',
      label: 'Preview mode',
      dataState: 'demo',
      freshness: 'waiting',
    });
    expect(view.label).not.toMatch(/waiting|demo/i);
  });

  it('progresses through connect, ready, recording, and paused states', () => {
    expect(deriveProductState(dashboardState()).label).toBe('Ready to connect');

    expect(deriveProductState(dashboardState({
      status: { state: 'connecting' },
    })).label).toBe('Connecting');

    expect(deriveProductState(dashboardState({
      status: { state: 'connected' },
      appState: { adapter: { connected: true } },
    })).label).toBe('Ready to drive');

    expect(deriveProductState(dashboardState({
      status: { state: 'connected' },
      appState: { adapter: { connected: true }, session: { sampleCount: 4 } },
      telemetry: { sampleCount: 4, updatedAt: 9_000 },
    }), 10_000).label).toBe('Recording drive');

    expect(deriveProductState(dashboardState({
      status: { state: 'connected' },
      appState: { adapter: { connected: true }, session: { sampleCount: 4 } },
      telemetry: { sampleCount: 4, updatedAt: 1_000 },
    }), 10_000).label).toBe('Live data paused');
  });

  it('renders the same product state into the shared badge contract', () => {
    const badge = document.createElement('button');
    const label = document.createElement('span');
    const view = renderProductStatusBadge(dashboardState(), badge, label);

    expect(label.textContent).toBe('Ready to connect');
    expect(badge.dataset.productState).toBe('ready-to-connect');
    expect(badge.dataset.state).toBe('idle');
    expect(badge.getAttribute('aria-label')).toContain(view.detail);
  });
});

describe('product-state — data availability contract', () => {
  it('keeps loading, unsupported, stale, empty, and ready distinct', () => {
    expect(deriveDataAvailability({ loading: true })).toBe('loading');
    expect(deriveDataAvailability({ supported: false })).toBe('unsupported');
    expect(deriveDataAvailability({ hasData: true, stale: true })).toBe('stale');
    expect(deriveDataAvailability({ hasData: false })).toBe('empty');
    expect(deriveDataAvailability({ hasData: true })).toBe('ready');
  });

  it('applies the state to reusable product surfaces', () => {
    const node = document.createElement('section');
    applyDataAvailability(node, 'unsupported');
    expect(node.dataset.availability).toBe('unsupported');
  });
});
