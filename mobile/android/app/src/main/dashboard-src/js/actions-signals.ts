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
  const area = document.createElement("textarea");
  area.value = String(text);
  area.setAttribute("readonly", "true");
  area.style.position = "fixed";
  area.style.left = "-9999px";
  document.body.append(area);
  area.select();
  try {
    document.execCommand("copy");
  } finally {
    area.remove();
  }
  return Promise.resolve();
}

function jsonText(payload: unknown) {
  return JSON.stringify(payload, null, 2);
}

function downloadTextFile(text: string, filename: string) {
  try {
    if (typeof Blob !== "function" || !window.URL || typeof window.URL.createObjectURL !== "function") return false;
    const blob = new Blob([text], { type: "application/json" });
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = filename;
    link.rel = "noopener";
    document.body.append(link);
    link.click();
    link.remove();
    window.setTimeout(() => window.URL.revokeObjectURL(url), 0);
    return true;
  } catch (_err) {
    return false;
  }
}

function deliverExport(VD: VoltDashboard, payload: unknown, filename: string, exportedDetail: string, copiedDetail: string) {
  const text = jsonText(payload);
  if (downloadTextFile(text, filename)) {
    VD.setStatus({ state: "ready", detail: exportedDetail });
    return;
  }
  writeClipboard(text)
    .then(() => VD.setStatus({ state: "ready", detail: copiedDetail }))
    .catch(() => VD.setStatus({ state: "blocked", detail: "Could not export detailed signal logs." }));
}

export function createSignalActions({ VD, bridge }: SignalActionContext) {
  function exportSignalLog(id: unknown) {
    if (!bridge || typeof bridge.exportDetailedSignalLog !== "function") {
      VD.setStatus({ state: "idle", detail: "Signal log export is only available inside the Android app." });
      return;
    }
    const result = bridge.exportDetailedSignalLog(String(id || ""));
    const parsed = VD.parsePayload<VoltExportResult>(result, {});
    if (parsed.ok === false) {
      VD.setStatus({ state: "blocked", detail: parsed.message || "Signal log export failed." });
      return;
    }
    deliverExport(
      VD,
      parsed,
      `volttracker-detailed-signal-${String(id || "log")}.json`,
      "Detailed signal log exported.",
      "Detailed signal log copied."
    );
  }

  function exportSignalLogs() {
    if (!bridge || typeof bridge.exportDetailedSignalLogs !== "function") {
      VD.setStatus({ state: "idle", detail: "Signal log export is only available inside the Android app." });
      return;
    }
    const result = bridge.exportDetailedSignalLogs();
    const parsed = VD.parsePayload<VoltExportResult>(result, {});
    if (parsed.ok === false) {
      VD.setStatus({ state: "blocked", detail: parsed.message || "Signal log export failed." });
      return;
    }
    deliverExport(
      VD,
      parsed,
      "volttracker-detailed-signal-logs.json",
      "Detailed signal logs exported.",
      "Detailed signal logs copied."
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
      bridge.deleteDetailedSignalLog(String(id || ""));
    });
  }

  return { exportSignalLog, exportSignalLogs, deleteSignalLog };
}
