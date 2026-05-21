// Volt Tracker app · extra screens: Insights, Settings, Map.

// ── Insights ────────────────────────────────────────────────────
function InsightsScreen() {
  const ctx = useVT();
  const [insFilter, setInsFilter] = React.useState('all');
  const insFiltered = VT.insights.filter((ins) =>
  insFilter === 'all' ? true :
  insFilter === 'improving' ? ins.kind === 'good' :
  ins.kind === 'warn');
  return (
    <VTShell kicker="INSIGHTS · APRIL 2026" title="What we noticed">
      {/* Hero summary band — three plain stats so the page leads with numbers */}
      <section className="vc-card-glass vc-ins-summary">
        <article>
          <span className="vc-kicker">EV RATIO · APRIL</span>
          <strong>78<em>%</em></strong>
          <small><i className="vc-trend-up">+14 pts</i> vs March</small>
        </article>
        <article>
          <span className="vc-kicker">GAS MPG · APRIL</span>
          <strong>44.1<em>mpg</em></strong>
          <small><i className="vc-trend-up">+1.8</i> vs March · best month yet</small>
        </article>
        <article>
          <span className="vc-kicker">SAVED · YTD</span>
          <strong>$920</strong>
          <small>vs gas-only @ 32 MPG · projecting <b>$2.8k</b> annual</small>
        </article>
        <article>
          <span className="vc-kicker">PACK HEALTH</span>
          <strong>91.3<em>%</em></strong>
          <small>1 cell on watch · degrading below fleet avg</small>
        </article>
      </section>

      {/* MPG vs Temp — featured chart */}
      <ScatterCard />

      {/* Insight tiles — full-width grid */}
      <section className="vc-ins-tiles">
        <header className="vc-ins-section-head">
          <div>
            <span className="vc-kicker">{insFiltered.length} {insFiltered.length === 1 ? 'THING' : 'THINGS'}</span>
            <h2>What changed this month</h2>
          </div>
          <div className="vc-segment-pill" role="tablist" aria-label="Filter insights">
            {[['all', 'All'], ['improving', 'Improving'], ['watch', 'Watch']].map(([v, l]) =>
            <button key={v} type="button" role="tab" aria-selected={insFilter === v}
            className={insFilter === v ? 'is-active' : ''}
            onClick={() => setInsFilter(v)}>{l}</button>
            )}
          </div>
        </header>
        <div className="vc-insight-grid">
          {insFiltered.length === 0 &&
          <div className="vc-insight-empty">No insights in this category.</div>
          }
          {insFiltered.map((ins, i) => (
            <article key={i} className={'vc-card-glass vc-insight-tile vc-insight-' + ins.kind}>
              <header>
                <span className="vc-insight-icon-lg">{ins.icon}</span>
                <span className={'vc-insight-tag vc-insight-tag-' + ins.kind}>
                  {ins.kind === 'good' ? 'IMPROVING' : ins.kind === 'warn' ? 'WATCH' : 'NOTE'}
                </span>
              </header>
              <h3>{ins.title}</h3>
              <p>{ins.body}</p>
              <footer>
                <button className="vc-link">view detail →</button>
                <button className="vc-link vc-link-dim" onClick={() => ctx.toast('Insight dismissed')}>dismiss</button>
              </footer>
            </article>
          ))}
        </div>
      </section>

      {/* SOC floor histogram + maintenance side-by-side */}
      <section className="vc-grid-2 vc-insights-bottom">
        <SocHistogram />
        <article className="vc-card-glass vc-maint-card">
          <header>
            <div>
              <span className="vc-kicker">MAINTENANCE</span>
              <h3>Next up: tire rotation</h3>
            </div>
            <button type="button" className="vc-glass-btn" onClick={() => ctx.toast('Maintenance log opened')}>Log service</button>
          </header>
          <ul className="vc-maint-list">
            {[
              ['Tire rotation',     'Feb 14, 2026 · 48,648 mi', 'in 1,420 mi', 'ok'],
              ['Cabin air filter',  'Jun 02, 2025 · 44,210 mi', 'in 6 months', 'ok'],
              ['Brake fluid',       'Aug 11, 2024 · 38,002 mi', 'in 8 months', 'ok'],
              ['Coolant (battery)', 'Apr 2022 · 18,450 mi',     'overdue',     'warn'],
              ['Engine oil (gas)',  'Mar 04, 2026 · 49,328 mi', 'in 740 mi',   'ok'],
            ].map(([k, sub, due, status]) => (
              <li key={k} className={'vc-maint-row vc-maint-' + status}>
                <i className="vc-maint-dot" />
                <div>
                  <strong>{k}</strong>
                  <small>last: {sub}</small>
                </div>
                <b>{due}</b>
              </li>
            ))}
          </ul>
        </article>
      </section>

      {/* Cost YTD full-width — needs the breathing room for its bars */}
      <CostCardWide />
    </VTShell>);

}

// ── Scatter card — its own component so the math doesn't clutter the screen ─
function ScatterCard() {
  // Reproducible scatter. 42 trips spread across 6 months. The points are
  // colored by month so the chart tells a seasonal story; bigger badges show
  // monthly mean MPG and a smooth curve links them.
  const monthLabels  = ['Nov', 'Dec', 'Jan', 'Feb', 'Mar', 'Apr'];
  const monthTempBase = [52, 46, 44, 50, 58, 64];
  const monthColors  = ['#7ea4ff', '#9da9d6', '#bf9eb5', '#d9a982', '#e6c356', '#e6ff3a'];

  // Chart dims · responsive aspect via viewBox.
  const W = 880, H = 400, padL = 64, padR = 36, padT = 40, padB = 60;
  const tMin = 30, tMax = 90, mMin = 30, mMax = 48;
  const xOf = (t) => padL + (t - tMin) / (tMax - tMin) * (W - padL - padR);
  const yOf = (m) => H - padB - (m - mMin) / (mMax - mMin) * (H - padT - padB);

  const points = Array.from({ length: 42 }).map((_, i) => {
    const month = i % 6;
    const seed = (i * 9301 + 49297) % 233280;
    const r = seed / 233280;
    const r2 = (i * 6427 + 14753) % 233280 / 233280;
    const t = monthTempBase[month] + (r - 0.5) * 18;
    const baseline = 30 + (t - 30) * 0.20;
    const noise = (r2 - 0.5) * 6;
    const m = Math.max(mMin + 1, Math.min(mMax - 1, baseline + noise));
    return { t, m, month };
  });

  const monthAvg = monthLabels.map((lbl, i) => {
    const ms = points.filter(p => p.month === i);
    const t = ms.reduce((s, p) => s + p.t, 0) / ms.length;
    const m = ms.reduce((s, p) => s + p.m, 0) / ms.length;
    return { lbl, t, m, color: monthColors[i] };
  });

  // Smooth curve through monthly averages (Catmull-Rom → cubic Bézier).
  const smoothCurve = (pts) => {
    if (pts.length < 2) return '';
    const cmd = [`M ${xOf(pts[0].t).toFixed(1)} ${yOf(pts[0].m).toFixed(1)}`];
    for (let i = 0; i < pts.length - 1; i++) {
      const p0 = pts[Math.max(0, i - 1)];
      const p1 = pts[i];
      const p2 = pts[i + 1];
      const p3 = pts[Math.min(pts.length - 1, i + 2)];
      const c1x = xOf(p1.t) + (xOf(p2.t) - xOf(p0.t)) / 6;
      const c1y = yOf(p1.m) + (yOf(p2.m) - yOf(p0.m)) / 6;
      const c2x = xOf(p2.t) - (xOf(p3.t) - xOf(p1.t)) / 6;
      const c2y = yOf(p2.m) - (yOf(p3.m) - yOf(p1.m)) / 6;
      cmd.push(`C ${c1x.toFixed(1)} ${c1y.toFixed(1)}, ${c2x.toFixed(1)} ${c2y.toFixed(1)}, ${xOf(p2.t).toFixed(1)} ${yOf(p2.m).toFixed(1)}`);
    }
    return cmd.join(' ');
  };

  // Fit line endpoints
  const fitA = { t: 32, m: 30 + (32 - 30) * 0.20 };
  const fitB = { t: 88, m: 30 + (88 - 30) * 0.20 };
  const bandPath = `M ${xOf(fitA.t)} ${yOf(fitA.m + 3)} L ${xOf(fitB.t)} ${yOf(fitB.m + 3)} L ${xOf(fitB.t)} ${yOf(fitB.m - 3)} L ${xOf(fitA.t)} ${yOf(fitA.m - 3)} Z`;

  return (
    <section className="vc-card-glass vc-scatter-card" data-comment-anchor="8f7cdd79ff-article-28-9">
      <header className="vc-scatter-head">
        <div>
          <span className="vc-kicker">CORRELATION · GAS MPG vs OUTSIDE TEMPERATURE</span>
          <h2>Every 5° warmer is about <em>+1.0 MPG</em>.</h2>
        </div>
        <div className="vc-scatter-meta">
          <div><span>R²</span><b>0.62</b></div>
          <div><span>SAMPLES</span><b>42 trips</b></div>
          <div><span>SLOPE</span><b>+0.20<em>mpg/°F</em></b></div>
        </div>
      </header>

      <div className="vc-scatter-frame">
        <div className="vc-scatter-plot-shell" aria-label="Gas MPG rises as outside temperature warms">
          <div className="vc-scatter-y-scale" aria-hidden="true">
            <span>48</span>
            <span>42</span>
            <span>36</span>
            <span>30</span>
          </div>
          <span className="vc-scatter-axis-title vc-scatter-axis-y" aria-hidden="true">MPG</span>
          <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="xMidYMid meet"
               className="vc-scatter" data-comment-anchor="ca98dd4287-svg-57-17"
               aria-hidden="true" focusable="false">
            <defs>
              <linearGradient id="vc-scatter-band" x1="0" x2="1" y1="0" y2="0">
                <stop offset="0%"   stopColor="var(--vc-mode)" stopOpacity="0.04" />
                <stop offset="100%" stopColor="var(--vc-mode)" stopOpacity="0.16" />
              </linearGradient>
              <linearGradient id="vc-scatter-curve" x1="0" x2="1" y1="0" y2="0">
                <stop offset="0%"   stopColor="#7ea4ff" stopOpacity="0.7" />
                <stop offset="100%" stopColor="#e6ff3a" stopOpacity="0.95" />
              </linearGradient>
            </defs>

            <rect x={padL} y={padT} width={W - padL - padR} height={H - padT - padB}
              fill="rgba(255,255,255,0.012)" stroke="rgba(255,255,255,0.06)" strokeWidth="1" />

            {[36, 42].map(m => (
              <line key={m} x1={padL} x2={W - padR} y1={yOf(m)} y2={yOf(m)}
                stroke="rgba(255,255,255,0.06)" strokeWidth="1" strokeDasharray="3 5" />
            ))}
            {[45, 60, 75].map(t => (
              <line key={t} x1={xOf(t)} x2={xOf(t)} y1={padT} y2={H - padB}
                stroke="rgba(255,255,255,0.035)" strokeWidth="1" strokeDasharray="3 5" />
            ))}

          {/* Confidence band + best-fit */}
            <path d={bandPath} fill="url(#vc-scatter-band)" />
            <line x1={xOf(fitA.t)} y1={yOf(fitA.m)} x2={xOf(fitB.t)} y2={yOf(fitB.m)}
              stroke="var(--vc-mode)" strokeOpacity="0.55" strokeWidth="2"
              strokeDasharray="6 6" strokeLinecap="round" />

          {/* Trip dots */}
            {points.map((p, i) => (
              <circle key={i} cx={xOf(p.t)} cy={yOf(p.m)} r="6"
                fill={monthColors[p.month]} fillOpacity="0.78"
                stroke="#08080c" strokeWidth="1.6" />
            ))}

          {/* Monthly trend curve through averages */}
            <path d={smoothCurve(monthAvg)} fill="none"
              stroke="url(#vc-scatter-curve)" strokeWidth="2.5"
              strokeLinecap="round" strokeLinejoin="round" opacity="0.6" />

          {/* Monthly badges. MPG values live in the legend/caption so the plot does not become a label cloud. */}
            {monthAvg.map((mo, i) => {
              return (
                <g key={i}>
                  <circle cx={xOf(mo.t)} cy={yOf(mo.m)} r="11"
                    fill="#08080c" stroke={mo.color} strokeWidth="2" />
                  <circle cx={xOf(mo.t)} cy={yOf(mo.m)} r="4" fill={mo.color} />
                </g>
              );
            })}
          </svg>
          <div className="vc-scatter-x-scale" aria-hidden="true">
            <span>30°</span>
            <span>45°</span>
            <span>60°</span>
            <span>75°</span>
            <span>90°</span>
          </div>
          <span className="vc-scatter-axis-title vc-scatter-axis-x" aria-hidden="true">Outside temp</span>
        </div>

        <div className="vc-scatter-caption">
          <p>
            Sparky's gas-only trips, colored by month. The dashed line is the best fit at
            <b style={{ color: 'var(--vc-mode)' }}> +0.20 MPG per °F</b>; the band is the 1σ scatter.
            April is averaging <b>44.1 MPG at 64°F</b> — the best month so far this year.
          </p>
        </div>
      </div>

      <footer className="vc-scatter-legend">
        {monthLabels.map((lbl, i) => (
          <span key={lbl}>
            <i style={{ background: monthColors[i] }} />{lbl} · {monthAvg[i].m.toFixed(1)}
          </span>
        ))}
        <span className="vc-scatter-spacer" />
        <span><i className="line" style={{ background: 'var(--vc-mode)' }} />best-fit + 1σ band</span>
        <span><i className="line" style={{ background: 'linear-gradient(90deg,#7ea4ff,#e6ff3a)' }} />seasonal trend</span>
      </footer>
    </section>
  );
}

// ── SOC floor histogram — a real chart, not just decoration ──────
function SocHistogram() {
  // Mock distribution: counts of SOC% when gas engine activates.
  // Most events cluster around 13-15%, with a few outliers up to ~20%.
  const bins = [
    [10, 0],  [11, 1],  [12, 3],  [13, 8],  [14, 12],
    [15, 9],  [16, 5],  [17, 3],  [18, 2],  [19, 1],  [20, 1],
  ];
  const maxCount = Math.max(...bins.map(b => b[1]));
  const target = 14; // typical Volt SOC reserve

  return (
    <article className="vc-card-glass vc-soc-hist-card">
      <header>
        <div>
          <span className="vc-kicker">SOC FLOOR · BATTERY HANDOFF</span>
          <h3>Engine starts around 13.4% SOC</h3>
        </div>
        <div className="vc-soc-hist-meta">
          <div><span>median</span><b>13<em>%</em></b></div>
          <div><span>events</span><b>42</b></div>
        </div>
      </header>
      <div className="vc-soc-hist-chart">
        <svg viewBox="0 0 400 180" preserveAspectRatio="xMidYMid meet">
          {/* Y gridlines */}
          {[0, 3, 6, 9, 12].map(c => {
            const y = 150 - (c / maxCount) * 130;
            return <line key={c} x1={36} x2={396} y1={y} y2={y}
              stroke="rgba(255,255,255,0.05)" strokeWidth="1" strokeDasharray="3 4" />;
          })}
          {/* Y labels */}
          {[0, 6, 12].map(c => {
            const y = 150 - (c / maxCount) * 130;
            return <text key={c} x={30} y={y + 4} fontSize="10" fill="var(--vc-text-mut)"
              fontFamily="var(--vc-mono)" textAnchor="end">{c}</text>;
          })}
          {/* Bars */}
          {bins.map(([bin, count], i) => {
            const x = 40 + i * 32;
            const h = (count / maxCount) * 130;
            const y = 150 - h;
            const isTarget = bin === target;
            return (
              <g key={bin}>
                <rect x={x} y={y} width={26} height={h}
                  fill={isTarget ? 'var(--vc-mode)' : 'rgba(230,255,58,0.32)'}
                  stroke={isTarget ? 'var(--vc-mode)' : 'transparent'} strokeWidth="1"
                  rx="3" />
                {count > 0 && (
                  <text x={x + 13} y={y - 6} fontSize="10" fill="var(--vc-text)"
                    fontFamily="var(--vc-mono)" textAnchor="middle">{count}</text>
                )}
                <text x={x + 13} y={166} fontSize="10" fill="var(--vc-text-mut)"
                  fontFamily="var(--vc-mono)" textAnchor="middle">{bin}</text>
              </g>
            );
          })}
          {/* Median marker */}
          <line x1={40 + 3 * 32 + 13} x2={40 + 3 * 32 + 13} y1={20} y2={150}
            stroke="var(--vc-mode)" strokeWidth="1" strokeDasharray="3 3" opacity="0.6" />
          <text x={40 + 3 * 32 + 16} y={30} fontSize="10" fill="var(--vc-mode)"
            fontFamily="var(--vc-mono)">median 13%</text>
        </svg>
      </div>
      <p className="vc-soc-hist-foot">
        A lower SOC floor is kinder to the battery long-term —
        <b style={{ color: 'var(--vc-ok)' }}> Sparky is well-positioned</b>. Most handoffs land in the 13–15% band, which matches the Volt's design reserve.
      </p>
    </article>
  );
}

// ── Cost YTD wide ──────────────────────────────────────────────
function CostCardWide() {
  return (
    <section className="vc-card-glass vc-cost-wide">
      <header>
        <div>
          <span className="vc-kicker">COST · YEAR TO DATE</span>
          <h2>$0.087 per mile · 3,021 mi covered</h2>
        </div>
        <Segmented options={['YTD', '12mo', 'life']} value="YTD" onChange={() => {}} />
      </header>
      <div className="vc-cost-wide-grid">
        {[
          { k: 'ELECTRICITY', v: '$48.20',  sub: '402 kWh @ ~$0.12/kWh',  frac: 0.18, color: '#7ea4ff' },
          { k: 'GASOLINE',    v: '$214.40', sub: '55.8 gal @ ~$3.85/gal', frac: 0.78, color: 'var(--vc-gas)' },
          { k: 'MAINTENANCE', v: '$0.00',   sub: 'no service YTD',         frac: 0.0,  color: null },
          { k: 'PER MILE',    v: '$0.087',  sub: 'all-in · 3,021 mi YTD',  frac: 0.0,  color: null, isTotal: true },
        ].map((row, i) => (
          <article key={i} className={row.isTotal ? 'vc-cost-wide-item is-total' : 'vc-cost-wide-item'}>
            <span>{row.k}</span>
            <strong>{row.v}</strong>
            {row.frac > 0 && (
              <i className="vc-cost-wide-bar">
                <b style={{ width: row.frac * 100 + '%', background: row.color }} />
              </i>
            )}
            <small>{row.sub}</small>
          </article>
        ))}
      </div>
      <footer className="vc-cost-wide-foot">
        A gas-only car at 32 MPG covering the same 3,021 miles would have cost <b>$363.40</b> just in gasoline.
        Estimated saving year-to-date: <b style={{ color: 'var(--vc-mode)' }}>$920</b>.
      </footer>
    </section>
  );
}

// ── Settings ────────────────────────────────────────────────────
const SETTINGS_TABS = [
['profile', 'Profile'],
['vehicle', 'Vehicle'],
['units', 'Units'],
['theme', 'Theme'],
['integrations', 'Integrations'],
['notifications', 'Notifications'],
['data', 'Data'],
['about', 'About']];


function SettingsScreen() {
  const ctx = useVT();
  const [tab, setTab] = React.useState('profile');
  const [dirty, setDirty] = React.useState(false);
  const markDirty = () => setDirty(true);
  const save = () => {setDirty(false);ctx.toast('Settings saved');};

  return (
    <VTShell kicker="PREFERENCES · VEHICLE · DATA" title="Settings"
    action={
    <span style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <small style={{ color: dirty ? 'var(--vc-warn)' : 'var(--vc-text-mut)', fontFamily: 'var(--vc-mono)', fontSize: 11 }}>
            {dirty ? '● unsaved changes' : 'no unsaved changes'}
          </small>
        </span>
    }>
      <section className="vc-settings-shell">
        <aside className="vc-settings-tabs">
          {SETTINGS_TABS.map(([id, label]) =>
          <button key={id} type="button" role="tab" aria-selected={tab === id} className={tab === id ? 'is-active' : ''} onClick={() => setTab(id)}>{label}</button>
          )}
        </aside>

        <div className="vc-settings-body">
          {tab === 'profile' && <SetProfile markDirty={markDirty} />}
          {tab === 'vehicle' && <SetVehicle markDirty={markDirty} />}
          {tab === 'units' && <SetUnits markDirty={markDirty} />}
          {tab === 'theme' && <SetTheme />}
          {tab === 'integrations' && <SetIntegrations />}
          {tab === 'notifications' && <SetNotifications markDirty={markDirty} />}
          {tab === 'data' && <SetData />}
          {tab === 'about' && <SetAbout />}

          <footer className="vc-settings-save" data-comment-anchor="f75e819ff1-footer-205-11">
            <button type="button" className="vc-glass-btn" disabled={!dirty} onClick={() => setDirty(false)}>Discard</button>
            <button type="button" className="vc-glass-btn vc-glass-primary" disabled={!dirty} onClick={save}>Save settings</button>
          </footer>
        </div>
      </section>
    </VTShell>);

}

function SetCard({ title, blurb, children, action }) {
  return (
    <section className="vc-set-card vc-card-glass">
      <header>
        <div>
          <h2>{title}</h2>
          <p>{blurb}</p>
        </div>
        {action}
      </header>
      {children}
    </section>);

}

function SetField({ label, sub, children }) {
  return (
    <label className="vc-set-field">
      <span>{label}</span>
      {children}
      {sub && <small>{sub}</small>}
    </label>);

}

function SetProfile({ markDirty }) {
  return (
    <SetCard title="Profile" blurb="Your account info as it appears in Volt Tracker."
    action={<button className="vc-glass-btn">Change avatar</button>}>
      <div className="vc-set-avatar-row">
        <div className="vc-set-avatar">S</div>
        <div>
          <strong>Sam Rivera</strong>
          <small>sam@example.org · admin · joined Jul 2019</small>
        </div>
      </div>
      <div className="vc-set-grid">
        <SetField label="Display name"><input type="text" defaultValue="Sam Rivera" onChange={markDirty} /></SetField>
        <SetField label="Email" sub="Verified ✓"><input type="email" defaultValue="sam@example.org" onChange={markDirty} /></SetField>
        <SetField label="Timezone"><input type="text" defaultValue="America/Los_Angeles" onChange={markDirty} /></SetField>
        <SetField label="Locale"><input type="text" defaultValue="en-US" onChange={markDirty} /></SetField>
      </div>
    </SetCard>);

}

function SetVehicle({ markDirty }) {
  return (
    <SetCard title="Vehicle" blurb="Used for baselines, range estimates, and alerts."
    action={<button className="vc-glass-btn">+ Add vehicle</button>}>
      <div className="vc-set-vehicle-strip">
        <div className="vc-set-vehicle-icon">⚡</div>
        <div>
          <strong>Sparky</strong>
          <small>2017 Chevrolet Volt Premier · telemetry primary</small>
        </div>
        <span className="vc-pill vc-pill-good">Active</span>
      </div>
      <div className="vc-set-grid">
        <SetField label="Name"><input type="text" defaultValue="Sparky" onChange={markDirty} /></SetField>
        <SetField label="VIN"><input type="text" defaultValue="1G1RD6•••••1042" onChange={markDirty} /></SetField>
        <SetField label="Year / Make / Model"><input type="text" defaultValue="2017 Chevrolet Volt" onChange={markDirty} /></SetField>
        <SetField label="Trim"><input type="text" defaultValue="Premier" onChange={markDirty} /></SetField>
        <SetField label="Battery capacity" sub="kWh nominal"><input type="number" defaultValue="18.4" step="0.1" onChange={markDirty} /></SetField>
        <SetField label="In service"><input type="text" defaultValue="Jul 2019" onChange={markDirty} /></SetField>
      </div>
    </SetCard>);

}

function SetUnits({ markDirty }) {
  const [units, setUnits] = React.useState({ distance: 'miles', temp: 'F' });
  const change = (k, v) => {setUnits((u) => ({ ...u, [k]: v }));markDirty();};
  return (
    <SetCard title="Units & rates" blurb="Used everywhere a number with a unit appears.">
      <div className="vc-set-grid">
        <SetField label="Electricity cost" sub="$/kWh for home charging"><input type="number" step="0.01" defaultValue="0.12" onChange={markDirty} /></SetField>
        <SetField label="Gas price" sub="$/gallon baseline"><input type="number" step="0.01" defaultValue="3.85" onChange={markDirty} /></SetField>
        <SetField label="Distance">
          <div className="vc-segment-pill" style={{ alignSelf: 'flex-start' }}>
            <button className={units.distance === 'miles' ? 'is-active' : ''} onClick={() => change('distance', 'miles')}>miles</button>
            <button className={units.distance === 'km' ? 'is-active' : ''} onClick={() => change('distance', 'km')}>km</button>
          </div>
        </SetField>
        <SetField label="Temperature">
          <div className="vc-segment-pill" style={{ alignSelf: 'flex-start' }}>
            <button className={units.temp === 'F' ? 'is-active' : ''} onClick={() => change('temp', 'F')}>°F</button>
            <button className={units.temp === 'C' ? 'is-active' : ''} onClick={() => change('temp', 'C')}>°C</button>
          </div>
        </SetField>
      </div>
    </SetCard>);

}

function SetTheme() {
  const ctx = useVT();
  const themes = [
  ['volt', 'Volt yellow', ['#08080c', '#14141a', '#e6ff3a']],
  ['electric', 'Electric teal', ['#070b12', '#0a121d', '#00e0c5']],
  ['signal', 'Signal red', ['#0c0808', '#1a1414', '#ff4d5a']],
  ['brass', 'Brass', ['#16110b', '#211a14', '#d4b56a']]];

  return (
    <SetCard title="Theme" blurb="Switch the signal color. Affects buttons, dials, and chart accents app-wide.">
      <div className="vc-set-theme-grid">
        {themes.map(([id, label, swatches]) =>
        <button key={id}
        className={'vc-theme-card' + (ctx.tweaks.accent === id ? ' is-active' : '')}
        onClick={() => ctx.setTweak('accent', id)}>
            <div className="vc-theme-swatches">
              {swatches.map((c, i) => <i key={i} style={{ background: c }} />)}
            </div>
            <strong>{label}</strong>
          </button>
        )}
      </div>
      <hr className="vc-set-rule" />
      <div className="vc-set-grid">
        <SetField label="Ambient mode lighting">
          <div className="vc-segment-pill" style={{ alignSelf: 'flex-start' }}>
            <button className={ctx.tweaks.ambient ? 'is-active' : ''} onClick={() => ctx.setTweak('ambient', true)}>on</button>
            <button className={!ctx.tweaks.ambient ? 'is-active' : ''} onClick={() => ctx.setTweak('ambient', false)}>off</button>
          </div>
        </SetField>
        <SetField label="Density">
          <div className="vc-segment-pill" style={{ alignSelf: 'flex-start' }}>
            {['compact', 'comfortable', 'cozy'].map((d) =>
            <button key={d} className={ctx.tweaks.density === d ? 'is-active' : ''} onClick={() => ctx.setTweak('density', d)}>{d}</button>
            )}
          </div>
        </SetField>
        <SetField label="Contrast" sub="High contrast pushes muted text and borders for easier scanning. Also respects OS prefers-contrast.">
          <div className="vc-segment-pill" style={{ alignSelf: 'flex-start' }}>
            {['normal', 'high'].map((c) => (
              <button key={c} className={(ctx.tweaks.contrast || 'normal') === c ? 'is-active' : ''}
                onClick={() => ctx.setTweak('contrast', c)}>{c}</button>
            ))}
          </div>
        </SetField>
      </div>
    </SetCard>);

}

function SetIntegrations() {
  const ints = [
  ['Torque Pro', 'Live telemetry uploader', 'connected', 'ok'],
  ['Home Assistant', 'Charging session pushes', 'available', 'ghost'],
  ['Apple Health', 'Trip → workout sync', 'available', 'ghost'],
  ['Webhooks', 'POST events to URL', '2 active', 'ok'],
  ['Apple Shortcuts', 'Siri "log fill-up"', 'connected', 'ok']];

  return (
    <SetCard title="Integrations" blurb="Services that read or write to Volt Tracker.">
      <div className="vc-set-int-list">
        {ints.map(([name, sub, badge, kind]) =>
        <div key={name} className="vc-set-int-row">
            <div>
              <strong>{name}</strong>
              <small>{sub}</small>
            </div>
            <span className={'vc-pill' + (kind === 'ok' ? ' vc-pill-good' : '')}>{badge}</span>
            <button className="vc-glass-btn">Configure</button>
          </div>
        )}
      </div>
    </SetCard>);

}

function SetNotifications({ markDirty }) {
  const items = [
  ['Trip completed', 'Push when a trip ends', false],
  ['Charging finished', 'Push when charging hits 100% or unplugs', true],
  ['Battery cell drift', 'Alert when any cell drifts > 25mV from pack mean', true],
  ['Off-peak window', 'Reminder 10 min before off-peak rate begins', true],
  ['Maintenance due', 'When odometer or date crosses scheduled service', true],
  ['Insights · weekly digest', 'Sunday morning email with the week\'s patterns', false]];

  return (
    <SetCard title="Notifications" blurb="What we ping you about, and how.">
      <div className="vc-set-toggle-list">
        {items.map(([k, sub, defOn], i) =>
        <SetToggle key={i} label={k} sub={sub} defaultChecked={defOn} onChange={markDirty} />
        )}
      </div>
    </SetCard>);

}

function SetToggle({ label, sub, defaultChecked, onChange }) {
  const [on, setOn] = React.useState(defaultChecked);
  return (
    <div className="vc-set-toggle-row">
      <div>
        <strong>{label}</strong>
        <small>{sub}</small>
      </div>
      <button type="button" className={'vc-toggle' + (on ? ' is-on' : '')}
      aria-pressed={on} aria-label={label}
      onClick={() => {setOn((o) => !o);onChange && onChange();}}>
        <span />
      </button>
    </div>);

}

function SetData() {
  const ctx = useVT();
  return (
    <SetCard title="Data" blurb="Exports, imports, retention, and the nuclear option.">
      <div className="vc-set-action-list">
        <button className="vc-set-action" onClick={() => ctx.setModal('export')}>
          <span className="vc-set-action-ico">↓</span>
          <div>
            <strong>Export everything</strong>
            <small>CSV, JSON, or full backup · re-importable</small>
          </div>
          <em>→</em>
        </button>
        <button className="vc-set-action" onClick={() => ctx.toast('CSV import opened')}>
          <span className="vc-set-action-ico">↑</span>
          <div>
            <strong>Import Torque CSV</strong>
            <small>Historical log files from Torque Pro</small>
          </div>
          <em>→</em>
        </button>
        <button className="vc-set-action" onClick={() => ctx.toast('Retention settings')}>
          <span className="vc-set-action-ico">⏱</span>
          <div>
            <strong>Retention</strong>
            <small>Telemetry: 5 years · trips: forever · sessions: forever</small>
          </div>
          <em>→</em>
        </button>
        <button className="vc-set-action vc-set-action-danger" onClick={() => ctx.toast('Confirm dialog would open')}>
          <span className="vc-set-action-ico">⚠</span>
          <div>
            <strong>Reset database</strong>
            <small>Deletes all trips, sessions, and telemetry. Cannot be undone.</small>
          </div>
          <em>→</em>
        </button>
      </div>
    </SetCard>);

}

function SetAbout() {
  return (
    <SetCard title="About" blurb="Versions, source, and credits.">
      <dl className="vc-set-about">
        <div><dt>App version</dt><dd>v2.4.0 · self-hosted</dd></div>
        <div><dt>Build</dt><dd>2026.04.30 · 18a4f2c</dd></div>
        <div><dt>Database</dt><dd>PostgreSQL 16 · TimescaleDB · 2.84 GB</dd></div>
        <div><dt>Uptime</dt><dd>21 days, 4 hours</dd></div>
        <div><dt>Source</dt><dd><a className="vc-link" href="https://github.com/jtn0123/VoltTracker" target="_blank" rel="noopener noreferrer">github.com/jtn0123/VoltTracker</a></dd></div>
        <div><dt>License</dt><dd>MIT</dd></div>
      </dl>
    </SetCard>);

}

// ── Map ─────────────────────────────────────────────────────────
function MapScreen() {
  const ctx = useVT();
  const [layer, setLayer] = React.useState('routes'); // 'routes' | 'heat' | 'stops'
  const [mapFull, setMapFull] = React.useState(false);
  return (
    <VTShell kicker="GPS MAP · APRIL · 480 MI" title="Map">
      <div className="vc-map-grid">
        <section className={'vc-card-glass vc-map-canvas' + (mapFull ? ' is-fullscreen' : '')}>
          <header className="vc-map-head">
            <div>
              <span className="vc-kicker">{VT.trips.length} trips · 480 mi</span>
              <h3>April · Bay Area + Tahoe</h3>
            </div>
            <div className="vc-map-controls">
              <div className="vc-map-control-row">
                <div className="vc-segment-pill">
                  {['routes', 'heat', 'stops'].map((l) =>
                  <button key={l} type="button" className={layer === l ? 'is-active' : ''} onClick={() => setLayer(l)}>
                      {l}
                    </button>
                  )}
                </div>
                <button type="button" className="vc-map-full-btn" aria-pressed={mapFull} onClick={() => setMapFull((value) => !value)}>
                  {mapFull ? 'Exit' : 'Full screen'}
                </button>
              </div>
              <div className="vc-map-legend">
                <span><i style={{ background: 'var(--vc-mode)' }} />EV</span>
                <span><i style={{ background: 'var(--vc-mixed)' }} />mixed</span>
                <span><i style={{ background: 'var(--vc-gas)' }} />gas</span>
              </div>
            </div>
          </header>

          {(() => {
            // Build per-trip routes from mock data. Each route gets colored
            // by mode. Mixed = volt-yellow base since the gas portion blends in.
            const routes = VT.trips.
            filter((t) => VT.tripRoutes[t.id]).
            map((t) => ({
              points: VT.tripRoutes[t.id],
              color: t.mode === 'gas' ? '#ff7141' :
              t.mode === 'mixed' ? '#c084fc' :
              'var(--vc-mode)'
            }));
            return <VTMap className="vc-map-real" height="100%" routes={routes} fitPad={36} layer={layer} />;
          })()}

          <footer className="vc-map-foot">
            <div className="vc-pill"><b>April 1 — 30, 2026</b></div>
            <span className="vc-kicker">layer · {layer === 'routes' ? 'trip lines' : layer === 'heat' ? 'heat — corridor density' : 'stops — start & end points'}</span>
          </footer>
        </section>

        <aside className="vc-map-side">
          <article className="vc-card-glass vc-map-panel">
            <header><span className="vc-kicker">BY DISTANCE</span></header>
            {VT.trips.slice().sort((a, b) => b.miles - a.miles).slice(0, 5).map((t) =>
            <button key={t.id} className={'vc-map-row vc-trip-' + t.mode}
            onClick={() => {ctx.setSelectedTripId(t.id);ctx.setRoute('trips');}}>
                <span className="vc-trip-dot" />
                <div>
                  <strong>{t.label}</strong>
                  <small>{t.date.split(' · ')[0]}</small>
                </div>
                <b>{t.miles}<em>mi</em></b>
              </button>
            )}
          </article>

          <article className="vc-card-glass vc-map-panel">
            <header><span className="vc-kicker">BY DAY</span></header>
            <div className="vc-map-day-grid">
              {Array.from({ length: 30 }).map((_, i) => {
                const d = i + 1;
                const hasTrip = VT.trips.find((t) => t.date.includes('Apr ' + d) || t.date.includes('Apr ' + String(d).padStart(2, '0')));
                return (
                  <span key={d} className={'vc-day' + (hasTrip ? ' has-trip vc-trip-' + hasTrip.mode : '')} title={'Apr ' + d}>
                    {d}
                  </span>);

              })}
            </div>
            <footer>
              <span><i style={{ background: 'var(--vc-mode)' }} />ev</span>
              <span><i style={{ background: 'var(--vc-mixed)' }} />mixed</span>
              <span><i style={{ background: 'var(--vc-gas)' }} />gas</span>
            </footer>
          </article>
        </aside>
      </div>
    </VTShell>);

}

Object.assign(window, {
  InsightsScreen, SettingsScreen, MapScreen,
  SetProfile, SetVehicle, SetUnits, SetTheme, SetIntegrations, SetNotifications, SetData, SetAbout
});
