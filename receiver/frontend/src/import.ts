/**
 * VoltTracker - Import Module
 * CSV import handling, status display, and result modals
 */

import { state } from '@/core';
import { loadTrips } from '@/trips';
import { loadSummary, loadMpgTrend } from '@/summary';
// Lazy import — battery is a dynamically loaded chunk
const loadSocAnalysis = async () => (await import('@/battery')).loadSocAnalysis();
import type { ImportResult } from '@/types/api';
import { ImportStatus } from '@/types/enums';

/**
 * Handle CSV import form submission (supports multiple files)
 */
export async function handleImport(event: Event): Promise<void> {
  event.preventDefault();

  const fileInput = document.getElementById('csv-file') as HTMLInputElement | null;
  const importBtn = document.getElementById('import-btn') as HTMLButtonElement | null;

  if (!fileInput || !fileInput.files?.length) {
    showImportStatus('Please select CSV files', 'error');
    return;
  }

  const files = Array.from(fileInput.files);
  const totalFiles = files.length;

  if (importBtn) importBtn.disabled = true;
  state.lastImportResults = [];

  let totalImported = 0;
  let totalSkipped = 0;
  let totalDuplicates = 0;
  const failedFiles: string[] = [];

  for (let i = 0; i < files.length; i++) {
    const file = files[i];
    showImportStatus(`Importing ${i + 1} of ${totalFiles}: ${file.name}...`, 'loading');

    const formData = new FormData();
    formData.append('file', file);

    try {
      console.log(`[Import] Starting import for: ${file.name}`);
      const response = await fetch('/api/import/csv', {
        method: 'POST',
        body: formData,
      });
      console.log(`[Import] Response status: ${response.status} ${response.statusText}`);

      let data: ImportResult;
      try {
        data = await response.json();
        console.log(`[Import] Response data:`, data);
      } catch (parseError) {
        console.error(`[Import] JSON parse error for ${file.name}:`, parseError);
        state.lastImportResults.push({
          filename: file.name,
          status: ImportStatus.Failed,
          failure_reason: 'invalid_response',
          message: 'Invalid server response',
          import_code: null,
          stats: { total_rows: 0, parsed_rows: 0, skipped_rows: 0, duplicate_rows: 0, columns_detected: [] },
        });
        failedFiles.push(file.name);
        continue;
      }

      state.lastImportResults.push({ filename: file.name, ...data });

      const status = data.status || (response.ok ? ImportStatus.Success : ImportStatus.Failed);
      const parsedRows = data.stats?.parsed_rows || 0;
      const duplicateRows = data.stats?.duplicate_rows || 0;

      if (status === ImportStatus.Success || status === ImportStatus.Partial) {
        totalImported += parsedRows;
        totalSkipped += data.stats?.skipped_rows || 0;
        totalDuplicates += duplicateRows;
      } else if (status === ImportStatus.Duplicate) {
        console.log(`[Import] Duplicate file: ${file.name} (${data.original_import_code})`);
      } else {
        failedFiles.push(file.name);
      }
    } catch (error) {
      console.error(`[Import] Network/JS error for ${file.name}:`, error);
      state.lastImportResults.push({
        filename: file.name,
        status: ImportStatus.Failed,
        failure_reason: 'network_error',
        message: (error as Error).message,
        import_code: null,
        stats: { total_rows: 0, parsed_rows: 0, skipped_rows: 0, duplicate_rows: 0, columns_detected: [] },
      });
      failedFiles.push(file.name);
    }
  }

  if (totalFiles === 1 && state.lastImportResults.length === 1) {
    showImportResultModal(state.lastImportResults[0]);
    const result = state.lastImportResults[0];
    if (result.status === ImportStatus.Success || result.status === ImportStatus.Partial) {
      showSuccess(`Import complete: ${result.stats?.parsed_rows || 0} records added`, 4000);
    } else if (result.status === ImportStatus.Duplicate) {
      showInfo('File already imported previously', 3000);
    } else {
      showError(`Import failed: ${result.message || 'Unknown error'}`);
    }
  } else {
    let message = `Imported ${totalImported} records from ${totalFiles - failedFiles.length} files.`;
    if (totalSkipped > 0) message += ` Skipped ${totalSkipped} invalid rows.`;
    if (totalDuplicates > 0) message += ` ${totalDuplicates} duplicate rows detected.`;
    if (failedFiles.length > 0) {
      message += ` Failed: ${failedFiles.join(', ')}`;
      showImportStatus(message, 'error');
      showError(`Import errors: ${failedFiles.length} of ${totalFiles} files failed`);
    } else {
      showImportStatus(message, 'success');
      showSuccess(`Successfully imported ${totalImported} records from ${totalFiles} files`, 5000);
    }
  }

  loadTrips();
  loadSummary();
  loadMpgTrend(state.currentTimeframe);
  loadSocAnalysis();

  fileInput.value = '';
  const fileName = document.getElementById('file-name');
  if (fileName) fileName.textContent = 'No file selected';
  if (importBtn) importBtn.disabled = false;
}

/**
 * Show import status message in the status bar
 */
export function showImportStatus(message: string, type: string): void {
  const statusDiv = document.getElementById('import-status');
  if (statusDiv) {
    statusDiv.textContent = message;
    statusDiv.className = `import-status show ${type}`;
  }
}

/**
 * Show import result in modal dialog
 */
export function showImportResultModal(data: ImportResult): void {
  const modal = document.getElementById('import-result-modal') as HTMLElement | null;
  if (!modal) return;

  const codeEl = document.getElementById('import-code-text');
  if (codeEl) codeEl.textContent = data.import_code || 'N/A';

  const statusEl = document.getElementById('import-status-badge');
  if (statusEl) {
    const status = data.status || 'unknown';
    statusEl.textContent = status.toUpperCase();
    statusEl.className = `import-status-badge status-${status}`;
  }

  const messageEl = document.getElementById('import-message');
  if (messageEl) messageEl.textContent = data.message || '';

  const stats = data.stats || {
    total_rows: 0,
    parsed_rows: 0,
    skipped_rows: 0,
    duplicate_rows: 0,
    columns_detected: [],
  };

  const setVal = (id: string, val: number) => {
    const el = document.getElementById(id);
    if (el) el.textContent = String(val);
  };
  setVal('import-total-rows', stats.total_rows);
  setVal('import-parsed-rows', stats.parsed_rows);
  setVal('import-skipped-rows', stats.skipped_rows);
  setVal('import-duplicate-rows', stats.duplicate_rows);

  const errorSection = document.getElementById('import-error-section');
  if (errorSection) {
    if (data.failure_reason) {
      errorSection.style.display = 'block';
      const reasonEl = document.getElementById('import-error-reason');
      if (reasonEl) reasonEl.textContent = data.failure_reason;
      const suggestionEl = document.getElementById('import-error-suggestion');
      if (suggestionEl) suggestionEl.textContent = data.suggestion || '';
    } else {
      errorSection.style.display = 'none';
    }
  }

  const columnsDetails = document.getElementById('import-columns-details');
  const columnsList = document.getElementById('import-columns-list');
  if (columnsDetails && columnsList) {
    if (stats.columns_detected && stats.columns_detected.length > 0) {
      columnsDetails.style.display = 'block';
      columnsList.textContent = stats.columns_detected.join(', ');
    } else {
      columnsDetails.style.display = 'none';
    }
  }

  modal.dataset.reportable = data.reportable || generateReportable(data);

  modal.classList.add('show');
  modal.setAttribute('aria-hidden', 'false');
  document.body.style.overflow = 'hidden';
}

/**
 * Generate reportable string if not provided by server
 */
export function generateReportable(data: ImportResult): string {
  const parts = [data.import_code || 'UNKNOWN', (data.status || 'UNKNOWN').toUpperCase()];

  if (data.failure_reason) parts.push(data.failure_reason);

  const stats = data.stats || {
    total_rows: 0,
    parsed_rows: 0,
    skipped_rows: 0,
    duplicate_rows: 0,
    columns_detected: [],
  };
  parts.push(`${stats.parsed_rows || 0}/${stats.total_rows || 0} rows`);

  if (data.trip_id) parts.push(`trip_id=${data.trip_id}`);

  return parts.join(' | ');
}

/**
 * Close import result modal
 */
export function closeImportResultModal(): void {
  const modal = document.getElementById('import-result-modal');
  if (modal) {
    modal.classList.remove('show');
    modal.setAttribute('aria-hidden', 'true');
    document.body.style.overflow = '';
  }
}

/**
 * Copy just the import code to clipboard
 */
export function copyImportCode(): void {
  const codeEl = document.getElementById('import-code-text');
  if (codeEl) {
    navigator.clipboard
      .writeText(codeEl.textContent || '')
      .then(() => {
        showToast('Import code copied');
      })
      .catch((err) => {
        console.error('Failed to copy:', err);
      });
  }
}

/**
 * Copy full import report to clipboard
 */
export function copyImportReport(): void {
  const modal = document.getElementById('import-result-modal') as HTMLElement | null;
  const reportable = modal?.dataset?.reportable;

  if (reportable) {
    navigator.clipboard
      .writeText(reportable)
      .then(() => {
        showToast('Report copied to clipboard');
      })
      .catch((err) => {
        console.error('Failed to copy:', err);
      });
  }
}
