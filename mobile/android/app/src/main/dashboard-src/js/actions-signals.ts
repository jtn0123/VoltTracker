import { confirmAppDialog } from "./app-dialog";

type SignalActionContext = {
  VD: VoltDashboard;
  bridge: VoltBridge | null;
};

function writeClipboard(text: unknown) {
  const nav = window.navigator;
  if (nav.clipboard && typeof nav.clipboard.writeText === "function") {
    return nav.clipboard.writeText(String(text));
  }
  if (typeof document.execCommand !== "function") {
    return Promise.reject(new Error("Clipboard unavailable"));
  }
  const area = document.createElement("textarea");
  area.value = String(text);
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

function jsonText(payload: unknown) {
  return JSON.stringify(payload, null, 2);
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

function reportBridgeFailure(VD: VoltDashboard, method: string, err: unknown) {
  if (typeof VD.reportClientError !== "function") return;
  const detail = err instanceof Error && err.message ? err.message : String(err || "");
  VD.reportClientError("bridge.call_failed", `bridge.${method} failed${detail ? `: ${detail}` : ""}`);
}

function deliverExport(VD: VoltDashboard, payload: unknown, filename: string, exportedDetail: string, copiedDetail: string, failureDetail: string) {
  const text = jsonText(payload);
  if (downloadTextFile(text, filename)) {
    VD.setStatus({ state: "ready", detail: exportedDetail });
    return;
  }
  writeClipboard(text)
    .then(() => VD.setStatus({ state: "ready", detail: copiedDetail }))
    .catch(() => VD.setStatus({ state: "blocked", detail: failureDetail }));
}

export function createSignalActions({ VD, bridge }: SignalActionContext) {
  function exportSignalLog(id: unknown) {
    if (!bridge || typeof bridge.exportDetailedSignalLog !== "function") {
      VD.setStatus({ state: "idle", detail: "Signal log export is only available inside the Android app." });
      return;
    }
    let parsed: VoltExportResult;
    try {
      const result = bridge.exportDetailedSignalLog(String(id || ""));
      parsed = VD.parsePayload<VoltExportResult>(result, {});
    } catch (err) {
      reportBridgeFailure(VD, "exportDetailedSignalLog", err);
      VD.setStatus({ state: "blocked", detail: "Signal log export failed." });
      return;
    }
    if (parsed.ok === false) {
      VD.setStatus({ state: "blocked", detail: parsed.message || "Signal log export failed." });
      return;
    }
    deliverExport(
      VD,
      parsed,
      `volttracker-detailed-signal-${String(id || "log")}.json`,
      "Detailed signal log exported.",
      "Detailed signal log copied.",
      "Could not export the detailed signal log."
    );
  }

  function exportSignalLogs() {
    if (!bridge || typeof bridge.exportDetailedSignalLogs !== "function") {
      VD.setStatus({ state: "idle", detail: "Signal log export is only available inside the Android app." });
      return;
    }
    let parsed: VoltExportResult;
    try {
      const result = bridge.exportDetailedSignalLogs();
      parsed = VD.parsePayload<VoltExportResult>(result, {});
    } catch (err) {
      reportBridgeFailure(VD, "exportDetailedSignalLogs", err);
      VD.setStatus({ state: "blocked", detail: "Signal log export failed." });
      return;
    }
    if (parsed.ok === false) {
      VD.setStatus({ state: "blocked", detail: parsed.message || "Signal log export failed." });
      return;
    }
    deliverExport(
      VD,
      parsed,
      "volttracker-detailed-signal-logs.json",
      "Detailed signal logs exported.",
      "Detailed signal logs copied.",
      "Could not export detailed signal logs."
    );
  }

  function deleteSignalLog(id: unknown) {
    if (!bridge || typeof bridge.deleteDetailedSignalLog !== "function") {
      VD.setStatus({ state: "idle", detail: "Signal log cleanup is only available inside the Android app." });
      return;
    }
    void confirmAppDialog({
      title: "Delete signal evidence",
      message: "Delete this saved detailed signal evidence row?",
      confirmLabel: "Delete"
    }).then((ok) => {
      if (!ok) return;
      try {
        bridge.deleteDetailedSignalLog(String(id || ""));
      } catch (err) {
        reportBridgeFailure(VD, "deleteDetailedSignalLog", err);
        VD.setStatus({ state: "blocked", detail: "Signal log cleanup failed." });
      }
    });
  }

  return { exportSignalLog, exportSignalLogs, deleteSignalLog };
}

window.VoltDashboardActionModules = window.VoltDashboardActionModules || {};
window.VoltDashboardActionModules.createSignalActions = createSignalActions;
