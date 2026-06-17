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

export function createStorageActions({ VD, bridge, withBusy }: StorageActionContext) {
  function refreshStorage() {
    if (bridge && typeof bridge.getStorageSummary === "function") {
      VD.setStorage(bridge.getStorageSummary());
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
      withBusy(button, () => {
        bridge.clearStoredData();
      });
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
      withBusy(button, () => bridge.shareBackup());
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
      confirmLabel: "Share encrypted"
    }).then((passphrase) => {
      if (!passphrase) {
        VD.setStatus({ state: "ready", detail: "Encrypted backup cancelled." });
        return;
      }
      withBusy(button, () => bridge.shareEncryptedBackup(passphrase));
    });
  }

  function restoreBackup(button?: BusyButton | null) {
    if (!bridge || typeof bridge.restoreBackup !== "function") {
      VD.setStatus({ state: "idle", detail: "Restore is only available inside the Android app." });
      return;
    }
    withBusy(button, () => bridge.restoreBackup());
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
      withBusy(button, () => bridge.restoreEncryptedBackup(passphrase));
    });
  }

  function exportDebugBundle() {
    if (!bridge || typeof bridge.exportDebugBundle !== "function") {
      VD.setStatus({ state: "idle", detail: "Debug export is only available inside the Android app." });
      return;
    }
    const result = VD.parsePayload<VoltExportResult>(bridge.exportDebugBundle(), {});
    if (result.ok) {
      VD.setStatus({ state: "ready", detail: `Debug summary exported: ${result.path || "app files"}.` });
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
