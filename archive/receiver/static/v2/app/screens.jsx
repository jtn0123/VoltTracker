// Volt Tracker app · screens.
// Each screen uses VTShell + context (useVT) for state.

// ── Dashboard ───────────────────────────────────────────────────
function DashboardScreen() {
  const ctx = useVT();
  return (
    <VTShell kicker="OVERVIEW · APR 2026" title="Good morning, Sam.">
      <section className="vc-hero">
        <div className="vc-hero-card vc-card-glass">
          <div className="vc-hero-row" data-comment-anchor="55f2da884b-div-11-11">
            <div className="vc-hero-meter">
              <VTDial value={78} max={100} label="EV RATIO" unit="%" big />
            </div>
            <div className="vc-hero-stack">
              <article className="vc-stat">
                <span>ELECTRIC MILES</span>
                <strong>36,064</strong>
                <small><i className="vc-trend-up">+842</i> this month</small>
              </article>
              <article className="vc-stat">
                <span>GAS MILES</span>
                <strong>2,348</strong>
                <small><i className="vc-trend-flat">+47</i> this month</small>
              </article>
              <article className="vc-stat">
                <span>$ / MILE YTD</span>
                <strong>$0.087</strong>
                <small>3,021 mi · 402 kWh · 55.8 gal</small>
              </article>
              <article className="vc-stat">
                <span>EST. SAVED</span>
                <strong>$1,840</strong>
                <small>vs gas-only baseline</small>
              </article>
            </div>
          </div>
        </div>
      </section>

      <section className="vc-dials-row">
        <article className="vc-card-glass vc-mini-dial" onClick={() => ctx.setRoute('insights')} onKeyDown={onActivate(() => ctx.setRoute('insights'))} role="button" tabIndex={0}>
          <VTDial value={42.3} max={50} label="LIFETIME GAS" unit="MPG" />
          <p>2,348 gas miles · trending up</p>
        </article>
        <article className="vc-card-glass vc-mini-dial" onClick={() => ctx.setRoute('insights')} onKeyDown={onActivate(() => ctx.setRoute('insights'))} role="button" tabIndex={0}>
          <VTDial value={44.1} max={50} label="TANK MPG" unit="MPG" />
          <p>312 mi · since Apr 23</p>
        </article>
        <article className="vc-card-glass vc-mini-dial" onClick={() => ctx.setRoute('live')} onKeyDown={onActivate(() => ctx.setRoute('live'))} role="button" tabIndex={0}>
          <VTDial value={4.15} max={5} label="ELECTRIC" unit="mi/kWh" />
          <p>241 Wh/mi · best in March</p>
        </article>
        <article className="vc-card-glass vc-mini-dial" onClick={() => ctx.setRoute('battery')} onKeyDown={onActivate(() => ctx.setRoute('battery'))} role="button" tabIndex={0}>
          <VTDial value={91.3} max={100} label="PACK HEALTH" unit="%" />
          <p>684 cycles · cell 47 watch</p>
        </article>
      </section>

      <section className="vc-grid-2">
        <article className="vc-card-glass vc-trend-card">
          <header>
            <div>
              <span className="vc-kicker">GAS MPG · 30 DAYS</span>
              <h3>42.3 → 44.1 <em className="vc-trend-up">+4.3%</em></h3>
            </div>
            <Segmented options={['7d', '30d', '90d', 'all']} value={ctx.dashTimeframe} onChange={ctx.setDashTimeframe} />
          </header>
          <svg className="vc-trend-svg" viewBox="0 0 100 60" preserveAspectRatio="none">
            <defs>
              <linearGradient id="vc-trend-grad" x1="0" x2="0" y1="0" y2="1">
                <stop offset="0%" stopColor="var(--vc-mode)" stopOpacity="0.34" />
                <stop offset="100%" stopColor="var(--vc-mode)" stopOpacity="0" />
              </linearGradient>
            </defs>
            {[0, 15, 30, 45, 60].map((y) => <line key={y} x1="0" x2="100" y1={y} y2={y} stroke="rgba(255,255,255,0.04)" strokeWidth="0.2" />)}
            <path d={vtPath(VT.mpgTrend, 100, 60, 39, 45.5) + ' L100 60 L0 60 Z'} fill="url(#vc-trend-grad)" />
            <path d={vtPath(VT.mpgTrend, 100, 60, 39, 45.5)} fill="none" stroke="var(--vc-mode)" strokeWidth="0.7" strokeLinejoin="round" />
          </svg>
          <footer className="vc-trend-foot">
            <span>min <b>39.5</b></span><span>median <b>42.7</b></span><span>max <b>44.6</b></span>
            <span style={{ marginLeft: 'auto', color: 'var(--vc-mode)' }}>latest <b>44.1</b></span>
          </footer>
        </article>

        <article className="vc-card-glass vc-status-card">
          <header>
            <span className="vc-kicker">PARKED · GARAGE</span>
            <h3>Sparky · {ctx.live.soc.toFixed(1)}% SOC</h3>
          </header>
          <div className="vc-status-meta">
            <div><span>RANGE</span><strong>{Math.round(ctx.live.soc * 0.523)} mi</strong></div>
            <div><span>FUEL</span><strong>{VT.live.fuel}%</strong></div>
            <div><span>PACK</span><strong>{ctx.live.batteryTemp}°F</strong></div>
            <div><span>ODO</span><strong>49,328</strong></div>
          </div>
          <div className="vc-status-actions">
            <button className="vc-action" onClick={() => ctx.setRoute('live')}>⊕ Start trip</button>
            <button className="vc-action vc-action-ghost" onClick={() => ctx.setRoute('map')}>↗ Open map</button>
          </div>
        </article>
      </section>

      <section className="vc-grid-2">
        <article className="vc-card-glass vc-trips-card">
          <header>
            <span className="vc-kicker">RECENT TRIPS</span>
            <button className="vc-link" onClick={() => ctx.setRoute('trips')}>view all →</button>
          </header>
          {VT.trips.slice(0, 5).map((t) =>
          <button key={t.id} className={'vc-trip vc-trip-' + t.mode}
          onClick={() => {ctx.setSelectedTripId(t.id);ctx.setRoute('trips');}}>
              <span className="vc-trip-dot" />
              <div>
                <strong>{t.label}</strong>
                <small>{t.date}</small>
              </div>
              <em>{t.miles}<i>mi</i></em>
              <b>{t.efficiency}</b>
            </button>
          )}
        </article>

        <article className="vc-card-glass vc-insight-card">
          <header>
            <span className="vc-kicker">INSIGHTS · {VT.insights.length} NEW</span>
            <button className="vc-link" onClick={() => ctx.setRoute('insights')}>see all →</button>
          </header>
          {VT.insights.slice(0, 3).map((ins, i) =>
          <div key={i} className={'vc-insight vc-insight-' + ins.kind}>
              <span className="vc-insight-icon">{ins.icon}</span>
              <div>
                <strong>{ins.title}</strong>
                <p>{ins.body}</p>
              </div>
            </div>
          )}
        </article>
      </section>
    </VTShell>);

}

function Segmented({ options, value, onChange }) {
  return (
    <div className="vc-segment-pill" role="tablist">
      {options.map((o) =>
      <button key={o} type="button" role="tab" aria-selected={value === o}
      className={value === o ? 'is-active' : ''}
      onClick={() => onChange(o)}>{o}</button>
      )}
    </div>);

}

// ── Live Drive ──────────────────────────────────────────────────
function LiveScreen() {
  const ctx = useVT();
  const live = ctx.live;
  return (
    <VTShell kicker="LIVE · I-680 NORTH" title={`${ctx.mode === 'gas' ? 'Gas mode · ICE running' : 'EV mode · drawing ' + live.power.toFixed(1) + ' kW'}`}>
      <section className="vc-live-stage">
        <div className="vc-speedo" data-comment-anchor="0c9e0322eb-div-163-9">
          <VTDial value={Math.round(live.speed)} max={85} peak={62} label="SPEED" unit="MPH" big />
          <div className="vc-speedo-extras">
            <div><span>peak</span><b>62</b></div>
            <div><span>avg</span><b>39</b></div>
            <div><span>limit</span><b>65</b></div>
          </div>
        </div>

        <div className="vc-power-card vc-card-glass">
          <div className="vc-power-label">POWER FLOW · BATTERY → MOTOR</div>
          <div className="vc-power-num">
            <strong>{live.power.toFixed(1)}</strong><em>kW</em>
          </div>
          <div className="vc-power-meter">
            <div className="vc-power-track">
              <span className="vc-power-zero" />
              <span className="vc-power-fill" style={{
                width: Math.abs(live.power / 84) * 50 + '%',
                left: live.power >= 0 ? '50%' : 50 - Math.abs(live.power / 84) * 50 + '%'
              }} />
            </div>
            <div className="vc-power-scale">
              <span>−84</span><span style={{ color: 'rgba(255,255,255,0.7)' }}>0</span><span>+84</span>
            </div>
          </div>
          <div className="vc-power-row">
            <div><span>MODE</span><strong>{ctx.mode === 'gas' ? 'GAS · ICE' : 'EV · DRIVE'}</strong></div>
            <div><span>RPM</span><strong>{ctx.mode === 'gas' ? '1,840' : '0'}</strong></div>
            <div><span>REGEN PEAK</span><strong>54 kW</strong></div>
          </div>
        </div>
      </section>

      <section className="vc-live-secondary">
        <article className="vc-card-glass vc-soc-card">
          <div className="vc-soc-head">
            <div>
              <span className="vc-kicker">BATTERY · {live.soc.toFixed(1)}%</span>
              <h3>~{Math.round(live.soc * 0.523)} mi remaining</h3>
            </div>
            <span className="vc-pill vc-pill-mode">{ctx.mode.toUpperCase()}</span>
          </div>
          <div className="vc-soc-bar">
            <span className="vc-soc-bar-fill" style={{ width: live.soc + '%' }}>
              <i />
            </span>
            <span className="vc-soc-bar-floor">14%</span>
          </div>
          <div className="vc-soc-bar-meta">
            <span>FLOOR · 13.4%</span>
            <span>NOW · {live.soc.toFixed(1)}%</span>
            <span>FULL · 100%</span>
          </div>
        </article>

        <article className="vc-card-glass vc-stream-card">
          <div className="vc-kicker">LAST 24 SECONDS</div>
          <svg className="vc-stream-svg" viewBox="0 0 100 50" preserveAspectRatio="none" data-comment-anchor="f447556944-svg-221-11">
            <defs>
              <linearGradient id="vc-stream-grad" x1="0" x2="0" y1="0" y2="1">
                <stop offset="0%" stopColor="var(--vc-mode)" stopOpacity="0.5" />
                <stop offset="100%" stopColor="var(--vc-mode)" stopOpacity="0" />
              </linearGradient>
            </defs>
            {[0, 12, 25, 37, 50].map((y) => <line key={y} x1="0" x2="100" y1={y} y2={y} stroke="rgba(255,255,255,0.05)" strokeWidth="0.6" vectorEffect="non-scaling-stroke" />)}
            <path d={vtPath(ctx.speedTrail, 100, 50, 0, 85) + ' L100 50 L0 50 Z'} fill="url(#vc-stream-grad)" />
            <path d={vtPath(ctx.speedTrail, 100, 50, 0, 85)} fill="none" stroke="var(--vc-mode)" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" vectorEffect="non-scaling-stroke" />
            <path d={vtPath(ctx.powerTrail, 100, 50, -10, 85)} fill="none" stroke="#a4b8ff" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" strokeDasharray="3 3" vectorEffect="non-scaling-stroke" />
          </svg>
          <div className="vc-stream-legend">
            <span><i style={{ background: 'var(--vc-mode)' }} />speed mph</span>
            <span><i style={{ background: '#a4b8ff' }} />power kW</span>
          </div>
        </article>
      </section>

      <section className="vc-tiles">
        {[
        ['FUEL', VT.live.fuel + '%', '~7.4 gal'],
        ['HV TEMP', live.batteryTemp + '°F', 'nominal'],
        ['OUTSIDE', VT.live.outsideTemp + '°F', 'partly cloudy'],
        ['HVAC', '72°F', 'auto · 0.4 kW'],
        ['TIRES F', '38 PSI', 'within range'],
        ['TIRES R', '38 PSI', 'within range'],
        ['ELEVATION', '312 ft', '+12 in 60s'],
        ['HEADING', 'N · 12°', 'I-680 north']].
        map(([k, v, s]) =>
        <article key={k} className="vc-card-glass vc-tile">
            <span>{k}</span>
            <strong>{v}</strong>
            <small>{s}</small>
          </article>
        )}
      </section>
    </VTShell>);

}

// ── Trips ───────────────────────────────────────────────────────
function TripsScreen() {
  const ctx = useVT();
  const filtered = React.useMemo(() => {
    return VT.trips.filter((t) => {
      if (ctx.tripFilter !== 'all' && t.mode !== ctx.tripFilter) return false;
      if (ctx.tripSearch && !t.label.toLowerCase().includes(ctx.tripSearch.toLowerCase())) return false;
      return true;
    });
  }, [ctx.tripFilter, ctx.tripSearch]);

  const selected = VT.trips.find((t) => t.id === ctx.selectedTripId) || VT.trips[0];
  const counts = {
    all: VT.trips.length,
    ev: VT.trips.filter((t) => t.mode === 'ev').length,
    mixed: VT.trips.filter((t) => t.mode === 'mixed').length,
    gas: VT.trips.filter((t) => t.mode === 'gas').length
  };

  // Per-trip speed profile + SOC slope + clock ticks, derived from the
  // selected trip. The timeline chart used to be one hardcoded SVG path with
  // fixed 08:14… labels — identical for every trip.
  const timeline = React.useMemo(() => {
    const n = 26;
    let seed = (selected.id * 2654435761) >>> 0;
    const rnd = () => {seed = (seed * 1103515245 + 12345) >>> 0;return seed / 4294967296;};
    const avg = selected.stats?.avgSpeed || Math.round(selected.miles / selected.mins * 60) || 32;
    const speed = [];
    for (let i = 0; i < n; i++) {
      const ramp = Math.sin(i / (n - 1) * Math.PI); // slow at the ends
      speed.push(Math.max(0, avg * (0.4 + ramp * 0.85) + (rnd() - 0.5) * avg * 0.45));
    }
    const peak = Math.max(...speed) * 1.12;
    const socDrop = selected.mode === 'gas' ? 3 : selected.mode === 'mixed' ? 11 : 18;
    const start = (selected.date.split(' · ')[1] || '08:14').split(':').map(Number);
    const startMin = (start[0] || 8) * 60 + (start[1] || 0);
    const ticks = [0, 0.25, 0.5, 0.75, 1].map((f) => {
      const m = Math.round(startMin + selected.mins * f);
      return String(Math.floor(m / 60) % 24).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0');
    });
    return { speed, peak, socDrop, ticks };
  }, [selected.id]);

  return (
    <VTShell kicker={`APRIL · ${VT.trips.length} TRIPS · 480 MI`} title="Trips">
      <div className="vc-trips-grid">
        <aside className="vc-trips-list vc-card-glass">
          <div className="vc-trips-tabs">
            {['all', 'ev', 'mixed', 'gas'].map((f) =>
            <button key={f} type="button" className={ctx.tripFilter === f ? 'is-active' : ''}
            onClick={() => ctx.setTripFilter(f)}>
                {f[0].toUpperCase() + f.slice(1)} <em>{counts[f]}</em>
              </button>
            )}
          </div>
          <div className="vc-trips-search">
            <span>⌕</span>
            <input placeholder="Search trips…"
            value={ctx.tripSearch}
            onChange={(e) => ctx.setTripSearch(e.target.value)} />
            <span className="vc-kbd">⌘K</span>
          </div>
          {filtered.length === 0 &&
          <div className="vc-trip-empty">No trips match.</div>
          }
          {filtered.map((t) =>
          <button key={t.id}
          onClick={() => ctx.setSelectedTripId(t.id)}
          className={'vc-trip-row vc-trip-' + t.mode + (selected.id === t.id ? ' is-selected' : '')}>
              <i className="vc-trip-dot" />
              <div>
                <strong>{t.label}</strong>
                <small>{t.date} · {t.miles}mi · {t.mins}m</small>
              </div>
              <b>{t.efficiency}</b>
            </button>
          )}
        </aside>

        <section className="vc-trip-detail">
          <header className="vc-trip-detail-head vc-card-glass">
            <div>
              <span className="vc-kicker">TRIP #{selected.id} · {selected.mode.toUpperCase()}</span>
              <h2>{selected.label}</h2>
              <p>{selected.date} · {selected.miles} mi · {selected.mins} minutes · {selected.stats?.avgSpeed || Math.round(selected.miles / selected.mins * 60)} mph avg</p>
            </div>
            <div className="vc-trip-detail-actions">
              <button type="button" className="vc-glass-btn" onClick={() => ctx.toast('Exported trip #' + selected.id + ' as GPX')}>Export GPX</button>
              <button type="button" className="vc-glass-btn" onClick={() => ctx.toast('Trip tagged')}>Tag trip</button>
              <button type="button" className="vc-glass-btn vc-glass-danger" onClick={() => ctx.toast('Trip would be deleted')}>Delete</button>
            </div>
          </header>

          <div className="vc-trip-stat-row vc-card-glass">
            {[
            ['Distance', selected.miles, 'mi'],
            ['Avg speed', selected.stats?.avgSpeed || Math.round(selected.miles / selected.mins * 60), 'mph'],
            ['Energy', selected.stats?.energy || (selected.miles * 0.24).toFixed(1), 'kWh'],
            ['mi / kWh', selected.stats?.miPerKwh || 4.15, ''],
            ['Cost', '$' + (selected.stats?.cost || selected.miles * 0.029).toFixed(2), '@ $0.12/kWh']].
            map(([k, v, u]) =>
            <div key={k}>
                <span>{k}</span>
                <strong>{v}{u && <em>{u}</em>}</strong>
              </div>
            )}
          </div>

          <div className="vc-trip-charts">
            <article className="vc-card-glass vc-trip-route-card">
              <div className="vc-card-head">
                <span className="vc-kicker">Route</span>
                <small>{selected.miles} mi · {selected.label}</small>
              </div>
              <VTMap
                className="vc-trip-route-map"
                height={240}
                routes={[{
                  points: VT.tripRoutes[selected.id] || VT.tripRoutes[8421],
                  color: selected.mode === 'gas' ? '#ff7141' : selected.mode === 'mixed' ? '#c084fc' : 'var(--vc-mode)'
                }]} />

            </article>

            <article className="vc-card-glass vc-trip-energy-card">
              <div className="vc-card-head"><span className="vc-kicker">Energy</span><small>{selected.stats?.whPerMi || 241} Wh/mi</small></div>
              <div className="vc-energy-rows">
                <div><span>Propulsion</span><b>62%</b><i style={{ width: '62%', background: 'var(--vc-mode)' }} /></div>
                <div><span>HVAC</span><b>18%</b><i style={{ width: '18%', background: '#a4b8ff' }} /></div>
                <div><span>Aux</span><b>5%</b><i style={{ width: '5%', background: 'rgba(255,255,255,0.32)' }} /></div>
                <div><span>Regen</span><b>−15%</b><i style={{ width: '15%', background: '#7be3a3' }} /></div>
              </div>
              <dl className="vc-trip-kv">
                <div><dt>Elev gain</dt><dd>+{selected.stats?.elev || 342} ft</dd></div>
                <div><dt>Temp</dt><dd>{selected.stats?.tempLo || 58}–{selected.stats?.tempHi || 67}°F</dd></div>
                <div><dt>Wind</dt><dd>W · 8 mph</dd></div>
                <div><dt>Stops</dt><dd>3</dd></div>
              </dl>
            </article>
          </div>

          <article className="vc-card-glass vc-trip-timeline-card">
            <div className="vc-card-head"><span className="vc-kicker">Speed & SOC · over time</span></div>
            <svg className="vc-trip-timeline" viewBox="0 0 100 28" preserveAspectRatio="none">
              {[0, 7, 14, 21, 28].map((y) => <line key={y} x1="0" x2="100" y1={y} y2={y} stroke="rgba(255,255,255,0.05)" strokeWidth="0.15" vectorEffect="non-scaling-stroke" />)}
              <path d={vtPath(timeline.speed, 100, 28, 0, timeline.peak) + ' L100 28 L0 28 Z'} fill="var(--vc-mode)" opacity="0.12" />
              <path d={vtPath(timeline.speed, 100, 28, 0, timeline.peak)}
              fill="none" stroke="var(--vc-mode)" strokeWidth="1.6" strokeLinejoin="round" strokeLinecap="round" vectorEffect="non-scaling-stroke" />
              <path d={`M0 4 L100 ${4 + timeline.socDrop}`} fill="none" stroke="#a4b8ff" strokeWidth="1.2" strokeDasharray="2 2" vectorEffect="non-scaling-stroke" />
            </svg>
            <div className="vc-timeline-x">
              {timeline.ticks.map((t, i) => <span key={i}>{t}</span>)}
            </div>
            <div className="vc-timeline-legend">
              <span><i style={{ background: 'var(--vc-mode)' }} />speed mph</span>
              <span><i style={{ background: '#a4b8ff' }} />SOC %</span>
            </div>
          </article>
        </section>
      </div>
    </VTShell>);

}

// ── Battery ─────────────────────────────────────────────────────
function BatteryScreen() {
  const b = VT.battery;
  return (
    <VTShell kicker="HV PACK · 18.4 kWh NOMINAL" title="Pack health">
      <section className="vc-bat-hero">
        <article className="vc-card-glass vc-bat-dial-card" data-comment-anchor="d3c7e3c8d4-article-407-9">
          <VTDial value={91.3} max={100} label="CAPACITY" unit="%" big />

          {/* Stylized pack — 3 modules with current SOC + balance state */}
          <div className="vc-bat-pack">
            {b.modules.map((m, i) =>
            <div key={m.id} className="vc-bat-pack-mod" title={`${m.id}: ${m.voltage}V`}>
                <span className="vc-bat-pack-fill" style={{ height: m.pct + '%' }} />
                <em>{m.id}</em>
                <small>{m.voltage}V</small>
              </div>
            )}
            <div className="vc-bat-pack-tip" />
          </div>

          <div className="vc-bat-dial-meta">
            <div><span>ACTUAL</span><strong>16.8<em>kWh</em></strong></div>
            <div><span>LOSS</span><strong>8.7<em>%</em></strong></div>
            <div><span>CYCLES</span><strong>684</strong></div>
            <div><span>TEMP</span><strong>72°F</strong></div>
            <div><span>BALANCE</span><strong>Apr 29</strong></div>
            <div><span>SOH</span><strong style={{ color: 'var(--vc-ok)' }}>A</strong></div>
          </div>
        </article>

        <article className="vc-card-glass vc-bat-trend">
          <header>
            <span className="vc-kicker">CAPACITY · LIFETIME</span>
            <span className="vc-pill vc-pill-good">↓ below fleet avg</span>
          </header>
          <svg viewBox="0 0 100 60" preserveAspectRatio="none">
            {[0, 15, 30, 45, 60].map((y) => <line key={y} x1="0" x2="100" y1={y} y2={y} stroke="rgba(255,255,255,0.05)" strokeWidth="0.2" />)}
            <path d={vtPath(b.capacityChart.expected, 100, 60, 75, 100)} fill="none" stroke="rgba(255,255,255,0.32)" strokeWidth="0.5" strokeDasharray="1 1" />
            <path d={vtPath(b.capacityChart.actual, 100, 60, 75, 100)} fill="none" stroke="var(--vc-mode)" strokeWidth="0.8" />
            <text x="2" y="6" fontSize="2.4" fill="rgba(255,255,255,0.45)">100%</text>
            <text x="2" y="58" fontSize="2.4" fill="rgba(255,255,255,0.45)">75%</text>
          </svg>
          <footer>
            {b.capacityChart.labels.map((l) => <span key={l}>{l}</span>)}
          </footer>
        </article>
      </section>

      <section className="vc-card-glass vc-cells-card" data-comment-anchor="6dc2c123d2-section-445-7">
        <header>
          <div>
            <span className="vc-kicker">CELL VOLTAGE · 96 cells · Δ {b.delta}mV</span>
            <h3>3 modules balanced · 1 cell to watch</h3>
          </div>
          <div className="vc-cells-summary">
            <span>min <b>{b.cellsMin}V</b></span>
            <span>mean <b>{b.cellsMean}V</b></span>
            <span>max <b>{b.cellsMax}V</b></span>
            <span className="vc-pill vc-pill-warn">cell 47 outlier</span>
          </div>
        </header>

        <div className="vc-modules">
          {b.modules.map((m) =>
          <div key={m.id} className="vc-module">
              <strong>{m.id}</strong>
              <span className="vc-module-bar"><i style={{ width: m.pct + '%' }} /></span>
              <b>{m.voltage}V</b>
            </div>
          )}
        </div>

        <div className="vc-cell-heatmap">
          {Array.from({ length: 96 }).map((_, i) => {
            const cell = i + 1;
            const out = cell === 47;
            const hash = (i * 13 + 5) % 9 / 9;
            return (
              <div key={cell} className={'vc-cell' + (out ? ' is-out' : '')}
              style={{ '--c': out ? 0.05 : 0.15 + hash * 0.6 }}>
                <span>{cell}</span>
              </div>);

          })}
        </div>

        <div className="vc-cells-foot">
          <span className="vc-pill">last balance · Apr 29</span>
          <span className="vc-pill">last full discharge · Mar 18</span>
          <span style={{ marginLeft: 'auto', fontSize: 12, color: 'var(--vc-text-mut)' }}>updated 2 min ago</span>
        </div>
      </section>
    </VTShell>);

}

// ── Charging ────────────────────────────────────────────────────
function ChargingScreen() {
  const ctx = useVT();
  const c = ctx.charging;
  return (
    <VTShell kicker="CHARGING · 30 DAYS"
    title="Charging"
    action={<button type="button" className="vc-glass-btn" onClick={() => ctx.setModal('add-charge')}>+ Session</button>}>
      <section className="vc-charge-hero">
        <div className="vc-card-glass vc-charge-kpis" data-comment-anchor="8bee0dc850-div-492-9">
          {[
          { label: 'ENERGY IN', value: c.last30Kwh.toFixed(1), unit: 'kWh', delta: '+12%', spark: [4, 5, 5, 3, 7, 8, 6, 9, 11, 10, 12, 8, 7, 9, 11, 13, 9, 7, 8, 10, 12, 10, 9, 11, 13, 12, 10, 8, 12, 11], sub: c.sessionsCount + ' sessions' },
          { label: 'COST', value: '$' + c.monthlyCost.toFixed(2), unit: '/mo', delta: '−$3.40', spark: [1.0, 1.2, 1.1, 0.6, 1.5, 1.7, 1.3, 2.0, 2.4, 2.1, 2.6, 1.7, 1.5, 2.0, 2.4, 2.8, 1.9, 1.4, 1.7, 2.2, 2.6, 2.1, 1.9, 2.3, 2.7, 2.5, 2.1, 1.6, 2.5, 2.3], sub: '@ $0.12 / kWh' },
          { label: 'AVG SESSION', value: (c.last30Kwh / c.sessionsCount).toFixed(1), unit: 'kWh', delta: '+0.6', spark: [8, 9, 11, 7, 10, 12, 9, 10, 11, 12, 13, 9, 8, 10, 12, 11, 9, 10, 11, 12, 11, 10, 11, 12, 11, 10, 11, 9, 11, 10], sub: '~3h on L2' },
          { label: 'GAS SAVED', value: '$' + c.gasEquivalent, unit: '', delta: '+$12', spark: [22, 38, 42, 55, 68, 72, 78, 82, 86, 89, 94, 98, 99, 100, 102, 103, 104, 104, 104, 104, 104, 104, 104, 104, 104, 104, 104, 104, 104, 104], sub: 'vs 42 MPG @ $3.85' }].
          map((kpi) =>
          <article key={kpi.label}>
              <span>{kpi.label}</span>
              <strong>{kpi.value}{kpi.unit && <em>{kpi.unit}</em>}</strong>
              <i className="vc-kpi-trend">{kpi.delta}</i>
              <svg className="vc-kpi-spark" viewBox="0 0 100 30" preserveAspectRatio="none">
                <path d={vtPath(kpi.spark, 100, 30, Math.min(...kpi.spark) - 1, Math.max(...kpi.spark) + 1) + ' L100 30 L0 30 Z'}
              fill="var(--vc-mode)" opacity="0.16" />
                <path d={vtPath(kpi.spark, 100, 30, Math.min(...kpi.spark) - 1, Math.max(...kpi.spark) + 1)}
              fill="none" stroke="var(--vc-mode)" strokeWidth="0.8" strokeLinejoin="round" />
              </svg>
              <small>{kpi.sub}</small>
            </article>
          )}
        </div>

        <div className="vc-card-glass vc-charge-mix">
          <span className="vc-kicker">SESSION MIX</span>
          <div className="vc-charge-rings">
            <VTDial value={c.l2} max={c.l1 + c.l2 + c.dcfc} label="L2" unit="" />
          </div>
          <div className="vc-charge-mix-rows">
            <div><span><i style={{ background: 'var(--vc-mode)' }} />L2</span><b>{c.l2}</b></div>
            <div><span><i style={{ background: '#ffb547' }} />L1</span><b>{c.l1}</b></div>
            <div><span><i style={{ background: '#ff7a4d' }} />DCFC</span><b>{c.dcfc}</b></div>
          </div>
        </div>
      </section>

      <section className="vc-card-glass vc-charge-hours">
        <header>
          <div>
            <span className="vc-kicker">WHEN YOU CHARGE</span>
            <h3>Peak window · 21:00 – 23:00</h3>
          </div>
          <div className="vc-charge-legend">
            <span><i className="is-offpeak" /> off-peak</span>
            <span><i className="is-peak" /> standard</span>
            <em>shift 2 sessions ⇒ save ~$8/mo</em>
          </div>
        </header>
        <div className="vc-hour-bars">
          {c.hourly.map((h, i) => {
            const off = i >= 21 || i < 6;
            return <span key={i} className={off ? 'is-offpeak' : 'is-peak'} style={{ height: h + '%' }} />;
          })}
        </div>
        <div className="vc-hour-x">
          {[0, 3, 6, 9, 12, 15, 18, 21, 23].map((i) => <span key={i}>{String(i).padStart(2, '0')}</span>)}
        </div>
      </section>

      <section className="vc-charge-bottom">
        <article className="vc-card-glass vc-loc-list">
          <div className="vc-kicker">LOCATIONS</div>
          {c.locations.map((l) =>
          <div key={l.name} className="vc-loc-row">
              <strong>{l.name}</strong>
              <small>{l.sessions} sess · {l.type}</small>
              <span className="vc-loc-bar"><i style={{ width: l.share + '%' }} /></span>
              <b>{l.share}%</b>
            </div>
          )}
        </article>

        <article className="vc-card-glass vc-session-list">
          <header>
            <span className="vc-kicker">RECENT SESSIONS</span>
            <button className="vc-link" onClick={() => ctx.toast('All sessions view coming soon')}>all sessions →</button>
          </header>
          {c.sessions.map((s, i) =>
          <button key={i} className="vc-session-row" onClick={() => ctx.setModal({ kind: 'session', session: s })}>
              <span className={'vc-session-badge vc-badge-' + s.type.toLowerCase()}>{s.type}</span>
              <div>
                <strong>{s.location}</strong>
                <small>{s.date}</small>
              </div>
              <em>{s.kwh}<i>kWh</i></em>
              <span className="vc-session-soc">{s.socIn}→{s.socOut}<small>%</small></span>
              <b>${s.cost.toFixed(2)}</b>
            </button>
          )}
        </article>
      </section>
    </VTShell>);

}

Object.assign(window, {
  DashboardScreen, LiveScreen, TripsScreen, BatteryScreen, ChargingScreen,
  Segmented
});
