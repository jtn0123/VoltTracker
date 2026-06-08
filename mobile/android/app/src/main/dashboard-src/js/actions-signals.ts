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
    writeClipboard(JSON.stringify(parsed, null, 2))
      .then(() => VD.setStatus({ state: "ready", detail: "Detailed signal log copied." }))
      .catch(() => VD.setStatus({ state: "blocked", detail: "Could not copy detailed signal log." }));
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
    writeClipboard(JSON.stringify(parsed, null, 2))
      .then(() => VD.setStatus({ state: "ready", detail: "Detailed signal logs copied." }))
      .catch(() => VD.setStatus({ state: "blocked", detail: "Could not copy detailed signal logs." }));
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
