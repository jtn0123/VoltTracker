import { confirmAppDialog, promptAppDialog } from "./app-dialog";

export type BusyButton = HTMLElement & {
  disabled?: boolean;
};

export type BusyGuard = <T>(button: BusyButton | null | undefined, fn: () => T) => T | undefined;

type StorageActionContext = {
  VD: VoltDashboard;
  bridge: VoltBridge | null;
  withBusy: BusyGuard;
};

function logBridgeFailure(bridge: VoltBridge | null, label: string, detail: string, err: unknown) {
  if (!bridge || typeof bridge.logClientError !== "function") return;
  const errorDetail = err instanceof Error && err.message ? err.message : String(err || "");
  try {
    bridge.logClientError(label, errorDetail ? `${detail} ${errorDetail}` : detail);
  } catch (_ignored) {}
}

export function createStorageActions({ VD, bridge, withBusy }: StorageActionContext) {
  function writeClipboard(text: string) {
    const nav = window.navigator;
    if (nav.clipboard && typeof nav.clipboard.writeText === "function") {
      return nav.clipboard.writeText(text);
    }
    if (typeof document.execCommand !== "function") {
      return Promise.reject(new Error("Clipboard unavailable"));
    }
    const area = document.createElement("textarea");
    area.value = text;
    area.setAttribute("readonly", "true");
    area.style.position = "fixed";
    area.style.left = "-9999px";
    document.body.append(area);
    area.select();
    try {
      const copied = document.execCommand("copy");
      return copied ? Promise.resolve() : Promise.reject(new Error("Copy command failed"));
    } catch (err) {
      return Promise.reject(err);
    } finally {
      area.remove();
    }
  }

  function downloadTextFile(text: string, filename: string) {
    let url: string | null = null;
    let link: HTMLAnchorElement | null = null;
    try {
      if (typeof Blob !== "function" || !window.URL || typeof window.URL.createObjectURL !== "function") return false;
      const blob = new Blob([text], { type: "application/json" });
      url = window.URL.createObjectURL(blob);
      link = document.createElement("a");
      link.href = url;
      link.download = filename;
      link.rel = "noopener";
      document.body.append(link);
      link.click();
      return true;
    } catch (_err) {
      return false;
    } finally {
      link?.remove();
      if (url) {
        const revokeUrl = url;
        window.setTimeout(() => window.URL.revokeObjectURL(revokeUrl), 0);
      }
    }
  }

  function refreshStorage() {
    if (!bridge) return;
    try {
      if (typeof bridge.requestStorageSummary === "function" && bridge.requestStorageSummary()) return;
      if (typeof bridge.getStorageSummary === "function") VD.setStorage(bridge.getStorageSummary());
    } catch (err) {
      const detail = "Could not refresh storage summary.";
      VD.setStatus({ state: "blocked", detail });
      logBridgeFailure(bridge, "storage_summary_failed", detail, err);
    }
  }

  function clearStorage(button?: BusyButton | null) {
    if (!bridge || typeof bridge.clearStoredData !== "function") return;
    void confirmAppDialog({
      title: "Clear stored data",
      message: "Clear local OBD sessions, samples, and debug events from this phone?",
      confirmLabel: "Clear data"
    }).then((confirmed) => {
      if (!confirmed) return;
      try {
        withBusy(button, () => {
          bridge.clearStoredData();
        });
      } catch (err) {
        const detail = "Could not clear stored data.";
        VD.setStatus({ state: "blocked", detail });
        logBridgeFailure(bridge, "clear_storage_failed", detail, err);
      }
    });
  }

  function shareBackup(button?: BusyButton | null) {
    if (!bridge || typeof bridge.shareBackup !== "function") {
      VD.setStatus({ state: "idle", detail: "Backup is only available inside the Android app." });
      return;
    }
    void confirmAppDialog({
      title: "Share plaintext backup",
      message:
        "Plaintext backup includes your GPS routes, every OBD sample, and adapter history.\n\n" +
        "Use encrypted backup unless another tool specifically needs the raw database. Continue?",
      confirmLabel: "Share plaintext"
    }).then((ok) => {
      if (!ok) {
        VD.setStatus({ state: "ready", detail: "Backup cancelled." });
        return;
      }
      try {
        withBusy(button, () => bridge.shareBackup());
      } catch (err) {
        const detail = "Could not start backup share.";
        VD.setStatus({ state: "blocked", detail });
        logBridgeFailure(bridge, "share_backup_failed", detail, err);
      }
    });
  }

  function shareEncryptedBackup(button?: BusyButton | null) {
    if (!bridge || typeof bridge.shareEncryptedBackup !== "function") {
      VD.setStatus({ state: "idle", detail: "Encrypted backup is only available inside the Android app." });
      return;
    }
    void promptAppDialog({
      title: "Encrypt backup",
      message: "Choose a passphrase for this encrypted backup. You will need it to restore.",
      inputLabel: "Backup passphrase",
      confirmLabel: "Share encrypted",
      autocomplete: "new-password"
    }).then((passphrase) => {
      if (!passphrase) {
        VD.setStatus({ state: "ready", detail: "Encrypted backup cancelled." });
        return;
      }
      try {
        withBusy(button, () => bridge.shareEncryptedBackup(passphrase));
      } catch (err) {
        const detail = "Could not start encrypted backup share.";
        VD.setStatus({ state: "blocked", detail });
        logBridgeFailure(bridge, "share_encrypted_backup_failed", detail, err);
      }
    });
  }

  function restoreBackup(button?: BusyButton | null) {
    if (!bridge || typeof bridge.restoreBackup !== "function") {
      VD.setStatus({ state: "idle", detail: "Restore is only available inside the Android app." });
      return;
    }
    try {
      withBusy(button, () => bridge.restoreBackup());
    } catch (err) {
      const detail = "Could not start restore.";
      VD.setStatus({ state: "blocked", detail });
      logBridgeFailure(bridge, "restore_backup_failed", detail, err);
    }
  }

  function restoreEncryptedBackup(button?: BusyButton | null) {
    if (!bridge || typeof bridge.restoreEncryptedBackup !== "function") {
      VD.setStatus({ state: "idle", detail: "Encrypted restore is only available inside the Android app." });
      return;
    }
    void promptAppDialog({
      title: "Restore encrypted backup",
      message: "Enter the passphrase for this encrypted backup.",
      inputLabel: "Backup passphrase",
      confirmLabel: "Choose file"
    }).then((passphrase) => {
      if (!passphrase) {
        VD.setStatus({ state: "ready", detail: "Encrypted restore cancelled." });
        return;
      }
      try {
        withBusy(button, () => bridge.restoreEncryptedBackup(passphrase));
      } catch (err) {
        const detail = "Could not start encrypted restore.";
        VD.setStatus({ state: "blocked", detail });
        logBridgeFailure(bridge, "restore_encrypted_backup_failed", detail, err);
      }
    });
  }

  function exportDebugBundle() {
    if (!bridge || typeof bridge.exportDebugBundle !== "function") {
      VD.setStatus({ state: "idle", detail: "Debug export is only available inside the Android app." });
      return;
    }
    let result: VoltExportResult;
    try {
      result = VD.parsePayload<VoltExportResult>(bridge.exportDebugBundle(), {});
    } catch (err) {
      const detail = "Debug export failed.";
      VD.setStatus({ state: "blocked", detail });
      logBridgeFailure(bridge, "debug_export_failed", detail, err);
      return;
    }
    if (result.ok) {
      const content = typeof result.content === "string" ? result.content : "";
      const filename =
        typeof result.filename === "string" && result.filename.trim()
          ? result.filename.trim()
          : "volttracker-debug-summary.json";
      if (!content) {
        VD.setStatus({ state: "blocked", detail: "Debug export did not include downloadable content." });
        return;
      }
      if (downloadTextFile(content, filename)) {
        VD.setStatus({ state: "ready", detail: "Debug summary exported." });
        return;
      }
      writeClipboard(content)
        .then(() => VD.setStatus({ state: "ready", detail: "Debug summary copied." }))
        .catch(() => VD.setStatus({ state: "blocked", detail: "Could not export the debug summary." }));
    } else {
      VD.setStatus({ state: "blocked", detail: result.error || "Debug export failed." });
    }
  }

  return {
    refreshStorage,
    clearStorage,
    shareBackup,
    shareEncryptedBackup,
    restoreBackup,
    restoreEncryptedBackup,
    exportDebugBundle
  };
}

window.VoltDashboardActionModules = window.VoltDashboardActionModules || {};
window.VoltDashboardActionModules.createStorageActions = createStorageActions;
