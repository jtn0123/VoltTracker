// Volt Tracker app · root.
// State, routing, live data sim, accent theming, and Tweaks wiring.

const TWEAK_DEFAULTS = /*EDITMODE-BEGIN*/{
  "accent": "volt",
  "ambient": true,
  "density": "comfortable",
  "contrast": "normal",
  "demoData": true
}/*EDITMODE-END*/;

function VTApp() {
  // ── Navigation ────────────────────────────────────────────────
  // Hash-based routing so refresh / share links survive.
  const initialRoute = (window.location.hash || '#home').replace('#', '');
  const [route, setRoute] = React.useState(NAV.some(n => n[0] === initialRoute) ? initialRoute : 'home');
  React.useEffect(() => {
    const onHash = () => {
      const r = (window.location.hash || '#home').replace('#', '');
      if (NAV.some(n => n[0] === r)) setRoute(r);
    };
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);
  React.useEffect(() => {
    window.history.replaceState(null, '', '#' + route);
  }, [route]);

  // ── Mode + selection ─────────────────────────────────────────
  const [mode, setMode] = React.useState('ev');
  const [driveMode, setDriveMode] = React.useState('normal');
  const [selectedTripId, setSelectedTripId] = React.useState(VT.trips[0].id);
  const [tripFilter, setTripFilter] = React.useState('all');
  const [tripSearch, setTripSearch] = React.useState('');
  const [dashTimeframe, setDashTimeframe] = React.useState('30d');
  const [modal, setModal] = React.useState(null);
  const [charging, setCharging] = React.useState(VT.charging);

  // ── Tweaks (live theme tweaks) ────────────────────────────────
  const [tweaks, setTweak] = useTweaks(TWEAK_DEFAULTS);

  // ── Toast ────────────────────────────────────────────────────
  const [toastText, setToastText] = React.useState(null);
  const toastTimer = React.useRef(null);
  const toast = React.useCallback((txt) => {
    setToastText(txt);
    clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setToastText(null), 2400);
  }, []);

  // ── Live data simulation ─────────────────────────────────────
  // Speed/power/SOC tick every 1.5s with realistic-ish drift.
  const [live, setLive] = React.useState({
    speed: 47, power: 18.2, soc: 78.4, batteryTemp: 72,
  });
  const [speedTrail, setSpeedTrail] = React.useState(VT.live.speedSamples);
  const [powerTrail, setPowerTrail] = React.useState(VT.live.powerSamples);

  React.useEffect(() => {
    const tick = () => {
      setLive(prev => {
        const speedJitter = (Math.random() - 0.5) * 4;
        const speedRaw = mode === 'gas'
          ? 58 + speedJitter * 2
          : 47 + speedJitter * 2;
        const speed = Math.max(0, Math.min(75, speedRaw));
        const accel = speed - prev.speed;
        // Power = baseline cruise draw ± acceleration component. Gas mode shows
        // engine torque pushing positive numbers.
        const cruise = mode === 'gas' ? 32 : 16;
        const power = Math.max(-30, Math.min(60, cruise + accel * 4 + (Math.random() - 0.5) * 4));
        const soc = mode === 'gas' ? prev.soc : Math.max(13.5, prev.soc - 0.02);
        const batteryTemp = prev.batteryTemp + (Math.random() - 0.5) * 0.3;
        return { speed, power, soc, batteryTemp: Math.round(batteryTemp * 10) / 10 };
      });
      setSpeedTrail(t => [...t.slice(1), 0].map((_, i, arr) =>
        i === arr.length - 1
          ? (mode === 'gas' ? 58 : 47) + (Math.random() - 0.5) * 6
          : t[i + 1] !== undefined ? t[i + 1] : t[i]
      ));
      setPowerTrail(t => [...t.slice(1), (mode === 'gas' ? 30 : 16) + (Math.random() - 0.5) * 12]);
    };
    const id = setInterval(tick, 1500);
    return () => clearInterval(id);
  }, [mode]);

  // ── Actions ──────────────────────────────────────────────────
  const addSession = React.useCallback((form) => {
    setCharging(c => ({
      ...c,
      sessions: [{
        date: new Date().toLocaleString('en-US', { month: 'short', day: 'numeric' }) + ' · ' + form.start,
        type: form.type,
        kwh: form.kwh,
        socIn: form.startSoc,
        socOut: form.endSoc,
        duration: '~3h',
        location: form.location,
        cost: form.cost,
      }, ...c.sessions],
      sessionsCount: c.sessionsCount + 1,
      last30Kwh: c.last30Kwh + form.kwh,
    }));
  }, []);

  // ── Context value ────────────────────────────────────────────
  const ctx = React.useMemo(() => ({
    route, setRoute,
    mode, setMode,
    driveMode, setDriveMode,
    selectedTripId, setSelectedTripId,
    tripFilter, setTripFilter,
    tripSearch, setTripSearch,
    dashTimeframe, setDashTimeframe,
    modal, setModal,
    charging, addSession,
    live, speedTrail, powerTrail,
    tweaks, setTweak,
    toast, toastText,
  }), [route, mode, driveMode, selectedTripId, tripFilter, tripSearch, dashTimeframe, modal, charging, live, speedTrail, powerTrail, tweaks, toast, toastText, addSession]);

  // Choose screen
  const Screen =
    route === 'home'     ? DashboardScreen :
    route === 'live'     ? LiveScreen :
    route === 'trips'    ? TripsScreen :
    route === 'battery'  ? BatteryScreen :
    route === 'charging' ? ChargingScreen :
    route === 'insights' ? InsightsScreen :
    route === 'settings' ? SettingsScreen :
    route === 'map'      ? MapScreen :
    DashboardScreen;

  // Apply accent + ambient as data attributes so CSS can react.
  React.useEffect(() => {
    document.documentElement.setAttribute('data-accent', tweaks.accent);
    document.documentElement.setAttribute('data-ambient', tweaks.ambient ? 'on' : 'off');
    document.documentElement.setAttribute('data-density', tweaks.density);
    document.documentElement.setAttribute('data-contrast', tweaks.contrast || 'normal');
  }, [tweaks.accent, tweaks.ambient, tweaks.density, tweaks.contrast]);
  React.useEffect(() => {
    document.documentElement.setAttribute('data-route', route);
  }, [route]);

  return (
    <VTCtx.Provider value={ctx}>
      <Screen />
      <VTToast />
      <VTTweaksPanel />
    </VTCtx.Provider>
  );
}

// ── Tweaks panel ──────────────────────────────────────────────
// Floating panel users can pop open via the toolbar to quickly
// experiment with theme. Settings → Theme has the same controls
// in a less invasive place.
function VTTweaksPanel() {
  const ctx = useVT();
  return (
    <TweaksPanel title="Live tweaks">
      <TweakSection label="Theme">
        <TweakColor
          label="Accent"
          options={['#e6ff3a', '#00e0c5', '#ff4d5a', '#d4b56a']}
          value={(
            ctx.tweaks.accent === 'volt'     ? '#e6ff3a' :
            ctx.tweaks.accent === 'electric' ? '#00e0c5' :
            ctx.tweaks.accent === 'signal'   ? '#ff4d5a' :
            '#d4b56a'
          )}
          onChange={(v) => {
            const name = v === '#e6ff3a' ? 'volt' :
                         v === '#00e0c5' ? 'electric' :
                         v === '#ff4d5a' ? 'signal' : 'brass';
            ctx.setTweak('accent', name);
          }}
        />
        <TweakToggle label="Ambient mode lighting" value={ctx.tweaks.ambient}
          onChange={(v) => ctx.setTweak('ambient', v)} />
        <TweakRadio label="Density" options={['compact','comfortable','cozy']}
          value={ctx.tweaks.density} onChange={(v) => ctx.setTweak('density', v)} />
      </TweakSection>

      <TweakSection label="Demo controls">
        <TweakRadio label="Drive mode" options={['ev', 'gas']}
          value={ctx.mode === 'gas' ? 'gas' : 'ev'}
          onChange={(v) => ctx.setMode(v)} />
        <TweakSelect label="Jump to screen"
          options={NAV.map(([id, label]) => ({ value: id, label }))}
          value={ctx.route}
          onChange={(v) => ctx.setRoute(v)} />
      </TweakSection>
    </TweaksPanel>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<VTApp />);
