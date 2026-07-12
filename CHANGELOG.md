# CHANGELOG


## v0.32.3 (2026-07-12)

### 🔺 Fix

- 20 deep-dive audit items + background telemetry backfill for live charts
  ([#339](https://github.com/jtn0123/VoltTracker/pull/339),
  [`799c934`](https://github.com/jtn0123/VoltTracker/commit/799c9341c73949161ab7434ce2c890bb884ee21b))


## v0.32.2 (2026-07-11)

### 🔺 Fix

- **privacy**: Disclose map-tile CDN egress in-app and in docs (E1)
  ([#337](https://github.com/jtn0123/VoltTracker/pull/337),
  [`241d2ab`](https://github.com/jtn0123/VoltTracker/commit/241d2abadbdf93cc8cae282676b28865e1987d1c))

- **security**: Tighten dashboard CSP style-src to 'self' (E2)
  ([#338](https://github.com/jtn0123/VoltTracker/pull/338),
  [`d1ea7d7`](https://github.com/jtn0123/VoltTracker/commit/d1ea7d7481e6c8b2f2f6d2a88dac141adf56b80c))

- **ui**: Surface a retry toast when a lazy dashboard chunk fails to load
  ([#336](https://github.com/jtn0123/VoltTracker/pull/336),
  [`98a0ede`](https://github.com/jtn0123/VoltTracker/commit/98a0edeb1650ed10287c02df1bc3cf31e9bb0e2d))


## v0.32.1 (2026-07-11)

### 🔺 Fix

- **android**: Extended mid-drive reconnect tier + consolidated service state (B3, B5)
  ([#315](https://github.com/jtn0123/VoltTracker/pull/315),
  [`77d1e56`](https://github.com/jtn0123/VoltTracker/commit/77d1e5678d7403d0439ad4d90d2c15f3a7e923db))

- **android**: Portable vehicle identity across reinstall/restore (B8 + ADR-0009)
  ([#319](https://github.com/jtn0123/VoltTracker/pull/319),
  [`2b68041`](https://github.com/jtn0123/VoltTracker/commit/2b680410cb6704b204a167807d0f809eeae43c20))

- **android**: Survive WebView renderer death, add dashboard-load watchdog and crash capture (B1,
  B2, B4) ([#317](https://github.com/jtn0123/VoltTracker/pull/317),
  [`72efb53`](https://github.com/jtn0123/VoltTracker/commit/72efb5330a9583363f0d8bee744095c938e63bf4))

- **android**: Transaction-safe vehicle store, release log stripping, passphrase whitespace handling
  (B6, B7, E3) ([#313](https://github.com/jtn0123/VoltTracker/pull/313),
  [`fd9a3a2`](https://github.com/jtn0123/VoltTracker/commit/fd9a3a294aa1a7217af2644aad14a24968e4de27))

- **dashboard**: Don't arm route-hydration retry after test env teardown
  ([#333](https://github.com/jtn0123/VoltTracker/pull/333),
  [`82197e1`](https://github.com/jtn0123/VoltTracker/commit/82197e1e41c6304c693250bb4ca34a97dbf217b0))

- **ui**: Settings licenses section, chart a11y labels, color-token sweep (C3, C4, C5)
  ([#320](https://github.com/jtn0123/VoltTracker/pull/320),
  [`f427c12`](https://github.com/jtn0123/VoltTracker/commit/f427c127ec632bf5c40d652e0bc176e3a43a588b))

- **ui**: Themed rename dialog, single speed unit, branded splash, input bounds, touch targets (C1,
  C2, C6, C8, C9) ([#314](https://github.com/jtn0123/VoltTracker/pull/314),
  [`08c2b1c`](https://github.com/jtn0123/VoltTracker/commit/08c2b1c1de3ba5919f589881ea57b4cbea8c663a))

### 🔷 Changed

- **deps**: Bump actions/setup-java from 5.4.0 to 5.5.0
  ([#329](https://github.com/jtn0123/VoltTracker/pull/329),
  [`430a495`](https://github.com/jtn0123/VoltTracker/commit/430a495f990a68782b9b28a2cba9fd6618cc8177))

- **deps**: Bump reactivecircus/android-emulator-runner
  ([#325](https://github.com/jtn0123/VoltTracker/pull/325),
  [`bf465c7`](https://github.com/jtn0123/VoltTracker/commit/bf465c7e412a1c41740a1b0f71b96cbdac16f3d0))

- **deps-dev**: Bump @vitest/coverage-istanbul
  ([#332](https://github.com/jtn0123/VoltTracker/pull/332),
  [`428c486`](https://github.com/jtn0123/VoltTracker/commit/428c486a935cf5ba091c567f5bb8ab3d87df7165))

- **deps-dev**: Bump eslint in /mobile/android/dashboard-tests
  ([#324](https://github.com/jtn0123/VoltTracker/pull/324),
  [`a144b54`](https://github.com/jtn0123/VoltTracker/commit/a144b548b1b1dd26c0e14431aa85dfc627943c5b))

- **deps-dev**: Bump typescript-eslint ([#326](https://github.com/jtn0123/VoltTracker/pull/326),
  [`358eb2b`](https://github.com/jtn0123/VoltTracker/commit/358eb2b7295128a890be0106e090d40c7bf7c19b))

- **deps-dev**: Bump vitest in /mobile/android/dashboard-tests
  ([#328](https://github.com/jtn0123/VoltTracker/pull/328),
  [`5b31a61`](https://github.com/jtn0123/VoltTracker/commit/5b31a6122c146319f044dbf7b87a11a6a9871337))

### 🔷 Changed

- Bump github/codeql-action init+analyze together to v4.37.0
  ([#335](https://github.com/jtn0123/VoltTracker/pull/335),
  [`b3f6c6c`](https://github.com/jtn0123/VoltTracker/commit/b3f6c6c704e40b22173d1fe19d322ef5b0af33d3))

- Tighten supply-chain gates, dedupe dashboard builds, surface e2e flake (F1-F3, G3, I1-I4, D2, D4)
  ([#312](https://github.com/jtn0123/VoltTracker/pull/312),
  [`049fec8`](https://github.com/jtn0123/VoltTracker/commit/049fec8d016d186bc833490fbd6ba1fdb3d27121))

### 🔷 Changed

- Add MIT LICENSE and fix doc/reality drift (H1-H5)
  ([#310](https://github.com/jtn0123/VoltTracker/pull/310),
  [`23a51f2`](https://github.com/jtn0123/VoltTracker/commit/23a51f2fa95a3c845ed2c48644738cb1a9027e52))

- **android**: Archive landed one-shot reports and refresh reports index
  ([#309](https://github.com/jtn0123/VoltTracker/pull/309),
  [`f704b8b`](https://github.com/jtn0123/VoltTracker/commit/f704b8bee7e32b7efe57923bc0222089363a84fb))

### 🔷 Changed

- Obd latency baseline machinery + startup bundle headroom (G1, G2)
  ([#322](https://github.com/jtn0123/VoltTracker/pull/322),
  [`4e40c2d`](https://github.com/jtn0123/VoltTracker/commit/4e40c2dd3a18416982ca2d55fc24d63333631d1f))

### 🔷 Changed

- **android**: Split VoltBridgeDataExports into per-feature bridge units (A3)
  ([#318](https://github.com/jtn0123/VoltTracker/pull/318),
  [`b7b7185`](https://github.com/jtn0123/VoltTracker/commit/b7b718504976739fdf9ca7b8e9ee2e930f98f6b3))

- **dashboard**: Migrate cross-module calls from window globals to typed ESM imports (C7)
  ([#323](https://github.com/jtn0123/VoltTracker/pull/323),
  [`c6fb1e2`](https://github.com/jtn0123/VoltTracker/commit/c6fb1e24a9f706e05d53142a04efa0d9434c951c))

- **data**: Extract vehicle-identity merge out of DatabaseMerger to restore the LargeClass ratchet
  ([#334](https://github.com/jtn0123/VoltTracker/pull/334),
  [`624cfa9`](https://github.com/jtn0123/VoltTracker/commit/624cfa9e746cda3d8df4c2fae1ad02c438432ecc))

- **data**: Narrow the ObdLocalStore facade behind capability interfaces (A2)
  ([#321](https://github.com/jtn0123/VoltTracker/pull/321),
  [`ec82232`](https://github.com/jtn0123/VoltTracker/commit/ec822326b1070372209f4ce22c35f5b0b73369b6))

- **obd**: Move Mode-22 decoders into the parser registry, lower complexity ratchets (A1)
  ([#316](https://github.com/jtn0123/VoltTracker/pull/316),
  [`7e13e96`](https://github.com/jtn0123/VoltTracker/commit/7e13e9654f875df2bdfc448299457ca47327236d))

### 🔷 Changed

- Branch-coverage ratchets, deterministic tab-switch gate, instrumented handshake smoke (D1, D3, D4)
  ([#311](https://github.com/jtn0123/VoltTracker/pull/311),
  [`27f16f0`](https://github.com/jtn0123/VoltTracker/commit/27f16f08ebd5eb7889726861732f071a09a53073))


## v0.32.0 (2026-07-10)

### 🔷 Changed

- **tooling**: Upgrade dashboard to Node 24 LTS
  ([#307](https://github.com/jtn0123/VoltTracker/pull/307),
  [`90eed74`](https://github.com/jtn0123/VoltTracker/commit/90eed74723c1f6b5eb9687461c4f2bf8b24a5bf2))

### ✳️ New

- **android**: Polish daily workflows and harden data reliability
  ([#308](https://github.com/jtn0123/VoltTracker/pull/308),
  [`107ef56`](https://github.com/jtn0123/VoltTracker/commit/107ef5652a9c43acb51174f037f329ac7e9f70ea))


## v0.31.2 (2026-07-10)

### 🔺 Fix

- **android**: Harden lifecycle and bridge reliability
  ([#306](https://github.com/jtn0123/VoltTracker/pull/306),
  [`71b5b78`](https://github.com/jtn0123/VoltTracker/commit/71b5b7813706d5616f952f43374563ff06845d45))


## v0.31.1 (2026-07-09)

### 🔺 Fix

- **dashboard**: Resolve flicker, loading, and flash-in/out bugs in the WebView UI
  ([#305](https://github.com/jtn0123/VoltTracker/pull/305),
  [`291dc3d`](https://github.com/jtn0123/VoltTracker/commit/291dc3d4cac4611eceb730074a1335de0ce58533))

### 🔷 Changed

- Cache the AVD snapshot and parallelize static analysis
  ([#304](https://github.com/jtn0123/VoltTracker/pull/304),
  [`fd5376d`](https://github.com/jtn0123/VoltTracker/commit/fd5376d3d01c305c4062b092864e27205b0f0e76))


## v0.31.0 (2026-07-09)

### ✳️ New

- **dashboard**: Align the whole dashboard with the v2 design handoff
  ([#303](https://github.com/jtn0123/VoltTracker/pull/303),
  [`b66a3af`](https://github.com/jtn0123/VoltTracker/commit/b66a3af81dca599fe166064f0098893e8417bbcd))


## v0.30.2 (2026-07-08)

### 🔺 Fix

- **app**: Batches B–D UI/UX bug-hunt fixes (medium + polish)
  ([#302](https://github.com/jtn0123/VoltTracker/pull/302),
  [`95d58c5`](https://github.com/jtn0123/VoltTracker/commit/95d58c57f5de49ba467f35e1b8e259b393588d97))

### 🔷 Changed

- **deps**: Bump github/codeql-action to v4.36.3
  ([#301](https://github.com/jtn0123/VoltTracker/pull/301),
  [`7d39ab9`](https://github.com/jtn0123/VoltTracker/commit/7d39ab9f4adfd38c9f5f50aef4b9f79a940ffd80))


## v0.30.1 (2026-07-08)

### 🔺 Fix

- **app**: Batch A UI/UX bug-hunt fixes (high-severity + guards)
  ([#300](https://github.com/jtn0123/VoltTracker/pull/300),
  [`79b56a7`](https://github.com/jtn0123/VoltTracker/commit/79b56a7ed1c7fbe3add6077482a69b680fe701aa))

### 🔷 Changed

- **deps**: Bump androidx.test.uiautomator:uiautomator
  ([#293](https://github.com/jtn0123/VoltTracker/pull/293),
  [`ff14786`](https://github.com/jtn0123/VoltTracker/commit/ff14786a012810fdf5d9f2b14f879043a6929f6c))

- **deps**: Bump com.diffplug.spotless in /mobile/android
  ([#294](https://github.com/jtn0123/VoltTracker/pull/294),
  [`2e4bf24`](https://github.com/jtn0123/VoltTracker/commit/2e4bf24a7ddd42bddcc8919558d446de42027b52))

- **deps**: Bump dorny/paths-filter from 4.0.1 to 4.0.2
  ([#289](https://github.com/jtn0123/VoltTracker/pull/289),
  [`d852269`](https://github.com/jtn0123/VoltTracker/commit/d852269ed5ecab081d0952fc830ce982b9525767))

- **deps-dev**: Bump typescript-eslint ([#290](https://github.com/jtn0123/VoltTracker/pull/290),
  [`2728c91`](https://github.com/jtn0123/VoltTracker/commit/2728c9182d1ba2468f8ba9815d9957e52091a7b9))


## v0.30.0 (2026-07-08)

### ✳️ New

- **dashboard**: Tab-by-tab fidelity pass for the VoltTracker v2 design
  ([#299](https://github.com/jtn0123/VoltTracker/pull/299),
  [`a86dcb3`](https://github.com/jtn0123/VoltTracker/commit/a86dcb3db88a7f7b1d3faffc67b9b3b71619c071))


## v0.29.0 (2026-07-08)

### ✳️ New

- **dashboard**: Implement VoltTracker v2 design handoff
  ([#298](https://github.com/jtn0123/VoltTracker/pull/298),
  [`5a1f5f2`](https://github.com/jtn0123/VoltTracker/commit/5a1f5f29c2ded0a17b34c97af00a484607770227))

### 🔷 Changed

- Expand Android simulation coverage ([#297](https://github.com/jtn0123/VoltTracker/pull/297),
  [`24cd13f`](https://github.com/jtn0123/VoltTracker/commit/24cd13f506016855ba810f725202f7e0a9d3a527))


## v0.28.1 (2026-07-06)

### 🔺 Fix

- Resolve reported diagnostics/map UI issues and 100+ audited bugs
  ([#296](https://github.com/jtn0123/VoltTracker/pull/296),
  [`80e41d4`](https://github.com/jtn0123/VoltTracker/commit/80e41d46c4d9487f25276ad54e73ece7ad29c358))


## v0.28.0 (2026-07-06)

### ✳️ New

- **dashboard**: Polish dashboard UI to match Polished design handoff
  ([#295](https://github.com/jtn0123/VoltTracker/pull/295),
  [`5ab56c4`](https://github.com/jtn0123/VoltTracker/commit/5ab56c4c6ee4398178131782f69157ccd34f99e1))


## v0.27.0 (2026-07-04)

### ✳️ New

- Dashboard UX polish pass + code-audit findings docs
  ([#287](https://github.com/jtn0123/VoltTracker/pull/287),
  [`41905be`](https://github.com/jtn0123/VoltTracker/commit/41905beefe909a94954f73c324927258527af7bf))


## v0.26.0 (2026-07-03)

### ✳️ New

- Compact dashboard header and polish Demo / Testing mode
  ([#286](https://github.com/jtn0123/VoltTracker/pull/286),
  [`6b359ce`](https://github.com/jtn0123/VoltTracker/commit/6b359ce69b4ca012781759012ccb4b05f6e9e5c3))


## v0.25.3 (2026-07-03)

### 🔺 Fix

- Polish dashboard copy, formatting, and theme consistency
  ([#285](https://github.com/jtn0123/VoltTracker/pull/285),
  [`08e62cd`](https://github.com/jtn0123/VoltTracker/commit/08e62cda6f060f3f79813b5e5702d027d9dbe934))


## v0.25.2 (2026-07-03)

### 🔺 Fix

- Polish dashboard demo-mode UI copy and signed-value glyphs
  ([#284](https://github.com/jtn0123/VoltTracker/pull/284),
  [`c4f4194`](https://github.com/jtn0123/VoltTracker/commit/c4f41946c29475fc3b5a93ee0a5d110b2b2430c5))


## v0.25.1 (2026-07-02)

### 🔺 Fix

- **dashboard**: Ui/ux polish pass — a11y states, tone tokens, touch targets
  ([#283](https://github.com/jtn0123/VoltTracker/pull/283),
  [`1f08480`](https://github.com/jtn0123/VoltTracker/commit/1f0848079821548c5eb83d19d110f21d06a356fc))


## v0.25.0 (2026-07-02)

### ✳️ New

- Battery cell map, driving trends, EV share, trip detail depth, share card, temperature insight
  ([#282](https://github.com/jtn0123/VoltTracker/pull/282),
  [`58d4513`](https://github.com/jtn0123/VoltTracker/commit/58d451392cde0583034e06ff6876c6a0a80a9945))


## v0.24.0 (2026-07-02)

### 🔷 Changed

- **deps**: Bump actions/cache from 5.0.5 to 6.1.0
  ([#273](https://github.com/jtn0123/VoltTracker/pull/273),
  [`0a3fc3f`](https://github.com/jtn0123/VoltTracker/commit/0a3fc3f844b5e8470add4bc6ec7bc249be514bfc))

- **deps**: Bump actions/checkout from 6.0.3 to 7.0.0
  ([#275](https://github.com/jtn0123/VoltTracker/pull/275),
  [`e419727`](https://github.com/jtn0123/VoltTracker/commit/e4197275ff7e6470fcca743d453d313195c913da))

- **deps**: Bump actions/setup-java from 5.2.0 to 5.4.0
  ([#271](https://github.com/jtn0123/VoltTracker/pull/271),
  [`46a8299`](https://github.com/jtn0123/VoltTracker/commit/46a8299dbe4671a184a27d2bd53933cea3c8e4c7))

- **deps**: Bump actions/setup-python from 6.2.0 to 6.3.0
  ([#276](https://github.com/jtn0123/VoltTracker/pull/276),
  [`2c94fab`](https://github.com/jtn0123/VoltTracker/commit/2c94fabd27ebee949d9d2748d7673e5e503946b2))

- **deps**: Bump gradle-wrapper from 9.6.0 to 9.6.1 in /mobile/android
  ([#278](https://github.com/jtn0123/VoltTracker/pull/278),
  [`3573724`](https://github.com/jtn0123/VoltTracker/commit/3573724b648c1cf0428f3d54f659ea948ae60536))

- **deps**: Bump gradle/actions/wrapper-validation from 4.4.4 to 6.2.0
  ([#272](https://github.com/jtn0123/VoltTracker/pull/272),
  [`48f38fd`](https://github.com/jtn0123/VoltTracker/commit/48f38fdd92ae7d51cd6ce0f8fc13e811b5d78113))

- **deps-dev**: Bump eslint from 10.5.0 to 10.6.0 in /mobile/android/dashboard-tests
  ([#274](https://github.com/jtn0123/VoltTracker/pull/274),
  [`c88ba7e`](https://github.com/jtn0123/VoltTracker/commit/c88ba7e26a2fcc9caf713cd60ae5f3cb61f054be))

- **deps-dev**: Bump typescript-eslint from 8.61.0 to 8.62.0 in /mobile/android/dashboard-tests
  ([#277](https://github.com/jtn0123/VoltTracker/pull/277),
  [`28a41e7`](https://github.com/jtn0123/VoltTracker/commit/28a41e708887bf09ea68f97d2a5fce06bed3a211))

### ✳️ New

- **diagnostics**: Add a quick car-code scan profile
  ([#270](https://github.com/jtn0123/VoltTracker/pull/270),
  [`6324569`](https://github.com/jtn0123/VoltTracker/commit/6324569f599d36acbe8da13b304feaa62e65566c))
  - _a stored-code check no longer requires sitting through the full multi-module sweep._


## v0.23.3 (2026-07-02)

### 🔺 Fix

- **build**: Exclude generated dashboard JS from privacyScan inputs for Gradle 9.6
  ([#269](https://github.com/jtn0123/VoltTracker/pull/269),
  [`dc86c9a`](https://github.com/jtn0123/VoltTracker/commit/dc86c9a8e92639ce3b4e7d4f0b9c833f51aad68c))


## v0.23.2 (2026-06-29)

### 🔺 Fix

- **android**: Harden dashboard bridge actions
  ([`75232f0`](https://github.com/jtn0123/VoltTracker/commit/75232f0ad8af2dfc4498e678946bb81a7b6e11f7))


## v0.23.1 (2026-06-29)

### 🔺 Fix

- **android**: Harden bridge failure paths
  ([`afbe430`](https://github.com/jtn0123/VoltTracker/commit/afbe430ac94116453b98f614a70e95f95b26be56))


## v0.23.0 (2026-06-29)

### 🔺 Fix

- **android**: Harden exports and validation gates
  ([`25fbad3`](https://github.com/jtn0123/VoltTracker/commit/25fbad36beb3c5a7f73291ca7877b28a5a36ce21))

- **dashboard**: Complete map-layer tablist semantics and document DTC lazy chunks
  ([#261](https://github.com/jtn0123/VoltTracker/pull/261),
  [`8dfe479`](https://github.com/jtn0123/VoltTracker/commit/8dfe4799fa717c4035e2bb29cd9910db52f9a202))

- **release**: Stop the changelog template dir from overwriting the root README
  ([#268](https://github.com/jtn0123/VoltTracker/pull/268),
  [`538eedd`](https://github.com/jtn0123/VoltTracker/commit/538eeddc9d4db9fc94caa1b4d1761883db290d97))
  - _the project README stops being clobbered on every release._

### 🔷 Changed

- **deps**: Bump actions/checkout from 6.0.3 to 7.0.0
  ([#248](https://github.com/jtn0123/VoltTracker/pull/248),
  [`9adf9fd`](https://github.com/jtn0123/VoltTracker/commit/9adf9fd56c8e701b5df46b672c16d21623437f79))

- **deps**: Bump actions/setup-java from 5.2.0 to 5.3.0
  ([#249](https://github.com/jtn0123/VoltTracker/pull/249),
  [`9b41da6`](https://github.com/jtn0123/VoltTracker/commit/9b41da6db88c53c3447f81846e66b9c89dd63c5d))

- **deps**: Bump com.diffplug.spotless in /mobile/android
  ([#256](https://github.com/jtn0123/VoltTracker/pull/256),
  [`8be917b`](https://github.com/jtn0123/VoltTracker/commit/8be917bddbf3ef5b5b8f389dbc4d1bfc80f52828))

- **deps**: Bump softprops/action-gh-release from 3.0.0 to 3.0.1
  ([#251](https://github.com/jtn0123/VoltTracker/pull/251),
  [`775d71b`](https://github.com/jtn0123/VoltTracker/commit/775d71bda8b979e4e9c13ddeb90f287e19efb71a))

- **deps-dev**: Bump @playwright/test in /mobile/android/dashboard-e2e
  ([#253](https://github.com/jtn0123/VoltTracker/pull/253),
  [`d0a1dd8`](https://github.com/jtn0123/VoltTracker/commit/d0a1dd8748aa6e5a90a4417b847a42ec6a6555dc))

- **deps-dev**: Bump @vitest/coverage-istanbul
  ([#254](https://github.com/jtn0123/VoltTracker/pull/254),
  [`413cf1c`](https://github.com/jtn0123/VoltTracker/commit/413cf1ce27c415144d5c215003ff6209100d9f8e))

- **deps-dev**: Bump vitest in /mobile/android/dashboard-tests
  ([#252](https://github.com/jtn0123/VoltTracker/pull/252),
  [`464fa3f`](https://github.com/jtn0123/VoltTracker/commit/464fa3f47fe0949d6105d598031f5af8eaa8436d))

### 🔷 Changed

- **tooling**: Add a privacy scanner and local performance-benchmark tooling
  ([#246](https://github.com/jtn0123/VoltTracker/pull/246),
  [`aa40fa5`](https://github.com/jtn0123/VoltTracker/commit/aa40fa5cf8ea134a81f8caf4984e8237cfb34a3f))
  - _tracked files are now scanned for leaked vehicle/location/device data on every PR, and there's a documented way to benchmark startup on a real device._

### ✳️ New

- **android**: Log how far a session got on terminal OBD failures
  ([#266](https://github.com/jtn0123/VoltTracker/pull/266),
  [`f7d24b9`](https://github.com/jtn0123/VoltTracker/commit/f7d24b9d91e44cddefe170bfd6248861bf6406d1))

- **dashboard**: Nav-safe spacing, 44px touch targets, tablet rail layout, light polish
  ([#259](https://github.com/jtn0123/VoltTracker/pull/259),
  [`1ce618b`](https://github.com/jtn0123/VoltTracker/commit/1ce618bee7cc801e6515391ded33aa4576981026))

### 🔷 Changed

- **android**: Annotate test-only seams with @VisibleForTesting
  ([#262](https://github.com/jtn0123/VoltTracker/pull/262),
  [`8a970ca`](https://github.com/jtn0123/VoltTracker/commit/8a970cae336047b55f62a71d629ee5650acab23d))

- **android**: Extract charge-summary engine from ObdStoreReports
  ([#263](https://github.com/jtn0123/VoltTracker/pull/263),
  [`cc249d1`](https://github.com/jtn0123/VoltTracker/commit/cc249d1b1d9169d3216c06ed015ef7086c2c56db))

- **android**: Extract Volt Mode-22 decoder from ObdProtocol
  ([#264](https://github.com/jtn0123/VoltTracker/pull/264),
  [`9d6b5b2`](https://github.com/jtn0123/VoltTracker/commit/9d6b5b2151a8b1e86aa6b86b7129096eef3d2781))

- **android**: Split the Volt PID catalog data out of EnhancedPidProfiles
  ([#265](https://github.com/jtn0123/VoltTracker/pull/265),
  [`5a3869d`](https://github.com/jtn0123/VoltTracker/commit/5a3869d6b6ed0f1aab79d09592069c4571a15fc8))


## v0.22.2 (2026-06-26)

### 🔺 Fix

- Bug-hunt batch — correctness/robustness fixes across app + dashboard
  ([#267](https://github.com/jtn0123/VoltTracker/pull/267),
  [`c1e03d6`](https://github.com/jtn0123/VoltTracker/commit/c1e03d602e03dd871eaed81b8e8b5ceadffe5a58))


## v0.22.1 (2026-06-22)

### 🔺 Fix

- **dashboard**: Announce status-toast failures assertively for screen readers
  ([#260](https://github.com/jtn0123/VoltTracker/pull/260),
  [`d492b58`](https://github.com/jtn0123/VoltTracker/commit/d492b58f8686ae2711c62baef4c9f346925d6d55))

### 🔷 Changed

- **release**: Render the changelog as compact emoji sections with impact notes
  ([#245](https://github.com/jtn0123/VoltTracker/pull/245),
  [`34a4da7`](https://github.com/jtn0123/VoltTracker/commit/34a4da76c11d00d4ee3e6f60e37820e4e2608d03))
  - _release notes now read as a scannable what-changed-and-why list instead of a wall of commit bodies._


## v0.22.0 (2026-06-19)

### ✳️ New

- **dashboard**: Add a hide-outliers toggle to the efficiency chart
  ([#244](https://github.com/jtn0123/VoltTracker/pull/244),
  [`c0b5eae`](https://github.com/jtn0123/VoltTracker/commit/c0b5eaef129a3c6c8ba55ed7d64d6e8b7761dc8d))

- **dashboard**: Add switchable efficiency chart views with grade-normalization
  ([#243](https://github.com/jtn0123/VoltTracker/pull/243),
  [`67270fd`](https://github.com/jtn0123/VoltTracker/commit/67270fd15e4c86662b6cfc7a2c0bcaf4f67d58d9))


## v0.21.0 (2026-06-18)

### ✳️ New

- **dashboard**: Replace soc donut with a battery gauge and de-clutter the efficiency scatter
  ([#242](https://github.com/jtn0123/VoltTracker/pull/242),
  [`eacd3d2`](https://github.com/jtn0123/VoltTracker/commit/eacd3d250a5bcaa7047211382c2dc6affb647b27))


## v0.20.0 (2026-06-18)

### ✳️ New

- **dashboard**: Polish battery charts + raise backup import limit to 4 GiB
  ([#241](https://github.com/jtn0123/VoltTracker/pull/241),
  [`58ab6d8`](https://github.com/jtn0123/VoltTracker/commit/58ab6d8a09f2b2851683124db60421202fd77a70))


## v0.19.1 (2026-06-18)

### 🔷 Changed

- **dashboard**: Lazy-load Map-tab CSS off the startup path
  ([#240](https://github.com/jtn0123/VoltTracker/pull/240),
  [`36b3cef`](https://github.com/jtn0123/VoltTracker/commit/36b3ceff1c22e56f38f1daaffc2cbd77eda53880))


## v0.19.0 (2026-06-18)

### ✳️ New

- **dashboard**: Recovery-first diagnostics, drive data provenance, map polish
  ([#238](https://github.com/jtn0123/VoltTracker/pull/238),
  [`04a8d92`](https://github.com/jtn0123/VoltTracker/commit/04a8d928e2bb423da6ff3e62692ee735b464ed51))

### 🔷 Changed

- **dashboard**: Phone a11y/visual/contract/perf guards for new surfaces
  ([#239](https://github.com/jtn0123/VoltTracker/pull/239),
  [`c0b7e37`](https://github.com/jtn0123/VoltTracker/commit/c0b7e37b91f7515e9f594ff49c1954b1d0843b6d))


## v0.18.9 (2026-06-18)

### 🔷 Changed

- Reduce duplicate Android slow lanes ([#235](https://github.com/jtn0123/VoltTracker/pull/235),
  [`82b388f`](https://github.com/jtn0123/VoltTracker/commit/82b388f8ea089e4e22c0ad71ee0cb9355a6d283f))

### 🔷 Changed

- Track startup and tab responsiveness ([#236](https://github.com/jtn0123/VoltTracker/pull/236),
  [`60d3c14`](https://github.com/jtn0123/VoltTracker/commit/60d3c1431dd3076d9a3d4a6718c59d95f11903e2))


## v0.18.8 (2026-06-17)

### 🔷 Changed

- Add startup benchmark and lazy dashboard panels
  ([#234](https://github.com/jtn0123/VoltTracker/pull/234),
  [`ff9af7b`](https://github.com/jtn0123/VoltTracker/commit/ff9af7b516d7e4573d54570507fb0d66e3b227db))


## v0.18.7 (2026-06-17)

### 🔷 Changed

- Harden dashboard and storage performance
  ([`1d10ccd`](https://github.com/jtn0123/VoltTracker/commit/1d10ccdea3865249d0337541ca4f66282bd10e95))


## v0.18.6 (2026-06-17)

### 🔷 Changed

- **obd,storage**: Finish remaining items — drop dead torque PID, faster prompt-recovery,
  single-scan counts (L3, L5, L10)
  ([`e7d8218`](https://github.com/jtn0123/VoltTracker/commit/e7d8218fb731867d2f3f2ad5ebeee29489874584))


## v0.18.5 (2026-06-17)

### 🔷 Changed

- More boot/dashboard fast-path wins (DataBackup off main thread, lazy troubleshooter.css)
  ([#231](https://github.com/jtn0123/VoltTracker/pull/231),
  [`37aa37d`](https://github.com/jtn0123/VoltTracker/commit/37aa37d2f4c42686018da7f90b0128decfe2ee5f))


## v0.18.4 (2026-06-17)

### 🔺 Fix

- **repo**: Remove case-duplicate .github/pull_request_template.md
  ([#230](https://github.com/jtn0123/VoltTracker/pull/230),
  [`adeab0f`](https://github.com/jtn0123/VoltTracker/commit/adeab0fd5ec09ad033ebad28236fe37f09560de9))


## v0.18.3 (2026-06-17)

### 🔷 Changed

- Faster connect + boot + steady-state (L6–L9b) with benchmarks
  ([#229](https://github.com/jtn0123/VoltTracker/pull/229),
  [`b1cb814`](https://github.com/jtn0123/VoltTracker/commit/b1cb814389aa5700ae9b4976e3001948a7190672))


## v0.18.2 (2026-06-17)

### 🔷 Changed

- **obd**: Pin CAN protocol before 0100 to skip the ~4.8s connect search
  ([#228](https://github.com/jtn0123/VoltTracker/pull/228),
  [`6057694`](https://github.com/jtn0123/VoltTracker/commit/6057694c7496a804011d195850ac780bbd32ed25))


## v0.18.1 (2026-06-17)

### 🔺 Fix

- **android**: End asleep-car sessions cleanly and retire dead PIDs
  ([#227](https://github.com/jtn0123/VoltTracker/pull/227),
  [`7b42e41`](https://github.com/jtn0123/VoltTracker/commit/7b42e4185b4c7e6c595d8621594d4a956920442a))

- **dashboard**: Style maintenance-form inline validation messages
  ([#226](https://github.com/jtn0123/VoltTracker/pull/226),
  [`d3c538e`](https://github.com/jtn0123/VoltTracker/commit/d3c538e90fd97dd9d0e21abe7fa602b576503973))


## v0.18.0 (2026-06-17)

### 🔷 Changed

- **dashboard**: Sort VOLT_DTC entries into ascending order within their blocks
  ([#225](https://github.com/jtn0123/VoltTracker/pull/225),
  [`b0854c8`](https://github.com/jtn0123/VoltTracker/commit/b0854c816311fa872ec5915d23fe073bac9d8c5b))

### ✳️ New

- **android**: Charge CSV export, maintenance alerts, per-trip cost, charge-scan cache
  ([#224](https://github.com/jtn0123/VoltTracker/pull/224),
  [`d6c3466`](https://github.com/jtn0123/VoltTracker/commit/d6c3466df4573ac57e0038412e1945b706c20ae5))


## v0.17.3 (2026-06-17)

### 🔷 Changed

- **android**: Serve OBD-connect VIN read from one query, not the full storage summary
  ([#223](https://github.com/jtn0123/VoltTracker/pull/223),
  [`ebf4e09`](https://github.com/jtn0123/VoltTracker/commit/ebf4e0975c1814d0cfe99be9a9d7f6bfb7708bd6))


## v0.17.2 (2026-06-17)

### 🔺 Fix

- **ui**: Dashboard UX + a11y polish and failure-branch test coverage
  ([#222](https://github.com/jtn0123/VoltTracker/pull/222),
  [`6d9de08`](https://github.com/jtn0123/VoltTracker/commit/6d9de0862bc6d7b71459da50ba8d58ab664b7233))


## v0.17.1 (2026-06-17)

### 🔺 Fix

- **android**: Odometer range guard, charge-energy carry-forward, dead-interface cleanup
  ([#221](https://github.com/jtn0123/VoltTracker/pull/221),
  [`50611a1`](https://github.com/jtn0123/VoltTracker/commit/50611a14dd7ec5ebfe6bbb98d013a9d62582c148))

### 🔷 Changed

- **tooling**: Fix stale docs, align CI node 22, harden gradle wrapper
  ([#220](https://github.com/jtn0123/VoltTracker/pull/220),
  [`23d63b7`](https://github.com/jtn0123/VoltTracker/commit/23d63b7e596cccff9cb50d189c8a0cb0c437c661))

### 🔷 Changed

- **adr**: Record encrypted-backup format and key derivation
  ([#219](https://github.com/jtn0123/VoltTracker/pull/219),
  [`4c26e8f`](https://github.com/jtn0123/VoltTracker/commit/4c26e8f7383e7e7d9c231f29f23815c6159ea64a))


## v0.17.0 (2026-06-16)

### ✳️ New

- **ux,a11y,docs**: Execute the B-grade Frontend + Docs findings
  ([#218](https://github.com/jtn0123/VoltTracker/pull/218),
  [`c1e84d2`](https://github.com/jtn0123/VoltTracker/commit/c1e84d21e1e211df55e5a445ce5f04adfdf8a414))


## v0.16.3 (2026-06-16)

### 🔺 Fix

- **ui**: End-user accessibility and clarity polish
  ([#217](https://github.com/jtn0123/VoltTracker/pull/217),
  [`d09df82`](https://github.com/jtn0123/VoltTracker/commit/d09df8218541722effee9f92fa50708f9073a824))

### 🔷 Changed

- De-complicate hot paths + fix edge-case bugs (26 files)
  ([#216](https://github.com/jtn0123/VoltTracker/pull/216),
  [`811d3a5`](https://github.com/jtn0123/VoltTracker/commit/811d3a5b643b8f48cc830b8d008ad13cfa177948))


## v0.16.2 (2026-06-16)

### 🔺 Fix

- **obd**: Parser correctness fixes in ObdProtocol/ObdElmDecode
  ([#215](https://github.com/jtn0123/VoltTracker/pull/215),
  [`ef15810`](https://github.com/jtn0123/VoltTracker/commit/ef15810264d3a7d4c6adeb855f47b6f3b07b7bea))


## v0.16.1 (2026-06-16)

### 🔺 Fix

- Deeper edge-case bug-fix pass (18 files) ([#214](https://github.com/jtn0123/VoltTracker/pull/214),
  [`e272e14`](https://github.com/jtn0123/VoltTracker/commit/e272e14b803b84e70ac6ce5959093fa215d6d359))

### 🔷 Changed

- Simplify, polish, and debug ~90 small areas across the app
  ([#213](https://github.com/jtn0123/VoltTracker/pull/213),
  [`6cb5c0f`](https://github.com/jtn0123/VoltTracker/commit/6cb5c0f95dae96f1e23ea773b6b36483b7d8c8b3))


## v0.16.0 (2026-06-15)

### ✳️ New

- Grade follow-ups — bug fixes, features, tests, docs, devex
  ([#212](https://github.com/jtn0123/VoltTracker/pull/212),
  [`e280356`](https://github.com/jtn0123/VoltTracker/commit/e2803566fd6c4eccc423748c3206102331b4fe43))


## v0.15.0 (2026-06-15)

### ✳️ New

- Grade-audit fixes, tests, and 10 end-user features
  ([#211](https://github.com/jtn0123/VoltTracker/pull/211),
  [`cdac653`](https://github.com/jtn0123/VoltTracker/commit/cdac65320f7c2e253692b31ea61eb2897db25b20))


## v0.14.0 (2026-06-15)

### ✳️ New

- **diagnostics**: Add self-selecting, budget-bounded diagnostics digest
  ([#210](https://github.com/jtn0123/VoltTracker/pull/210),
  [`a5dc070`](https://github.com/jtn0123/VoltTracker/commit/a5dc07017e0948d53ef764c39c2457eeced625e9))


## v0.13.1 (2026-06-14)

### 🔺 Fix

- **ui**: Remove WebView cold-start flash, show connect spinner, announce map empty state
  ([#209](https://github.com/jtn0123/VoltTracker/pull/209),
  [`1a0011f`](https://github.com/jtn0123/VoltTracker/commit/1a0011f5618fc60df1595aafbc48352a2c414d26))

### 🔷 Changed

- Polish repo docs, GitHub templates, and stale config cleanup
  ([#189](https://github.com/jtn0123/VoltTracker/pull/189),
  [`52b9f58`](https://github.com/jtn0123/VoltTracker/commit/52b9f583d874425da699fdf245db0327599f7649))

- **deps**: Bump androidx.core to 1.19.0 + raise compileSdk to 37
  ([#208](https://github.com/jtn0123/VoltTracker/pull/208),
  [`186fcdf`](https://github.com/jtn0123/VoltTracker/commit/186fcdf9b8fe25519a0069bc34e3b3a27b30115f))

- **deps-dev**: Batch low-risk dev-dependency bumps
  ([#206](https://github.com/jtn0123/VoltTracker/pull/206),
  [`32e6d15`](https://github.com/jtn0123/VoltTracker/commit/32e6d1590410a5faf1530ae725640cd6414351ed))

### 🔷 Changed

- Bump actions/checkout to v6.0.3 and osv-scanner-action to v2.3.8
  ([#207](https://github.com/jtn0123/VoltTracker/pull/207),
  [`4341d6d`](https://github.com/jtn0123/VoltTracker/commit/4341d6dac8e8384a866f4f0c6b86ac0962a9e3a2))


## v0.13.0 (2026-06-13)

### 🔺 Fix

- **release**: Clear esbuild audit advisory + #199 CI regressions
  ([#205](https://github.com/jtn0123/VoltTracker/pull/205),
  [`869e251`](https://github.com/jtn0123/VoltTracker/commit/869e2518d11aa9246a03cb89c2ecdc3f1ad7404d))

### ✳️ New

- **dashboard**: Live-map follow + direction, live-signals + battery views, charge over-count fix,
  WebView lifecycle ([#199](https://github.com/jtn0123/VoltTracker/pull/199),
  [`44ad880`](https://github.com/jtn0123/VoltTracker/commit/44ad8809e9e5f81a8c4036e9125779bc3b5238ff))


## v0.12.1 (2026-06-12)

### 🔺 Fix

- Implement the codebase-eval polish items across data, dashboard, app, and CI
  ([#198](https://github.com/jtn0123/VoltTracker/pull/198),
  [`b4f688e`](https://github.com/jtn0123/VoltTracker/commit/b4f688eef6f85ba5414d4c8215a57637aa52cd2a))


## v0.12.0 (2026-06-12)

### ✳️ New

- Implement the 20-point polish plan across app, data, dashboard, and CI
  ([#197](https://github.com/jtn0123/VoltTracker/pull/197),
  [`6b700e8`](https://github.com/jtn0123/VoltTracker/commit/6b700e8bc80b4c9736c993608f2aa777d125be67))


## v0.11.5 (2026-06-12)

### 🔺 Fix

- Implement top-10 polish items from the app-wide review
  ([#196](https://github.com/jtn0123/VoltTracker/pull/196),
  [`51894c3`](https://github.com/jtn0123/VoltTracker/commit/51894c364ba0e0a7a7205ddc5f697443fceb475d))


## v0.11.4 (2026-06-12)

### 🔺 Fix

- Dashboard state consistency, DTC dialog dismiss, and quieter failure logging
  ([#195](https://github.com/jtn0123/VoltTracker/pull/195),
  [`76ae8f6`](https://github.com/jtn0123/VoltTracker/commit/76ae8f6c4b0b6396b101f52dc47b7a4c3a410df5))


## v0.11.3 (2026-06-11)

### 🔺 Fix

- Restore determinate progress bar and weeks-deep map history
  ([#194](https://github.com/jtn0123/VoltTracker/pull/194),
  [`de427f9`](https://github.com/jtn0123/VoltTracker/commit/de427f9551c7dfa55c75666010d47f02fb3ddf14))


## v0.11.2 (2026-06-11)

### 🔺 Fix

- **dashboard**: Stop Drive tab cards overflowing the viewport width
  ([#193](https://github.com/jtn0123/VoltTracker/pull/193),
  [`4ae5b3c`](https://github.com/jtn0123/VoltTracker/commit/4ae5b3cdb301df39e93c5304ab71acff2623ee4a))


## v0.11.1 (2026-06-11)

### 🔺 Fix

- 39 bugs across OBD protocol, data layer, services, and dashboard
  ([#192](https://github.com/jtn0123/VoltTracker/pull/192),
  [`fe076ba`](https://github.com/jtn0123/VoltTracker/commit/fe076baea97b750524d20f429ac682cf8c2ba27d))


## v0.11.0 (2026-06-10)

### ✳️ New

- **dashboard**: 17 UI/UX improvements across tabs
  ([#191](https://github.com/jtn0123/VoltTracker/pull/191),
  [`be2e4e6`](https://github.com/jtn0123/VoltTracker/commit/be2e4e6969863a07c71fab69c713e5933d218c47))


## v0.10.2 (2026-06-10)

### 🔺 Fix

- **android**: Bluetooth permission feedback, auto-resume, and status badge popover
  ([#190](https://github.com/jtn0123/VoltTracker/pull/190),
  [`2d92f0e`](https://github.com/jtn0123/VoltTracker/commit/2d92f0ed7d864ced752360cb3cc47e58f2f1353c))


## v0.10.1 (2026-06-10)

### 🔺 Fix

- **android**: Restore progress, map cleanup, charge inference
  ([#188](https://github.com/jtn0123/VoltTracker/pull/188),
  [`1da0d3e`](https://github.com/jtn0123/VoltTracker/commit/1da0d3e88ff43a5291d6ec8fa705f4adae5c345d))


## v0.10.0 (2026-06-10)

### ✳️ New

- **android**: Expand sensors and harden app flows
  ([#187](https://github.com/jtn0123/VoltTracker/pull/187),
  [`3a2dd59`](https://github.com/jtn0123/VoltTracker/commit/3a2dd59c2f498dab215a9a343729d80725ff2477))


## v0.9.3 (2026-06-09)

### 🔺 Fix

- **android**: Restore map data for matched backup sessions
  ([#186](https://github.com/jtn0123/VoltTracker/pull/186),
  [`1c2282e`](https://github.com/jtn0123/VoltTracker/commit/1c2282e21620fb1280628ec67156987273efe745))


## v0.9.2 (2026-06-09)

### 🔺 Fix

- **android**: Allow 200 MB backup restores
  ([#185](https://github.com/jtn0123/VoltTracker/pull/185),
  [`5472231`](https://github.com/jtn0123/VoltTracker/commit/5472231c8a2483d99e4aaa3b7598c7984d15f8d6))


## v0.9.1 (2026-06-09)

### 🔺 Fix

- **android**: Quiet offline dashboard and surface restore
  ([#184](https://github.com/jtn0123/VoltTracker/pull/184),
  [`8c8c6a2`](https://github.com/jtn0123/VoltTracker/commit/8c8c6a2485326f5d5c7c67c1e3a728a8934e6290))


## v0.9.0 (2026-06-09)

### ✳️ New

- **android**: Polish dashboard and restore feedback
  ([`e1cb0aa`](https://github.com/jtn0123/VoltTracker/commit/e1cb0aa4116458041d48182dda1ffeec38c8776c))


## v0.8.1 (2026-06-08)

### 🔺 Fix

- **android**: Harden release flow and auto-connect UX
  ([`17234dd`](https://github.com/jtn0123/VoltTracker/commit/17234dd52d1f46ed717250bf81cb54d9c7000668))

- **release**: Install Playwright before preflight
  ([`356c97d`](https://github.com/jtn0123/VoltTracker/commit/356c97db1bc019b4f4c864be5516f447c46a64a4))

- **release**: Serialize release preflight verification
  ([`aaf0a20`](https://github.com/jtn0123/VoltTracker/commit/aaf0a2017bea8c0e6d349e947d7212edce78f480))

### 🔷 Changed

- **release**: Repair tagged APK publishing
  ([`af1bb57`](https://github.com/jtn0123/VoltTracker/commit/af1bb57f8049160387cd26c9c091fb88fab53592))


## v0.8.0 (2026-06-08)

### ✳️ New

- **android**: Ship VoltTracker 0.8.0 release
  ([`0c9fd7c`](https://github.com/jtn0123/VoltTracker/commit/0c9fd7c6e04418ab65c17e326b3188889beed71a))


## v0.7.0 (2026-06-04)

### ✳️ New

- Add enhanced signal discovery workspace ([#168](https://github.com/jtn0123/VoltTracker/pull/168),
  [`fd2649c`](https://github.com/jtn0123/VoltTracker/commit/fd2649c3c2a2b2b8aa5b8706b8a213916f991b60))


## v0.6.2 (2026-06-03)

### 🔺 Fix

- Split long obd sessions into drive windows
  ([#167](https://github.com/jtn0123/VoltTracker/pull/167),
  [`f0ade30`](https://github.com/jtn0123/VoltTracker/commit/f0ade3026ffe7c0b113fc6a828b3b9a5fa743902))

### 🔷 Changed

- **deps**: Bump softprops/action-gh-release from 2.6.2 to 3.0.0
  ([`b11bca2`](https://github.com/jtn0123/VoltTracker/commit/b11bca2a9b313633aaa755fc898c5d27daa52e72))

- **deps-dev**: Bump eslint in /mobile/android/dashboard-tests
  ([`f737981`](https://github.com/jtn0123/VoltTracker/commit/f737981f641f76f6bd2826a251944108a67b2f3a))

### 🔷 Changed

- **android**: Add Drive live-canvas functional e2e
  ([#165](https://github.com/jtn0123/VoltTracker/pull/165),
  [`414f612`](https://github.com/jtn0123/VoltTracker/commit/414f6122450f9399023827d5e05510b127595270))

- **android**: Add Playwright visual-regression baselines (advisory)
  ([#164](https://github.com/jtn0123/VoltTracker/pull/164),
  [`a3296e0`](https://github.com/jtn0123/VoltTracker/commit/a3296e00586a2586291b0426e94c3b6edcc0acf1))

- **android**: Cover the native Replace/Merge/Cancel restore dialog
  ([#163](https://github.com/jtn0123/VoltTracker/pull/163),
  [`1b867dc`](https://github.com/jtn0123/VoltTracker/commit/1b867dc4efed2afbc7bc3e8057023a2f9af2963c))


## v0.6.1 (2026-06-02)

### 🔺 Fix

- **android**: Drop background tasks submitted after executor shutdown
  ([#162](https://github.com/jtn0123/VoltTracker/pull/162),
  [`112bed6`](https://github.com/jtn0123/VoltTracker/commit/112bed647fa4c4c115043d4c524c928b77d412fb))

### 🔷 Changed

- **android**: Add Playwright dashboard e2e suite + CI gate
  ([#160](https://github.com/jtn0123/VoltTracker/pull/160),
  [`59d51b5`](https://github.com/jtn0123/VoltTracker/commit/59d51b57d62f509341b8c633f083ac055f45ab65))

- **android**: Broaden Playwright e2e to Map/Charge/Insights + interactions
  ([#161](https://github.com/jtn0123/VoltTracker/pull/161),
  [`9f9369d`](https://github.com/jtn0123/VoltTracker/commit/9f9369d404e3317fc3fde536723c1e43d583eba8))


## v0.6.0 (2026-06-02)

### 🔷 Changed

- Remove stale Codex scratch reports from the repo
  ([#158](https://github.com/jtn0123/VoltTracker/pull/158),
  [`1a8c6de`](https://github.com/jtn0123/VoltTracker/commit/1a8c6de0f3b2266cadf7e7042e83caaab19be112))

### ✳️ New

- **android**: Merge older backups + Trips/header/demo UI overhaul
  ([#159](https://github.com/jtn0123/VoltTracker/pull/159),
  [`d8d2795`](https://github.com/jtn0123/VoltTracker/commit/d8d2795d899d973b7ae9f667edb4142cff24c09d))


## v0.5.0 (2026-06-02)

### ✳️ New

- **android**: Merge a backup into the live database instead of only replacing
  ([#157](https://github.com/jtn0123/VoltTracker/pull/157),
  [`6be018e`](https://github.com/jtn0123/VoltTracker/commit/6be018e878a2cfa389dc87ba32070fe812a25192))


## v0.4.12 (2026-06-02)

### 🔺 Fix

- **android**: Round-7 grade-report remediation — 20 items via 4 parallel agents
  ([#156](https://github.com/jtn0123/VoltTracker/pull/156),
  [`677c159`](https://github.com/jtn0123/VoltTracker/commit/677c15973eb6079a8e6ee2dda23dc3966ec94708))

### 🔷 Changed

- **android**: Round-7 perf/polish (config cache, ts-check, schema split)
  ([#155](https://github.com/jtn0123/VoltTracker/pull/155),
  [`20b1ba0`](https://github.com/jtn0123/VoltTracker/commit/20b1ba0dd246a736896cf00be817068ff3a43b51))


## v0.4.11 (2026-06-01)

### 🔷 Changed

- **android**: Round-6 grade-report remediation
  ([#152](https://github.com/jtn0123/VoltTracker/pull/152),
  [`0fb17da`](https://github.com/jtn0123/VoltTracker/commit/0fb17dac45664146c4c59a9d4fbbd1574b645d8a))


## v0.4.10 (2026-05-29)

### 🔺 Fix

- **android**: Load dashboard JS as classic scripts (modules dead over file://)
  ([#151](https://github.com/jtn0123/VoltTracker/pull/151),
  [`18b698c`](https://github.com/jtn0123/VoltTracker/commit/18b698c9a1a7de08847a517183b270b9b09c3ae1))


## v0.4.9 (2026-05-29)

### 🔺 Fix

- **android**: Inset WebView by system bars so bottom-nav is tappable
  ([#150](https://github.com/jtn0123/VoltTracker/pull/150),
  [`59341ba`](https://github.com/jtn0123/VoltTracker/commit/59341bafa8b5d0a26dafb8db694533eeea8a0aea))


## v0.4.8 (2026-05-29)

### 🔺 Fix

- **android**: Polish dashboard drive and trips UX
  ([#148](https://github.com/jtn0123/VoltTracker/pull/148),
  [`f23e1c6`](https://github.com/jtn0123/VoltTracker/commit/f23e1c69867a20620c93900b92cdfadd99b8e72b))


## v0.4.7 (2026-05-28)

### 🔺 Fix

- **android**: Wait for dashboard bridge readiness
  ([#147](https://github.com/jtn0123/VoltTracker/pull/147),
  [`a5f7e16`](https://github.com/jtn0123/VoltTracker/commit/a5f7e16d64629531249274b7c041cce47f7148d5))


## v0.4.6 (2026-05-28)

### 🔺 Fix

- **android**: Publish release and debug APKs
  ([#145](https://github.com/jtn0123/VoltTracker/pull/145),
  [`10e7628`](https://github.com/jtn0123/VoltTracker/commit/10e7628372c8be38f2c3d09142569c59b9cb2046))

- **release**: Repair two-apk release contract
  ([#146](https://github.com/jtn0123/VoltTracker/pull/146),
  [`8c1bd15`](https://github.com/jtn0123/VoltTracker/commit/8c1bd15ff18e87e0f9e05ec9c3e791e6050aec6f))


## v0.4.5 (2026-05-28)

### 🔺 Fix

- **android**: Finish grade remediation follow-up
  ([#144](https://github.com/jtn0123/VoltTracker/pull/144),
  [`1c13854`](https://github.com/jtn0123/VoltTracker/commit/1c138544430c2305c88167d5984a1b063602f139))


## v0.4.4 (2026-05-27)

### 🔺 Fix

- **android**: Resolve grade audit findings
  ([#138](https://github.com/jtn0123/VoltTracker/pull/138),
  [`2dac11c`](https://github.com/jtn0123/VoltTracker/commit/2dac11cc4111927fb517794cedd263d2d202fa1f))


## v0.4.3 (2026-05-27)

### 🔺 Fix

- **android**: Resolve bug-hunt findings and tooling drift
  ([`5a88b70`](https://github.com/jtn0123/VoltTracker/commit/5a88b70b388464f2c307822682d1ed411398aeca))


## v0.4.2 (2026-05-27)

### 🔺 Fix

- **android**: Resolve validated obd backup and dashboard bugs
  ([#136](https://github.com/jtn0123/VoltTracker/pull/136),
  [`fdd47c4`](https://github.com/jtn0123/VoltTracker/commit/fdd47c4c0afd5b2c2a49ba72d8ab50004ad28e63))


## v0.4.1 (2026-05-26)

### 🔺 Fix

- Address 28 dogfood-audit findings (charge materializer, classifier sign, dashboard XSS, ELM races,
  …) ([#135](https://github.com/jtn0123/VoltTracker/pull/135),
  [`f967e53`](https://github.com/jtn0123/VoltTracker/commit/f967e53f0a33709177b62f39442c6936e2cc1e14))

### 🔷 Changed

- Rebuild rolling debug APK after each semantic-release bump
  ([#134](https://github.com/jtn0123/VoltTracker/pull/134),
  [`689293d`](https://github.com/jtn0123/VoltTracker/commit/689293d93d5930a0cf36a4c5f9d37d490bf7e82f))


## v0.4.0 (2026-05-26)

### 🔷 Changed

- Execute round-6 grade-codebase items (B4 E2 G1 D1 H1 H2 C7 C8 C9 C10 B7 B8 H3 A2 A1)
  ([#132](https://github.com/jtn0123/VoltTracker/pull/132),
  [`05722e4`](https://github.com/jtn0123/VoltTracker/commit/05722e455e5dc6250e74353c17e5f55f475a567a))

### ✳️ New

- **dashboard**: Show app version in Settings and stop truncating long-drive maps
  ([#133](https://github.com/jtn0123/VoltTracker/pull/133),
  [`c42a8ef`](https://github.com/jtn0123/VoltTracker/commit/c42a8efd2654001c776a5c5ce3e6d3ebdd92167b))


## v0.3.0 (2026-05-26)

### ✳️ New

- **obd**: Classify connection failures + observability + dashboard troubleshooter
  ([#131](https://github.com/jtn0123/VoltTracker/pull/131),
  [`fba1fb7`](https://github.com/jtn0123/VoltTracker/commit/fba1fb7b0d1a0305a151d887963cec4d94dfb641))


## v0.2.1 (2026-05-24)

### 🔺 Fix

- **obd**: Accel-pedal PID, raw HV pack columns, real trip energy & classification, smarter charge
  detection ([#130](https://github.com/jtn0123/VoltTracker/pull/130),
  [`c23bf9a`](https://github.com/jtn0123/VoltTracker/commit/c23bf9abcafcb168a1e76ca52750abe9fe00c888))


## v0.2.0 (2026-05-24)

### ✳️ New

- **release**: Sign tagged APKs with keystore decoded from CI secrets
  ([#129](https://github.com/jtn0123/VoltTracker/pull/129),
  [`a360c53`](https://github.com/jtn0123/VoltTracker/commit/a360c53a7ffe2bb2502f906a2165d1430a01479d))


## v0.1.1 (2026-05-24)

### 🔺 Fix

- **release**: Preserve config comments and reset initial CHANGELOG
  ([#128](https://github.com/jtn0123/VoltTracker/pull/128),
  [`dc7c2ca`](https://github.com/jtn0123/VoltTracker/commit/dc7c2cad4dc37dc8eaa6108aeae0fec9912424c7))


## v0.1.0 (2026-05-24)

### 🔺 Fix

- 23 bugs from audit pass 3 ([#35](https://github.com/jtn0123/VoltTracker/pull/35),
  [`3ad8692`](https://github.com/jtn0123/VoltTracker/commit/3ad8692c8697723c87ff651ca3db50981fb977cf))

- 32 bugs and UI inconsistencies from deep audit
  ([#33](https://github.com/jtn0123/VoltTracker/pull/33),
  [`f0170f9`](https://github.com/jtn0123/VoltTracker/commit/f0170f9dd2af0564d77016a450087c0590cd036f))

- Audit pass 2 — bug fixes ([#34](https://github.com/jtn0123/VoltTracker/pull/34),
  [`8afb1fd`](https://github.com/jtn0123/VoltTracker/commit/8afb1fd3c4b6cc78ef9b3c4d7d1f5db3dfef823e))

- Bug fixes, structured logging, performance optimization, and reliability hardening
  ([#27](https://github.com/jtn0123/VoltTracker/pull/27),
  [`27e8dea`](https://github.com/jtn0123/VoltTracker/commit/27e8dea94deb6a997ad2e6359a2312d274f490ad))

- Bump Flask-HTTPAuth to 5.1.0 (CVE fix for empty token verification)
  ([`bf83232`](https://github.com/jtn0123/VoltTracker/commit/bf83232135a551c564329ed5720259e0d7af3ba2))

- Bump requests to 2.33.0 (CVE fix for insecure temp file reuse)
  ([`453cdae`](https://github.com/jtn0123/VoltTracker/commit/453cdaec4c845d407a13052337f5d15b39a5712d))

- Bump sonarsource/sonarqube-scan-action v5 → v7 (CVE fix)
  ([`af73ad3`](https://github.com/jtn0123/VoltTracker/commit/af73ad378481d7ad758f2141c1320c6dbf6c8936))

- Dogfood polish pass — favicon, map filter validation, empty state dedup
  ([#77](https://github.com/jtn0123/VoltTracker/pull/77),
  [`3b9f1ee`](https://github.com/jtn0123/VoltTracker/commit/3b9f1eeb4597a3452ceca1856fc8a2820d6eac73))

- Enhance CSV import timestamp parsing
  ([`705019f`](https://github.com/jtn0123/VoltTracker/commit/705019f0da1ff9cb3b3b428f0547032c098558de))

- High-priority security, bugs, and performance from audit
  ([#29](https://github.com/jtn0123/VoltTracker/pull/29),
  [`5fbbbe0`](https://github.com/jtn0123/VoltTracker/commit/5fbbbe001443442975e52cdcbd5967c2ee35614d))

- Improve CSV import error handling and logging
  ([`15919e8`](https://github.com/jtn0123/VoltTracker/commit/15919e88958535a316d4d19cadf71ba3da9a19ea))

- Improve timezone handling consistency
  ([`a630ef3`](https://github.com/jtn0123/VoltTracker/commit/a630ef3f5de940e8d45ea52e50ce32d0a14ba6a7))

- Pin Flask-HTTPAuth to 4.8.1 (5.1.0 doesn't exist on PyPI)
  ([#66](https://github.com/jtn0123/VoltTracker/pull/66),
  [`42a0dd4`](https://github.com/jtn0123/VoltTracker/commit/42a0dd418367979b9e9d3a12d5f905954372115b))

- Resolve all CI failures on main — E2E env, frontend tests, PG compat, mypy
  ([#38](https://github.com/jtn0123/VoltTracker/pull/38),
  [`5cd9604`](https://github.com/jtn0123/VoltTracker/commit/5cd96045997972f24fa186c9304f540751b784ee))

- Resolve remaining SonarQube issues ([#45](https://github.com/jtn0123/VoltTracker/pull/45),
  [`bad0491`](https://github.com/jtn0123/VoltTracker/commit/bad0491ddc6011e2f4ba1bccde10a22aa1d9eb7d))

- Toast notification dedup + version display ([#26](https://github.com/jtn0123/VoltTracker/pull/26),
  [`ba4d351`](https://github.com/jtn0123/VoltTracker/commit/ba4d351f0fb8e7bc1ae578f1c650e955b131ac4a))

- Upgrade vite to latest + npm audit fix across frontend and e2e
  ([`6df368b`](https://github.com/jtn0123/VoltTracker/commit/6df368b0e29c46c36a74964a35cba297610967f4))

- Websocket auth + DEBUG opt-in + CSS modularization
  ([#14](https://github.com/jtn0123/VoltTracker/pull/14),
  [`bdb9350`](https://github.com/jtn0123/VoltTracker/commit/bdb935088240097a95df35fe947fe0ac044613d4))

- **backend**: Resolve 40 bugs found in backend audit
  ([`7e8468b`](https://github.com/jtn0123/VoltTracker/commit/7e8468b28e6bc56c1d12473cd70b94e63b4bc54d))

- **charging**: Wire up Add Session button and form submit (JTN-484, JTN-485)
  ([#74](https://github.com/jtn0123/VoltTracker/pull/74),
  [`d203f9c`](https://github.com/jtn0123/VoltTracker/commit/d203f9c1f8e075bd222ad8d6a285960f0472e8ed))

- **frontend**: Add id to import section so lazy observer actually fires (JTN-492)
  ([#79](https://github.com/jtn0123/VoltTracker/pull/79),
  [`03bcdab`](https://github.com/jtn0123/VoltTracker/commit/03bcdab9501f9a48f017b63602c6ea078aa80663))

- **frontend**: Csv import preventDefault must run synchronously (JTN-486)
  ([#75](https://github.com/jtn0123/VoltTracker/pull/75),
  [`3acd9d2`](https://github.com/jtn0123/VoltTracker/commit/3acd9d2e3bbd19ca9a871b2391de6169b36fa960))

- **frontend**: Eagerly fetch card subtitles (JTN-487)
  ([#78](https://github.com/jtn0123/VoltTracker/pull/78),
  [`48e34ee`](https://github.com/jtn0123/VoltTracker/commit/48e34ee594532b44b8c9453b3c3f2fb66cfee8f5))

- **frontend**: Key dashboard lazy-load observer on #soc-section (JTN-483)
  ([#76](https://github.com/jtn0123/VoltTracker/pull/76),
  [`64fec61`](https://github.com/jtn0123/VoltTracker/commit/64fec61d4eb0540f996f530122065eb8169bcca4))

- **jobs**: Repair latent ImportError in weather_jobs.fetch_weather_for_trip
  ([#67](https://github.com/jtn0123/VoltTracker/pull/67),
  [`856a247`](https://github.com/jtn0123/VoltTracker/commit/856a247889eb26fc26a3d886b32fd7e503a264ed))

- **obd**: Correct session status, strip ELM noise, poll HV pack, speed initial connect
  ([#105](https://github.com/jtn0123/VoltTracker/pull/105),
  [`861819c`](https://github.com/jtn0123/VoltTracker/commit/861819c8c8ee1ca5076c9c330bd64fd4207109f5))

- **receiver**: Move APP_VERSION to dedicated module (JTN-482)
  ([#73](https://github.com/jtn0123/VoltTracker/pull/73),
  [`e225060`](https://github.com/jtn0123/VoltTracker/commit/e2250601e88f522fefdd43f0a5dc2df53d1befaf))

- **socketio**: Disable manage_session to stop POST 400 flood (JTN-488)
  ([#80](https://github.com/jtn0123/VoltTracker/pull/80),
  [`ed761b5`](https://github.com/jtn0123/VoltTracker/commit/ed761b5787f28fc1478d8607c6b1cc51c5e2de34))

### 🔷 Changed

- Enforce LF line endings for shell scripts
  ([`d1d6bd2`](https://github.com/jtn0123/VoltTracker/commit/d1d6bd2907218000c2ddadedd2819391ebf6a31c))

- Execute all 30 items from round-2 grade-codebase audit
  ([#118](https://github.com/jtn0123/VoltTracker/pull/118),
  [`b3f2622`](https://github.com/jtn0123/VoltTracker/commit/b3f2622ed9eee401b0ede68697fbfa2bcc31c0eb))

- Execute all 38 items from grade-codebase audit
  ([#107](https://github.com/jtn0123/VoltTracker/pull/107),
  [`0f9e112`](https://github.com/jtn0123/VoltTracker/commit/0f9e11242d006e01538e3c801bde8616c7a7c760))

- Execute top-9 from round-4 grade-codebase audit + B6 tiered polling
  ([#125](https://github.com/jtn0123/VoltTracker/pull/125),
  [`9bda397`](https://github.com/jtn0123/VoltTracker/commit/9bda39782f00a8fbae0de43b29786fd586d73b2f))

- **ci**: Fix pre-existing infra failures hitting every PR
  ([#71](https://github.com/jtn0123/VoltTracker/pull/71),
  [`f5aa037`](https://github.com/jtn0123/VoltTracker/commit/f5aa037158f5ee812f54febce4f939a6daa0ec2e))

- **deps**: Bump actions/setup-java from 4.8.0 to 5.2.0
  ([#108](https://github.com/jtn0123/VoltTracker/pull/108),
  [`fdc76b8`](https://github.com/jtn0123/VoltTracker/commit/fdc76b85438292de287f50920d05565020d42ae2))

- **deps**: Bump actions/upload-artifact from 4.6.2 to 7.0.1
  ([#110](https://github.com/jtn0123/VoltTracker/pull/110),
  [`ca6f0fd`](https://github.com/jtn0123/VoltTracker/commit/ca6f0fd08cf41ad95912c3a95a89a24f6fa39d47))

- **deps**: Bump androidx.core:core ([#109](https://github.com/jtn0123/VoltTracker/pull/109),
  [`9dd3ca1`](https://github.com/jtn0123/VoltTracker/commit/9dd3ca154a9e6c9d5ace78291c4b5eb33db87c37))

- **deps**: Bump com.diffplug.spotless in /mobile/android
  ([#117](https://github.com/jtn0123/VoltTracker/pull/117),
  [`8359726`](https://github.com/jtn0123/VoltTracker/commit/83597264f6da8a2ce1be1d5136675d62b1f9d0f1))

- **deps**: Bump the test-deps group across 1 directory with 2 updates
  ([#111](https://github.com/jtn0123/VoltTracker/pull/111),
  [`3bc87c3`](https://github.com/jtn0123/VoltTracker/commit/3bc87c37fa0cdb4b680c69fde89eb0c49679e10f))

- **deps**: Upgrade backend dependencies and fix CVE-2026-28684
  ([`340f7bb`](https://github.com/jtn0123/VoltTracker/commit/340f7bb58082be88eadcd6e2827699fb85366067))

- **deps-dev**: Bump vitest in /mobile/android/dashboard-tests
  ([#112](https://github.com/jtn0123/VoltTracker/pull/112),
  [`f39d0f6`](https://github.com/jtn0123/VoltTracker/commit/f39d0f6ff95c23487a14b5b25367879ec8f1079c))

- **tests**: Delete dead test_api_integration.py placeholder suite
  ([#68](https://github.com/jtn0123/VoltTracker/pull/68),
  [`58cdcf2`](https://github.com/jtn0123/VoltTracker/commit/58cdcf20fb32e5987cf1ff7ba2948c432da0a730))

### 🔷 Changed

- Add CodeQL code scanning workflow
  ([`636ad34`](https://github.com/jtn0123/VoltTracker/commit/636ad3427dd59162db8a0d89119022a36b306cb1))

- Add dependabot configuration for automated dependency updates
  ([`1bd761a`](https://github.com/jtn0123/VoltTracker/commit/1bd761ada35c5443af358bd6c8c5b95f66f9ca07))

- Add python 3.13 to CI matrix and bump deps that lack 3.13 wheels
  ([#69](https://github.com/jtn0123/VoltTracker/pull/69),
  [`97f432f`](https://github.com/jtn0123/VoltTracker/commit/97f432f37e2ca6972c1519c075f37a8ac387107b))

- Add SonarQube workflow ([#39](https://github.com/jtn0123/VoltTracker/pull/39),
  [`1036ee1`](https://github.com/jtn0123/VoltTracker/commit/1036ee1ae7984e9ebb9de3e71efdb77c439cf5ba))

- Pr APK + SDK session hook; bump Gradle 9, AGP 9, jsdom 29
  ([#121](https://github.com/jtn0123/VoltTracker/pull/121),
  [`de4266a`](https://github.com/jtn0123/VoltTracker/commit/de4266a91521215a9339df07c4c2baa403f196ef))

- Publish main-branch debug APK to rolling 'latest-debug' release
  ([#122](https://github.com/jtn0123/VoltTracker/pull/122),
  [`f8bad28`](https://github.com/jtn0123/VoltTracker/commit/f8bad286d935e7a05dda0268b579cfe2e8d8c354))

- Switch all jobs to self-hosted runners ([#46](https://github.com/jtn0123/VoltTracker/pull/46),
  [`98c59c0`](https://github.com/jtn0123/VoltTracker/commit/98c59c0eaec92bb17e52dfe71565da2090b55657))

- **tests**: Run concurrency + transaction tests in postgres CI job
  ([#70](https://github.com/jtn0123/VoltTracker/pull/70),
  [`5a7136e`](https://github.com/jtn0123/VoltTracker/commit/5a7136e7b42abe577492e2e784c498dafdc3eb6f))

### 🔷 Changed

- Align AGENTS.md with Android pivot; fix gradlew exec bit
  ([#104](https://github.com/jtn0123/VoltTracker/pull/104),
  [`3d160b6`](https://github.com/jtn0123/VoltTracker/commit/3d160b695a828c83ddb75096395bc44223a48516))

### ✳️ New

- Add battery cell voltage UI
  ([`ea2ad98`](https://github.com/jtn0123/VoltTracker/commit/ea2ad98328dcae0665cccbcb0e27fc8c3262f66d))

- Add charging session curve visualization
  ([`ae798db`](https://github.com/jtn0123/VoltTracker/commit/ae798dbe14e3c8ebc80c6df7e9af3f0f23fb551e))

- Add custom exception classes for better error handling
  ([`b4a19f3`](https://github.com/jtn0123/VoltTracker/commit/b4a19f3eda011d83303d88887e03c2772527ce75))

- Add kWh/mile efficiency display
  ([`30976e6`](https://github.com/jtn0123/VoltTracker/commit/30976e6e36ac848711169ebfd7af9ca5d9ab0ca2))

- Comprehensive debugging, performance, testing, and error tracing
  ([#28](https://github.com/jtn0123/VoltTracker/pull/28),
  [`09d340b`](https://github.com/jtn0123/VoltTracker/commit/09d340b5dc6fa20e07617cc44528e2a8a7a0df64))

- Gps quality, map legends, RDP subsampling + import bug fixes
  ([#65](https://github.com/jtn0123/VoltTracker/pull/65),
  [`293982e`](https://github.com/jtn0123/VoltTracker/commit/293982e88fafe236b7d9b78902f696b39582c0e3))

- Loading skeletons, frontend CI/tests, map coverage, Docker hardening, PWA icons
  ([#32](https://github.com/jtn0123/VoltTracker/pull/32),
  [`d0b1303`](https://github.com/jtn0123/VoltTracker/commit/d0b1303a1fb2f35f7a2581f0308f61359c1565b0))

- Redesign theme with modern aesthetic ([#22](https://github.com/jtn0123/VoltTracker/pull/22),
  [`2801bb8`](https://github.com/jtn0123/VoltTracker/commit/2801bb8bf48ad2cbe333a2f1a2caaabe0f817be7))

- **ci**: Per-build version metadata + semantic-release for tagged APKs
  ([#127](https://github.com/jtn0123/VoltTracker/pull/127),
  [`031fd50`](https://github.com/jtn0123/VoltTracker/commit/031fd5009bb4fcec17193161583387b2f3fdf9db))

### 🔷 Changed

- Migrate remaining modules to api() wrapper ([#19](https://github.com/jtn0123/VoltTracker/pull/19),
  [`6a84e18`](https://github.com/jtn0123/VoltTracker/commit/6a84e1840113a8a20fab28b3d8b972ab9cb9571a))

- Modularize routes and enhance functionality
  ([`45e8b65`](https://github.com/jtn0123/VoltTracker/commit/45e8b65891877948a5a4b457d607bf11e095d72b))

- Reorganize app.py and improve import structure
  ([`acf6b26`](https://github.com/jtn0123/VoltTracker/commit/acf6b26d5e459a48ce590ca9cc8b6f006b8605b7))

- Split dashboard.js into ES modules ([#13](https://github.com/jtn0123/VoltTracker/pull/13),
  [`4e8f8d7`](https://github.com/jtn0123/VoltTracker/commit/4e8f8d7daf8fcd03062e6021ae988bc8b19667c0))

- Streamline dashboard route and remove circular import workarounds
  ([`41e635e`](https://github.com/jtn0123/VoltTracker/commit/41e635ef03625d6b605079d9433ec9bb4c22e3c4))

### 🔷 Changed

- Comprehensive testing — 195 new tests (unit, integration, E2E)
  ([#25](https://github.com/jtn0123/VoltTracker/pull/25),
  [`021813d`](https://github.com/jtn0123/VoltTracker/commit/021813d2a296d2d81e1c499e7989b8d2c1defd59))

- **backend**: Add regression tests for the 40 audited bug fixes
  ([`a87a83a`](https://github.com/jtn0123/VoltTracker/commit/a87a83ad24b996effc6f037c1b86a3b3ee193c1a))

- **frontend**: Add vitest tests for 5 untested src/ modules
  ([#72](https://github.com/jtn0123/VoltTracker/pull/72),
  [`7a6eaad`](https://github.com/jtn0123/VoltTracker/commit/7a6eaad1027eb39238f5dd03362413aa0aae5bb7))
