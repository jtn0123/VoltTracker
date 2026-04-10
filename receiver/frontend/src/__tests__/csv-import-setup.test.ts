/**
 * Tests for csv-import-setup.ts
 *
 * Regression coverage for JTN-486: the submit listener on #import-form must
 * call event.preventDefault() SYNCHRONOUSLY, before the lazy-loaded import
 * module resolves. Otherwise the browser falls through to a default GET
 * submission and navigates the page to `/?`, and the POST to /api/import/csv
 * never fires.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { setupCsvImport } from '../csv-import-setup';

const FORM_HTML = `
  <form id="import-form" aria-label="CSV import form" method="post" action="#">
    <label for="csv-file">
      Choose CSV Files
      <input type="file" id="csv-file" accept=".csv" required hidden multiple />
    </label>
    <span id="file-name">No file selected</span>
    <button type="submit" id="import-btn" disabled>Import</button>
  </form>
  <div id="import-status"></div>
`;

describe('setupCsvImport (JTN-486 regression)', () => {
  beforeEach(() => {
    document.body.innerHTML = FORM_HTML;
  });

  it('calls event.preventDefault() synchronously on submit', () => {
    // Simulate a slow lazy-loaded import module — the promise never resolves
    // within this test. The fix is only correct if preventDefault() is called
    // before awaiting this promise.
    const handleImport = vi.fn();
    const showImportStatus = vi.fn();
    let _resolve: ((m: { handleImport: typeof handleImport; showImportStatus: typeof showImportStatus }) => void) | null = null;
    const slowGetImport = vi.fn(
      () =>
        new Promise<{ handleImport: typeof handleImport; showImportStatus: typeof showImportStatus }>((resolve) => {
          _resolve = resolve;
        }),
    );

    setupCsvImport(
      slowGetImport as unknown as () => Promise<typeof import('../import')>,
    );

    const form = document.getElementById('import-form') as HTMLFormElement;
    const submitEvent = new Event('submit', { bubbles: true, cancelable: true });
    const preventSpy = vi.spyOn(submitEvent, 'preventDefault');

    form.dispatchEvent(submitEvent);

    // preventDefault must have been called by the time dispatchEvent returns,
    // i.e. synchronously from the listener.
    expect(preventSpy).toHaveBeenCalledTimes(1);
    expect(submitEvent.defaultPrevented).toBe(true);

    // The lazy loader should have been kicked off, but handleImport should
    // not have been called yet because the module promise is still pending.
    expect(slowGetImport).toHaveBeenCalledTimes(1);
    expect(handleImport).not.toHaveBeenCalled();
  });

  it('invokes handleImport from the lazy-loaded module once it resolves', async () => {
    const handleImport = vi.fn().mockResolvedValue(undefined);
    const showImportStatus = vi.fn();
    const getImport = vi
      .fn()
      .mockResolvedValue({ handleImport, showImportStatus });

    setupCsvImport(getImport as unknown as () => Promise<typeof import('../import')>);

    const form = document.getElementById('import-form') as HTMLFormElement;
    const submitEvent = new Event('submit', { bubbles: true, cancelable: true });
    form.dispatchEvent(submitEvent);

    // Flush the microtask queue so the .then() callback runs.
    await Promise.resolve();
    await Promise.resolve();

    expect(getImport).toHaveBeenCalledTimes(1);
    expect(handleImport).toHaveBeenCalledTimes(1);
    expect(handleImport).toHaveBeenCalledWith(submitEvent);
  });

  it('swallows a rejected import module load without throwing', async () => {
    const consoleErr = vi.spyOn(console, 'error').mockImplementation(() => {});
    const getImport = vi.fn().mockRejectedValue(new Error('chunk load failed'));

    setupCsvImport(getImport as unknown as () => Promise<typeof import('../import')>);

    const form = document.getElementById('import-form') as HTMLFormElement;
    const submitEvent = new Event('submit', { bubbles: true, cancelable: true });
    expect(() => form.dispatchEvent(submitEvent)).not.toThrow();

    // Wait for the rejection to propagate through the .catch handler.
    await Promise.resolve();
    await Promise.resolve();

    expect(submitEvent.defaultPrevented).toBe(true);
    expect(consoleErr).toHaveBeenCalled();
    consoleErr.mockRestore();
  });

  it('wires the file input change handler to update the label and enable the button', () => {
    const getImport = vi.fn();
    setupCsvImport(getImport as unknown as () => Promise<typeof import('../import')>);

    const fileInput = document.getElementById('csv-file') as HTMLInputElement;
    const importBtn = document.getElementById('import-btn') as HTMLButtonElement;
    const fileName = document.getElementById('file-name')!;

    // Start: no files -> button disabled, label says "No file selected".
    fileInput.dispatchEvent(new Event('change'));
    expect(importBtn.disabled).toBe(true);
    expect(fileName.textContent).toBe('No file selected');

    // One file -> label shows filename, button enabled.
    const singleFile = new File(['timestamp\n2025-01-01'], 'trip.csv', {
      type: 'text/csv',
    });
    Object.defineProperty(fileInput, 'files', {
      value: [singleFile],
      configurable: true,
    });
    fileInput.dispatchEvent(new Event('change'));
    expect(importBtn.disabled).toBe(false);
    expect(fileName.textContent).toBe('trip.csv');

    // Multiple files -> label shows count.
    const f1 = new File([''], 'a.csv', { type: 'text/csv' });
    const f2 = new File([''], 'b.csv', { type: 'text/csv' });
    Object.defineProperty(fileInput, 'files', {
      value: [f1, f2],
      configurable: true,
    });
    fileInput.dispatchEvent(new Event('change'));
    expect(importBtn.disabled).toBe(false);
    expect(fileName.textContent).toBe('2 files selected');
  });

  it('click on a disabled import button calls preventDefault and shows a status message', async () => {
    const handleImport = vi.fn();
    const showImportStatus = vi.fn();
    const getImport = vi
      .fn()
      .mockResolvedValue({ handleImport, showImportStatus });

    setupCsvImport(getImport as unknown as () => Promise<typeof import('../import')>);

    const importBtn = document.getElementById('import-btn') as HTMLButtonElement;
    expect(importBtn.disabled).toBe(true);

    const clickEvent = new MouseEvent('click', { bubbles: true, cancelable: true });
    const preventSpy = vi.spyOn(clickEvent, 'preventDefault');
    importBtn.dispatchEvent(clickEvent);

    expect(preventSpy).toHaveBeenCalled();

    await Promise.resolve();
    await Promise.resolve();

    expect(showImportStatus).toHaveBeenCalledWith(
      'Please select CSV files first',
      'error',
    );
  });

  it('does nothing silently when #import-form is missing', () => {
    document.body.innerHTML = '';
    const getImport = vi.fn();
    expect(() =>
      setupCsvImport(getImport as unknown as () => Promise<typeof import('../import')>),
    ).not.toThrow();
  });
});
