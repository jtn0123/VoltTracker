/**
 * Tests for import.ts module
 * Covers: generateReportable, showImportStatus, showImportResultModal,
 * closeImportResultModal, copyImportCode, copyImportReport, handleImport.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// Mock trips/summary/battery so handleImport's post-import refresh is a no-op
vi.mock('@/trips', () => ({
  loadTrips: vi.fn(),
}));

vi.mock('@/summary', () => ({
  loadSummary: vi.fn(),
  loadMpgTrend: vi.fn(),
}));

vi.mock('@/battery', () => ({
  loadSocAnalysis: vi.fn(),
}));

// Mock core — we only need DEBUG and the state object
vi.mock('@/core', () => ({
  DEBUG: false,
  state: {
    lastImportResults: [],
    currentTimeframe: 30,
  },
}));

const MODAL_HTML = `
  <div id="import-status"></div>
  <div id="import-result-modal" aria-hidden="true">
    <span id="import-code-text"></span>
    <span id="import-message"></span>
    <span id="import-status-badge"></span>
    <span id="import-total-rows"></span>
    <span id="import-parsed-rows"></span>
    <span id="import-skipped-rows"></span>
    <span id="import-duplicate-rows"></span>
    <div id="import-error-section" style="display:none">
      <span id="import-error-reason"></span>
      <span id="import-error-suggestion"></span>
    </div>
    <div id="import-columns-details" style="display:none">
      <span id="import-columns-list"></span>
    </div>
  </div>
  <input type="file" id="csv-file" />
  <button id="import-btn"></button>
  <div id="file-name">No file selected</div>
`;

describe('import module', () => {
  beforeEach(() => {
    document.body.innerHTML = MODAL_HTML;
    // Clipboard stub — setup.ts doesn't touch navigator.clipboard
    (navigator as unknown as { clipboard: { writeText: ReturnType<typeof vi.fn> } }).clipboard = {
      writeText: vi.fn().mockResolvedValue(undefined),
    };
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe('generateReportable', () => {
    it('builds a pipe-delimited string for a successful import', async () => {
      const { generateReportable } = await import('../import');
      const out = generateReportable({
        import_code: 'IMP-123',
        status: 'success',
        message: 'ok',
        stats: {
          total_rows: 100,
          parsed_rows: 98,
          skipped_rows: 2,
          duplicate_rows: 0,
          columns_detected: ['timestamp', 'soc'],
        },
      } as unknown as Parameters<typeof generateReportable>[0]);

      expect(out).toBe('IMP-123 | SUCCESS | 98/100 rows');
    });

    it('includes failure_reason when present and trip_id when provided', async () => {
      const { generateReportable } = await import('../import');
      const out = generateReportable({
        import_code: 'IMP-ERR',
        status: 'failed',
        message: 'bad',
        failure_reason: 'bad_header',
        trip_id: 42,
        stats: {
          total_rows: 10,
          parsed_rows: 0,
          skipped_rows: 10,
          duplicate_rows: 0,
          columns_detected: [],
        },
      } as unknown as Parameters<typeof generateReportable>[0]);

      expect(out).toContain('IMP-ERR');
      expect(out).toContain('FAILED');
      expect(out).toContain('bad_header');
      expect(out).toContain('0/10 rows');
      expect(out).toContain('trip_id=42');
    });

    it('falls back to UNKNOWN when code/status/stats are missing', async () => {
      const { generateReportable } = await import('../import');
      const out = generateReportable({} as unknown as Parameters<typeof generateReportable>[0]);
      expect(out).toContain('UNKNOWN');
      expect(out).toContain('0/0 rows');
    });
  });

  describe('showImportStatus', () => {
    it('sets the text and class on #import-status', async () => {
      const { showImportStatus } = await import('../import');
      showImportStatus('Uploading...', 'loading');

      const el = document.getElementById('import-status');
      expect(el?.textContent).toBe('Uploading...');
      expect(el?.className).toBe('import-status show loading');
    });

    it('is a no-op when #import-status is missing', async () => {
      document.body.innerHTML = '';
      const { showImportStatus } = await import('../import');
      expect(() => showImportStatus('hi', 'success')).not.toThrow();
    });
  });

  describe('showImportResultModal + closeImportResultModal', () => {
    it('populates modal fields and toggles visibility', async () => {
      const { showImportResultModal, closeImportResultModal } = await import('../import');

      showImportResultModal({
        import_code: 'IMP-XYZ',
        status: 'success',
        message: 'All good',
        stats: {
          total_rows: 50,
          parsed_rows: 49,
          skipped_rows: 1,
          duplicate_rows: 0,
          columns_detected: ['a', 'b', 'c'],
        },
      } as unknown as Parameters<typeof showImportResultModal>[0]);

      const modal = document.getElementById('import-result-modal');
      expect(modal?.classList.contains('show')).toBe(true);
      expect(modal?.getAttribute('aria-hidden')).toBe('false');
      expect(document.getElementById('import-code-text')?.textContent).toBe('IMP-XYZ');
      expect(document.getElementById('import-message')?.textContent).toBe('All good');
      expect(document.getElementById('import-status-badge')?.textContent).toBe('SUCCESS');
      expect(document.getElementById('import-total-rows')?.textContent).toBe('50');
      expect(document.getElementById('import-parsed-rows')?.textContent).toBe('49');

      // Columns section should be visible, with joined list
      const colDetails = document.getElementById('import-columns-details');
      expect(colDetails?.style.display).toBe('block');
      expect(document.getElementById('import-columns-list')?.textContent).toBe('a, b, c');

      // Error section stays hidden
      expect(document.getElementById('import-error-section')?.style.display).toBe('none');

      // reportable should have been generated and stashed on dataset
      expect(modal?.dataset.reportable).toContain('IMP-XYZ');

      closeImportResultModal();
      expect(modal?.classList.contains('show')).toBe(false);
      expect(modal?.getAttribute('aria-hidden')).toBe('true');
      expect(document.body.style.overflow).toBe('');
    });

    it('shows the error section when failure_reason is set', async () => {
      const { showImportResultModal } = await import('../import');
      showImportResultModal({
        import_code: null,
        status: 'failed',
        message: 'bad',
        failure_reason: 'missing_columns',
        suggestion: 'add timestamp column',
        stats: {
          total_rows: 0,
          parsed_rows: 0,
          skipped_rows: 0,
          duplicate_rows: 0,
          columns_detected: [],
        },
      } as unknown as Parameters<typeof showImportResultModal>[0]);

      const errSection = document.getElementById('import-error-section');
      expect(errSection?.style.display).toBe('block');
      expect(document.getElementById('import-error-reason')?.textContent).toBe('missing_columns');
      expect(document.getElementById('import-error-suggestion')?.textContent).toBe('add timestamp column');
      // code_text falls back to N/A
      expect(document.getElementById('import-code-text')?.textContent).toBe('N/A');
    });

    it('is a no-op when #import-result-modal is missing', async () => {
      document.body.innerHTML = '';
      const { showImportResultModal, closeImportResultModal } = await import('../import');
      expect(() =>
        showImportResultModal({
          import_code: 'X',
          status: 'success',
          message: '',
          stats: {
            total_rows: 0,
            parsed_rows: 0,
            skipped_rows: 0,
            duplicate_rows: 0,
            columns_detected: [],
          },
        } as unknown as Parameters<typeof showImportResultModal>[0])
      ).not.toThrow();
      expect(() => closeImportResultModal()).not.toThrow();
    });
  });

  describe('clipboard helpers', () => {
    it('copyImportCode writes the import code to clipboard', async () => {
      const codeEl = document.getElementById('import-code-text');
      if (codeEl) codeEl.textContent = 'IMP-COPY';

      const { copyImportCode } = await import('../import');
      copyImportCode();

      expect(navigator.clipboard.writeText).toHaveBeenCalledWith('IMP-COPY');
    });

    it('copyImportReport writes the stored reportable string', async () => {
      const modal = document.getElementById('import-result-modal');
      if (modal) modal.dataset.reportable = 'IMP-123 | SUCCESS | 1/1 rows';

      const { copyImportReport } = await import('../import');
      copyImportReport();

      expect(navigator.clipboard.writeText).toHaveBeenCalledWith('IMP-123 | SUCCESS | 1/1 rows');
    });

    it('copyImportReport is a no-op when no reportable is stored', async () => {
      const { copyImportReport } = await import('../import');
      copyImportReport();
      expect(navigator.clipboard.writeText).not.toHaveBeenCalled();
    });
  });

  describe('handleImport', () => {
    it('shows an error status when no files are selected', async () => {
      const { handleImport } = await import('../import');
      const event = { preventDefault: vi.fn() } as unknown as Event;

      await handleImport(event);

      expect(event.preventDefault).toHaveBeenCalled();
      const statusEl = document.getElementById('import-status');
      expect(statusEl?.textContent).toBe('Please select CSV files');
      expect(statusEl?.className).toContain('error');
    });

    it('uploads a single file and shows the result modal on success', async () => {
      const { state } = await import('@/core');
      (state as { lastImportResults: unknown[] }).lastImportResults = [];

      vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue({
          status: 200,
          statusText: 'OK',
          json: () =>
            Promise.resolve({
              import_code: 'IMP-OK',
              status: 'success',
              message: 'imported',
              stats: {
                total_rows: 5,
                parsed_rows: 5,
                skipped_rows: 0,
                duplicate_rows: 0,
                columns_detected: ['timestamp'],
              },
            }),
        })
      );

      const { handleImport } = await import('../import');
      const file = new File(['timestamp\n2025-01-01'], 'trip.csv', { type: 'text/csv' });
      const input = document.getElementById('csv-file') as HTMLInputElement;
      Object.defineProperty(input, 'files', {
        value: [file],
        configurable: true,
      });

      await handleImport({ preventDefault: vi.fn() } as unknown as Event);

      expect(fetch).toHaveBeenCalledWith(
        '/api/import/csv',
        expect.objectContaining({ method: 'POST' })
      );
      const modal = document.getElementById('import-result-modal');
      expect(modal?.classList.contains('show')).toBe(true);
      expect(document.getElementById('import-code-text')?.textContent).toBe('IMP-OK');
      expect((state as { lastImportResults: unknown[] }).lastImportResults.length).toBe(1);

      // Post-import UI reset
      expect(input.value).toBe('');
      expect(document.getElementById('file-name')?.textContent).toBe('No file selected');
      const btn = document.getElementById('import-btn') as HTMLButtonElement;
      expect(btn.disabled).toBe(false);
    });

    it('records a failed upload when fetch throws', async () => {
      const { state } = await import('@/core');
      (state as { lastImportResults: unknown[] }).lastImportResults = [];

      vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));

      const { handleImport } = await import('../import');
      const file = new File(['x'], 'bad.csv', { type: 'text/csv' });
      const input = document.getElementById('csv-file') as HTMLInputElement;
      Object.defineProperty(input, 'files', {
        value: [file],
        configurable: true,
      });

      await handleImport({ preventDefault: vi.fn() } as unknown as Event);

      const results = (state as { lastImportResults: Array<{ status: string; failure_reason?: string }> })
        .lastImportResults;
      expect(results.length).toBe(1);
      expect(results[0].status).toBe('failed');
      expect(results[0].failure_reason).toBe('network_error');
    });
  });
});
