# CHANGELOG


## v0.36.1 (2026-08-09)

### 🔺 Fix

- **build**: Unbreak tagged release APK builds — stale dependency lock
  ([#3](https://github.com/jtn0123/VoltTracker/pull/3),
  [`917e984`](https://github.com/jtn0123/VoltTracker/commit/917e984d0de9e2456be68093ade2ae58564551b8))

### 🔷 Changed

- Add gitleaks secret scan
  ([`a6b6cbb`](https://github.com/jtn0123/VoltTracker/commit/a6b6cbb79dd0e7b6c7fcb65b8c199fcddd6525eb))

- **release**: Make PSR push over SSH so the deploy key can bypass main's ruleset
  ([#5](https://github.com/jtn0123/VoltTracker/pull/5),
  [`bf4a57b`](https://github.com/jtn0123/VoltTracker/commit/bf4a57bd477ad4d0493c2a69fea2da9299541243))

- **release**: Push version bumps with a deploy key to bypass main protection
  ([#4](https://github.com/jtn0123/VoltTracker/pull/4),
  [`cfa85af`](https://github.com/jtn0123/VoltTracker/commit/cfa85af0b937e69f0184d11b7482fcd006e346ac))

### 🔷 Changed

- Document consumed environment variables in .env.example
  ([`2b75bfc`](https://github.com/jtn0123/VoltTracker/commit/2b75bfcf28cf46e69b455acfd93c43caad533aa2))


## v0.36.0 (2026-08-04)

### ✳️ New

- **update**: In-app auto-update from GitHub Releases
  ([#368](https://github.com/jtn0123/VoltTracker/pull/368),
  [`2adc630`](https://github.com/jtn0123/VoltTracker/commit/2adc630ea9507d31f9205176bf716ce9dc77fb2a))


## v0.35.0 (2026-08-04)

### ✳️ New

- **ui**: Make the Compose dashboard the launcher, live-wired to ObdService
  ([#367](https://github.com/jtn0123/VoltTracker/pull/367),
  [`09faaed`](https://github.com/jtn0123/VoltTracker/commit/09faaed7a6b50985deb07af96fbc176174fc5d51))


## v0.34.0 (2026-08-03)

### 🔷 Changed

- Run dependencyUpdates without the configuration cache
  ([#361](https://github.com/jtn0123/VoltTracker/pull/361),
  [`86fd75e`](https://github.com/jtn0123/VoltTracker/commit/86fd75e5d203a43ee719bd13ac7e35dcfd6e9ead))

### ✳️ New

- **ui**: Compose rewrite of all six dashboard screens + screenshot pipeline
  ([#366](https://github.com/jtn0123/VoltTracker/pull/366),
  [`5a7804b`](https://github.com/jtn0123/VoltTracker/commit/5a7804b7ac1f87b73597d366fa4a2c67e9a4b6f7))


## v0.33.0 (2026-07-27)

### ✳️ New

- **dashboard**: Brand unit quantities, widen the selector contract, close deferred decisions
  ([#360](https://github.com/jtn0123/VoltTracker/pull/360),
  [`d9e6ab7`](https://github.com/jtn0123/VoltTracker/commit/d9e6ab70a40fea0645010a37e720ebb826203811))


## v0.32.5 (2026-07-25)

### 🔺 Fix

- **dashboard**: Read the session duration native actually sends
  ([#359](https://github.com/jtn0123/VoltTracker/pull/359),
  [`7e106da`](https://github.com/jtn0123/VoltTracker/commit/7e106daee4eceb6e94726b6da67d8721e60a16f3))


## v0.32.4 (2026-07-25)

### 🔺 Fix

- **dashboard**: Clear every native reading that ages out, not just the base ones
  ([#353](https://github.com/jtn0123/VoltTracker/pull/353),
  [`cd85eb9`](https://github.com/jtn0123/VoltTracker/commit/cd85eb9e6b669ee76a3d6c15f61db9c3ab53b562))

### 🔷 Changed

- Structural engine/service packages (A3) + BackupController decomposition
  ([#341](https://github.com/jtn0123/VoltTracker/pull/341),
  [`de3c899`](https://github.com/jtn0123/VoltTracker/commit/de3c899e2829fa518c07e3569b690ca4ca86245f))

- Structural splits (A1/A2), out-of-order OBD batch probe (B11), local gate parity (I1/I2)
  ([#340](https://github.com/jtn0123/VoltTracker/pull/340),
  [`2b83762`](https://github.com/jtn0123/VoltTracker/commit/2b83762b18d5c80a9c3a2fcd051f11bd08c2fae8))

- **dashboard**: Move drive-browser filters, primary action, and reconnect gate into state
  ([#352](https://github.com/jtn0123/VoltTracker/pull/352),
  [`6f56772`](https://github.com/jtn0123/VoltTracker/commit/6f56772c65ceb83b95bd85358de78c56e75e2448))

- **dashboard**: Single-writer state seam, render pass, architecture ratchet
  ([#351](https://github.com/jtn0123/VoltTracker/pull/351),
  [`fd87aaf`](https://github.com/jtn0123/VoltTracker/commit/fd87aaf049bbf34b53e0a3516de3e6ecfd0febff))


## v0.32.3 (2026-07-12)

### 🔺 Fix

- 20 deep-dive audit items + background telemetry backfill for live charts
  ([#339](https://github.com/jtn0123/VoltTracker/pull/339),
  [`8cd01d6`](https://github.com/jtn0123/VoltTracker/commit/8cd01d6670a01d4287bee0c21e24b94c5ad9d74b))


## v0.32.2 (2026-07-11)

### 🔺 Fix

- **privacy**: Disclose map-tile CDN egress in-app and in docs (E1)
  ([#337](https://github.com/jtn0123/VoltTracker/pull/337),
  [`a474889`](https://github.com/jtn0123/VoltTracker/commit/a4748895115d4376f2941fb1418973391c158fe4))

- **security**: Tighten dashboard CSP style-src to 'self' (E2)
  ([#338](https://github.com/jtn0123/VoltTracker/pull/338),
  [`e9fbbc3`](https://github.com/jtn0123/VoltTracker/commit/e9fbbc35c842771611c9ee091b3fb17404a1fb93))

- **ui**: Surface a retry toast when a lazy dashboard chunk fails to load
  ([#336](https://github.com/jtn0123/VoltTracker/pull/336),
  [`f273cce`](https://github.com/jtn0123/VoltTracker/commit/f273cceae966576c5880f962e44a5b80b90fa674))


## v0.32.1 (2026-07-11)

### 🔺 Fix

- **android**: Extended mid-drive reconnect tier + consolidated service state (B3, B5)
  ([#315](https://github.com/jtn0123/VoltTracker/pull/315),
  [`733fba0`](https://github.com/jtn0123/VoltTracker/commit/733fba0140f86b7a49e3a4922ced3beb6f7087fa))

- **android**: Portable vehicle identity across reinstall/restore (B8 + ADR-0009)
  ([#319](https://github.com/jtn0123/VoltTracker/pull/319),
  [`cdb7e25`](https://github.com/jtn0123/VoltTracker/commit/cdb7e2584d69aecfab92651803463be9b8477540))

- **android**: Survive WebView renderer death, add dashboard-load watchdog and crash capture (B1,
  B2, B4) ([#317](https://github.com/jtn0123/VoltTracker/pull/317),
  [`d1bf6b1`](https://github.com/jtn0123/VoltTracker/commit/d1bf6b1520fcaad046f74d4341110d7418940d76))

- **android**: Transaction-safe vehicle store, release log stripping, passphrase whitespace handling
  (B6, B7, E3) ([#313](https://github.com/jtn0123/VoltTracker/pull/313),
  [`4e18800`](https://github.com/jtn0123/VoltTracker/commit/4e18800a866bab384b0ee7b733fa906bdcb3e850))

- **dashboard**: Don't arm route-hydration retry after test env teardown
  ([#333](https://github.com/jtn0123/VoltTracker/pull/333),
  [`dd5040a`](https://github.com/jtn0123/VoltTracker/commit/dd5040a2730c145fa6299ba4836afa8f42d91d8c))

- **ui**: Settings licenses section, chart a11y labels, color-token sweep (C3, C4, C5)
  ([#320](https://github.com/jtn0123/VoltTracker/pull/320),
  [`7abec1d`](https://github.com/jtn0123/VoltTracker/commit/7abec1da52b49ee2876be1464244b49f3f32cbce))

- **ui**: Themed rename dialog, single speed unit, branded splash, input bounds, touch targets (C1,
  C2, C6, C8, C9) ([#314](https://github.com/jtn0123/VoltTracker/pull/314),
  [`54a8680`](https://github.com/jtn0123/VoltTracker/commit/54a8680bb83dcf42b5063b388a1412ab32c83c1e))

### 🔷 Changed

- **deps**: Bump actions/setup-java from 5.4.0 to 5.5.0
  ([#329](https://github.com/jtn0123/VoltTracker/pull/329),
  [`8e39e38`](https://github.com/jtn0123/VoltTracker/commit/8e39e3873c09ebd24c7ed7dd3016f73afb01c87a))

- **deps**: Bump reactivecircus/android-emulator-runner
  ([#325](https://github.com/jtn0123/VoltTracker/pull/325),
  [`ffbc5ba`](https://github.com/jtn0123/VoltTracker/commit/ffbc5ba2ce1a30933f90acb70c9ba5902d6e5a0e))

- **deps-dev**: Bump @vitest/coverage-istanbul
  ([#332](https://github.com/jtn0123/VoltTracker/pull/332),
  [`8ee0f4e`](https://github.com/jtn0123/VoltTracker/commit/8ee0f4ea13e2fa16bba9d28608c10452770765ad))

- **deps-dev**: Bump eslint in /mobile/android/dashboard-tests
  ([#324](https://github.com/jtn0123/VoltTracker/pull/324),
  [`1c7eb98`](https://github.com/jtn0123/VoltTracker/commit/1c7eb98a73bbbca2f8749513a42c7c4658a4f36c))

- **deps-dev**: Bump typescript-eslint ([#326](https://github.com/jtn0123/VoltTracker/pull/326),
  [`888ecf8`](https://github.com/jtn0123/VoltTracker/commit/888ecf8d48ed0bb13215f7c6decd45d8c24d4b6d))

- **deps-dev**: Bump vitest in /mobile/android/dashboard-tests
  ([#328](https://github.com/jtn0123/VoltTracker/pull/328),
  [`1a1eec2`](https://github.com/jtn0123/VoltTracker/commit/1a1eec2c6e1a533d5e4760c939603abbbcdf26db))

### 🔷 Changed

- Bump github/codeql-action init+analyze together to v4.37.0
  ([#335](https://github.com/jtn0123/VoltTracker/pull/335),
  [`a04c96d`](https://github.com/jtn0123/VoltTracker/commit/a04c96dd8ce185c9dfc70599874a063779158ef6))

- Tighten supply-chain gates, dedupe dashboard builds, surface e2e flake (F1-F3, G3, I1-I4, D2, D4)
  ([#312](https://github.com/jtn0123/VoltTracker/pull/312),
  [`04892cd`](https://github.com/jtn0123/VoltTracker/commit/04892cd504e10e993c133f317cec0a10a0641c45))

### 🔷 Changed

- Add MIT LICENSE and fix doc/reality drift (H1-H5)
  ([#310](https://github.com/jtn0123/VoltTracker/pull/310),
  [`84f89af`](https://github.com/jtn0123/VoltTracker/commit/84f89afa4f10311ced9fbe9869280c11eae1565e))

- **android**: Archive landed one-shot reports and refresh reports index
  ([#309](https://github.com/jtn0123/VoltTracker/pull/309),
  [`77faab8`](https://github.com/jtn0123/VoltTracker/commit/77faab8c29205c657076723612da2615e6a1b2c3))

### 🔷 Changed

- Obd latency baseline machinery + startup bundle headroom (G1, G2)
  ([#322](https://github.com/jtn0123/VoltTracker/pull/322),
  [`6ab1167`](https://github.com/jtn0123/VoltTracker/commit/6ab1167136875cac25e60d3b1810f124817fd535))

### 🔷 Changed

- **android**: Split VoltBridgeDataExports into per-feature bridge units (A3)
  ([#318](https://github.com/jtn0123/VoltTracker/pull/318),
  [`3bded51`](https://github.com/jtn0123/VoltTracker/commit/3bded516f34682e84a8dea6e69d2d506447fa4e0))

- **dashboard**: Migrate cross-module calls from window globals to typed ESM imports (C7)
  ([#323](https://github.com/jtn0123/VoltTracker/pull/323),
  [`36aff73`](https://github.com/jtn0123/VoltTracker/commit/36aff7307b5295edec69492b377d6d4f640b1614))

- **data**: Extract vehicle-identity merge out of DatabaseMerger to restore the LargeClass ratchet
  ([#334](https://github.com/jtn0123/VoltTracker/pull/334),
  [`4f71c63`](https://github.com/jtn0123/VoltTracker/commit/4f71c637e5eee29afd54cf4513dc30c8f655a1b9))

- **data**: Narrow the ObdLocalStore facade behind capability interfaces (A2)
  ([#321](https://github.com/jtn0123/VoltTracker/pull/321),
  [`7a2ccfb`](https://github.com/jtn0123/VoltTracker/commit/7a2ccfb4d0b78e54084723fb7e4b851c8d8e2de0))

- **obd**: Move Mode-22 decoders into the parser registry, lower complexity ratchets (A1)
  ([#316](https://github.com/jtn0123/VoltTracker/pull/316),
  [`abdb554`](https://github.com/jtn0123/VoltTracker/commit/abdb554bef3ffeb9fb5c5a05c85208f742787d3b))

### 🔷 Changed

- Branch-coverage ratchets, deterministic tab-switch gate, instrumented handshake smoke (D1, D3, D4)
  ([#311](https://github.com/jtn0123/VoltTracker/pull/311),
  [`7887388`](https://github.com/jtn0123/VoltTracker/commit/7887388bf54db086069d78895e252f7139a3ed24))


## v0.32.0 (2026-07-10)

### 🔷 Changed

- **tooling**: Upgrade dashboard to Node 24 LTS
  ([#307](https://github.com/jtn0123/VoltTracker/pull/307),
  [`855668d`](https://github.com/jtn0123/VoltTracker/commit/855668da6d63cd2b9c13439bd53dfe5f5bd7e82a))

### ✳️ New

- **android**: Polish daily workflows and harden data reliability
  ([#308](https://github.com/jtn0123/VoltTracker/pull/308),
  [`55229bf`](https://github.com/jtn0123/VoltTracker/commit/55229bfd0a3749424853fa2c8ca6e4fa08d3ca74))


## v0.31.2 (2026-07-10)

### 🔺 Fix

- **android**: Harden lifecycle and bridge reliability
  ([#306](https://github.com/jtn0123/VoltTracker/pull/306),
  [`991c606`](https://github.com/jtn0123/VoltTracker/commit/991c6065bc01baad1f16c3096c2fb0eb31fe296e))


## v0.31.1 (2026-07-09)

### 🔺 Fix

- **dashboard**: Resolve flicker, loading, and flash-in/out bugs in the WebView UI
  ([#305](https://github.com/jtn0123/VoltTracker/pull/305),
  [`3556e7c`](https://github.com/jtn0123/VoltTracker/commit/3556e7cbe73a6421622a8971dc33536accede583))

### 🔷 Changed

- Cache the AVD snapshot and parallelize static analysis
  ([#304](https://github.com/jtn0123/VoltTracker/pull/304),
  [`dcd607b`](https://github.com/jtn0123/VoltTracker/commit/dcd607b593937325e166d0b2a2973f76b52e3c1f))


## v0.31.0 (2026-07-09)

### ✳️ New

- **dashboard**: Align the whole dashboard with the v2 design handoff
  ([#303](https://github.com/jtn0123/VoltTracker/pull/303),
  [`8849beb`](https://github.com/jtn0123/VoltTracker/commit/8849beb54a2ee74e98f6f1a3984c8bbf37ecbd70))


## v0.30.2 (2026-07-08)

### 🔺 Fix

- **app**: Batches B–D UI/UX bug-hunt fixes (medium + polish)
  ([#302](https://github.com/jtn0123/VoltTracker/pull/302),
  [`59bbfff`](https://github.com/jtn0123/VoltTracker/commit/59bbfff3083a2103a7663b144c96566f3deab3b6))

### 🔷 Changed

- **deps**: Bump github/codeql-action to v4.36.3
  ([#301](https://github.com/jtn0123/VoltTracker/pull/301),
  [`87328a8`](https://github.com/jtn0123/VoltTracker/commit/87328a8c31778ee6e1e27821119d5ebd655c149f))


## v0.30.1 (2026-07-08)

### 🔺 Fix

- **app**: Batch A UI/UX bug-hunt fixes (high-severity + guards)
  ([#300](https://github.com/jtn0123/VoltTracker/pull/300),
  [`c5c9452`](https://github.com/jtn0123/VoltTracker/commit/c5c9452e3196964b624c5aa90e9ea98c09a14957))

### 🔷 Changed

- **deps**: Bump androidx.test.uiautomator:uiautomator
  ([#293](https://github.com/jtn0123/VoltTracker/pull/293),
  [`51e78ab`](https://github.com/jtn0123/VoltTracker/commit/51e78abc1b335c6a281f3c664fce1b1dfdbc4b15))

- **deps**: Bump com.diffplug.spotless in /mobile/android
  ([#294](https://github.com/jtn0123/VoltTracker/pull/294),
  [`8ee1c53`](https://github.com/jtn0123/VoltTracker/commit/8ee1c530b15826aa245e87eea302ca7b10745aff))

- **deps**: Bump dorny/paths-filter from 4.0.1 to 4.0.2
  ([#289](https://github.com/jtn0123/VoltTracker/pull/289),
  [`dc7e28f`](https://github.com/jtn0123/VoltTracker/commit/dc7e28fbc3216840ad3d9d3dc978a5c030faed5e))

- **deps-dev**: Bump typescript-eslint ([#290](https://github.com/jtn0123/VoltTracker/pull/290),
  [`3761ae3`](https://github.com/jtn0123/VoltTracker/commit/3761ae3f509fe88cb2ead2e11aa3fd4c54883a7f))


## v0.30.0 (2026-07-08)

### ✳️ New

- **dashboard**: Tab-by-tab fidelity pass for the VoltTracker v2 design
  ([#299](https://github.com/jtn0123/VoltTracker/pull/299),
  [`301d2ba`](https://github.com/jtn0123/VoltTracker/commit/301d2baf29be25fe8290466bfec098d9c82e3ab0))


## v0.29.0 (2026-07-08)

### ✳️ New

- **dashboard**: Implement VoltTracker v2 design handoff
  ([#298](https://github.com/jtn0123/VoltTracker/pull/298),
  [`1ce6d17`](https://github.com/jtn0123/VoltTracker/commit/1ce6d17310c71d54a10912a8927284ba84141081))

### 🔷 Changed

- Expand Android simulation coverage ([#297](https://github.com/jtn0123/VoltTracker/pull/297),
  [`ea86209`](https://github.com/jtn0123/VoltTracker/commit/ea86209a8cf72bce7a3add0a0efbc42eb229787e))


## v0.28.1 (2026-07-06)

### 🔺 Fix

- Resolve reported diagnostics/map UI issues and 100+ audited bugs
  ([#296](https://github.com/jtn0123/VoltTracker/pull/296),
  [`2ad85b0`](https://github.com/jtn0123/VoltTracker/commit/2ad85b03a6806c99053062a3b96a178d587486bd))


## v0.28.0 (2026-07-06)

### ✳️ New

- **dashboard**: Polish dashboard UI to match Polished design handoff
  ([#295](https://github.com/jtn0123/VoltTracker/pull/295),
  [`f05de97`](https://github.com/jtn0123/VoltTracker/commit/f05de9722a192561978ccd85d7ee97922ca9e9f2))


## v0.27.0 (2026-07-04)

### ✳️ New

- Dashboard UX polish pass + code-audit findings docs
  ([#287](https://github.com/jtn0123/VoltTracker/pull/287),
  [`5e80612`](https://github.com/jtn0123/VoltTracker/commit/5e80612d3187cd5a01d30e9b1d892f9230bb5850))


## v0.26.0 (2026-07-03)

### ✳️ New

- Compact dashboard header and polish Demo / Testing mode
  ([#286](https://github.com/jtn0123/VoltTracker/pull/286),
  [`4099e5d`](https://github.com/jtn0123/VoltTracker/commit/4099e5d1096fc98c29e2d204ff4b8d7629240998))


## v0.25.3 (2026-07-03)

### 🔺 Fix

- Polish dashboard copy, formatting, and theme consistency
  ([#285](https://github.com/jtn0123/VoltTracker/pull/285),
  [`3f1edb6`](https://github.com/jtn0123/VoltTracker/commit/3f1edb635ba0178839dd08ac414747030862bf91))


## v0.25.2 (2026-07-03)

### 🔺 Fix

- Polish dashboard demo-mode UI copy and signed-value glyphs
  ([#284](https://github.com/jtn0123/VoltTracker/pull/284),
  [`e1a8bcc`](https://github.com/jtn0123/VoltTracker/commit/e1a8bcc4a2f021eead7c75864811bf8c0a4a10fd))


## v0.25.1 (2026-07-02)

### 🔺 Fix

- **dashboard**: Ui/ux polish pass — a11y states, tone tokens, touch targets
  ([#283](https://github.com/jtn0123/VoltTracker/pull/283),
  [`67d713a`](https://github.com/jtn0123/VoltTracker/commit/67d713ab65e6bd85500d02a1dc8805f5630afcec))


## v0.25.0 (2026-07-02)

### ✳️ New

- Battery cell map, driving trends, EV share, trip detail depth, share card, temperature insight
  ([#282](https://github.com/jtn0123/VoltTracker/pull/282),
  [`ea8048d`](https://github.com/jtn0123/VoltTracker/commit/ea8048d8475895f5dbc20d81cb8bf6c1a2e2c977))


## v0.24.0 (2026-07-02)

### 🔷 Changed

- **deps**: Bump actions/cache from 5.0.5 to 6.1.0
  ([#273](https://github.com/jtn0123/VoltTracker/pull/273),
  [`291f7c2`](https://github.com/jtn0123/VoltTracker/commit/291f7c2d5c14a73bd99aff956771faf7276d0fe5))

- **deps**: Bump actions/checkout from 6.0.3 to 7.0.0
  ([#275](https://github.com/jtn0123/VoltTracker/pull/275),
  [`cee31d3`](https://github.com/jtn0123/VoltTracker/commit/cee31d34db5bb16dc9d3a2056bddea5c5e0670af))

- **deps**: Bump actions/setup-java from 5.2.0 to 5.4.0
  ([#271](https://github.com/jtn0123/VoltTracker/pull/271),
  [`114ae93`](https://github.com/jtn0123/VoltTracker/commit/114ae9324b39a9b47e11d8e601fc3a3d8b97cc68))

- **deps**: Bump actions/setup-python from 6.2.0 to 6.3.0
  ([#276](https://github.com/jtn0123/VoltTracker/pull/276),
  [`3cf0ba5`](https://github.com/jtn0123/VoltTracker/commit/3cf0ba53c62fa013c9124ce2a7c5638bdd800b44))

- **deps**: Bump gradle-wrapper from 9.6.0 to 9.6.1 in /mobile/android
  ([#278](https://github.com/jtn0123/VoltTracker/pull/278),
  [`6f18a82`](https://github.com/jtn0123/VoltTracker/commit/6f18a82fd0b5ab029dfbc314d6ebc6e8b262da56))

- **deps**: Bump gradle/actions/wrapper-validation from 4.4.4 to 6.2.0
  ([#272](https://github.com/jtn0123/VoltTracker/pull/272),
  [`deab3c1`](https://github.com/jtn0123/VoltTracker/commit/deab3c1059f6114e0851c940ea211491dad9b967))

- **deps-dev**: Bump eslint from 10.5.0 to 10.6.0 in /mobile/android/dashboard-tests
  ([#274](https://github.com/jtn0123/VoltTracker/pull/274),
  [`0d434b9`](https://github.com/jtn0123/VoltTracker/commit/0d434b95c7814da740521d56367dd4f1a7a79d6e))

- **deps-dev**: Bump typescript-eslint from 8.61.0 to 8.62.0 in /mobile/android/dashboard-tests
  ([#277](https://github.com/jtn0123/VoltTracker/pull/277),
  [`48e0d72`](https://github.com/jtn0123/VoltTracker/commit/48e0d72c636729a71bed5da72928e47fc39180d9))

### ✳️ New

- **diagnostics**: Add a quick car-code scan profile
  ([#270](https://github.com/jtn0123/VoltTracker/pull/270),
  [`03ba717`](https://github.com/jtn0123/VoltTracker/commit/03ba717ca6c3b631da64697a07c369823013119c))
  - _a stored-code check no longer requires sitting through the full multi-module sweep._


## v0.23.3 (2026-07-02)

### 🔺 Fix

- **build**: Exclude generated dashboard JS from privacyScan inputs for Gradle 9.6
  ([#269](https://github.com/jtn0123/VoltTracker/pull/269),
  [`438496f`](https://github.com/jtn0123/VoltTracker/commit/438496f7919541b2250599c5f8c16b212ebe7066))


## v0.23.2 (2026-06-29)

### 🔺 Fix

- **android**: Harden dashboard bridge actions
  ([`2853dea`](https://github.com/jtn0123/VoltTracker/commit/2853dea78c12cac3e976e034b00b1e2a304a65a6))


## v0.23.1 (2026-06-29)

### 🔺 Fix

- **android**: Harden bridge failure paths
  ([`7f67bb8`](https://github.com/jtn0123/VoltTracker/commit/7f67bb82c7b30b5ad145489a21cda81807e596e7))


## v0.23.0 (2026-06-29)

### 🔺 Fix

- **android**: Harden exports and validation gates
  ([`298f998`](https://github.com/jtn0123/VoltTracker/commit/298f998addc514b45ff5ef7161358726c817e0d3))

- **dashboard**: Complete map-layer tablist semantics and document DTC lazy chunks
  ([#261](https://github.com/jtn0123/VoltTracker/pull/261),
  [`cac2f3f`](https://github.com/jtn0123/VoltTracker/commit/cac2f3f2a4ef1073f0d33c5e5a587cdf77612717))

- **release**: Stop the changelog template dir from overwriting the root README
  ([#268](https://github.com/jtn0123/VoltTracker/pull/268),
  [`07ea047`](https://github.com/jtn0123/VoltTracker/commit/07ea0479e0e2cc4708e78f391201addb0ee58dd7))
  - _the project README stops being clobbered on every release._

### 🔷 Changed

- **deps**: Bump actions/checkout from 6.0.3 to 7.0.0
  ([#248](https://github.com/jtn0123/VoltTracker/pull/248),
  [`014a3f2`](https://github.com/jtn0123/VoltTracker/commit/014a3f2f7001adbf1e834d798c7b1f155fb825e8))

- **deps**: Bump actions/setup-java from 5.2.0 to 5.3.0
  ([#249](https://github.com/jtn0123/VoltTracker/pull/249),
  [`1c9c3a2`](https://github.com/jtn0123/VoltTracker/commit/1c9c3a238aadccf123b4ec80f3a2225fa12ec795))

- **deps**: Bump com.diffplug.spotless in /mobile/android
  ([#256](https://github.com/jtn0123/VoltTracker/pull/256),
  [`1743bb4`](https://github.com/jtn0123/VoltTracker/commit/1743bb4758c317548a62ed509c8cf4767f46dc91))

- **deps**: Bump softprops/action-gh-release from 3.0.0 to 3.0.1
  ([#251](https://github.com/jtn0123/VoltTracker/pull/251),
  [`e94ef3e`](https://github.com/jtn0123/VoltTracker/commit/e94ef3e58c8215eeeab18c645267ad07b3dcc028))

- **deps-dev**: Bump @playwright/test in /mobile/android/dashboard-e2e
  ([#253](https://github.com/jtn0123/VoltTracker/pull/253),
  [`8fa6a5a`](https://github.com/jtn0123/VoltTracker/commit/8fa6a5a9aebcc69eddfcdbe3e17d144df92ea2e4))

- **deps-dev**: Bump @vitest/coverage-istanbul
  ([#254](https://github.com/jtn0123/VoltTracker/pull/254),
  [`1f2c2bb`](https://github.com/jtn0123/VoltTracker/commit/1f2c2bb68ce8c9d88ed3607fd45cf27fbe12aaf7))

- **deps-dev**: Bump vitest in /mobile/android/dashboard-tests
  ([#252](https://github.com/jtn0123/VoltTracker/pull/252),
  [`6049fc0`](https://github.com/jtn0123/VoltTracker/commit/6049fc093e043fead8e013373efa7fb8030e00e9))

### 🔷 Changed

- **tooling**: Add a privacy scanner and local performance-benchmark tooling
  ([#246](https://github.com/jtn0123/VoltTracker/pull/246),
  [`052cfb0`](https://github.com/jtn0123/VoltTracker/commit/052cfb07c22f19faa4f32119a7b796c935f9ec7f))
  - _tracked files are now scanned for leaked vehicle/location/device data on every PR, and there's a documented way to benchmark startup on a real device._

### ✳️ New

- **android**: Log how far a session got on terminal OBD failures
  ([#266](https://github.com/jtn0123/VoltTracker/pull/266),
  [`e146537`](https://github.com/jtn0123/VoltTracker/commit/e146537ca5348e13898c7f4541f14c2895dc5736))

- **dashboard**: Nav-safe spacing, 44px touch targets, tablet rail layout, light polish
  ([#259](https://github.com/jtn0123/VoltTracker/pull/259),
  [`0340105`](https://github.com/jtn0123/VoltTracker/commit/0340105b02fb3678d09d3f7893d67168debd6671))

### 🔷 Changed

- **android**: Annotate test-only seams with @VisibleForTesting
  ([#262](https://github.com/jtn0123/VoltTracker/pull/262),
  [`032409d`](https://github.com/jtn0123/VoltTracker/commit/032409d17d5ab63eb4e05eeeac6ec65a1d176e1f))

- **android**: Extract charge-summary engine from ObdStoreReports
  ([#263](https://github.com/jtn0123/VoltTracker/pull/263),
  [`fe90eaf`](https://github.com/jtn0123/VoltTracker/commit/fe90eaf1fdb43d266d0403d95004ffff408f13d9))

- **android**: Extract Volt Mode-22 decoder from ObdProtocol
  ([#264](https://github.com/jtn0123/VoltTracker/pull/264),
  [`bcf3263`](https://github.com/jtn0123/VoltTracker/commit/bcf3263f5d31ec799db93f5571eaa14cd910b382))

- **android**: Split the Volt PID catalog data out of EnhancedPidProfiles
  ([#265](https://github.com/jtn0123/VoltTracker/pull/265),
  [`66b0f1b`](https://github.com/jtn0123/VoltTracker/commit/66b0f1b4cb189e8b8cf1c0a2289628d7a73db662))


## v0.22.2 (2026-06-26)

### 🔺 Fix

- Bug-hunt batch — correctness/robustness fixes across app + dashboard
  ([#267](https://github.com/jtn0123/VoltTracker/pull/267),
  [`176e7d4`](https://github.com/jtn0123/VoltTracker/commit/176e7d4b14f380204822e1bb222d4f1490702999))


## v0.22.1 (2026-06-22)

### 🔺 Fix

- **dashboard**: Announce status-toast failures assertively for screen readers
  ([#260](https://github.com/jtn0123/VoltTracker/pull/260),
  [`ca904f4`](https://github.com/jtn0123/VoltTracker/commit/ca904f41bd84a4cd8c22da5df94d00f42a6df28b))

### 🔷 Changed

- **release**: Render the changelog as compact emoji sections with impact notes
  ([#245](https://github.com/jtn0123/VoltTracker/pull/245),
  [`f3bde6f`](https://github.com/jtn0123/VoltTracker/commit/f3bde6f4f5e77a4796eb10f051b10b1ef51b76b0))
  - _release notes now read as a scannable what-changed-and-why list instead of a wall of commit bodies._


## v0.22.0 (2026-06-19)

### ✳️ New

- **dashboard**: Add a hide-outliers toggle to the efficiency chart
  ([#244](https://github.com/jtn0123/VoltTracker/pull/244),
  [`161ef51`](https://github.com/jtn0123/VoltTracker/commit/161ef510d8152d9b2da0416732eff8cf71c8cea8))

- **dashboard**: Add switchable efficiency chart views with grade-normalization
  ([#243](https://github.com/jtn0123/VoltTracker/pull/243),
  [`1f078af`](https://github.com/jtn0123/VoltTracker/commit/1f078af938723457080d72f75593384a512daea9))


## v0.21.0 (2026-06-18)

### ✳️ New

- **dashboard**: Replace soc donut with a battery gauge and de-clutter the efficiency scatter
  ([#242](https://github.com/jtn0123/VoltTracker/pull/242),
  [`b8a58fb`](https://github.com/jtn0123/VoltTracker/commit/b8a58fb3faca7e34390b224ee5e72e8f3ef6c283))


## v0.20.0 (2026-06-18)

### ✳️ New

- **dashboard**: Polish battery charts + raise backup import limit to 4 GiB
  ([#241](https://github.com/jtn0123/VoltTracker/pull/241),
  [`09694ca`](https://github.com/jtn0123/VoltTracker/commit/09694ca4c4f6ea75826c3b330ec3ac6ec1a60ae3))


## v0.19.1 (2026-06-18)

### 🔷 Changed

- **dashboard**: Lazy-load Map-tab CSS off the startup path
  ([#240](https://github.com/jtn0123/VoltTracker/pull/240),
  [`8e3b7e3`](https://github.com/jtn0123/VoltTracker/commit/8e3b7e3f0a2c8d0a6d4ceaa5485fa776c07542f6))


## v0.19.0 (2026-06-18)

### ✳️ New

- **dashboard**: Recovery-first diagnostics, drive data provenance, map polish
  ([#238](https://github.com/jtn0123/VoltTracker/pull/238),
  [`a59b000`](https://github.com/jtn0123/VoltTracker/commit/a59b0001c1f8706fa41d3e4cd3cb97f967cecd3d))

### 🔷 Changed

- **dashboard**: Phone a11y/visual/contract/perf guards for new surfaces
  ([#239](https://github.com/jtn0123/VoltTracker/pull/239),
  [`42ef467`](https://github.com/jtn0123/VoltTracker/commit/42ef467cb0e8db278d9451b7fa7a84606853417b))


## v0.18.9 (2026-06-18)

### 🔷 Changed

- Reduce duplicate Android slow lanes ([#235](https://github.com/jtn0123/VoltTracker/pull/235),
  [`d307dab`](https://github.com/jtn0123/VoltTracker/commit/d307dab165aba889220aa3e024461798ddaae3ac))

### 🔷 Changed

- Track startup and tab responsiveness ([#236](https://github.com/jtn0123/VoltTracker/pull/236),
  [`7bb9507`](https://github.com/jtn0123/VoltTracker/commit/7bb950762ec43a1d56fe957f19e301b7f2b546b2))


## v0.18.8 (2026-06-17)

### 🔷 Changed

- Add startup benchmark and lazy dashboard panels
  ([#234](https://github.com/jtn0123/VoltTracker/pull/234),
  [`b776e8b`](https://github.com/jtn0123/VoltTracker/commit/b776e8b696292307bc87ad4b17fc5a082c276ec1))


## v0.18.7 (2026-06-17)

### 🔷 Changed

- Harden dashboard and storage performance
  ([`4d9a93a`](https://github.com/jtn0123/VoltTracker/commit/4d9a93a3581542447aeab66e134608ab5ff61c3a))


## v0.18.6 (2026-06-17)

### 🔷 Changed

- **obd,storage**: Finish remaining items — drop dead torque PID, faster prompt-recovery,
  single-scan counts (L3, L5, L10)
  ([`a945db6`](https://github.com/jtn0123/VoltTracker/commit/a945db6b73469cf72bbe7200f00c395de4c60ef1))


## v0.18.5 (2026-06-17)

### 🔷 Changed

- More boot/dashboard fast-path wins (DataBackup off main thread, lazy troubleshooter.css)
  ([#231](https://github.com/jtn0123/VoltTracker/pull/231),
  [`e7a1b20`](https://github.com/jtn0123/VoltTracker/commit/e7a1b204e9cfb86e15c35986abb190247cdb1689))


## v0.18.4 (2026-06-17)

### 🔺 Fix

- **repo**: Remove case-duplicate .github/pull_request_template.md
  ([#230](https://github.com/jtn0123/VoltTracker/pull/230),
  [`fc6f4a4`](https://github.com/jtn0123/VoltTracker/commit/fc6f4a48dd91c3a9170127c52350bd53bfe477bb))


## v0.18.3 (2026-06-17)

### 🔷 Changed

- Faster connect + boot + steady-state (L6–L9b) with benchmarks
  ([#229](https://github.com/jtn0123/VoltTracker/pull/229),
  [`952f2ce`](https://github.com/jtn0123/VoltTracker/commit/952f2cea6f59c9ee9dbed7de9ef16549a3458a43))


## v0.18.2 (2026-06-17)

### 🔷 Changed

- **obd**: Pin CAN protocol before 0100 to skip the ~4.8s connect search
  ([#228](https://github.com/jtn0123/VoltTracker/pull/228),
  [`2ba284e`](https://github.com/jtn0123/VoltTracker/commit/2ba284e9a02eed0e8e82524600ead1f45b8f38ea))


## v0.18.1 (2026-06-17)

### 🔺 Fix

- **android**: End asleep-car sessions cleanly and retire dead PIDs
  ([#227](https://github.com/jtn0123/VoltTracker/pull/227),
  [`70ceb43`](https://github.com/jtn0123/VoltTracker/commit/70ceb431d5a0049ea6a3350f347c922ed3e4e8f2))

- **dashboard**: Style maintenance-form inline validation messages
  ([#226](https://github.com/jtn0123/VoltTracker/pull/226),
  [`d524bdd`](https://github.com/jtn0123/VoltTracker/commit/d524bddb95cf2cacdb93208bea9a8213b16389c4))


## v0.18.0 (2026-06-17)

### 🔷 Changed

- **dashboard**: Sort VOLT_DTC entries into ascending order within their blocks
  ([#225](https://github.com/jtn0123/VoltTracker/pull/225),
  [`24a5944`](https://github.com/jtn0123/VoltTracker/commit/24a5944d29ddef8836c68df0f838db4df8c503e3))

### ✳️ New

- **android**: Charge CSV export, maintenance alerts, per-trip cost, charge-scan cache
  ([#224](https://github.com/jtn0123/VoltTracker/pull/224),
  [`9c50059`](https://github.com/jtn0123/VoltTracker/commit/9c50059c0a0773f8038acedbe35aa8e0b1423305))


## v0.17.3 (2026-06-17)

### 🔷 Changed

- **android**: Serve OBD-connect VIN read from one query, not the full storage summary
  ([#223](https://github.com/jtn0123/VoltTracker/pull/223),
  [`de13dcf`](https://github.com/jtn0123/VoltTracker/commit/de13dcf86d1d29bab915ac088c075839308fd30e))


## v0.17.2 (2026-06-17)

### 🔺 Fix

- **ui**: Dashboard UX + a11y polish and failure-branch test coverage
  ([#222](https://github.com/jtn0123/VoltTracker/pull/222),
  [`f070c3d`](https://github.com/jtn0123/VoltTracker/commit/f070c3d2878d22879117d217bfbdfbf8f7b75154))


## v0.17.1 (2026-06-17)

### 🔺 Fix

- **android**: Odometer range guard, charge-energy carry-forward, dead-interface cleanup
  ([#221](https://github.com/jtn0123/VoltTracker/pull/221),
  [`76166b6`](https://github.com/jtn0123/VoltTracker/commit/76166b6f8d68f2726997998f16bf7a24318e7f88))

### 🔷 Changed

- **tooling**: Fix stale docs, align CI node 22, harden gradle wrapper
  ([#220](https://github.com/jtn0123/VoltTracker/pull/220),
  [`a80e257`](https://github.com/jtn0123/VoltTracker/commit/a80e2574e1cf78a93cb1ef2baed5bf90640001bf))

### 🔷 Changed

- **adr**: Record encrypted-backup format and key derivation
  ([#219](https://github.com/jtn0123/VoltTracker/pull/219),
  [`ab41f82`](https://github.com/jtn0123/VoltTracker/commit/ab41f82957cb215f34029515bfbf22973101a245))


## v0.17.0 (2026-06-16)

### ✳️ New

- **ux,a11y,docs**: Execute the B-grade Frontend + Docs findings
  ([#218](https://github.com/jtn0123/VoltTracker/pull/218),
  [`73577c4`](https://github.com/jtn0123/VoltTracker/commit/73577c45f76c88770693254babafc68b3aa7b764))


## v0.16.3 (2026-06-16)

### 🔺 Fix

- **ui**: End-user accessibility and clarity polish
  ([#217](https://github.com/jtn0123/VoltTracker/pull/217),
  [`54149f3`](https://github.com/jtn0123/VoltTracker/commit/54149f3802b4a9ea0da74bc28507e587e346c354))

### 🔷 Changed

- De-complicate hot paths + fix edge-case bugs (26 files)
  ([#216](https://github.com/jtn0123/VoltTracker/pull/216),
  [`4669d3d`](https://github.com/jtn0123/VoltTracker/commit/4669d3d71c30d5f6eec54b8f840b3af89de880bb))


## v0.16.2 (2026-06-16)

### 🔺 Fix

- **obd**: Parser correctness fixes in ObdProtocol/ObdElmDecode
  ([#215](https://github.com/jtn0123/VoltTracker/pull/215),
  [`f532899`](https://github.com/jtn0123/VoltTracker/commit/f5328990df102378fe8150997e0217a85b4b8c71))


## v0.16.1 (2026-06-16)

### 🔺 Fix

- Deeper edge-case bug-fix pass (18 files) ([#214](https://github.com/jtn0123/VoltTracker/pull/214),
  [`a40bb70`](https://github.com/jtn0123/VoltTracker/commit/a40bb701ec35a5701f88227e8a3ed7746a8828c6))

### 🔷 Changed

- Simplify, polish, and debug ~90 small areas across the app
  ([#213](https://github.com/jtn0123/VoltTracker/pull/213),
  [`fac0f8b`](https://github.com/jtn0123/VoltTracker/commit/fac0f8b792bb9d14e51c2ef0919e25b630a301f8))


## v0.16.0 (2026-06-15)

### ✳️ New

- Grade follow-ups — bug fixes, features, tests, docs, devex
  ([#212](https://github.com/jtn0123/VoltTracker/pull/212),
  [`0b7f9b3`](https://github.com/jtn0123/VoltTracker/commit/0b7f9b38ebdb869a884db700c8178daaa09a1473))


## v0.15.0 (2026-06-15)

### ✳️ New

- Grade-audit fixes, tests, and 10 end-user features
  ([#211](https://github.com/jtn0123/VoltTracker/pull/211),
  [`06043c2`](https://github.com/jtn0123/VoltTracker/commit/06043c210da9e8b5adeef9e7ce8a466f211a9fc2))


## v0.14.0 (2026-06-15)

### ✳️ New

- **diagnostics**: Add self-selecting, budget-bounded diagnostics digest
  ([#210](https://github.com/jtn0123/VoltTracker/pull/210),
  [`b385180`](https://github.com/jtn0123/VoltTracker/commit/b3851803a68490322154baac84306f3b8e30e9c4))


## v0.13.1 (2026-06-14)

### 🔺 Fix

- **ui**: Remove WebView cold-start flash, show connect spinner, announce map empty state
  ([#209](https://github.com/jtn0123/VoltTracker/pull/209),
  [`50fcfc9`](https://github.com/jtn0123/VoltTracker/commit/50fcfc9f2373bd22e9eac51fb669bae6e8ddede3))

### 🔷 Changed

- Polish repo docs, GitHub templates, and stale config cleanup
  ([#189](https://github.com/jtn0123/VoltTracker/pull/189),
  [`ab70618`](https://github.com/jtn0123/VoltTracker/commit/ab70618f31f6b2e32a094a2d1864bbe3f642d9ef))

- **deps**: Bump androidx.core to 1.19.0 + raise compileSdk to 37
  ([#208](https://github.com/jtn0123/VoltTracker/pull/208),
  [`f88076d`](https://github.com/jtn0123/VoltTracker/commit/f88076d7648c6f8a0de5764525c19804a2deda31))

- **deps-dev**: Batch low-risk dev-dependency bumps
  ([#206](https://github.com/jtn0123/VoltTracker/pull/206),
  [`f561154`](https://github.com/jtn0123/VoltTracker/commit/f56115435c9a0d24a16e47c7f3cd6442993931bf))

### 🔷 Changed

- Bump actions/checkout to v6.0.3 and osv-scanner-action to v2.3.8
  ([#207](https://github.com/jtn0123/VoltTracker/pull/207),
  [`eee616a`](https://github.com/jtn0123/VoltTracker/commit/eee616a79214fa23593233367e89dda34053eb77))


## v0.13.0 (2026-06-13)

### 🔺 Fix

- **release**: Clear esbuild audit advisory + #199 CI regressions
  ([#205](https://github.com/jtn0123/VoltTracker/pull/205),
  [`433230f`](https://github.com/jtn0123/VoltTracker/commit/433230fcd450f0853856b2e5969519cbc6693a7b))

### ✳️ New

- **dashboard**: Live-map follow + direction, live-signals + battery views, charge over-count fix,
  WebView lifecycle ([#199](https://github.com/jtn0123/VoltTracker/pull/199),
  [`ddc6c6f`](https://github.com/jtn0123/VoltTracker/commit/ddc6c6f5d532bed4b4663c8d459038b5cd548363))


## v0.12.1 (2026-06-12)

### 🔺 Fix

- Implement the codebase-eval polish items across data, dashboard, app, and CI
  ([#198](https://github.com/jtn0123/VoltTracker/pull/198),
  [`47497c5`](https://github.com/jtn0123/VoltTracker/commit/47497c5fca27c33ce4da02233f36555360588910))


## v0.12.0 (2026-06-12)

### ✳️ New

- Implement the 20-point polish plan across app, data, dashboard, and CI
  ([#197](https://github.com/jtn0123/VoltTracker/pull/197),
  [`5df915c`](https://github.com/jtn0123/VoltTracker/commit/5df915c2d2ef581c144e0927f2a63ea1f12704bc))


## v0.11.5 (2026-06-12)

### 🔺 Fix

- Implement top-10 polish items from the app-wide review
  ([#196](https://github.com/jtn0123/VoltTracker/pull/196),
  [`7197002`](https://github.com/jtn0123/VoltTracker/commit/719700285d5b9fa2c30f62c9f485068c8fabb280))


## v0.11.4 (2026-06-12)

### 🔺 Fix

- Dashboard state consistency, DTC dialog dismiss, and quieter failure logging
  ([#195](https://github.com/jtn0123/VoltTracker/pull/195),
  [`a4f947b`](https://github.com/jtn0123/VoltTracker/commit/a4f947bb106840ae15dd598ae78e484526585641))


## v0.11.3 (2026-06-11)

### 🔺 Fix

- Restore determinate progress bar and weeks-deep map history
  ([#194](https://github.com/jtn0123/VoltTracker/pull/194),
  [`5e026e6`](https://github.com/jtn0123/VoltTracker/commit/5e026e62f35a1a612f110dbee8527e7789bc9087))


## v0.11.2 (2026-06-11)

### 🔺 Fix

- **dashboard**: Stop Drive tab cards overflowing the viewport width
  ([#193](https://github.com/jtn0123/VoltTracker/pull/193),
  [`d511d13`](https://github.com/jtn0123/VoltTracker/commit/d511d13f129ffa61a68e400e8ed3791708a12142))


## v0.11.1 (2026-06-11)

### 🔺 Fix

- 39 bugs across OBD protocol, data layer, services, and dashboard
  ([#192](https://github.com/jtn0123/VoltTracker/pull/192),
  [`8fc5386`](https://github.com/jtn0123/VoltTracker/commit/8fc538667674e08bd20ef8c2701f1adb0e68ce82))


## v0.11.0 (2026-06-10)

### ✳️ New

- **dashboard**: 17 UI/UX improvements across tabs
  ([#191](https://github.com/jtn0123/VoltTracker/pull/191),
  [`bc9d797`](https://github.com/jtn0123/VoltTracker/commit/bc9d7973cee685cdbfdb4ce86d5b7bb161444f30))


## v0.10.2 (2026-06-10)

### 🔺 Fix

- **android**: Bluetooth permission feedback, auto-resume, and status badge popover
  ([#190](https://github.com/jtn0123/VoltTracker/pull/190),
  [`707a393`](https://github.com/jtn0123/VoltTracker/commit/707a3937f42e04fa325bbf51c76aef720bcf32b4))


## v0.10.1 (2026-06-10)

### 🔺 Fix

- **android**: Restore progress, map cleanup, charge inference
  ([#188](https://github.com/jtn0123/VoltTracker/pull/188),
  [`c64e8cf`](https://github.com/jtn0123/VoltTracker/commit/c64e8cf55ba526fcc4ac9918789e97808b84e671))


## v0.10.0 (2026-06-10)

### ✳️ New

- **android**: Expand sensors and harden app flows
  ([#187](https://github.com/jtn0123/VoltTracker/pull/187),
  [`e22be71`](https://github.com/jtn0123/VoltTracker/commit/e22be71b3b2386d0504d3f63c4acdd5014774700))


## v0.9.3 (2026-06-09)

### 🔺 Fix

- **android**: Restore map data for matched backup sessions
  ([#186](https://github.com/jtn0123/VoltTracker/pull/186),
  [`4f8a676`](https://github.com/jtn0123/VoltTracker/commit/4f8a67696d0a95158a4cecfd0da40085874a7ef0))


## v0.9.2 (2026-06-09)

### 🔺 Fix

- **android**: Allow 200 MB backup restores
  ([#185](https://github.com/jtn0123/VoltTracker/pull/185),
  [`f344d0e`](https://github.com/jtn0123/VoltTracker/commit/f344d0e70ed61accf58704951e59fef97b0aa153))


## v0.9.1 (2026-06-09)

### 🔺 Fix

- **android**: Quiet offline dashboard and surface restore
  ([#184](https://github.com/jtn0123/VoltTracker/pull/184),
  [`d718b59`](https://github.com/jtn0123/VoltTracker/commit/d718b59e64055ff9117f029baa45ebed5517eec3))


## v0.9.0 (2026-06-09)

### ✳️ New

- **android**: Polish dashboard and restore feedback
  ([`1700e2f`](https://github.com/jtn0123/VoltTracker/commit/1700e2f46d34d39346ad71a5a7cfbdfdcb9d095e))


## v0.8.1 (2026-06-08)

### 🔺 Fix

- **android**: Harden release flow and auto-connect UX
  ([`5066fa9`](https://github.com/jtn0123/VoltTracker/commit/5066fa924bd604351cba2fe475433300059a5ff0))

- **release**: Install Playwright before preflight
  ([`f00cb54`](https://github.com/jtn0123/VoltTracker/commit/f00cb542036af4cc1114ea6c53884be5bffcf520))

- **release**: Serialize release preflight verification
  ([`b9ef6a2`](https://github.com/jtn0123/VoltTracker/commit/b9ef6a2d5d8d0ef089c955fde8c00b8ce65c0d2b))

### 🔷 Changed

- **release**: Repair tagged APK publishing
  ([`d134552`](https://github.com/jtn0123/VoltTracker/commit/d134552627af01c049e486274c21836a384ec758))


## v0.8.0 (2026-06-08)

### ✳️ New

- **android**: Ship VoltTracker 0.8.0 release
  ([`db8f3fe`](https://github.com/jtn0123/VoltTracker/commit/db8f3feb361c42c9a3414751868a244853faa6cd))


## v0.7.0 (2026-06-04)

### ✳️ New

- Add enhanced signal discovery workspace ([#168](https://github.com/jtn0123/VoltTracker/pull/168),
  [`b00f91c`](https://github.com/jtn0123/VoltTracker/commit/b00f91c7d55c280166b3a0aa14db621aac8d3147))


## v0.6.2 (2026-06-03)

### 🔺 Fix

- Split long obd sessions into drive windows
  ([#167](https://github.com/jtn0123/VoltTracker/pull/167),
  [`b525413`](https://github.com/jtn0123/VoltTracker/commit/b5254138da11b94bb29ec5d135388f73ca93edc6))

### 🔷 Changed

- **deps**: Bump softprops/action-gh-release from 2.6.2 to 3.0.0
  ([`dd4d327`](https://github.com/jtn0123/VoltTracker/commit/dd4d327f56ead55a04ac932f04196ab48a98edf6))

- **deps-dev**: Bump eslint in /mobile/android/dashboard-tests
  ([`d239534`](https://github.com/jtn0123/VoltTracker/commit/d239534bf4f97a8bd2052174e86486151077a1bf))

### 🔷 Changed

- **android**: Add Drive live-canvas functional e2e
  ([#165](https://github.com/jtn0123/VoltTracker/pull/165),
  [`6edde07`](https://github.com/jtn0123/VoltTracker/commit/6edde0769554da4fa5d130a6e3611a1d60aa368e))

- **android**: Add Playwright visual-regression baselines (advisory)
  ([#164](https://github.com/jtn0123/VoltTracker/pull/164),
  [`cdc32fa`](https://github.com/jtn0123/VoltTracker/commit/cdc32fada3c07904537413481e935559bfefcc91))

- **android**: Cover the native Replace/Merge/Cancel restore dialog
  ([#163](https://github.com/jtn0123/VoltTracker/pull/163),
  [`aefe103`](https://github.com/jtn0123/VoltTracker/commit/aefe1032fc9c68e8fe660e84b41ba931fe83d660))


## v0.6.1 (2026-06-02)

### 🔺 Fix

- **android**: Drop background tasks submitted after executor shutdown
  ([#162](https://github.com/jtn0123/VoltTracker/pull/162),
  [`b68dfea`](https://github.com/jtn0123/VoltTracker/commit/b68dfeab4c41e1831c1d6185f1755efe8cc72c86))

### 🔷 Changed

- **android**: Add Playwright dashboard e2e suite + CI gate
  ([#160](https://github.com/jtn0123/VoltTracker/pull/160),
  [`1671bbe`](https://github.com/jtn0123/VoltTracker/commit/1671bbe66f49b0412d6c252f0e21327f55927517))

- **android**: Broaden Playwright e2e to Map/Charge/Insights + interactions
  ([#161](https://github.com/jtn0123/VoltTracker/pull/161),
  [`e43fb12`](https://github.com/jtn0123/VoltTracker/commit/e43fb120a0d678f319389e2e9e6bcac87e133cb0))


## v0.6.0 (2026-06-02)

### 🔷 Changed

- Remove stale Codex scratch reports from the repo
  ([#158](https://github.com/jtn0123/VoltTracker/pull/158),
  [`212bd3c`](https://github.com/jtn0123/VoltTracker/commit/212bd3cb4865a45c144d5d255e3990e2d6bf1e36))

### ✳️ New

- **android**: Merge older backups + Trips/header/demo UI overhaul
  ([#159](https://github.com/jtn0123/VoltTracker/pull/159),
  [`9ee8f49`](https://github.com/jtn0123/VoltTracker/commit/9ee8f49e32e50be0783d47386397afdc8320dad9))


## v0.5.0 (2026-06-02)

### ✳️ New

- **android**: Merge a backup into the live database instead of only replacing
  ([#157](https://github.com/jtn0123/VoltTracker/pull/157),
  [`4f03af3`](https://github.com/jtn0123/VoltTracker/commit/4f03af319484d906c185ffc7785cd7a12ef31eb6))


## v0.4.12 (2026-06-02)

### 🔺 Fix

- **android**: Round-7 grade-report remediation — 20 items via 4 parallel agents
  ([#156](https://github.com/jtn0123/VoltTracker/pull/156),
  [`431fe93`](https://github.com/jtn0123/VoltTracker/commit/431fe93847108050787929d85270bfd41cf6c79e))

### 🔷 Changed

- **android**: Round-7 perf/polish (config cache, ts-check, schema split)
  ([#155](https://github.com/jtn0123/VoltTracker/pull/155),
  [`72effa7`](https://github.com/jtn0123/VoltTracker/commit/72effa7bc3079e4732aa951f2d0e825b5e08258f))


## v0.4.11 (2026-06-01)

### 🔷 Changed

- **android**: Round-6 grade-report remediation
  ([#152](https://github.com/jtn0123/VoltTracker/pull/152),
  [`8121db6`](https://github.com/jtn0123/VoltTracker/commit/8121db60b5b0ef9c2fcaca92e69471ab9af39403))


## v0.4.10 (2026-05-29)

### 🔺 Fix

- **android**: Load dashboard JS as classic scripts (modules dead over file://)
  ([#151](https://github.com/jtn0123/VoltTracker/pull/151),
  [`a5e882b`](https://github.com/jtn0123/VoltTracker/commit/a5e882bfbf135411957b1d77a68d4c652217a592))


## v0.4.9 (2026-05-29)

### 🔺 Fix

- **android**: Inset WebView by system bars so bottom-nav is tappable
  ([#150](https://github.com/jtn0123/VoltTracker/pull/150),
  [`b9138f8`](https://github.com/jtn0123/VoltTracker/commit/b9138f8c3b188b413a9409cdc057967661bcde4c))


## v0.4.8 (2026-05-29)

### 🔺 Fix

- **android**: Polish dashboard drive and trips UX
  ([#148](https://github.com/jtn0123/VoltTracker/pull/148),
  [`a7faaf1`](https://github.com/jtn0123/VoltTracker/commit/a7faaf14c4bb281f6251f45017bbf8171ecb9cd4))


## v0.4.7 (2026-05-28)

### 🔺 Fix

- **android**: Wait for dashboard bridge readiness
  ([#147](https://github.com/jtn0123/VoltTracker/pull/147),
  [`47f6489`](https://github.com/jtn0123/VoltTracker/commit/47f64893c3813daee462935069e372f1022c2e9d))


## v0.4.6 (2026-05-28)

### 🔺 Fix

- **android**: Publish release and debug APKs
  ([#145](https://github.com/jtn0123/VoltTracker/pull/145),
  [`0c01614`](https://github.com/jtn0123/VoltTracker/commit/0c01614db8cf6defeb919d07447dbb812cc640a8))

- **release**: Repair two-apk release contract
  ([#146](https://github.com/jtn0123/VoltTracker/pull/146),
  [`c759b99`](https://github.com/jtn0123/VoltTracker/commit/c759b99fa87c31fb8f702cee5727ab3b4ae2d6f2))


## v0.4.5 (2026-05-28)

### 🔺 Fix

- **android**: Finish grade remediation follow-up
  ([#144](https://github.com/jtn0123/VoltTracker/pull/144),
  [`ac030cc`](https://github.com/jtn0123/VoltTracker/commit/ac030ccb3eb77d591d4cd31aa895a064f2e0ec20))


## v0.4.4 (2026-05-27)

### 🔺 Fix

- **android**: Resolve grade audit findings
  ([#138](https://github.com/jtn0123/VoltTracker/pull/138),
  [`07157ce`](https://github.com/jtn0123/VoltTracker/commit/07157cee2d761d566afe1d1856480c44e9301e5b))


## v0.4.3 (2026-05-27)

### 🔺 Fix

- **android**: Resolve bug-hunt findings and tooling drift
  ([`4b6787c`](https://github.com/jtn0123/VoltTracker/commit/4b6787ceb50aca5ca3a7669b3cb049312566f62c))


## v0.4.2 (2026-05-27)

### 🔺 Fix

- **android**: Resolve validated obd backup and dashboard bugs
  ([#136](https://github.com/jtn0123/VoltTracker/pull/136),
  [`e1bceee`](https://github.com/jtn0123/VoltTracker/commit/e1bceee557fed89f5b1f4e47a0793a1e212e2410))


## v0.4.1 (2026-05-26)

### 🔺 Fix

- Address 28 dogfood-audit findings (charge materializer, classifier sign, dashboard XSS, ELM races,
  …) ([#135](https://github.com/jtn0123/VoltTracker/pull/135),
  [`7793f09`](https://github.com/jtn0123/VoltTracker/commit/7793f092735a7d972beddc760390d3232d26bfe6))

### 🔷 Changed

- Rebuild rolling debug APK after each semantic-release bump
  ([#134](https://github.com/jtn0123/VoltTracker/pull/134),
  [`ee6e81a`](https://github.com/jtn0123/VoltTracker/commit/ee6e81adef30e3d7e702c12ab30b5d7e86d24ef6))


## v0.4.0 (2026-05-26)

### 🔷 Changed

- Execute round-6 grade-codebase items (B4 E2 G1 D1 H1 H2 C7 C8 C9 C10 B7 B8 H3 A2 A1)
  ([#132](https://github.com/jtn0123/VoltTracker/pull/132),
  [`15a1bcf`](https://github.com/jtn0123/VoltTracker/commit/15a1bcff39312c8b000bf984d0f9fbc34dd2b1d7))

### ✳️ New

- **dashboard**: Show app version in Settings and stop truncating long-drive maps
  ([#133](https://github.com/jtn0123/VoltTracker/pull/133),
  [`d4cbd8d`](https://github.com/jtn0123/VoltTracker/commit/d4cbd8d75ea23e40bb487918ddd9e67ffd5ba897))


## v0.3.0 (2026-05-26)

### ✳️ New

- **obd**: Classify connection failures + observability + dashboard troubleshooter
  ([#131](https://github.com/jtn0123/VoltTracker/pull/131),
  [`e5cf686`](https://github.com/jtn0123/VoltTracker/commit/e5cf6865df7ee7aa132afb076986850775cddbd4))


## v0.2.1 (2026-05-24)

### 🔺 Fix

- **obd**: Accel-pedal PID, raw HV pack columns, real trip energy & classification, smarter charge
  detection ([#130](https://github.com/jtn0123/VoltTracker/pull/130),
  [`5f31cfb`](https://github.com/jtn0123/VoltTracker/commit/5f31cfbdd8ac4412d408fe31ec39fef7eeb99d2e))


## v0.2.0 (2026-05-24)

### ✳️ New

- **release**: Sign tagged APKs with keystore decoded from CI secrets
  ([#129](https://github.com/jtn0123/VoltTracker/pull/129),
  [`7d92d57`](https://github.com/jtn0123/VoltTracker/commit/7d92d570c6fbfaca4fb0b5a30c8459728a544956))


## v0.1.1 (2026-05-24)

### 🔺 Fix

- **release**: Preserve config comments and reset initial CHANGELOG
  ([#128](https://github.com/jtn0123/VoltTracker/pull/128),
  [`1e24202`](https://github.com/jtn0123/VoltTracker/commit/1e242026d2c077c7d2f355ce88283de61d0e5080))


## v0.1.0 (2026-05-24)

### 🔺 Fix

- 23 bugs from audit pass 3 ([#35](https://github.com/jtn0123/VoltTracker/pull/35),
  [`c8f663f`](https://github.com/jtn0123/VoltTracker/commit/c8f663fb28a0ad2a4327fec69fbe7598f1a2e960))

- 32 bugs and UI inconsistencies from deep audit
  ([#33](https://github.com/jtn0123/VoltTracker/pull/33),
  [`8e9bd62`](https://github.com/jtn0123/VoltTracker/commit/8e9bd62e7dbbef718c643f5914e7550a0be3e0f4))

- Audit pass 2 — bug fixes ([#34](https://github.com/jtn0123/VoltTracker/pull/34),
  [`7018244`](https://github.com/jtn0123/VoltTracker/commit/7018244ceede24c849a8aedfa5b24b312c86e1b3))

- Bug fixes, structured logging, performance optimization, and reliability hardening
  ([#27](https://github.com/jtn0123/VoltTracker/pull/27),
  [`9206fad`](https://github.com/jtn0123/VoltTracker/commit/9206fade2ee4f56309ea7ca69237723e098bfc46))

- Bump Flask-HTTPAuth to 5.1.0 (CVE fix for empty token verification)
  ([`216d41f`](https://github.com/jtn0123/VoltTracker/commit/216d41f548395813333194072d39d965ac8fce1a))

- Bump requests to 2.33.0 (CVE fix for insecure temp file reuse)
  ([`71f15a0`](https://github.com/jtn0123/VoltTracker/commit/71f15a0825ac41e5b7baefe9a71a1478b1a877ad))

- Bump sonarsource/sonarqube-scan-action v5 → v7 (CVE fix)
  ([`b27b0fe`](https://github.com/jtn0123/VoltTracker/commit/b27b0fe2ae8dc277942a70c16be082a34cc7bdd9))

- Dogfood polish pass — favicon, map filter validation, empty state dedup
  ([#77](https://github.com/jtn0123/VoltTracker/pull/77),
  [`374555f`](https://github.com/jtn0123/VoltTracker/commit/374555f3f2624c37a52cb3916412610c9651639a))

- Enhance CSV import timestamp parsing
  ([`705019f`](https://github.com/jtn0123/VoltTracker/commit/705019f0da1ff9cb3b3b428f0547032c098558de))

- High-priority security, bugs, and performance from audit
  ([#29](https://github.com/jtn0123/VoltTracker/pull/29),
  [`12e2cd3`](https://github.com/jtn0123/VoltTracker/commit/12e2cd3ca24fdf0409ad572c15f2928c9ff2d4f4))

- Improve CSV import error handling and logging
  ([`15919e8`](https://github.com/jtn0123/VoltTracker/commit/15919e88958535a316d4d19cadf71ba3da9a19ea))

- Improve timezone handling consistency
  ([`a630ef3`](https://github.com/jtn0123/VoltTracker/commit/a630ef3f5de940e8d45ea52e50ce32d0a14ba6a7))

- Pin Flask-HTTPAuth to 4.8.1 (5.1.0 doesn't exist on PyPI)
  ([#66](https://github.com/jtn0123/VoltTracker/pull/66),
  [`ab0dfa0`](https://github.com/jtn0123/VoltTracker/commit/ab0dfa0da8c39faf96073f2c001f3ec519a16c5a))

- Resolve all CI failures on main — E2E env, frontend tests, PG compat, mypy
  ([#38](https://github.com/jtn0123/VoltTracker/pull/38),
  [`1b28b67`](https://github.com/jtn0123/VoltTracker/commit/1b28b674b8efcfecbeabe1c608b9c84cfdcf56c6))

- Resolve remaining SonarQube issues ([#45](https://github.com/jtn0123/VoltTracker/pull/45),
  [`d5887de`](https://github.com/jtn0123/VoltTracker/commit/d5887de37fc9f6eaa772cb363e2caf50b05fe167))

- Toast notification dedup + version display ([#26](https://github.com/jtn0123/VoltTracker/pull/26),
  [`5b32b77`](https://github.com/jtn0123/VoltTracker/commit/5b32b77e31611f64cbb06beb043233e3d07a5ba7))

- Upgrade vite to latest + npm audit fix across frontend and e2e
  ([`ca93e56`](https://github.com/jtn0123/VoltTracker/commit/ca93e56025a16ca5e0048f34ee2a84193d1819e6))

- Websocket auth + DEBUG opt-in + CSS modularization
  ([#14](https://github.com/jtn0123/VoltTracker/pull/14),
  [`6ac6e2c`](https://github.com/jtn0123/VoltTracker/commit/6ac6e2c40caf144175fa27227bbd0aa8c5b8fcca))

- **backend**: Resolve 40 bugs found in backend audit
  ([`aeb3727`](https://github.com/jtn0123/VoltTracker/commit/aeb3727d6d24eb3db20393ac9ef1a9696da34ed6))

- **charging**: Wire up Add Session button and form submit (JTN-484, JTN-485)
  ([#74](https://github.com/jtn0123/VoltTracker/pull/74),
  [`9eb7d78`](https://github.com/jtn0123/VoltTracker/commit/9eb7d789aed8faa9a31d4ac53ba4fcf6e93a231c))

- **frontend**: Add id to import section so lazy observer actually fires (JTN-492)
  ([#79](https://github.com/jtn0123/VoltTracker/pull/79),
  [`08c2703`](https://github.com/jtn0123/VoltTracker/commit/08c27039d840e2f9224f1db9f1ad5cab27f72786))

- **frontend**: Csv import preventDefault must run synchronously (JTN-486)
  ([#75](https://github.com/jtn0123/VoltTracker/pull/75),
  [`c0d5655`](https://github.com/jtn0123/VoltTracker/commit/c0d56552b67f0d0dc9c80f8d0fc49d1c1fcd4df8))

- **frontend**: Eagerly fetch card subtitles (JTN-487)
  ([#78](https://github.com/jtn0123/VoltTracker/pull/78),
  [`00cb206`](https://github.com/jtn0123/VoltTracker/commit/00cb2064d17722c9ae5807bd7e173fdc70922dae))

- **frontend**: Key dashboard lazy-load observer on #soc-section (JTN-483)
  ([#76](https://github.com/jtn0123/VoltTracker/pull/76),
  [`ab8ad55`](https://github.com/jtn0123/VoltTracker/commit/ab8ad559096961b0901775f09f76d699ac00ca82))

- **jobs**: Repair latent ImportError in weather_jobs.fetch_weather_for_trip
  ([#67](https://github.com/jtn0123/VoltTracker/pull/67),
  [`06dfac4`](https://github.com/jtn0123/VoltTracker/commit/06dfac4bc16017d2e5af8b48f2164483fa3a5355))

- **obd**: Correct session status, strip ELM noise, poll HV pack, speed initial connect
  ([#105](https://github.com/jtn0123/VoltTracker/pull/105),
  [`4a6125f`](https://github.com/jtn0123/VoltTracker/commit/4a6125ff62137f0dc62fc56e8f94a1b15c870b13))

- **receiver**: Move APP_VERSION to dedicated module (JTN-482)
  ([#73](https://github.com/jtn0123/VoltTracker/pull/73),
  [`e8fe8a3`](https://github.com/jtn0123/VoltTracker/commit/e8fe8a35e4454af00494e2aca9a339932c336331))

- **socketio**: Disable manage_session to stop POST 400 flood (JTN-488)
  ([#80](https://github.com/jtn0123/VoltTracker/pull/80),
  [`7c5e835`](https://github.com/jtn0123/VoltTracker/commit/7c5e83510d7da8fa46034b7ab410daec4de7ce36))

### 🔷 Changed

- Enforce LF line endings for shell scripts
  ([`12363fd`](https://github.com/jtn0123/VoltTracker/commit/12363fdfa35dc70b3b28685a83b7319ed2289118))

- Execute all 30 items from round-2 grade-codebase audit
  ([#118](https://github.com/jtn0123/VoltTracker/pull/118),
  [`3af41be`](https://github.com/jtn0123/VoltTracker/commit/3af41be3d5d024a5a393a7109be8f711c2e6cf62))

- Execute all 38 items from grade-codebase audit
  ([#107](https://github.com/jtn0123/VoltTracker/pull/107),
  [`72da7e8`](https://github.com/jtn0123/VoltTracker/commit/72da7e8853074c0c450cc619341957bae6d5329e))

- Execute top-9 from round-4 grade-codebase audit + B6 tiered polling
  ([#125](https://github.com/jtn0123/VoltTracker/pull/125),
  [`843c686`](https://github.com/jtn0123/VoltTracker/commit/843c68649f1afe56527732ae5d9220ef22c24e69))

- **ci**: Fix pre-existing infra failures hitting every PR
  ([#71](https://github.com/jtn0123/VoltTracker/pull/71),
  [`dae543b`](https://github.com/jtn0123/VoltTracker/commit/dae543b05fa2d22560de09f5f57acea8b8390186))

- **deps**: Bump actions/setup-java from 4.8.0 to 5.2.0
  ([#108](https://github.com/jtn0123/VoltTracker/pull/108),
  [`df5fc34`](https://github.com/jtn0123/VoltTracker/commit/df5fc34970214137e515a013841e6641cf56007d))

- **deps**: Bump actions/upload-artifact from 4.6.2 to 7.0.1
  ([#110](https://github.com/jtn0123/VoltTracker/pull/110),
  [`d01bc03`](https://github.com/jtn0123/VoltTracker/commit/d01bc035be2bad38dd274ef63be6c4918fc787b3))

- **deps**: Bump androidx.core:core ([#109](https://github.com/jtn0123/VoltTracker/pull/109),
  [`577e8f4`](https://github.com/jtn0123/VoltTracker/commit/577e8f491fe03f5a86cc56a2b109ad96c8e4cbcf))

- **deps**: Bump com.diffplug.spotless in /mobile/android
  ([#117](https://github.com/jtn0123/VoltTracker/pull/117),
  [`5479ba6`](https://github.com/jtn0123/VoltTracker/commit/5479ba6d239d48550823f4722d9963523aba37b1))

- **deps**: Bump the test-deps group across 1 directory with 2 updates
  ([#111](https://github.com/jtn0123/VoltTracker/pull/111),
  [`cd726dd`](https://github.com/jtn0123/VoltTracker/commit/cd726ddbc865c406687e214b7df569a38dc0bc1e))

- **deps**: Upgrade backend dependencies and fix CVE-2026-28684
  ([`6f5c081`](https://github.com/jtn0123/VoltTracker/commit/6f5c081b831c3adb448735444a80ee7109ef8b16))

- **deps-dev**: Bump vitest in /mobile/android/dashboard-tests
  ([#112](https://github.com/jtn0123/VoltTracker/pull/112),
  [`0390a56`](https://github.com/jtn0123/VoltTracker/commit/0390a56943cfffe0bfa627988145caab3200ad72))

- **tests**: Delete dead test_api_integration.py placeholder suite
  ([#68](https://github.com/jtn0123/VoltTracker/pull/68),
  [`97de5d2`](https://github.com/jtn0123/VoltTracker/commit/97de5d2e358f00e2e293bcc2d6825d0d3eb062d1))

### 🔷 Changed

- Add CodeQL code scanning workflow
  ([`a595fc4`](https://github.com/jtn0123/VoltTracker/commit/a595fc48b52276489807d39f41d9985ab3513216))

- Add dependabot configuration for automated dependency updates
  ([`9f9fcc7`](https://github.com/jtn0123/VoltTracker/commit/9f9fcc78be553addbdc057ce8b36e3c6dab8d572))

- Add python 3.13 to CI matrix and bump deps that lack 3.13 wheels
  ([#69](https://github.com/jtn0123/VoltTracker/pull/69),
  [`6455f7a`](https://github.com/jtn0123/VoltTracker/commit/6455f7ab7765e0ebb0c0340db6466d0f169fd9c2))

- Add SonarQube workflow ([#39](https://github.com/jtn0123/VoltTracker/pull/39),
  [`465edad`](https://github.com/jtn0123/VoltTracker/commit/465edad27bd86e32b5e5c26db3606ef896f0964b))

- Pr APK + SDK session hook; bump Gradle 9, AGP 9, jsdom 29
  ([#121](https://github.com/jtn0123/VoltTracker/pull/121),
  [`0064162`](https://github.com/jtn0123/VoltTracker/commit/0064162b17f6182ecd9fc38c4f6d498f7f24fc56))

- Publish main-branch debug APK to rolling 'latest-debug' release
  ([#122](https://github.com/jtn0123/VoltTracker/pull/122),
  [`012ac94`](https://github.com/jtn0123/VoltTracker/commit/012ac94872fb08f35e47e6b3c047799cece1647c))

- Switch all jobs to self-hosted runners ([#46](https://github.com/jtn0123/VoltTracker/pull/46),
  [`2c3ee90`](https://github.com/jtn0123/VoltTracker/commit/2c3ee90245378c14d0fc8fb0df6ef8e65730f0d8))

- **tests**: Run concurrency + transaction tests in postgres CI job
  ([#70](https://github.com/jtn0123/VoltTracker/pull/70),
  [`813881c`](https://github.com/jtn0123/VoltTracker/commit/813881c4146830a11123b3410dfc64fc3c5ef95d))

### 🔷 Changed

- Align AGENTS.md with Android pivot; fix gradlew exec bit
  ([#104](https://github.com/jtn0123/VoltTracker/pull/104),
  [`d85a5dc`](https://github.com/jtn0123/VoltTracker/commit/d85a5dc7e5c7a4f9ce15e2208102a14f1c366ac7))

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
  [`92a54a6`](https://github.com/jtn0123/VoltTracker/commit/92a54a62870731b1004e3e463ce4be689fb731c0))

- Gps quality, map legends, RDP subsampling + import bug fixes
  ([#65](https://github.com/jtn0123/VoltTracker/pull/65),
  [`46ef09a`](https://github.com/jtn0123/VoltTracker/commit/46ef09a7033a0d5a9c7030c1804c0b8be139451d))

- Loading skeletons, frontend CI/tests, map coverage, Docker hardening, PWA icons
  ([#32](https://github.com/jtn0123/VoltTracker/pull/32),
  [`6223931`](https://github.com/jtn0123/VoltTracker/commit/62239314926272302b47005058850f7f04ef2c2b))

- Redesign theme with modern aesthetic ([#22](https://github.com/jtn0123/VoltTracker/pull/22),
  [`0e96180`](https://github.com/jtn0123/VoltTracker/commit/0e96180efc19de8305376d72d5aa443a001a7a2e))

- **ci**: Per-build version metadata + semantic-release for tagged APKs
  ([#127](https://github.com/jtn0123/VoltTracker/pull/127),
  [`d2eefaa`](https://github.com/jtn0123/VoltTracker/commit/d2eefaac555135a6423e5d40504733104740363a))

### 🔷 Changed

- Migrate remaining modules to api() wrapper ([#19](https://github.com/jtn0123/VoltTracker/pull/19),
  [`83d8a00`](https://github.com/jtn0123/VoltTracker/commit/83d8a0039721671d5d6a4b44f2d347c00fa16081))

- Modularize routes and enhance functionality
  ([`45e8b65`](https://github.com/jtn0123/VoltTracker/commit/45e8b65891877948a5a4b457d607bf11e095d72b))

- Reorganize app.py and improve import structure
  ([`acf6b26`](https://github.com/jtn0123/VoltTracker/commit/acf6b26d5e459a48ce590ca9cc8b6f006b8605b7))

- Split dashboard.js into ES modules ([#13](https://github.com/jtn0123/VoltTracker/pull/13),
  [`d679cc9`](https://github.com/jtn0123/VoltTracker/commit/d679cc9f3e799dcc6b369358addef0f20d289a19))

- Streamline dashboard route and remove circular import workarounds
  ([`41e635e`](https://github.com/jtn0123/VoltTracker/commit/41e635ef03625d6b605079d9433ec9bb4c22e3c4))

### 🔷 Changed

- Comprehensive testing — 195 new tests (unit, integration, E2E)
  ([#25](https://github.com/jtn0123/VoltTracker/pull/25),
  [`52172ee`](https://github.com/jtn0123/VoltTracker/commit/52172ee29ef6e031144c0bac73196710280fa182))

- **backend**: Add regression tests for the 40 audited bug fixes
  ([`55ed8e7`](https://github.com/jtn0123/VoltTracker/commit/55ed8e7e48993544daeffc19cdc06d2eea845707))

- **frontend**: Add vitest tests for 5 untested src/ modules
  ([#72](https://github.com/jtn0123/VoltTracker/pull/72),
  [`88ac8b9`](https://github.com/jtn0123/VoltTracker/commit/88ac8b9d37f17aeea8e0984d3627e53095403b66))
