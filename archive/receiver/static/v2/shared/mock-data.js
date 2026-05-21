// Shared mock data across all 3 design directions.
// Keeps the same vehicle "story" so directions are comparable.

window.VT_DATA = {
  vehicle: {
    name: 'Sparky',
    model: '2017 Chevrolet Volt Premier',
    vin: '1G1RD6•••••1042',
    inService: 'Jul 2019',
  },
  status: {
    connected: true,
    lastSync: '12s ago',
    lifetimeMiles: 38412,
    electricMiles: 36064,
    gasMiles: 2348,
    evRatio: 0.78,
  },
  summary: {
    lifetimeMpg: 42.3,
    tankMpg: 44.1,
    socFloor: 13.4,
    socTransitions: 42,
    totalMiles: 38412,
    kWhPerMile: 0.241,
    miPerKWh: 4.15,
    totalKWhCharged: 8421,
    chargingSessions: 612,
  },
  live: {
    speed: 47,
    speedPeak: 62,
    power: 18.2,
    powerPeak: 84,
    mode: 'EV',
    soc: 78.4,
    fuel: 64.0,
    rpm: 0,
    batteryTemp: 72,
    outsideTemp: 64,
    hvac: '72°F auto',
    tires: '38 / 38 PSI',
    elevation: 312,
    heading: 'N · 12°',
    range: 41,
    odometer: 49328,
    motorA: 2840,
    motorB: 2840,
    generator: 0,
    powerDirection: 'discharge',
    // last 24 samples of speed (mph)
    speedSamples: [38, 40, 44, 48, 52, 55, 58, 60, 62, 60, 58, 56, 54, 50, 47, 44, 42, 41, 43, 45, 46, 47, 47, 47],
    powerSamples: [10, 14, 18, 24, 30, 36, 42, 48, 52, 48, 40, 32, 24, 16, 8, 2, -4, -8, -6, 0, 8, 14, 18, 18],
    socSamples: [80, 80, 79.8, 79.6, 79.4, 79.2, 79.0, 78.8, 78.7, 78.7, 78.6, 78.6, 78.6, 78.6, 78.6, 78.5, 78.5, 78.5, 78.5, 78.5, 78.4, 78.4, 78.4, 78.4],
  },
  mpgTrend: [
    // ~30 days, MPG values
    41.2, 40.8, 39.5, 42.1, 43.4, 41.8, 40.2, 42.8, 44.0, 43.6,
    42.3, 41.9, 40.8, 42.7, 43.5, 44.2, 43.8, 42.5, 41.4, 42.6,
    43.1, 42.9, 43.7, 44.4, 43.8, 42.5, 41.7, 43.2, 44.6, 44.1,
  ],
  mpgTrendDates: [
    'Apr 1','Apr 2','Apr 3','Apr 4','Apr 5','Apr 6','Apr 7','Apr 8','Apr 9','Apr 10',
    'Apr 11','Apr 12','Apr 13','Apr 14','Apr 15','Apr 16','Apr 17','Apr 18','Apr 19','Apr 20',
    'Apr 21','Apr 22','Apr 23','Apr 24','Apr 25','Apr 26','Apr 27','Apr 28','Apr 29','Apr 30',
  ],
  trips: [
    { id: 8421, label: 'Home → Office',           date: 'Apr 30 · 08:14', miles: 18.4, mins: 28,  efficiency: '4.1 mi/kWh', mode: 'ev',    selected: true,
      stats: { distance: 18.4, avgSpeed: 39, energy: 4.4, gas: null, miPerKwh: 4.15, regen: 15, propulsion: 62, hvac: 18, aux: 5, whPerMi: 241, elev: 342, tempLo: 58, tempHi: 67, cost: 0.53 } },
    { id: 8420, label: 'Office → Trader Joe\'s',  date: 'Apr 29 · 17:42', miles: 6.1,  mins: 14,  efficiency: '3.9 mi/kWh', mode: 'ev' },
    { id: 8419, label: 'Trader Joe\'s → Home',    date: 'Apr 29 · 19:08', miles: 7.3,  mins: 14,  efficiency: '4.0 mi/kWh', mode: 'ev' },
    { id: 8418, label: 'Home → Tahoe',            date: 'Apr 28 · 07:22', miles: 184.2,mins: 173, efficiency: '41.7 MPG',   mode: 'mixed' },
    { id: 8417, label: 'Tahoe Loop',              date: 'Apr 27 · 10:00', miles: 22.0, mins: 47,  efficiency: '3.6 mi/kWh', mode: 'ev' },
    { id: 8416, label: 'Tahoe → Home',            date: 'Apr 26 · 16:48', miles: 178.5,mins: 160, efficiency: '43.2 MPG',   mode: 'gas' },
    { id: 8415, label: 'Home → Hardware Store',   date: 'Apr 25 · 09:12', miles: 4.7,  mins: 12,  efficiency: '3.9 mi/kWh', mode: 'ev' },
    { id: 8414, label: 'Home → Office',           date: 'Apr 24 · 08:18', miles: 18.4, mins: 28,  efficiency: '4.1 mi/kWh', mode: 'ev' },
    { id: 8413, label: 'Home → Costco',           date: 'Apr 23 · 11:02', miles: 12.6, mins: 22,  efficiency: '4.2 mi/kWh', mode: 'ev' },
    { id: 8412, label: 'Office → Home',           date: 'Apr 22 · 18:34', miles: 18.4, mins: 31,  efficiency: '4.0 mi/kWh', mode: 'ev' },
  ],
  battery: {
    capacity: 91.3,
    actualKwh: 16.8,
    originalKwh: 18.4,
    label: 'Healthy',
    cellsMin: 3.971,
    cellsMean: 3.982,
    cellsMax: 3.989,
    delta: 18, // mV
    cycles: 684,
    temperature: 72,
    tempBand: '18 – 32 °C',
    lastBalance: 'Apr 29, 2026',
    capacityChart: {
      // 9 sample points
      actual:   [100, 99.2, 98.1, 96.9, 95.2, 93.7, 92.4, 91.7, 91.3],
      expected: [100, 98.4, 96.7, 94.4, 92.0, 89.4, 86.8, 84.0, 81.0],
      labels:   ['2019','2020','2021','2022','2023','2024','2025','Apr','Now'],
    },
    modules: [
      { id: 'M1', voltage: 3.982, pct: 82 },
      { id: 'M2', voltage: 3.977, pct: 76 },
      { id: 'M3', voltage: 3.989, pct: 89 },
    ],
    outlierCell: 47,
  },
  charging: {
    last30Kwh: 248.6,
    sessionsCount: 24,
    monthlyCost: 29.83,
    avgSession: 10.4,
    gasEquivalent: 104,
    l1: 1,
    l2: 23,
    dcfc: 0,
    hourly: [8, 12, 18, 24, 16, 10, 4, 0, 0, 2, 4, 8, 12, 10, 6, 4, 8, 14, 22, 30, 42, 68, 82, 54],
    locations: [
      { name: 'Home Garage', sessions: 19, type: 'L2', share: 78 },
      { name: 'Office',      sessions: 4,  type: 'L2', share: 18 },
      { name: 'Public L2',   sessions: 1,  type: 'L2', share: 6 },
    ],
    sessions: [
      { date: 'Apr 30 · 21:18', type: 'L2', kwh: 11.8, socIn: 24, socOut: 91, duration: '3h 18m', location: 'Home',   cost: 1.41 },
      { date: 'Apr 29 · 22:04', type: 'L2', kwh: 9.6,  socIn: 36, socOut: 90, duration: '2h 42m', location: 'Home',   cost: 1.15 },
      { date: 'Apr 28 · 18:12', type: 'L1', kwh: 5.2,  socIn: 58, socOut: 88, duration: '7h 05m', location: 'Office', cost: 0.62 },
      { date: 'Apr 27 · 20:48', type: 'L2', kwh: 10.4, socIn: 32, socOut: 90, duration: '2h 58m', location: 'Home',   cost: 1.25 },
      { date: 'Apr 26 · 22:10', type: 'L2', kwh: 12.1, socIn: 22, socOut: 92, duration: '3h 22m', location: 'Home',   cost: 1.45 },
    ],
  },
  insights: [
    { kind: 'good', icon: '↑', title: 'Best month yet for EV ratio',
      body: 'April hit 78% electric, up from 64% in March. If you hold this pattern, projected annual savings rise about $45.' },
    { kind: 'good', icon: '✓', title: 'Battery degrading below average',
      body: '8.7% capacity loss across 38k miles vs ~12% expected for 2017 Volts. The 13.4% average SOC floor is helping.' },
    { kind: 'warn', icon: '!', title: 'Cell 47 trending low',
      body: 'Cell 47 has drifted 18mV below the pack mean over the past two weeks. Worth watching, not yet replacing.' },
    { kind: 'info', icon: 'i', title: 'Cheaper to charge after 21:00',
      body: 'Your utility off-peak window starts at 9pm. Shifting two L2 sessions per week saves ~$8/mo.' },
    { kind: 'good', icon: '◇', title: 'Tahoe trip MPG within 4% of route avg',
      body: 'Apr 28\'s 184mi roundtrip hit 41.7 MPG — strong for that elevation profile.' },
  ],
  cost: {
    electricity: 48.20,
    gasoline: 214.40,
    maintenance: 0,
    perMile: 0.087,
    milesYTD: 3021,
    kwhYTD: 402,
    galYTD: 55.8,
  },

  /* Plausible Bay-Area + Tahoe coordinates for real map rendering. */
  places: {
    home:      [37.886, -122.118],   // Lafayette
    office:    [37.795, -122.401],   // SF Financial District
    traderJoes:[37.891, -122.106],   // Lafayette TJs
    hardware:  [37.910, -122.067],   // Walnut Creek
    costco:    [37.973, -122.052],   // Concord
    tahoe:     [38.940, -119.969],   // South Lake Tahoe
  },

  /* Per-trip route polylines (approximated). Keyed by trip id. */
  tripRoutes: {
    8421: [[37.886,-122.118],[37.888,-122.131],[37.889,-122.156],[37.888,-122.184],[37.881,-122.213],[37.874,-122.245],[37.862,-122.273],[37.844,-122.292],[37.826,-122.315],[37.807,-122.366],[37.800,-122.395],[37.795,-122.401]],
    8420: [[37.795,-122.401],[37.810,-122.385],[37.829,-122.331],[37.851,-122.292],[37.871,-122.252],[37.886,-122.165],[37.891,-122.106]],
    8419: [[37.891,-122.106],[37.890,-122.114],[37.887,-122.119],[37.886,-122.118]],
    8418: [[37.886,-122.118],[37.905,-122.085],[37.940,-122.041],[37.992,-121.998],[38.045,-121.880],[38.124,-121.633],[38.262,-121.366],[38.395,-121.118],[38.527,-120.812],[38.640,-120.530],[38.732,-120.288],[38.842,-120.106],[38.940,-119.969]],
    8417: [[38.940,-119.969],[38.962,-119.948],[38.985,-119.928],[39.005,-119.952],[39.012,-119.989],[38.998,-120.019],[38.972,-120.020],[38.948,-119.997],[38.940,-119.969]],
    8416: [[38.940,-119.969],[38.842,-120.106],[38.732,-120.288],[38.640,-120.530],[38.527,-120.812],[38.395,-121.118],[38.262,-121.366],[38.124,-121.633],[38.045,-121.880],[37.992,-121.998],[37.940,-122.041],[37.905,-122.085],[37.886,-122.118]],
    8415: [[37.886,-122.118],[37.892,-122.108],[37.901,-122.094],[37.910,-122.080],[37.910,-122.067]],
    8414: [[37.886,-122.118],[37.888,-122.131],[37.889,-122.156],[37.888,-122.184],[37.881,-122.213],[37.874,-122.245],[37.862,-122.273],[37.844,-122.292],[37.826,-122.315],[37.807,-122.366],[37.800,-122.395],[37.795,-122.401]],
    8413: [[37.886,-122.118],[37.905,-122.150],[37.928,-122.193],[37.951,-122.232],[37.973,-122.052]],
    8412: [[37.795,-122.401],[37.800,-122.395],[37.807,-122.366],[37.826,-122.315],[37.844,-122.292],[37.862,-122.273],[37.874,-122.245],[37.881,-122.213],[37.888,-122.184],[37.889,-122.156],[37.888,-122.131],[37.886,-122.118]],
  },
};
