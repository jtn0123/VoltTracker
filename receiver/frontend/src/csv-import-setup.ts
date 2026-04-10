/**
 * CSV Import wiring for the dashboard form.
 *
 * Extracted from main.ts so it can be unit-tested in isolation and so the
 * submit handler is not accidentally regressed into the "async listener that
 * forgets to call preventDefault() synchronously" shape. See JTN-486.
 */

type ImportModule = typeof import('./import');

/**
 * Wire up the #import-form on the dashboard.
 *
 * @param getImport  Thunk that lazily loads the import module. Passed in so
 *                   tests can supply a mock and so main.ts retains control
 *                   over the lazy-loading cache.
 */
export function setupCsvImport(getImport: () => Promise<ImportModule>): void {
  const fileInput = document.getElementById('csv-file') as HTMLInputElement | null;
  const fileNameDisplay = document.getElementById('file-name');
  const importBtn = document.getElementById('import-btn') as HTMLButtonElement | null;
  const importForm = document.getElementById('import-form');

  if (fileInput) {
    fileInput.addEventListener('change', () => {
      const count = fileInput.files?.length || 0;
      if (fileNameDisplay) {
        if (count > 1) {
          fileNameDisplay.textContent = `${count} files selected`;
        } else if (count === 1) {
          fileNameDisplay.textContent = fileInput.files![0].name;
        } else {
          fileNameDisplay.textContent = 'No file selected';
        }
      }
      if (importBtn) importBtn.disabled = count === 0;
    });
  }

  if (importForm) {
    // IMPORTANT: preventDefault() MUST run synchronously, before any `await`
    // or promise resolution. If it runs inside an async callback, the browser
    // has already started the default GET form submission by the time the
    // lazy-loaded import module resolves, which navigates the page to `/?`
    // and the POST to /api/import/csv never fires. See JTN-486.
    importForm.addEventListener('submit', (e) => {
      e.preventDefault();
      getImport()
        .then((mod) => mod.handleImport(e))
        .catch((err) => {
          console.error('[VoltTracker] Failed to load import module:', err);
        });
    });
  }

  if (importBtn) {
    importBtn.addEventListener('click', (e) => {
      if (importBtn.disabled) {
        e.preventDefault();
        getImport()
          .then((mod) => mod.showImportStatus('Please select CSV files first', 'error'))
          .catch((err) => {
            console.error('[VoltTracker] Failed to load import module:', err);
          });
      }
    });
  }
}
