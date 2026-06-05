// @ts-check
// Last-connected badge + adapter health pill.
//
// Reads recent session summaries via VoltTrackerAndroid.getRecentSessions(n)
// on dashboard load and on every status broadcast, then renders into the
// topbar. The low-voltage hint also lives here (the hint element is in
// connection-tools.html but the status payload is the same).
(function () {
  "use strict";
  const VD = /** @type {any} */ (window.VoltDashboard = window.VoltDashboard || /** @type {VoltDashboard} */ ({}));
  const bridge = window.VoltTrackerAndroid || null;
  const el = (/** @type {any} */ id) => document.getElementById(id);

  function parseSessions(/** @type {number} */ n) {
    if (!bridge || typeof bridge.getRecentSessions !== "function") return [];
    try {
      const raw = bridge.getRecentSessions(n);
      const arr = JSON.parse(raw || "[]");
      return Array.isArray(arr) ? arr : [];
    } catch (ignored) {
      return [];
    }
  }

  function formatRelative(/** @type {number} */ ms) {
    if (!ms) return "--";
    const now = Date.now();
    const delta = now - ms;
    if (delta < 60_000) return "just now";
    if (delta < 3_600_000) return Math.floor(delta / 60_000) + " min ago";
    if (delta < 86_400_000) return Math.floor(delta / 3_600_000) + "h ago";
    const days = Math.floor(delta / 86_400_000);
    if (days === 1) return "yesterday";
    if (days < 7) return days + " days ago";
    return new Date(ms).toLocaleDateString();
  }

  // A demo run is not a real adapter connection. New demo sessions are no longer written to the
  // summary store, but defend against any legacy "Demo stream" rows so the last-connected line
  // never shows the demo as if it were the last adapter (it would double up with the live
  // "Demo preview" chip).
  function isDemoSession(/** @type {any} */ s) {
    return /^demo/i.test(String((s && s.adapter) || ""));
  }

  // Render the "last connected" badge from the most recent REAL session.
  function renderLastConnected() {
    const badge = el("lastConnectedBadge");
    const label = el("lastConnectedLabel");
    const at = el("lastConnectedAt");
    if (!badge || !label || !at) return;
    const s = parseSessions(8).find((session) => !isDemoSession(session));
    if (!s) {
      badge.hidden = true;
      return;
    }
    label.textContent = s.adapter || "OBD adapter";
    at.textContent = formatRelative(s.endMs || s.startMs);
    badge.dataset.state = s.outcome || "unknown";
    badge.hidden = false;
  }

  // Low-voltage hint keyed off the lastVoltage field on status payloads.
  function renderLowVoltageHint(/** @type {any} */ status) {
    const hint = el("lowVoltageHint");
    if (!hint || !status) return;
    const v = typeof status.lastVoltage === "number" ? status.lastVoltage : null;
    if (v == null) {
      hint.hidden = true;
      return;
    }
    // Thresholds mirror VehicleStateClassifier.LOW_BATTERY_VOLTS (= 12.7) so the dashboard
    // hint stays aligned with the backend's "parked / low_voltage" classification — without
    // this, a 12.5–12.7 V reading would silently leave the hint hidden while the backend
    // already considers the battery low. The "bad" floor at 12.2 V keeps a tighter band for
    // the more urgent copy.
    if (v < 12.2) {
      hint.dataset.tone = "bad";
      hint.textContent =
        "Battery voltage looks low (" +
        v.toFixed(2) +
        " V). The OBD port may sleep — start the car before the next probe.";
      hint.hidden = false;
    } else if (v < 12.7) {
      hint.dataset.tone = "warn";
      hint.textContent =
        "Battery voltage is borderline (" + v.toFixed(2) + " V).";
      hint.hidden = false;
    } else {
      hint.hidden = true;
    }
  }

  // Re-render on every status broadcast — session summaries can change when
  // a session ends, and lastVoltage updates inline.
  function noteStatus(/** @type {any} */ payload) {
    renderLastConnected();
    renderLowVoltageHint(payload || {});
  }

  function installStatusObserver() {
    const wrap = (/** @type {any} */ prior) =>
      function (/** @type {any} */ payload) {
        let result;
        if (typeof prior === "function") {
          result = prior(payload);
        }
        try {
          const parsed = VD.parsePayload
            ? VD.parsePayload(payload, {})
            : payload;
          noteStatus(parsed);
        } catch (ignored) {
          // Observer must never break the underlying setStatus call.
        }
        return result;
      };
    VD.setStatus = wrap(VD.setStatus);
    if (window.VoltTrackerNative) {
      window.VoltTrackerNative.setStatus = wrap(window.VoltTrackerNative.setStatus);
    }
  }

  // Initial render on load.
  renderLastConnected();
  installStatusObserver();
})();