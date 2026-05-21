// Volt Tracker app · shared components.
// Components use a shared context (VTCtx) so any screen can read/write
// global state without prop drilling — route, mode, selected trip, modal, tweaks.

const VT = window.VT_DATA;

// ── Context ─────────────────────────────────────────────────────
const VTCtx = React.createContext(null);
const useVT = () => React.useContext(VTCtx);

// ── Shared helpers ──────────────────────────────────────────────
function vtPath(data, w, h, min, max) {
  return data.map((v, i) => {
    const x = i / (data.length - 1) * w;
    const y = h - (v - min) / (max - min) * h;
    return (i === 0 ? 'M' : 'L') + x.toFixed(2) + ' ' + y.toFixed(2);
  }).join(' ');
}

function fmtNumber(n, d = 0) {
  return Number(n).toLocaleString(undefined, { minimumFractionDigits: d, maximumFractionDigits: d });
}

// ── Shell ───────────────────────────────────────────────────────
const NAV = [
['home', 'Drive', 'home'],
['live', 'Live', 'live'],
['trips', 'Trips', 'trips'],
['battery', 'Pack', 'battery'],
['charging', 'Charging', 'charging'],
['insights', 'Insights', 'insights'],
['map', 'Map', 'map'],
['settings', 'Settings', 'settings']];

const MOBILE_NAV = [
['home', 'Drive', 'home'],
['trips', 'Trips', 'trips'],
['charging', 'Charge', 'charging'],
['insights', 'Insights', 'insights'],
['settings', 'Settings', 'settings']];


function VTShell({ kicker, title, children, action }) {
  const ctx = useVT();
  const mainRef = React.useRef(null);
  React.useLayoutEffect(() => {
    const main = mainRef.current;
    if (!main) return;
    const key = 'vt-v2-scroll:' + ctx.route;
    try {
      const saved = Number(sessionStorage.getItem(key) || 0);
      requestAnimationFrame(() => main.scrollTo({ top: saved, left: 0, behavior: 'auto' }));
    } catch (_) {}
    return () => {
      try { sessionStorage.setItem(key, String(main.scrollTop)); } catch (_) {}
    };
  }, [ctx.route]);
  return (
    <div className={'vc-root vc-mode-' + ctx.mode} data-density={ctx.tweaks.density}>
      <a href="#vc-main-content" className="vc-skip-link">Skip to content</a>
      <div className="vc-ambient" aria-hidden="true">
        <span className="vc-ambient-glow" />
        <span className="vc-ambient-grain" />
      </div>

      <aside className="vc-rail">
        <button className="vc-rail-brand" onClick={() => ctx.setRoute('home')} aria-label="Dashboard">
          <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <path d="M5 12 L11 4 L11 11 L19 11 L13 20 L13 13 L5 13 Z" fill="currentColor" />
          </svg>
          <span>VT</span>
        </button>
        <nav className="vc-rail-nav">
          {NAV.map(([id, label, icon]) =>
          <button key={id}
          type="button"
          className={'vc-rail-item' + (ctx.route === id ? ' is-active' : '')}
          onClick={() => ctx.setRoute(id)}
          aria-current={ctx.route === id ? 'page' : undefined}
          aria-label={label}
          title={label}>
              <span className="vc-rail-tip">{label}</span>
              <i className={'vc-rail-icon vc-icon-' + icon} aria-hidden="true" />
            </button>
          )}
        </nav>
        <div className="vc-rail-foot">
          <span className="vc-rail-soc" title={`State of charge: ${ctx.live.soc.toFixed(1)}%`}>
            <svg viewBox="0 0 24 14">
              <rect x="0.5" y="0.5" width="20" height="13" rx="2" fill="none" stroke="currentColor" opacity="0.4" />
              <rect x="22" y="4" width="2" height="6" fill="currentColor" opacity="0.4" />
              <rect x="2" y="2" width={ctx.live.soc * 0.16} height="10" fill="currentColor" />
            </svg>
            <small>{ctx.live.soc.toFixed(0)}%</small>
          </span>
        </div>
      </aside>

      <main className="vc-main" id="vc-main-content" tabIndex="-1" ref={mainRef}>
        <header className="vc-topbar">
          <div className="vc-top-title">
            <span className="vc-kicker">{kicker}</span>
            <h1 className="vc-h1">{title}</h1>
          </div>
          <div className="vc-mobile-controls">
            <div className="vc-state-cluster" data-comment-anchor="4240c33b1a-div-78-11">
              {/* Primary: propulsion STATE — EV vs Gas. This is the headline
                    read-only-by-default switch users actually care about. */}
              <div className="vc-state-pill" role="tablist" aria-label="Propulsion state">
                {[
                ['ev', 'EV', '⚡'],
                ['gas', 'GAS', '⛽']].
                map(([m, label, icon]) =>
                <button key={m} type="button"
                role="tab" aria-selected={ctx.mode === m}
                className={ctx.mode === m ? 'is-active' : ''}
                onClick={() => ctx.setMode(m)}>
                    <span className="vc-state-icon" aria-hidden="true">{icon}</span>
                    <span className="vc-state-label">{label}</span>
                  </button>
                )}
                <span className="vc-state-divider" aria-hidden="true" />
              </div>
              {/* Secondary: Volt-specific driver-selectable mode. Less prominent
                    because Normal is the default 90% of the time. */}
              <div className="vc-drive-chip" title="Volt drive mode">
                <span className="vc-kicker">DRIVE</span>
                <VTDropdown
                ariaLabel="Volt drive mode"
                value={ctx.driveMode || 'normal'}
                onChange={(v) => ctx.setDriveMode(v)}
                options={[
                { value: 'normal', label: 'Normal' },
                { value: 'sport', label: 'Sport' },
                { value: 'mountain', label: 'Mountain' },
                { value: 'hold', label: 'Hold' }]} />
              </div>
            </div>
            <div className="vc-top-actions">
              <div className="vc-glass-btn vc-status-chip" role="status" title={`Last sync: ${VT.status.lastSync}`}>
                <i className="vc-dot vc-dot-live" />live · {VT.status.lastSync}
              </div>
              {action}
          <button type="button" className="vc-glass-btn vc-glass-primary" onClick={() => ctx.setModal('export')}>Export</button>
            </div>
          </div>
        </header>
        {children}
      </main>

      <V2BottomNav />

      {/* Modal portal */}
      {ctx.modal && <VTModal />}
    </div>);

}

function V2BottomNav() {
  const ctx = useVT();
  return (
    <nav className="vc-bottom-nav" aria-label="Main navigation">
      {MOBILE_NAV.map(([id, label, icon]) =>
      <button key={id}
      type="button"
      className={'vc-bottom-nav-item' + (ctx.route === id ? ' is-active' : '')}
      onClick={() => ctx.setRoute(id)}
      aria-current={ctx.route === id ? 'page' : undefined}>
          <i className={'vc-rail-icon vc-icon-' + icon} aria-hidden="true" />
          <span>{label}</span>
        </button>
      )}
    </nav>);
}

// ── Dial component (half circle gauge) ──────────────────────────
function VTDial({ value, max, label, unit, peak, sub, big = false }) {
  // Stable, collision-free gradient id. The old id was built from `label`
  // (which contains spaces — invalid in an id) and from `value`, so it
  // changed every tick on the live dial and collided between equal-value
  // dials. useId gives one stable unique id per dial instance.
  const gid = 'vcd' + React.useId().replace(/:/g, '');
  const r = big ? 130 : 96;
  const pad = 14;
  const cx = r + pad,cy = r + pad;
  const startA = Math.PI,endA = 2 * Math.PI;
  const v = Math.min(value, max);
  const valA = startA + v / max * Math.PI;
  const track = `M ${pad} ${cy} A ${r} ${r} 0 0 1 ${cx + r} ${cy}`;
  const x = cx + r * Math.cos(valA);
  const y = cy + r * Math.sin(valA);
  const fill = `M ${pad} ${cy} A ${r} ${r} 0 0 1 ${x.toFixed(2)} ${y.toFixed(2)}`;
  const w = (r + pad) * 2,h = r + pad * 2;
  const tickCount = 10;
  return (
    <div className={'vc-dial' + (big ? ' vc-dial-big' : '')}>
      <svg viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="xMidYMid meet" data-comment-anchor="77b3d056c1-svg-145-7">
        <defs>
          <linearGradient id={gid} x1="0" x2="1" y1="0" y2="0">
            <stop offset="0%" stopColor="var(--vc-mode)" stopOpacity="0.3" />
            <stop offset="100%" stopColor="var(--vc-mode)" stopOpacity="1" />
          </linearGradient>
        </defs>
        {Array.from({ length: tickCount + 1 }).map((_, i) => {
          const a = startA + i / tickCount * Math.PI;
          const len = i % 5 === 0 ? 10 : 5;
          const active = i <= Math.round(v / max * tickCount);
          return (
            <line key={i}
            x1={cx + (r + 4) * Math.cos(a)} y1={cy + (r + 4) * Math.sin(a)}
            x2={cx + (r + 4 + len) * Math.cos(a)} y2={cy + (r + 4 + len) * Math.sin(a)}
            stroke={active ? 'var(--vc-mode)' : 'rgba(255,255,255,0.18)'}
            strokeWidth="1.2" strokeLinecap="round" />);

        })}
        <path d={track} fill="none" stroke="rgba(255,255,255,0.06)" strokeWidth={big ? 18 : 14} strokeLinecap="round" />
        <path d={fill} fill="none" stroke={`url(#${gid})`} strokeWidth={big ? 18 : 14} strokeLinecap="round" />
        {peak !== undefined &&
        <circle cx={cx + r * Math.cos(startA + peak / max * Math.PI)}
        cy={cy + r * Math.sin(startA + peak / max * Math.PI)}
        r="3" fill="rgba(255,255,255,0.5)" />
        }
      </svg>
      <div className="vc-dial-text">
        <strong>{typeof value === 'number' && !Number.isInteger(value) ? value.toFixed(1) : value}</strong>
        <em>{unit}</em>
        <small>{label}{sub ? <span>{sub}</span> : null}</small>
      </div>
    </div>);

}

// ── Modal ───────────────────────────────────────────────────────
function VTModal() {
  const ctx = useVT();
  const close = () => ctx.setModal(null);
  const dialogRef = React.useRef(null);
  // VTModal mounts on open and unmounts on close, so [] deps are correct.
  // (The old effect had no deps and re-bound the listener every render.)
  // Also: move focus into the dialog, trap Tab inside it, and restore focus
  // to the trigger element on close.
  React.useEffect(() => {
    const prevFocus = document.activeElement;
    const dlg = dialogRef.current;
    const SEL = 'input, button, select, textarea, a[href], [tabindex]:not([tabindex="-1"])';
    if (dlg) {
      const first = dlg.querySelector(SEL);
      (first || dlg).focus();
    }
    const onKey = (e) => {
      if (e.key === 'Escape') { close(); return; }
      if (e.key === 'Tab' && dlg) {
        const els = Array.from(dlg.querySelectorAll(SEL));
        if (!els.length) return;
        const first = els[0], last = els[els.length - 1];
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
      }
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      if (prevFocus && prevFocus.focus) prevFocus.focus();
    };
  }, []);

  const body =
  ctx.modal === 'add-charge' ? <AddChargeModal /> :
  ctx.modal === 'export' ? <ExportModal /> :
  ctx.modal && ctx.modal.kind === 'session' ? <SessionDetailModal session={ctx.modal.session} /> :
  null;

  if (!body) return null;
  return (
    <div className="vt-modal-backdrop" onClick={close}>
      <div className="vt-modal vc-card-glass" ref={dialogRef} tabIndex={-1} onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true" aria-label="VoltTracker dialog">
        <button type="button" className="vt-modal-close" onClick={close} aria-label="Close">✕</button>
        {body}
      </div>
    </div>);

}

function AddChargeModal() {
  const ctx = useVT();
  const [form, setForm] = React.useState({
    start: '21:18', end: '00:36', startSoc: 24, endSoc: 91,
    kwh: 11.8, type: 'L2', cost: 1.41, location: 'Home'
  });
  const set = (k, v) => setForm((f) => ({ ...f, [k]: v }));
  return (
    <div className="vt-modal-form">
      <header>
        <span className="vc-kicker">+ New session</span>
        <h2>Log a charging session</h2>
        <p>Records this session against your monthly stats and locations.</p>
      </header>
      <div className="vt-form-grid">
        <label>Start<input type="time" value={form.start} onChange={(e) => set('start', e.target.value)} /></label>
        <label>End<input type="time" value={form.end} onChange={(e) => set('end', e.target.value)} /></label>
        <label>SOC start<input type="number" min="0" max="100" value={form.startSoc} onChange={(e) => set('startSoc', +e.target.value)} /></label>
        <label>SOC end<input type="number" min="0" max="100" value={form.endSoc} onChange={(e) => set('endSoc', +e.target.value)} /></label>
        <label>kWh<input type="number" step="0.1" min="0" max="20" value={form.kwh} onChange={(e) => set('kwh', +e.target.value)} /></label>
        <label>Type
          <select value={form.type} onChange={(e) => set('type', e.target.value)}>
            <option>L1</option><option>L2</option><option>DCFC</option>
          </select>
        </label>
        <label>Cost ($)<input type="number" step="0.01" min="0" value={form.cost} onChange={(e) => set('cost', +e.target.value)} /></label>
        <label>Location<input type="text" value={form.location} onChange={(e) => set('location', e.target.value)} /></label>
      </div>
      <footer>
        <button type="button" className="vc-glass-btn" onClick={() => ctx.setModal(null)}>Cancel</button>
        <button type="button" className="vc-glass-btn vc-glass-primary" onClick={() => {
          ctx.addSession(form);
          ctx.toast('Charging session logged · ' + form.kwh + ' kWh');
          ctx.setModal(null);
        }}>Save session</button>
      </footer>
    </div>);

}

function ExportModal() {
  const ctx = useVT();
  const items = [
  ['trips.csv', 'Trips · CSV', '10 trips · April'],
  ['trips.json', 'Trips · JSON', 'with full telemetry'],
  ['fuel.csv', 'Fuel events · CSV', '6 events YTD'],
  ['charging.csv', 'Charging · CSV', '24 sessions · 30d'],
  ['backup.json', 'Full backup · JSON', 'everything, every table'],
  ['volt_pids.csv', 'Torque PIDs · CSV', 'for Torque Pro import']];

  return (
    <div className="vt-modal-form">
      <header>
        <span className="vc-kicker">EXPORT</span>
        <h2>Download your data</h2>
        <p>Plain files. No vendor lock-in. Re-importable.</p>
      </header>
      <div className="vt-export-list">
        {items.map(([k, label, sub]) =>
        <button key={k} type="button" className="vt-export-item" onClick={() => {ctx.toast('Downloading ' + k);ctx.setModal(null);}}>
            <span className="vt-export-icon">↓</span>
            <span>
              <strong>{label}</strong>
              <small>{sub}</small>
            </span>
            <em>{k}</em>
          </button>
        )}
      </div>
    </div>);

}

function SessionDetailModal({ session: s }) {
  return (
    <div className="vt-modal-form vt-session-modal">
      <header>
        <span className="vc-kicker">SESSION · {s.date}</span>
        <h2>{s.location} · {s.type} · {s.kwh} kWh</h2>
        <p>{s.duration} · SOC {s.socIn}% → {s.socOut}% · ${s.cost.toFixed(2)}</p>
      </header>

      <div className="vt-session-stats">
        {[
        ['Average power', (s.kwh / (parseFloat(s.duration) || 3.3)).toFixed(1) + ' kW', 'avg over session'],
        ['SOC delta', s.socOut - s.socIn + ' pts', '+' + Math.round((s.socOut - s.socIn) * 0.184) + ' mi range'],
        ['$ / kWh', '$' + (s.cost / s.kwh).toFixed(3), 'utility rate'],
        ['$ / mile', '$' + (s.cost / ((s.socOut - s.socIn) * 0.4)).toFixed(3), 'vs $0.084 gas']].
        map(([k, v, sub]) =>
        <div key={k}>
            <span>{k}</span>
            <strong>{v}</strong>
            <small>{sub}</small>
          </div>
        )}
      </div>

      <div className="vt-session-curve">
        <div className="vc-kicker">SOC · over session</div>
        <svg viewBox="0 0 100 36" preserveAspectRatio="none">
          {[0, 9, 18, 27, 36].map((y) => <line key={y} x1="0" x2="100" y1={y} y2={y} stroke="rgba(255,255,255,0.05)" strokeWidth="0.15" />)}
          <path d={`M0 ${36 - s.socIn / 100 * 36} Q 30 ${36 - s.socOut / 100 * 32}, 70 ${36 - s.socOut / 100 * 34}, 100 ${36 - s.socOut / 100 * 36}`}
          fill="none" stroke="var(--vc-mode)" strokeWidth="0.6" />
          <text x="2" y={36 - s.socIn / 100 * 36 - 1} fontSize="2.2" fill="var(--vc-text-mut)" fontFamily="var(--vc-mono)">{s.socIn}%</text>
          <text x="92" y={36 - s.socOut / 100 * 36 - 1} fontSize="2.2" fill="var(--vc-text-mut)" fontFamily="var(--vc-mono)">{s.socOut}%</text>
        </svg>
      </div>
    </div>);

}

// ── Map (Leaflet wrapper) ───────────────────────────────────────
// Real dark-tile basemap with one polyline per route. Cleans itself up
// on unmount and re-fits when routes change.
function VTMap({ routes, height = 240, fitPad = 24, interactive = true, className = '', layer = 'routes' }) {
  const ref = React.useRef(null);
  React.useEffect(() => {
    if (!ref.current || !window.L) return;
    // Resolve any 'var(--vc-mode)' style route colors to a real hex — Leaflet's
    // SVG/Canvas renderers can't parse CSS variables in stroke/fill.
    const cssRoot = getComputedStyle(document.documentElement);
    const resolveColor = (c) => {
      if (!c) return '#e6ff3a';
      if (typeof c === 'string' && c.startsWith('var(')) {
        const name = c.slice(4, -1).trim();
        return cssRoot.getPropertyValue(name).trim() || '#e6ff3a';
      }
      return c;
    };
    const map = L.map(ref.current, {
      zoomControl: interactive,
      attributionControl: false,
      scrollWheelZoom: interactive,
      dragging: interactive,
      doubleClickZoom: interactive,
      touchZoom: interactive
    });
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      subdomains: 'abc',
      className: 'vt-map-tile',
      crossOrigin: true
    }).addTo(map);

    const bounds = L.latLngBounds([]);
    (routes || []).forEach((r) => {
      if (!r.points || r.points.length < 2) return;
      const color = resolveColor(r.color);
      if (layer === 'heat') {
        // Heat layer — wide, soft, additive strokes so overlapping routes
        // build up brightness on shared corridors.
        L.polyline(r.points, { color, weight: 22, opacity: 0.10, lineCap: 'round', lineJoin: 'round' }).addTo(map);
        L.polyline(r.points, { color, weight: 11, opacity: 0.16, lineCap: 'round', lineJoin: 'round' }).addTo(map);
        const line = L.polyline(r.points, { color, weight: 3, opacity: 0.55, lineCap: 'round' }).addTo(map);
        bounds.extend(line.getBounds());
      } else {
        // Three-stroke stack for contrast on dark tiles: a wide blurred glow,
        // then a thin white casing, then the colored line on top. The 'stops'
        // layer dims the lines so the endpoint markers read as the subject.
        const dim = layer === 'stops';
        L.polyline(r.points, { color, weight: 10, opacity: dim ? 0.12 : 0.28 }).addTo(map);
        L.polyline(r.points, { color: '#fff', weight: 5, opacity: dim ? 0.14 : 0.35 }).addTo(map);
        const line = L.polyline(r.points, { color, weight: dim ? 2 : 3.2, opacity: dim ? 0.5 : 1 }).addTo(map);
        bounds.extend(line.getBounds());
      }
      if (layer !== 'heat') {
        const start = r.points[0], end = r.points[r.points.length - 1];
        const big = layer === 'stops';
        L.circleMarker(start, { radius: big ? 6 : 5, color: '#fff', fillColor: color, fillOpacity: 1, weight: 2 }).addTo(map);
        L.circleMarker(end, { radius: big ? 9 : 6, color: '#fff', fillColor: color, fillOpacity: 1, weight: big ? 3 : 2.5 }).addTo(map);
      }
      if (r.label) {
        L.tooltip({ permanent: true, direction: 'top', offset: [0, -10], className: 'vt-map-tip' }).
        setContent(r.label).
        setLatLng(r.points[r.points.length - 1]).
        addTo(map);
      }
    });
    const fit = () => {
      if (bounds.isValid()) map.fitBounds(bounds, { padding: [fitPad, fitPad] });
      else map.setView([37.85, -122.2], 11);
    };
    fit();

    // Leaflet sizes its tile grid from the container's dimensions at creation
    // time. Inside flex / transitioned cards the container often has no final
    // size yet, which leaves the map grey or clipped. Recompute once layout
    // settles and on every container resize.
    const refit = () => { map.invalidateSize({ animate: false }); fit(); };
    const raf = requestAnimationFrame(refit);
    const timer = setTimeout(refit, 260);
    let ro;
    if (window.ResizeObserver) {
      ro = new ResizeObserver(() => map.invalidateSize({ animate: false }));
      ro.observe(ref.current);
    }
    return () => {
      cancelAnimationFrame(raf);
      clearTimeout(timer);
      if (ro) ro.disconnect();
      map.remove();
    };
  }, [JSON.stringify(routes || []), interactive, fitPad, layer]);
  return <div ref={ref} className={'vt-map ' + className} style={{ height, width: '100%' }} />;
}

// ── Custom dropdown ─────────────────────────────────────────────
// Replaces native <select>: the OS-rendered option list can't be themed.
// Fully keyboard-operable (Enter/Space/↑/↓/Esc) and closes on outside click.
function VTDropdown({ value, options, onChange, ariaLabel }) {
  const opts = (options || []).map((o) => typeof o === 'object' ? o : { value: o, label: o });
  const [open, setOpen] = React.useState(false);
  const [active, setActive] = React.useState(0);
  const rootRef = React.useRef(null);
  const current = opts.find((o) => o.value === value) || opts[0] || { label: '' };

  React.useEffect(() => {
    if (!open) return;
    setActive(Math.max(0, opts.findIndex((o) => o.value === value)));
    const onDoc = (e) => {
      if (rootRef.current && !rootRef.current.contains(e.target)) setOpen(false);
    };
    document.addEventListener('mousedown', onDoc);
    return () => document.removeEventListener('mousedown', onDoc);
  }, [open]);

  const choose = (v) => { onChange(v); setOpen(false); };
  const onKey = (e) => {
    if (e.key === 'Escape') { setOpen(false); return; }
    if (!open) {
      if (e.key === 'Enter' || e.key === ' ' || e.key === 'ArrowDown') { e.preventDefault(); setOpen(true); }
      return;
    }
    if (e.key === 'ArrowDown') { e.preventDefault(); setActive((a) => Math.min(opts.length - 1, a + 1)); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setActive((a) => Math.max(0, a - 1)); }
    else if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); choose(opts[active].value); }
  };

  return (
    <div className="vc-dropdown" ref={rootRef}>
      <button type="button" className="vc-dropdown-btn"
      aria-haspopup="listbox" aria-expanded={open} aria-label={ariaLabel}
      onClick={() => setOpen((o) => !o)} onKeyDown={onKey}>
        <span>{current.label}</span>
        <i className="vc-dropdown-caret" aria-hidden="true" />
      </button>
      {open &&
      <ul className="vc-dropdown-menu" role="listbox" aria-label={ariaLabel}>
          {opts.map((o, i) =>
        <li key={o.value} role="option" aria-selected={o.value === value}
        className={'vc-dropdown-opt' + (o.value === value ? ' is-selected' : '') + (i === active ? ' is-active' : '')}
        onMouseEnter={() => setActive(i)}
        onMouseDown={(e) => { e.preventDefault(); choose(o.value); }}>
              {o.label}
            </li>
        )}
        </ul>
      }
    </div>);

}

// Keyboard activation for non-button elements that carry role="button".
// Returns an onKeyDown handler that fires `fn` on Enter / Space.
function onActivate(fn) {
  return (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); fn(e); }
  };
}

// ── Toast ───────────────────────────────────────────────────────
function VTToast() {
  const ctx = useVT();
  if (!ctx.toastText) return null;
  return <div className="vt-toast vc-card-glass" role="status" aria-live="polite">{ctx.toastText}</div>;
}

Object.assign(window, {
  VT, VTCtx, useVT, vtPath, fmtNumber,
  VTShell, V2BottomNav, VTDial, VTMap, VTDropdown, onActivate, VTModal, AddChargeModal, ExportModal, SessionDetailModal, VTToast
});
