/**
 * VoltTracker - Import Module
 * CSV import handling, status display, and result modals
 */

import { DEBUG, state } from '@/core';
import { loadTrips } from '@/trips';
import { loadSummary, loadMpgTrend } from '@/summary';
// Lazy import — battery is a dynamically loaded chunk
const loadSocAnalysis = async () => (await import('@/battery')).loadSocAnalysis();
import type { ImportResult } from '@/types/api';
import { ImportStatus } from '@/types/enums';

/** Create a failed import result entry. */
function failedResult(filename: string, reason: string, message: string): ImportResult & { filename: string } {
  return {
    filename,
    status: ImportStatus.Failed,
    failure_reason: reason,
    message,
    import_code: null,
    stats: { total_rows: 0, parsed_rows: 0, skipped_rows: 0, duplicate_rows: 0, columns_detected: [] },
  };
}

/** Import a single file and return the result. */
async function importSingleFile(file: File): Promise<ImportResult & { filename: string }> {
  const formData = new FormData();
  formData.append('file', file);

  try {
    if (DEBUG) console.log(`[Import] Starting import for: ${file.name}`);
    const response = await fetch('/api/import/csv', { method: 'POST', body: formData });
    if (DEBUG) console.log(`[Import] Response status: ${response.status} ${response.statusText}`);

    let data: ImportResult;
    try {
      data = await response.json();
      if (DEBUG) console.log(`[Import] Response data:`, data);
    } catch (parseError) {
      console.error(`[Import] JSON parse error for ${file.name}:`, parseError);
      return failedResult(file.name, 'invalid_response', 'Invalid server response');
    }

    return { filename: file.name, ...data };
  } catch (error) {
    console.error(`[Import] Network/JS error for ${file.name}:`, error);
    return failedResult(file.name, 'network_error', (error as Error).message);
  }
}

/** Show toast/status for a single-file import result. */
function showSingleFileResult(result: ImportResult & { filename: string }): void {
  showImportResultModal(result);
  if (result.status === ImportStatus.Success || result.status === ImportStatus.Partial) {
    showSuccess(`Import complete: ${result.stats?.parsed_rows || 0} records added`, 4000);
  } else if (result.status === ImportStatus.Duplicate) {
    showInfo('File already imported previously', 3000);
  } else {
    showError(`Import failed: ${result.message || 'Unknown error'}`);
  }
}

/** Show toast/status for a multi-file import batch. */
function showMultiFileResult(totalImported: number, totalSkipped: number, totalDuplicates: number,
                              failedFiles: string[], totalFiles: number): void {
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

/**
 * Handle CSV import form submission (supports multiple files)
 */
export async function handleImport(event: Event): Promise<void> {
  event.preventDefault();

  const fileInput = document.getElementById('csv-file') as HTMLInputElement | null;
  const importBtn = document.getElementById('import-btn') as HTMLButtonElement | null;

  if (!fileInput?.files?.length) {
    showImportStatus('Please select CSV files', 'error');
    return;
  }

  const files = Array.from(fileInput.files);
  if (importBtn) importBtn.disabled = true;
  state.lastImportResults = [];

  let totalImported = 0, totalSkipped = 0, totalDuplicates = 0;
  const failedFiles: string[] = [];

  for (let i = 0; i < files.length; i++) {
    showImportStatus(`Importing ${i + 1} of ${files.length}: ${files[i].name}...`, 'loading');
    const result = await importSingleFile(files[i]);
    state.lastImportResults.push(result);

    const status = result.status || ImportStatus.Failed;
    if (status === ImportStatus.Success || status === ImportStatus.Partial) {
      totalImported += result.stats?.parsed_rows || 0;
      totalSkipped += result.stats?.skipped_rows || 0;
      totalDuplicates += result.stats?.duplicate_rows || 0;
    } else if (status === ImportStatus.Duplicate) {
      if (DEBUG) console.log(`[Import] Duplicate file: ${files[i].name}`);
    } else {
      failedFiles.push(files[i].name);
    }
  }

  if (files.length === 1 && state.lastImportResults.length === 1) {
    showSingleFileResult({ ...state.lastImportResults[0], filename: state.lastImportResults[0].filename ?? files[0].name });
  } else {
    showMultiFileResult(totalImported, totalSkipped, totalDuplicates, failedFiles, files.length);
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

/** Populate import error section in the modal. */
function populateImportErrorSection(data: ImportResult): void {
  const errorSection = document.getElementById('import-error-section');
  if (!errorSection) return;
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

/** Populate detected columns section in the modal. */
function populateImportColumnsSection(stats: ImportResult['stats']): void {
  const details = document.getElementById('import-columns-details');
  const list = document.getElementById('import-columns-list');
  if (!details || !list) return;
  if (stats?.columns_detected?.length) {
    details.style.display = 'block';
    list.textContent = stats.columns_detected.join(', ');
  } else {
    details.style.display = 'none';
  }
}

/**
 * Show import result in modal dialog
 */
export function showImportResultModal(data: ImportResult): void {
  const modal = document.getElementById('import-result-modal');
  if (!modal) return;

  const setEl = (id: string, text: string) => { const el = document.getElementById(id); if (el) el.textContent = text; };

  setEl('import-code-text', data.import_code || 'N/A');
  setEl('import-message', data.message || '');

  const statusEl = document.getElementById('import-status-badge');
  if (statusEl) {
    const status = data.status || 'unknown';
    statusEl.textContent = status.toUpperCase();
    statusEl.className = `import-status-badge status-${status}`;
  }

  const stats = data.stats || { total_rows: 0, parsed_rows: 0, skipped_rows: 0, duplicate_rows: 0, columns_detected: [] };
  setEl('import-total-rows', String(stats.total_rows));
  setEl('import-parsed-rows', String(stats.parsed_rows));
  setEl('import-skipped-rows', String(stats.skipped_rows));
  setEl('import-duplicate-rows', String(stats.duplicate_rows));

  populateImportErrorSection(data);
  populateImportColumnsSection(stats);

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
  const modal = document.getElementById('import-result-modal');
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
