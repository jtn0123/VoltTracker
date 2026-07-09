import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

describe('dashboard enhanced capability evidence', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  it('renders persisted enhanced PID discovery results from storage', () => {
    const VD = window.VoltDashboard;

    VD.setStorage({
      fieldCapabilityCount: 2,
      detailedSignalCatalog: [
        {
          key: 'maintenance.oil_temp.221154',
          category: 'maintenance',
          header: 'ATSH7E0',
          command: '221154',
          pid: '1154',
          name: 'engine oil temperature',
          unit: 'C',
          pollLane: 'thermal',
          scanStage: 'low-risk',
          risk: 'low',
          validationStatus: 'confirmed',
          source: 'Volt community PID sheet',
          notes: 'Confirmed on the real car.',
        },
        {
          key: 'odometer.passive.120',
          category: 'odometer',
          header: 'CAN:120',
          command: 'CAN:120',
          pid: 'CAN:120',
          name: 'odometer',
          unit: 'km',
          pollLane: 'passive',
          scanStage: 'passive',
          risk: 'safe',
          validationStatus: 'candidate',
          source: 'GM Volt reverse engineering wiki',
          notes: 'Requires a passive monitor.',
        },
        {
          key: 'battery.coolant.pump',
          category: 'battery',
          header: 'ATSH7E4',
          command: '22F00A',
          pid: 'F00A',
          name: 'battery coolant pump RPM',
          unit: 'rpm',
          pollLane: 'diagnostic_only',
          scanStage: 'experimental',
          risk: 'medium',
          validationStatus: 'candidate',
          source: 'research candidate',
          notes: 'Candidate only.',
        },
      ],
      enhancedCapabilities: [
        {
          header: 'ATSH7E0',
          id: 10,
          command: '221154',
          pid: '1154',
          name: 'engine oil temperature',
          supported: true,
          responseCount: 1,
          lastSeenMs: Date.now(),
          sample: {
            pollLane: 'warm',
            scanStage: 'low-risk',
            risk: 'low',
            validationStatus: 'confirmed',
            rawResponse: '62115460',
          },
        },
        {
          header: 'ATSH760',
          id: 11,
          command: '224051',
          pid: '4051',
          name: 'candidate tire receiver slot 1',
          supported: false,
          responseCount: 0,
          lastSeenMs: Date.now(),
          sample: {
            pollLane: 'slow',
            scanStage: 'tires',
            risk: 'medium',
            validationStatus: 'candidate',
            rawResponse: 'NO DATA',
          },
        },
      ],
    });

    expect(document.getElementById('enhancedTitle').textContent).toBe('4 detailed signals tracked');
    expect(document.getElementById('enhancedConfirmedCount').textContent).toBe('1');
    expect(document.getElementById('enhancedRejectedCount').textContent).toBe('1');
    expect(document.getElementById('enhancedCandidateCount').textContent).toBe('1');
    expect(document.getElementById('enhancedDeferredCount').textContent).toBe('1');
    expect(document.querySelectorAll('#enhancedCapabilityList .enhanced-capability-item')).toHaveLength(4);
    expect(document.getElementById('enhancedCapabilityList').textContent).toContain('engine oil temperature');
    expect(document.getElementById('enhancedCapabilityList').textContent).toContain('working');
    expect(document.getElementById('enhancedCapabilityList').textContent).toContain('candidate tire receiver slot 1');
    expect(document.getElementById('enhancedCapabilityList').textContent).toContain('no hit');
    expect(document.getElementById('enhancedCapabilityList').textContent).toContain('battery coolant pump RPM');
    expect(document.querySelectorAll('[data-signal-export]')).toHaveLength(2);

    document.querySelector('[data-signal-stage="experimental"]').click();

    expect(document.getElementById('signalStageLabel').textContent).toBe('Experimental');
    expect(document.getElementById('enhancedNextList').textContent).toContain('battery coolant pump RPM');

    document.querySelector('[data-signal-filter="deferred"]').click();

    expect(document.querySelectorAll('#enhancedCapabilityList .enhanced-capability-item')).toHaveLength(1);
    expect(document.getElementById('enhancedCapabilityList').textContent).toContain('odometer');
    expect(document.getElementById('enhancedCapabilityList').textContent).toContain('deferred');
  });

  it('does not rebuild the detailed-signal list on an unchanged broadcast, preserving focus', () => {
    const VD = window.VoltDashboard;
    VD.setStorage({
      fieldCapabilityCount: 2,
      enhancedCapabilities: [
        {
          header: 'ATSH7E0', id: 10, command: '221154', pid: '1154',
          name: 'engine oil temperature', supported: true, responseCount: 1, lastSeenMs: 1000,
          sample: { pollLane: 'warm', scanStage: 'low-risk', risk: 'low', validationStatus: 'confirmed' },
        },
        {
          header: 'ATSH7E4', id: 12, command: '22F00A', pid: 'F00A',
          name: 'battery coolant pump RPM', supported: true, responseCount: 3, lastSeenMs: 2000,
          sample: { pollLane: 'warm', scanStage: 'experimental', risk: 'medium', validationStatus: 'confirmed' },
        },
      ],
    });
    const list = document.getElementById('enhancedCapabilityList');
    const firstRow = list.querySelector('.enhanced-capability-item');
    expect(firstRow).not.toBeNull();

    // Re-render with identical storage — the rows must be the SAME nodes.
    VD.updateEnhancedCapabilityUi();
    expect(list.querySelector('.enhanced-capability-item')).toBe(firstRow);

    // Focus an Export button; an unchanged re-render must not steal focus.
    const exportBtn = list.querySelector('[data-signal-export]');
    exportBtn.focus();
    expect(document.activeElement).toBe(exportBtn);
    VD.updateEnhancedCapabilityUi();
    expect(document.activeElement).toBe(exportBtn);

    // A real change (fresh last-seen) rebuilds.
    VD.state.storage.enhancedCapabilities[0].lastSeenMs = 9999;
    VD.updateEnhancedCapabilityUi();
    expect(list.querySelector('.enhanced-capability-item')).not.toBe(firstRow);
  });
});
