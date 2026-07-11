// charge-history.ts — the Charge tab's per-session history (rows, energy/cost
// rollup, monthly trend chart) and the Insights hero's pack-stat row, plus the
// monthly bar-chart builder those trends share with insights-panel.ts.
//
// Split out of storage-status.ts (G2 startup-headroom pass) as a LAZY chunk:
// none of this renders on the Drive-first startup path — the Charge/Insights
// tabs only paint it after a user navigates there, so the code loads through
// core.ts#ensureChargeHistoryModule (setView("charge") and the insights-panel
// chain) instead of riding the eager app.js bundle. Cross-module entry points
// are attached to the shared VD global; storage-status.ts calls them through
// optional-subscriber guards (the updateEnhancedCapabilityUi pattern), and this
// chunk re-renders the latest stored state on load so an already-open tab
// hydrates immediately.
//
// cost-model is a small shared leaf module (insights-panel bundles it the same
// way); everything else is read off VD to avoid duplicating core/prefs here.
import { rateForCharger } from "./cost-model";
// VD: this file is a LAZY chunk (own esbuild bundle) — every call into the
// eager bundle and every entry point it publishes crosses the chunk boundary
// through the VD registry (see vd-registry.ts).
import { VD } from "./vd-registry";

(function () {
  "use strict";

  const bridge = VD.bridge;
  const el = VD.el;
  const setSvgAttrs = VD.setSvgAttrs;
  const prefs = VD.prefs;
  const units = VD.units;

  function isChargeInProgress(session: VoltChargeSessionRow) {
    return Boolean(
      session && session.endedAtMs == null && session.startedAtMs &&
        (session.startSoc != null || session.powerKw != null),
    );
  }

  // Per-session charge history for the Charge tab. The native chargeSummary now
  // ships a `recentSessions` array (newest first); the card stays hidden until
  // at least one real session exists so the empty tab keeps its first-run guide.
  // Memoized like renderCellGrid/renderLiveSignals: native re-delivers storage
  // on every broadcast, and rebuilding 12 unchanged rows each time is wasted DOM
  // churn on the busiest render path.
  // Memo signatures for the always-visible Insights hero renders that run on
  // every renderRealV2Ui tick (every app-state/storage broadcast). Without them
  // the pack-stat row rebuilt its DOM ~1-2 Hz during a live stream — churn and
  // reflow on every sample. Mirrors lastChargeSessionsSig.
  let lastPackStatsSig = "";
  let lastChargeSessionsSig = "";
  function renderChargeSessions(charge: VoltChargeSummary) {
    const card = el("chargeSessionsCard");
    const list = el("chargeSessionsList");
    const sessions = Array.isArray(charge.recentSessions) ? charge.recentSessions : [];
    if (card) card.hidden = sessions.length === 0;
    if (!list) return;
    // The rates are render inputs too: buildChargeSessionRow prices each row
    // via chargeRates()/rateForCharger(), so a $/kWh preference edit must bust
    // the memo even when recentSessions is unchanged.
    const rates = chargeRates();
    // chargeSessionCount drives the "X of Y charges" title but isn't one of the
    // newest-12 row fields, so a backfilled older charge (count 12→13, rows
    // unchanged) must still bust the memo or the title stays stale at "12 recent
    // charges" instead of "12 of 13 charges".
    const sig = rates.home + "|" + rates.public + "|" + sessions.length + "|" + (charge.chargeSessionCount || sessions.length) + "|" + sessions.slice(0, 12).map((s) => [
      s.id, s.startedAtMs, s.endedAtMs, s.startSoc, s.endSoc, s.energyKwh, s.powerKw, s.chargerType
    ].join(":")).join(";");
    if (sig === lastChargeSessionsSig) return;
    lastChargeSessionsSig = sig;
    if (!sessions.length) {
      list.replaceChildren();
      // renderChargeEnergy owns the energy card's hidden flag — it must run on
      // the empty path too, or a stale "Energy logged" total survives clearing
      // the stored data.
      renderChargeEnergy(sessions);
      return;
    }
    const shown = Math.min(sessions.length, 12);
    // v2 design: the headline carries the energy rollup too ("29.6 kWh across
    // 4 charges") — the old standalone Energy card folded in here. Native caps
    // recentSessions at 12, so compare against the true lifetime total
    // (chargeSessionCount); when more exist than we hold, say "last N of M" so
    // the copy doesn't imply a lifetime figure that contradicts the count.
    const totalCharges = Number(charge.chargeSessionCount || sessions.length);
    const shownEnergyKwh = sessions.slice(0, 12).reduce((acc, session) => {
      const e = chargeNum(session.energyKwh);
      return Number.isFinite(e) && e > 0 ? acc + e : acc;
    }, 0);
    const countLabel =
      totalCharges > sessions.length
        ? `last ${shown} of ${totalCharges} charges`
        : `${sessions.length} charge${sessions.length === 1 ? "" : "s"}`;
    VD.setText(
      "chargeSessionsTitle",
      shownEnergyKwh > 0 ? `${shownEnergyKwh.toFixed(1)} kWh across ${countLabel}` : countLabel
    );
    // Scale each row's background bar to the biggest charge on screen so the
    // list doubles as a bar chart (11.8 kWh fills the row; 3 kWh ~a quarter).
    const shownSessions = sessions.slice(0, 12);
    const maxEnergyKwh = shownSessions.reduce((acc, session) => {
      const energy = chargeNum(session.energyKwh);
      return Number.isFinite(energy) && energy > acc ? energy : acc;
    }, 0);
    list.replaceChildren(...shownSessions.map((session) => buildChargeSessionRow(session, maxEnergyKwh)));
    renderChargeEnergy(sessions);
  }

  function formatMoney(value: number) {
    return "$" + value.toFixed(2);
  }

  // The two electricity rates that drive every charge-cost figure. `home` is the
  // single rate used everywhere by default; `public` (optional) is billed only
  // to public / DC-fast sessions when set. A non-positive home rate means "no
  // rate set" — callers hide the cost. See cost-model.rateForCharger for the
  // per-session selection and the unset-public fallback.
  function chargeRates(): { home: number; public: number } {
    return {
      home: prefs.get<number>("pricePerKwh", 0),
      public: prefs.get<number>("publicPricePerKwh", 0)
    };
  }

  // Total $ to charge a set of sessions, billing each session at the rate its
  // charger type selects (public/DCFC → public rate when set; else home rate).
  // Only positive energy contributes. Returns 0 when the home rate is unset.
  function chargeCostFor(sessions: VoltChargeSessionRow[]): number {
    const rates = chargeRates();
    if (!(rates.home > 0)) return 0;
    return sessions.reduce((acc, session) => {
      const energy = chargeNum(session.energyKwh);
      if (!Number.isFinite(energy) || energy <= 0) return acc;
      return acc + energy * rateForCharger(session.chargerType, rates.home, rates.public);
    }, 0);
  }

  // Charge-history CSV export (M1). Forwards to native, which serializes every logged charge into one
  // CSV (one row per charge) and opens the share sheet. Passes the user's electricity rate (Settings →
  // Preferences) through so native can append an estimated-cost column when it is set. The rate is a
  // display-layer preference read the same way the charge cost/savings math reads it; native treats a
  // non-positive / unparseable rate as "no cost column". Degrades to a status hint without the bridge.
  function exportChargeSessionsCsv() {
    if (!bridge || typeof bridge.exportChargeSessionsCsv !== "function") {
      VD.setStatus({ state: "idle", detail: "Charge-history export is only available inside the Android app." });
      return;
    }
    const price = prefs.get<number>("pricePerKwh", 0);
    try {
      bridge.exportChargeSessionsCsv(price > 0 ? String(price) : "");
      VD.showToast?.("Exporting charge history CSV…");
    } catch (err) {
      VD.reportBridgeWriteFailure("charge_export_failed", "Charge-history export failed.", err);
    }
  }

  // Estimated total charging cost for the logged sessions (v2: an Est. cost KPI
  // tile — the kWh total lives in the Recent-charges headline). Bills each
  // session at the rate its charger type selects (home vs public), rather than
  // a single flat rate on the lifetime kWh, so a mix of cheap overnight +
  // pricey DC-fast charges estimates honestly. The rate is a display-layer
  // preference, so the math lives here in JS.
  function renderChargeEnergy(sessions: VoltChargeSessionRow[]) {
    const total = sessions.reduce((acc, session) => {
      const e = chargeNum(session.energyKwh);
      return Number.isFinite(e) && e > 0 ? acc + e : acc;
    }, 0);
    const price = prefs.get<number>("pricePerKwh", 0);
    const hint = el("chargeEnergyHint");
    const costEl = el("chargeEnergyCost");
    if (total > 0 && price > 0) {
      VD.setText("chargeEnergyCost", formatMoney(chargeCostFor(sessions)));
      if (costEl) costEl.dataset.state = "recorded";
      const rates = chargeRates();
      // Mirror chargeCostFor's per-session billing in the caption: count and
      // quote only the sessions that actually contribute energy, and name the
      // rate only when every one of them bills at the same one — a home +
      // public/DC-fast mix says "mixed rates" so the caption can never
      // contradict the total above it.
      const billableSessions = sessions.filter((session) => {
        const e = chargeNum(session.energyKwh);
        return Number.isFinite(e) && e > 0;
      });
      const billedRates = new Set(
        billableSessions.map((session) => rateForCharger(session.chargerType, rates.home, rates.public))
      );
      const chargesLabel = `${billableSessions.length} charge${billableSessions.length === 1 ? "" : "s"}`;
      VD.setText(
        "chargeEnergyHint",
        billedRates.size === 1
          ? `${chargesLabel} @ ${formatMoney([...billedRates][0] as number)}/kWh`
          : `${chargesLabel} · mixed rates`
      );
      if (hint) hint.hidden = false;
    } else {
      // Clear a previous figure so a cleared database can't flash stale cost.
      VD.setText("chargeEnergyCost", "--");
      if (costEl) costEl.dataset.state = "empty";
      // Distinguish "no rate configured" from "rate set but no energy logged yet":
      // this else fires for both (price===0 OR total===0), and telling a user who
      // already set a $/kWh rate to "Set rate in Settings" is misleading — the cost
      // just can't be computed until a session records energy.
      VD.setText("chargeEnergyHint", price > 0 ? "No charge energy logged yet" : "Set rate in Settings");
      if (hint) hint.hidden = false;
    }
    renderChargeCostTrend(sessions);
  }

  // ---- Charging cost / energy trend over time (M5) -------------------------
  // Buckets logged charge sessions into calendar months and plots monthly
  // energy (kWh) — or estimated cost (kWh × electricity rate) when the rate is
  // set — as an SVG bar chart, reusing the SOH-trend rendering pattern (pure
  // createElement/setSvgAttrs, theme-aware tokens, XSS-safe). The single flat
  // lifetime cost figure on the Energy card answers "how much total"; this
  // answers "is it trending up or down, month to month".
  // `costUsd` is the month's estimated cost with each session billed at the rate
  // its charger type selects (home vs public). It's accumulated alongside the raw
  // energy so the trend can plot cost without re-deriving a per-session rate at
  // render time; it's 0 when the home rate is unset (the render falls back to
  // plotting energy in that case).
  type MonthBucket = { key: string; label: string; ms: number; energyKwh: number; costUsd: number };

  // Calendar-month key + short label ("May ’26") for a charge timestamp.
  function monthBucketKey(ms: number): { key: string; label: string; firstMs: number } {
    const d = new Date(ms);
    const year = d.getFullYear();
    const month = d.getMonth();
    // `undefined` locale follows the device's runtime locale; the month-short
    // option keeps the compact "May ’26" shape across locales.
    const label = d.toLocaleDateString(undefined, { month: "short" }) + " ’" + String(year).slice(-2);
    return { key: `${year}-${String(month + 1).padStart(2, "0")}`, label, firstMs: new Date(year, month, 1).getTime() };
  }

  // Group sessions by month, summing positive energy and the per-session cost
  // (each session billed at its charger type's rate — home vs public). Months
  // with no energy are dropped (a charge stub with no kWh contributes nothing).
  // Ascending by month.
  function bucketChargesByMonth(sessions: VoltChargeSessionRow[]): MonthBucket[] {
    const rates = chargeRates();
    const byKey = new Map<string, MonthBucket>();
    for (const session of sessions) {
      const ms = Number(session.startedAtMs);
      const energy = chargeNum(session.energyKwh);
      if (!Number.isFinite(ms) || ms <= 0 || !Number.isFinite(energy) || energy <= 0) continue;
      const cost = rates.home > 0 ? energy * rateForCharger(session.chargerType, rates.home, rates.public) : 0;
      const { key, label, firstMs } = monthBucketKey(ms);
      const existing = byKey.get(key);
      if (existing) {
        existing.energyKwh += energy;
        existing.costUsd += cost;
      } else {
        byKey.set(key, { key, label, ms: firstMs, energyKwh: energy, costUsd: cost });
      }
    }
    return Array.from(byKey.values()).sort((a, b) => a.ms - b.ms);
  }

  // Monthly bar chart shared by the charging trend (Battery tab) and the driving
  // trend (Insights tab, via VD.buildMonthlyTrendSvg) so the two can't drift.
  function buildMonthlyTrendSvg(
    labels: string[],
    values: number[],
    ariaLabel: string,
    host?: Element | null,
    opts?: {
      // Resolve a specific CSS accent (e.g. "--ev") instead of the view accent.
      colorVar?: string;
      // Full-opacity this bar, dim the rest (design "today" highlight).
      highlightIndex?: number;
      // Print each non-zero value above its bar.
      showValues?: boolean;
      valueFormat?: (v: number) => string;
      // Draw a dashed baseline placeholder for zero-value slots.
      dashEmpty?: boolean;
    },
  ): SVGElement {
    const ns = "http://www.w3.org/2000/svg";
    const w = 320;
    const h = 132;
    const padL = 30;
    const padR = 10;
    const padT = 12;
    const padB = 28;
    const plotW = w - padL - padR;
    const plotH = h - padT - padB;
    const maxV = Math.max(...values, 0) || 1;
    // Theme-aware colors: CSS variables don't cascade into SVG fill/stroke, so
    // resolve the tokens once (mirrors the insights scatter approach).
    // Resolve on the chart's host so --view-accent cascades in: the same
    // builder renders green bars on Charge and purple on Insights instead of
    // painting Drive orange onto every tab.
    const tokens = getComputedStyle(host || document.documentElement);
    const token = (name: string, fallback: string) => (tokens.getPropertyValue(name) || "").trim() || fallback;
    const barColor = token(opts?.colorVar || "--view-accent", token("--volt", "#ff7a45"));
    const axisColor = token("--muted", "#aaaab4");
    const lineColor = token("--line", "rgba(255,255,255,0.1)");
    const make = (tag: string, attrs: Record<string, string | number>) =>
      setSvgAttrs(document.createElementNS(ns, tag) as SVGElement, attrs);
    const svg = make("svg", {
      viewBox: `0 0 ${w} ${h}`,
      class: "charge-cost-trend-svg",
      role: "img",
      "aria-label": ariaLabel,
    });
    // Baseline.
    svg.appendChild(make("line", {
      x1: String(padL), x2: String(w - padR), y1: String(padT + plotH), y2: String(padT + plotH), stroke: lineColor,
    }));
    const n = values.length;
    // Cap the per-bar slot so a 1–2 month history doesn't stretch a couple of
    // bars across the whole plot (which reads as broken/sparse); center the bar
    // group in that case. With many months the natural slot is already below the
    // cap, so the full-width layout is unchanged.
    const slot = Math.min(plotW / n, 56);
    const originX = padL + (plotW - slot * n) / 2;
    const barW = Math.max(4, Math.min(34, slot * 0.6));
    const baselineY = padT + plotH;
    values.forEach((v, i) => {
      const cx = originX + slot * (i + 0.5);
      const barH = (v / maxV) * plotH;
      if (!(v > 0) && opts?.dashEmpty) {
        // Design: a dashed placeholder marks a day/slot with no data.
        svg.appendChild(make("line", {
          x1: (cx - barW / 2).toFixed(1), x2: (cx + barW / 2).toFixed(1),
          y1: baselineY.toFixed(1), y2: baselineY.toFixed(1),
          stroke: barColor, "stroke-opacity": 0.4, "stroke-width": 2, "stroke-dasharray": "2 4",
        }));
      } else {
        // Highlight one bar (design "today"); dim the rest. No highlight → flat 0.85.
        const op = opts?.highlightIndex != null ? (i === opts.highlightIndex ? 1 : 0.3) : 0.85;
        svg.appendChild(make("rect", {
          x: (cx - barW / 2).toFixed(1),
          y: (baselineY - barH).toFixed(1),
          width: barW.toFixed(1),
          height: Math.max(0, barH).toFixed(1),
          rx: 3,
          fill: barColor,
          "fill-opacity": op,
        }));
        if (opts?.showValues && v > 0) {
          const valLabel = make("text", {
            x: cx.toFixed(1), y: Math.max(padT + 7, baselineY - barH - 4).toFixed(1), fill: axisColor,
            "font-size": 9, "font-family": "ui-monospace,monospace", "text-anchor": "middle",
          });
          valLabel.textContent = opts.valueFormat ? opts.valueFormat(v) : String(Math.round(v));
          svg.appendChild(valLabel);
        }
      }
      const label = make("text", {
        x: cx.toFixed(1), y: (h - padB + 16).toFixed(1), fill: axisColor,
        "font-size": 9, "font-family": "ui-monospace,monospace", "text-anchor": "middle",
      });
      // Show every label when few buckets; thin to every other when crowded,
      // but always keep the most-recent (last) label so it's never dropped when
      // n is even and i=n-1 lands on an odd index.
      label.textContent = n <= 6 || i % 2 === 0 || i === n - 1 ? labels[i] as string : "";
      svg.appendChild(label);
    });
    return svg;
  }

  function renderChargeCostTrend(sessions: VoltChargeSessionRow[]) {
    const card = el("chargeCostTrendCard");
    if (!card) return;
    const buckets = bucketChargesByMonth(sessions);
    const chart = el("chargeCostTrendChart");
    const empty = el("chargeCostTrendEmpty");
    const stats = el("chargeCostTrendStats");
    // Need at least two months for a meaningful "trend". One month (or none)
    // hides the whole card — the flat Energy card already covers single-month.
    if (buckets.length < 2) {
      card.hidden = true;
      if (chart) chart.replaceChildren();
      return;
    }
    card.hidden = false;
    if (empty) empty.hidden = true;
    if (stats) stats.hidden = false;
    const price = prefs.get<number>("pricePerKwh", 0);
    const showCost = price > 0;
    // costUsd already bills each session at its charger type's rate (home vs
    // public); fall back to energy when no home rate is set.
    const values = buckets.map((b) => (showCost ? b.costUsd : b.energyKwh));
    const total = values.reduce((acc, v) => acc + v, 0);
    // "Avg / month" is per ACTIVE charging month by design: bucketChargesByMonth
    // emits only months that logged energy, so a calendar month with no charging
    // is not in the denominator (and the bars are drawn gapless). Intentional — a
    // month you didn't plug in shouldn't dilute the per-charge-month average.
    const avg = total / values.length;
    const fmt = (v: number) => (showCost ? formatMoney(v) : `${v.toFixed(1)} kWh`);
    VD.setText("chargeCostTrendTitle", showCost ? "Monthly charging cost" : "Monthly charging energy");
    VD.setText("chargeCostTrendLatest", fmt(values[values.length - 1] as number));
    VD.setText("chargeCostTrendSpanLabel", "Avg / month");
    VD.setText("chargeCostTrendAvg", fmt(avg));
    VD.setText("chargeCostTrendMonths", String(buckets.length));
    VD.setText("chargeCostTrendTotal", fmt(total));
    if (chart) {
      const latest = values[values.length - 1] as number;
      const aria = `Monthly charging ${showCost ? "cost" : "energy"} trend, latest ${
        showCost ? "$" + latest.toFixed(2) : latest.toFixed(1) + " kWh"
      }`;
      chart.replaceChildren(buildMonthlyTrendSvg(buckets.map((b) => b.label), values, aria, chart));
    }
  }

  function chargeNum(value: unknown) {
    // Native sends JSON null for missing fields; coerce those to NaN so a real
    // 0 reading and "no data" don't both render as "0".
    return value == null || value === "" ? NaN : Number(value);
  }

  function chargerLabel(type: unknown) {
    const raw = String(type == null ? "" : type).trim();
    const key = raw.toLowerCase().replace(/[\s-]+/g, "_");
    if (!key || key === "unknown" || key === "null") return "";
    const known: Record<string, string> = {
      level1: "Level 1",
      level2: "Level 2",
      dc_fast: "DC fast",
      dcfast: "DC fast"
    };
    return known[key] || raw.charAt(0).toUpperCase() + raw.slice(1);
  }

  function buildChargeSessionRow(session: VoltChargeSessionRow, maxEnergyKwh = 0) {
    const row = document.createElement("article");
    row.className = "charge-session-row";
    const inProgress = isChargeInProgress(session);
    if (inProgress) row.dataset.charging = "1";
    // Proportional energy bar behind the text (see .charge-kwh-bar). Skipped
    // when this session logged no energy — the row keeps its flat background.
    const rowEnergy = chargeNum(session.energyKwh);
    if (Number.isFinite(rowEnergy) && rowEnergy > 0 && maxEnergyKwh > 0) {
      const bar = document.createElement("span");
      bar.className = "charge-kwh-bar";
      bar.style.width = `${Math.max(6, Math.min(100, (rowEnergy / maxEnergyKwh) * 100)).toFixed(0)}%`;
      row.append(bar);
    }
    const center = document.createElement("span");
    const strong = document.createElement("strong");
    strong.textContent = [VD.formatWhen(session.startedAtMs), chargerLabel(session.chargerType)]
      .filter(Boolean)
      .join(" · ");
    const small = document.createElement("small");
    const startSoc = chargeNum(session.startSoc);
    const endSoc = chargeNum(session.endSoc);
    const power = chargeNum(session.powerKw);
    const endedAtMs = chargeNum(session.endedAtMs);
    const durationMs = Number.isFinite(endedAtMs) ? endedAtMs - Number(session.startedAtMs) : NaN;
    const parts: string[] = [];
    if (Number.isFinite(startSoc) && Number.isFinite(endSoc)) parts.push(`${Math.round(startSoc)}% → ${Math.round(endSoc)}%`);
    if (Number.isFinite(power) && power > 0) parts.push(`${power.toFixed(1)} kW`);
    if (inProgress) parts.push("charging now");
    else if (Number.isFinite(durationMs) && durationMs > 0 && typeof VD.formatDuration === "function") parts.push(VD.formatDuration(durationMs));
    const energy = chargeNum(session.energyKwh);
    // Bill this session at the rate its charger type selects: the public/DCFC
    // rate for a public charger when one is set, else the home rate. v2 design:
    // the estimated cost joins the meta line; the right pill stays kWh-only.
    const rates = chargeRates();
    const sessionRate = rateForCharger(session.chargerType, rates.home, rates.public);
    if (Number.isFinite(energy) && energy > 0 && rates.home > 0) {
      parts.push(formatMoney(energy * sessionRate));
    }
    small.textContent = parts.length ? parts.join(" · ") : "charge details pending";
    center.append(strong, small);
    const right = document.createElement("b");
    const socGain = Number.isFinite(startSoc) && Number.isFinite(endSoc) ? endSoc - startSoc : NaN;
    if (Number.isFinite(energy) && energy > 0) {
      right.textContent = `${energy.toFixed(1)} kWh`;
    } else if (Number.isFinite(socGain) && socGain > 0) {
      right.textContent = `+${Math.round(socGain)}%`;
    } else {
      right.textContent = "--";
    }
    row.append(center, right);
    return row;
  }

  function firstNum(values: unknown[]) {
    for (const v of values) {
      if (v == null || v === "") continue;
      const n = Number(v);
      if (Number.isFinite(n)) return n;
    }
    return NaN;
  }

  // HV-pack detail. The battery snapshot already rides in the storage payload —
  // surface voltage / temp / health / power as a stat row beneath the SOC ring
  // so the Insights hero shows the pack, not just a charge percentage. Hidden
  // until at least one field is real.
  function renderPackStats(latest: Record<string, unknown>) {
    const row = el("realPackStats");
    if (!row) return;
    const voltage = chargeNum(latest.packVoltage);
    const temp = firstNum([latest.batteryTempC, latest.batteryTemp]);
    const soh = Number(latest.sohPct);
    const packPower = firstNum([latest.packPowerKw, latest.powerKw]);
    const stats: Array<[string, string | null]> = [
      ["Pack", Number.isFinite(voltage) ? `${Math.round(voltage)} V` : null],
      ["Temp", Number.isFinite(temp) ? units.tempText(temp) : null],
      ["Health", Number.isFinite(soh) && soh > 0 ? `${Math.round(soh)}%` : null],
      ["Power", Number.isFinite(packPower) && packPower !== 0 ? `${packPower.toFixed(1)} kW` : null]
    ].filter((pair) => pair[1] != null) as Array<[string, string]>;
    const sig = stats.length ? stats.map((pair) => pair[0] + "=" + pair[1]).join("|") : "empty";
    if (sig === lastPackStatsSig) return;
    lastPackStatsSig = sig;
    if (!stats.length) {
      row.hidden = true;
      row.replaceChildren();
      return;
    }
    row.hidden = false;
    row.replaceChildren(...stats.map((pair) => buildPackStat(String(pair[0]), String(pair[1]))));
  }

  function buildPackStat(label: string, value: string) {
    const cell = document.createElement("div");
    const span = document.createElement("span");
    span.textContent = label;
    const strong = document.createElement("strong");
    strong.textContent = value;
    cell.append(span, strong);
    return cell;
  }

  Object.assign(VD, {
    renderChargeSessions,
    renderPackStats,
    exportChargeSessionsCsv,
    monthBucketKey,
    buildMonthlyTrendSvg
  });

  // The eager storage-status renders call these entry points through optional-
  // subscriber guards, so any broadcasts that landed before this chunk loaded
  // skipped the charge/pack sections. Re-render once from the current state so
  // an already-open Charge/Insights tab hydrates the moment the chunk arrives.
  if (typeof VD.renderRealV2Ui === "function") VD.renderRealV2Ui();
})();

export {};
