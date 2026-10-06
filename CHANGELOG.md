# CHANGELOG


## v0.62.0 (2026-10-06)

### ✳️ New

- **car**: Show the odometer under the car on the Car tab
  ([#160](https://github.com/jtn0123/VoltTracker/pull/160),
  [`e424cd8`](https://github.com/jtn0123/VoltTracker/commit/e424cd844ed819e30c118e1d92bc3cfa0b31b017))


## v0.61.0 (2026-10-06)

### 🔺 Fix

- **service**: Keep the poll thread's notification text in the session log
  ([#171](https://github.com/jtn0123/VoltTracker/pull/171),
  [`feec24d`](https://github.com/jtn0123/VoltTracker/commit/feec24deb46c9afdcba9a703ab7fc95eef1a9e71))

- **swcan-tool**: Read logs only from ~/volttracker-logs, picked by name
  ([`c78dd48`](https://github.com/jtn0123/VoltTracker/commit/c78dd48093b9126af9cfe7fa8fbd0f6ca80df1c1))

### 🔷 Changed

- **dashboard**: Fund the demo stream's new SW-CAN fields in the lazy budget
  ([`d2761d5`](https://github.com/jtn0123/VoltTracker/commit/d2761d52f2652f64cd3679bd4193b61203e86c20))

### 🔷 Changed

- **swcan**: Add SW-CAN signal map and frame correlator
  ([`59ffdb2`](https://github.com/jtn0123/VoltTracker/commit/59ffdb2f53cf53e6fa972f0629a39fe280e1404f))

- **swcan**: Name frames from GM's signal list and satisfy Sonar's path checks
  ([`cf6f17d`](https://github.com/jtn0123/VoltTracker/commit/cf6f17d48df9b579f1cc88e1457973097b34181e))

### ✳️ New

- **swcan**: Read wheel speeds, trips, drive-unit oil and the car's energy screen
  ([`0c78cfe`](https://github.com/jtn0123/VoltTracker/commit/0c78cfed190a2d5b6a2ebcc93e5a278f21ce9475))

### 🔷 Changed

- **swcan**: Split the correlator's long functions and bound its file paths
  ([`653a035`](https://github.com/jtn0123/VoltTracker/commit/653a035d86402c01f2e9c5b2d8b1fc149588f406))


## v0.60.2 (2026-10-06)

### 🔺 Fix

- **tires**: Keep the tire pressures for the drive and say how old they are
  ([#163](https://github.com/jtn0123/VoltTracker/pull/163),
  [`7115a9a`](https://github.com/jtn0123/VoltTracker/commit/7115a9a9c9afb585a80571a0bb02e8a2cc6ac0ae))


## v0.60.1 (2026-10-06)

### 🔺 Fix

- Address Android app quality audit findings
  ([`3724ee9`](https://github.com/jtn0123/VoltTracker/commit/3724ee9333abcb491fdf94132e0c3a074be79fbb))

### 🔷 Changed

- **deps**: Bump source-map-js to 1.2.2 for GHSA-68fv-2mgg-jv7q
  ([#166](https://github.com/jtn0123/VoltTracker/pull/166),
  [`d893d93`](https://github.com/jtn0123/VoltTracker/commit/d893d9301455f386003bf533895b5001587c8888))

### 🔷 Changed

- Request supported SDK packages for native visual checks
  ([`f177528`](https://github.com/jtn0123/VoltTracker/commit/f1775287e9a17c97ab21567836a47ae0495402e0))

### 🔷 Changed

- Address audit quality gate feedback
  ([`34fd0f3`](https://github.com/jtn0123/VoltTracker/commit/34fd0f3c04171dc88769c45dd44f4d6119f380d9))

- Keep status and session helpers within static limits
  ([`8a5887c`](https://github.com/jtn0123/VoltTracker/commit/8a5887c76185d1090ce0ee18822fc6ec9f5fc07e))

### 🔷 Changed

- Align reviewed visual baselines with audit fixes
  ([`1d86247`](https://github.com/jtn0123/VoltTracker/commit/1d862478cdbb2461162c3a82be0fdc0aed58846c))


## v0.60.0 (2026-10-05)

### ✳️ New

- **health**: Show the pack's internal resistance on the battery card
  ([#158](https://github.com/jtn0123/VoltTracker/pull/158),
  [`7da1425`](https://github.com/jtn0123/VoltTracker/commit/7da1425edf00fb7e8872ca3ce680495dda7c4291))


## v0.59.0 (2026-10-04)

### 🔷 Changed

- **coverage**: The sim answers trouble-code modes now
  ([#155](https://github.com/jtn0123/VoltTracker/pull/155),
  [`09c5296`](https://github.com/jtn0123/VoltTracker/commit/09c52964c5e5c7727b0305a109bbf7eb28414a7e))

### ✳️ New

- **health**: Show oil and motor B temperatures in Live signals, drop rows the car never reports
  ([#156](https://github.com/jtn0123/VoltTracker/pull/156),
  [`3218ab1`](https://github.com/jtn0123/VoltTracker/commit/3218ab1836fbaf748364e9a077154285eab492ec))


## v0.58.0 (2026-10-04)

### ✳️ New

- **swcan**: Log raw frames for every listen window in debug builds
  ([#153](https://github.com/jtn0123/VoltTracker/pull/153),
  [`2334f74`](https://github.com/jtn0123/VoltTracker/commit/2334f749713b6549c63ec07ba8bcd6c61326da83))


## v0.57.6 (2026-10-03)

### 🔺 Fix

- **health**: Show a stored code as stored, and run trouble-code scans against the virtual Volt
  ([#152](https://github.com/jtn0123/VoltTracker/pull/152),
  [`7a5d3d2`](https://github.com/jtn0123/VoltTracker/commit/7a5d3d2facd5fb1d8bb7fef1c2b3aed420c4ecc5))

### 🔷 Changed

- Fix every Kotlin compiler warning instead of living with them
  ([#151](https://github.com/jtn0123/VoltTracker/pull/151),
  [`93f08b3`](https://github.com/jtn0123/VoltTracker/commit/93f08b33ef4d7a6d614713b8a44653cf5c091fb4))


## v0.57.5 (2026-10-03)

### 🔺 Fix

- **charge**: Truncate the charge ring and session SOC like every other battery figure
  ([#150](https://github.com/jtn0123/VoltTracker/pull/150),
  [`ffc0324`](https://github.com/jtn0123/VoltTracker/commit/ffc03245a9e3b0886ceff3c54dc2771e7a364b5a))

### 🔷 Changed

- Render virtual Volt telemetry through the Compose screens
  ([#149](https://github.com/jtn0123/VoltTracker/pull/149),
  [`a817d40`](https://github.com/jtn0123/VoltTracker/commit/a817d40891e6e3f9d3531d0d11f33587d5695713))


## v0.57.4 (2026-10-03)

### 🔺 Fix

- **swcan**: Read the blower from byte 2 and use real car replies in the virtual Volt
  ([#148](https://github.com/jtn0123/VoltTracker/pull/148),
  [`851d6db`](https://github.com/jtn0123/VoltTracker/commit/851d6dbb1bafae14bf8f928e05a06d7dfcd395de))

### 🔷 Changed

- Gate the car-confirmed SW-CAN and dash-SOC readings in the virtual Volt
  ([#143](https://github.com/jtn0123/VoltTracker/pull/143),
  [`bdc7b4c`](https://github.com/jtn0123/VoltTracker/commit/bdc7b4c31bb18a772eacda20d4e33b2e72ef7673))


## v0.57.3 (2026-10-03)

### 🔺 Fix

- **dashboard**: Stop rewriting the status pill text on every telemetry sample
  ([#146](https://github.com/jtn0123/VoltTracker/pull/146),
  [`f537828`](https://github.com/jtn0123/VoltTracker/commit/f537828664572763017ca1bda45dcb2e9b2c9055))

### 🔷 Changed

- Per-feature test coverage and gap matrix ([#144](https://github.com/jtn0123/VoltTracker/pull/144),
  [`ea779d6`](https://github.com/jtn0123/VoltTracker/commit/ea779d650fe40b01412a9bef9dc54d1403833900))


## v0.57.2 (2026-10-03)

### 🔺 Fix

- Stop showing a fake 100 % 12 V state of charge
  ([#142](https://github.com/jtn0123/VoltTracker/pull/142),
  [`d9f78dd`](https://github.com/jtn0123/VoltTracker/commit/d9f78ddeccae723ebf92d729dd1246b7a4c1f624))

### 🔷 Changed

- **deps**: Bump agp from 9.4.0 to 9.4.1 in /mobile/android
  ([#136](https://github.com/jtn0123/VoltTracker/pull/136),
  [`8d9d626`](https://github.com/jtn0123/VoltTracker/commit/8d9d6262e4ed94d32688f7094bdc256cde9dc320))

- **deps**: Bump com.github.ben-manes.versions in /mobile/android
  ([#137](https://github.com/jtn0123/VoltTracker/pull/137),
  [`e054efb`](https://github.com/jtn0123/VoltTracker/commit/e054efb2b3d7838b5352ff3f744b5bd9705420e8))

- **deps**: Bump gradle-wrapper from 9.7.1 to 9.8.0 in /mobile/android
  ([#134](https://github.com/jtn0123/VoltTracker/pull/134),
  [`295b639`](https://github.com/jtn0123/VoltTracker/commit/295b6398b0c32d54bdaf354e70224329a8913028))

- **deps**: Bump org.gradle.test-retry in /mobile/android
  ([#138](https://github.com/jtn0123/VoltTracker/pull/138),
  [`6d42c15`](https://github.com/jtn0123/VoltTracker/commit/6d42c15767812f2e3acb4e6b066d3f7ae6a74f0b))

- **deps**: Bump roborazzi from 1.74.0 to 1.75.0 in /mobile/android
  ([#135](https://github.com/jtn0123/VoltTracker/pull/135),
  [`256cea6`](https://github.com/jtn0123/VoltTracker/commit/256cea629eea04e05c8ec18527a264dcedf25924))

- **deps-dev**: Bump jsdom in /mobile/android/dashboard-tests
  ([#132](https://github.com/jtn0123/VoltTracker/pull/132),
  [`954b271`](https://github.com/jtn0123/VoltTracker/commit/954b271503a826c0b1881e096c89f00fdef5fbc0))

- **deps-dev**: Bump typescript-eslint ([#133](https://github.com/jtn0123/VoltTracker/pull/133),
  [`f28fc08`](https://github.com/jtn0123/VoltTracker/commit/f28fc08bc6b8024c844e6526a9178fcb7e944f27))

### 🔷 Changed

- Boot the Compose launcher in the emulator smoke and make it required again
  ([#141](https://github.com/jtn0123/VoltTracker/pull/141),
  [`1dbd2bd`](https://github.com/jtn0123/VoltTracker/commit/1dbd2bd8c322ed2e4b327794d43609c9e37f8e4a))


## v0.57.1 (2026-10-03)

### 🔺 Fix

- Clear mechanical SonarCloud TypeScript findings
  ([#123](https://github.com/jtn0123/VoltTracker/pull/123),
  [`e994b35`](https://github.com/jtn0123/VoltTracker/commit/e994b35996f534d560ef5ed4c30b9ca49cb6261a))

- Clear SonarCloud security and bug findings
  ([#122](https://github.com/jtn0123/VoltTracker/pull/122),
  [`7952555`](https://github.com/jtn0123/VoltTracker/commit/7952555aa78b0c0423227e89d9519689762253ca))

- Split a second batch of complex functions flagged by SonarCloud
  ([#129](https://github.com/jtn0123/VoltTracker/pull/129),
  [`9e10c85`](https://github.com/jtn0123/VoltTracker/commit/9e10c85f40c7bbbf60294e5d3ad882df5a9cbd0f))

- Split the most complex functions flagged by SonarCloud
  ([#124](https://github.com/jtn0123/VoltTracker/pull/124),
  [`f89bd30`](https://github.com/jtn0123/VoltTracker/commit/f89bd30e8df0a8a78134c2c8128ce39bbd84a6da))

### 🔷 Changed

- **deps**: Bump actions/setup-java from 6.0.0 to 6.0.1
  ([#59](https://github.com/jtn0123/VoltTracker/pull/59),
  [`77191d1`](https://github.com/jtn0123/VoltTracker/commit/77191d125dd704429f2835097cfc73950321f97a))

- **deps**: Bump com.diffplug.spotless from 8.10.1 to 8.10.3 in /mobile/android
  ([#54](https://github.com/jtn0123/VoltTracker/pull/54),
  [`f309c59`](https://github.com/jtn0123/VoltTracker/commit/f309c5916eb0964346f624e5fb555c0a3dd41cae))

- **deps**: Bump google/osv-scanner-action/osv-scanner-action
  ([#60](https://github.com/jtn0123/VoltTracker/pull/60),
  [`a076dec`](https://github.com/jtn0123/VoltTracker/commit/a076dec6048a3e8e94c7c005a059c00203ae3e8b))

- **deps**: Bump org.jetbrains.kotlin.plugin.compose from 2.4.10 to 2.4.20 in /mobile/android
  ([#61](https://github.com/jtn0123/VoltTracker/pull/61),
  [`f62c69d`](https://github.com/jtn0123/VoltTracker/commit/f62c69d5a7143bf239bb51c4fad794c02b0dbde1))

- **deps**: Bump org.robolectric:robolectric from 4.16.1 to 4.17 in /mobile/android in the test-deps
  group across 1 directory ([#58](https://github.com/jtn0123/VoltTracker/pull/58),
  [`41d7e16`](https://github.com/jtn0123/VoltTracker/commit/41d7e167fe6a943e15d005e9e2982b6cbcfd102b))

- **deps**: Bump roborazzi from 1.73.0 to 1.74.0 in /mobile/android
  ([#62](https://github.com/jtn0123/VoltTracker/pull/62),
  [`3f34cbf`](https://github.com/jtn0123/VoltTracker/commit/3f34cbfcae95425052771e78ed41c64e0f3fb9fc))

- **deps**: Bump the androidx group across 1 directory with 3 updates
  ([#128](https://github.com/jtn0123/VoltTracker/pull/128),
  [`ea445eb`](https://github.com/jtn0123/VoltTracker/commit/ea445eb06e6090ffc7a9400e040f548cee779ea6))

- **deps**: Bump the codeql-action group across 1 directory with 2 updates
  ([#56](https://github.com/jtn0123/VoltTracker/pull/56),
  [`e31a617`](https://github.com/jtn0123/VoltTracker/commit/e31a617117ce9125bd932717dee0f026ab37bd45))

- **deps-dev**: Bump @playwright/test in /mobile/android/dashboard-e2e
  ([#51](https://github.com/jtn0123/VoltTracker/pull/51),
  [`2f23928`](https://github.com/jtn0123/VoltTracker/commit/2f23928c2aced6276186623c0c52e21c445d3686))

- **deps-dev**: Bump eslint in /mobile/android/dashboard-tests
  ([#63](https://github.com/jtn0123/VoltTracker/pull/63),
  [`709f773`](https://github.com/jtn0123/VoltTracker/commit/709f773eabc7128fa0e3c6ff41fe2b0ada503517))

- **deps-dev**: Bump typescript-eslint ([#127](https://github.com/jtn0123/VoltTracker/pull/127),
  [`820839b`](https://github.com/jtn0123/VoltTracker/commit/820839b79fa428b3357862e0ae476ff8c8a368bb))

- **deps-dev**: Bump vitest + @vitest/coverage-istanbul to 5.0.3
  ([#130](https://github.com/jtn0123/VoltTracker/pull/130),
  [`a5c2837`](https://github.com/jtn0123/VoltTracker/commit/a5c28373c6166efa1bc5fb3717a8901fb98a0a0a))

### 🔷 Changed

- Fix silently broken release, weekly smoke and latest-debug lanes
  ([#140](https://github.com/jtn0123/VoltTracker/pull/140),
  [`28f8266`](https://github.com/jtn0123/VoltTracker/commit/28f8266b6696fcccd530e5a9e554897615ee6e17))

- Stop gating ci-success on flaky emulator-smoke
  ([#139](https://github.com/jtn0123/VoltTracker/pull/139),
  [`f8d8a3a`](https://github.com/jtn0123/VoltTracker/commit/f8d8a3aa35969bf6b69ca8c4cedb3c70f2e24297))

- **dependabot**: Group vitest with @vitest/* in dashboard-tests
  ([#131](https://github.com/jtn0123/VoltTracker/pull/131),
  [`05899cc`](https://github.com/jtn0123/VoltTracker/commit/05899cc8b22967bace6d985b3290cdba89521141))


## v0.57.0 (2026-10-01)

### ✳️ New

- Read tire pressure from the body computer
  ([#121](https://github.com/jtn0123/VoltTracker/pull/121),
  [`43ac431`](https://github.com/jtn0123/VoltTracker/commit/43ac431e441c58953dfe2ce4c98c1519bb4ca047))


## v0.56.1 (2026-10-01)

### 🔺 Fix

- Harden the app before phone install ([#120](https://github.com/jtn0123/VoltTracker/pull/120),
  [`5409026`](https://github.com/jtn0123/VoltTracker/commit/54090262125ad6ee68c69834007d02f204a52d4c))


## v0.56.0 (2026-09-30)

### ✳️ New

- **ui**: Usefulness pass — trip and charge receipts, insights in plain words, battery health trend
  ([#119](https://github.com/jtn0123/VoltTracker/pull/119),
  [`adf7971`](https://github.com/jtn0123/VoltTracker/commit/adf7971b0f7aec11e0170ab2bbce0ab5e5f553a0))


## v0.55.0 (2026-09-30)

### ✳️ New

- **ui**: Ux pass — one connection status everywhere, a way out of every empty screen, pull to
  refresh ([#118](https://github.com/jtn0123/VoltTracker/pull/118),
  [`c9b3b1e`](https://github.com/jtn0123/VoltTracker/commit/c9b3b1ec09a40ee0bdf5067a09afafdc79034306))


## v0.54.0 (2026-09-30)

### ✳️ New

- **ui**: Motion pass — screen transitions, gliding gauge, press and haptic feedback, loading
  skeletons ([#117](https://github.com/jtn0123/VoltTracker/pull/117),
  [`4d7fa21`](https://github.com/jtn0123/VoltTracker/commit/4d7fa211f425c6d303d2b412837b9cba77e37198))


## v0.53.0 (2026-09-30)

### 🔺 Fix

- Batch 2 on-car follow-ups — body test, parked listen, trips, labels
  ([#113](https://github.com/jtn0123/VoltTracker/pull/113),
  [`3cb3d23`](https://github.com/jtn0123/VoltTracker/commit/3cb3d23cf81689821cfccb313fe56f53665f733e))

- **build**: Unblock releases — privacyScan skips the generated DTC table
  ([#116](https://github.com/jtn0123/VoltTracker/pull/116),
  [`93131e0`](https://github.com/jtn0123/VoltTracker/commit/93131e0fecdc79846a09d77c363ade58e63c0323))

- **obd**: On-car fixes for dash soc, fuel, engine-on and adapter timing
  ([#112](https://github.com/jtn0123/VoltTracker/pull/112),
  [`770b058`](https://github.com/jtn0123/VoltTracker/commit/770b0586cd829f29cd8083b2fc2b314bf0a77343))

- **service**: Stop the pause-time ANR behind prefs fsyncs
  ([#103](https://github.com/jtn0123/VoltTracker/pull/103),
  [`b3b150f`](https://github.com/jtn0123/VoltTracker/commit/b3b150fb34628ee844c1ae0be6b1c4a84ad00886))

- **ui**: Consistent charge eta, readable tiles and units
  ([#110](https://github.com/jtn0123/VoltTracker/pull/110),
  [`f0b32d9`](https://github.com/jtn0123/VoltTracker/commit/f0b32d9676095909329dfa783946b8a15b5eb6f5))

- **ui**: Demo headers say "Sample data"; cockpit holds up at large text
  ([#105](https://github.com/jtn0123/VoltTracker/pull/105),
  [`85c8770`](https://github.com/jtn0123/VoltTracker/commit/85c8770ef45150e474d8d9485ce00798f2563223))

- **ui**: Dogfood pass on the compose dashboard with demo mode
  ([#108](https://github.com/jtn0123/VoltTracker/pull/108),
  [`b13f939`](https://github.com/jtn0123/VoltTracker/commit/b13f9399a3d22572be87e3c533dc262f2b5c34f1))

- **ui**: Offline and large-text polish from the visual pass
  ([#115](https://github.com/jtn0123/VoltTracker/pull/115),
  [`296ad63`](https://github.com/jtn0123/VoltTracker/commit/296ad63ff4f4e70155d3e298f27f603bb2d45d06))

- **ui**: Polish pass 3 — accessibility, motion, placeholders, large text, wording
  ([#104](https://github.com/jtn0123/VoltTracker/pull/104),
  [`fb7260e`](https://github.com/jtn0123/VoltTracker/commit/fb7260e2001a5e9ebb618ca32172ce6372db9c7b))

### 🔷 Changed

- **dev**: Reliable local emulator testing script
  ([#107](https://github.com/jtn0123/VoltTracker/pull/107),
  [`30954dc`](https://github.com/jtn0123/VoltTracker/commit/30954dcbb2e3f3bdc7458001cdcf3f1f00735452))

### ✳️ New

- Native freeze frame and all-readings screens, scan-complete label, body-test sim
  ([#114](https://github.com/jtn0123/VoltTracker/pull/114),
  [`87a7998`](https://github.com/jtn0123/VoltTracker/commit/87a7998cd26810ff6c0dee0a2f6fc8aace4d5a80))

- **ui**: First-run setup card on drive ([#111](https://github.com/jtn0123/VoltTracker/pull/111),
  [`e6e38df`](https://github.com/jtn0123/VoltTracker/commit/e6e38df98534e7cab09867f1c720c59c4295a19c))

- **ui**: Make the new Compose dashboard the app launcher
  ([#106](https://github.com/jtn0123/VoltTracker/pull/106),
  [`697ea87`](https://github.com/jtn0123/VoltTracker/commit/697ea878cff5f44c5f38374191dfd7b1d9928a7a))

- **ui**: Native live signals, landscape drive, classic back returns
  ([#109](https://github.com/jtn0123/VoltTracker/pull/109),
  [`5093038`](https://github.com/jtn0123/VoltTracker/commit/50930383bfa612f8a8df55ebf55190379f01d9df))

- **ui**: Polish theme legibility, controls and touch targets
  ([#101](https://github.com/jtn0123/VoltTracker/pull/101),
  [`3bbb999`](https://github.com/jtn0123/VoltTracker/commit/3bbb9999aa198006e7d810907a690bdec76a9b4d))

- **ui**: Units everywhere, loading states and stale-value fixes
  ([#102](https://github.com/jtn0123/VoltTracker/pull/102),
  [`add92b9`](https://github.com/jtn0123/VoltTracker/commit/add92b9c1b17267d94971eaec13ed24fcf8aaffb))

- **ui**: Wire the Health screen to real trouble codes, scan and clear
  ([#100](https://github.com/jtn0123/VoltTracker/pull/100),
  [`e5fe481`](https://github.com/jtn0123/VoltTracker/commit/e5fe48164957005e5825c923aff7491748168612))


## v0.52.0 (2026-09-28)

### ✳️ New

- **map**: Replace the broken CARTO basemap with Stadia Maps
  ([#99](https://github.com/jtn0123/VoltTracker/pull/99),
  [`6bd32b8`](https://github.com/jtn0123/VoltTracker/commit/6bd32b888777c984720ab73011eef7ee5f739acb))


## v0.51.0 (2026-09-28)

### ✳️ New

- **car**: Redesign car tab with top-down tires, body state and car controls
  ([#98](https://github.com/jtn0123/VoltTracker/pull/98),
  [`bc89ce3`](https://github.com/jtn0123/VoltTracker/commit/bc89ce385e6b74f841968f27a181243fd86904ad))


## v0.50.0 (2026-09-28)

### ✳️ New

- **insights**: Redesign insights tab with electric share, weekly bars and speed efficiency
  ([#97](https://github.com/jtn0123/VoltTracker/pull/97),
  [`c669fbe`](https://github.com/jtn0123/VoltTracker/commit/c669fbe4a30ff3ff836c80c8a5effda1178585c5))


## v0.49.0 (2026-09-28)

### ✳️ New

- **trips**: Redesign trips tab with ev/gas route map and grouped drives
  ([#96](https://github.com/jtn0123/VoltTracker/pull/96),
  [`bac8e5b`](https://github.com/jtn0123/VoltTracker/commit/bac8e5b701321f8f85a8c9847c054d7cb5dd6ea5))


## v0.48.0 (2026-09-28)

### ✳️ New

- **charge**: Redesign charge tab with session curve and logged sessions
  ([#95](https://github.com/jtn0123/VoltTracker/pull/95),
  [`005045d`](https://github.com/jtn0123/VoltTracker/commit/005045d249a9a8a6414c48bbe1c3ab40a5d5eda6))

- **demo**: Drive arc with regen, a gas stretch and a park
  ([#94](https://github.com/jtn0123/VoltTracker/pull/94),
  [`c26c611`](https://github.com/jtn0123/VoltTracker/commit/c26c6119fbddcd89e880678171d7f814144ca533))

- **settings**: Wire compose settings tools through shared helpers
  ([#92](https://github.com/jtn0123/VoltTracker/pull/92),
  [`236209a`](https://github.com/jtn0123/VoltTracker/commit/236209abc119c609ed896467061531b35d7aacca))

- **ui**: Oled Black (5 accents), Saddle Leather and Latte themes
  ([#93](https://github.com/jtn0123/VoltTracker/pull/93),
  [`520beec`](https://github.com/jtn0123/VoltTracker/commit/520beec243ca904b63813d913ca10701d3647515))


## v0.47.0 (2026-09-28)

### ✳️ New

- **settings**: Shared native display prefs, live-wired compose settings
  ([#91](https://github.com/jtn0123/VoltTracker/pull/91),
  [`46ec1fe`](https://github.com/jtn0123/VoltTracker/commit/46ec1fec1224c048ee79fc2d3aeb2be0f54c97cb))


## v0.46.0 (2026-09-28)

### ✳️ New

- **ui**: Drive redesign — Arc ring (Focus) + Cockpit (Detailed), live-wired
  ([#90](https://github.com/jtn0123/VoltTracker/pull/90),
  [`e9ffc5c`](https://github.com/jtn0123/VoltTracker/commit/e9ffc5c31ac019bf49166a40af339469aada4f16))


## v0.45.1 (2026-09-28)

### 🔺 Fix

- **service**: Demo mode starts without Bluetooth/location permissions
  ([#89](https://github.com/jtn0123/VoltTracker/pull/89),
  [`d69604b`](https://github.com/jtn0123/VoltTracker/commit/d69604ba8e43463035919ac6c82b37d49d3fe7d5))


## v0.45.0 (2026-09-27)

### ✳️ New

- **ui**: 5-tab redesign navigation, gear Settings, Car tab + Health route; demo charges at 3.6 kW
  ([#88](https://github.com/jtn0123/VoltTracker/pull/88),
  [`5a45e56`](https://github.com/jtn0123/VoltTracker/commit/5a45e56187326fa02784f21e01bcacca93646f7f))


## v0.44.0 (2026-09-27)

### ✳️ New

- **ui**: Redesign theme tokens (dark + light), fonts, Appearance setting
  ([#87](https://github.com/jtn0123/VoltTracker/pull/87),
  [`2074fa3`](https://github.com/jtn0123/VoltTracker/commit/2074fa352e427cddb0f81aafe18f9ad2e5992e7b))


## v0.43.0 (2026-09-27)

### ✳️ New

- **ui**: Bold layout pass — collapse empty fields, EV/gas chip, compact charts
  ([#86](https://github.com/jtn0123/VoltTracker/pull/86),
  [`0702475`](https://github.com/jtn0123/VoltTracker/commit/0702475653956918c1e2e5960a624659f53d921c))


## v0.42.0 (2026-09-27)

### ✳️ New

- **ui**: "clean EV" theme — calm tokens, Volt-teal accent, color for meaning
  ([#85](https://github.com/jtn0123/VoltTracker/pull/85),
  [`260f071`](https://github.com/jtn0123/VoltTracker/commit/260f071d858696336692c6c88fa693f8933f7351))


## v0.41.3 (2026-09-26)

### 🔺 Fix

- **dashboard**: One-drive trip card, live-first SOC, consistent charge count, trip-detail scrim
  ([#84](https://github.com/jtn0123/VoltTracker/pull/84),
  [`704b572`](https://github.com/jtn0123/VoltTracker/commit/704b572442a36864a23629e34d8f0f18fe2dcf57))


## v0.41.2 (2026-09-26)

### 🔺 Fix

- **dashboard**: Polish round 2 — gear chip, unit-aware live signals, trip-detail sheet
  ([#82](https://github.com/jtn0123/VoltTracker/pull/82),
  [`d4d43f6`](https://github.com/jtn0123/VoltTracker/commit/d4d43f62fc3ac8ea232e68d4d5a1d4ce7d088a41))

### 🔷 Changed

- Cut ~2-4 min off the Android PR critical path; always report ci-success
  ([#83](https://github.com/jtn0123/VoltTracker/pull/83),
  [`8484473`](https://github.com/jtn0123/VoltTracker/commit/84844739c1879d85876d43fa195633be7e0a8a4c))


## v0.41.1 (2026-09-26)

### 🔺 Fix

- **ui**: Polish pass over car controls, gear, SW-CAN and EV range UI
  ([#81](https://github.com/jtn0123/VoltTracker/pull/81),
  [`2df6652`](https://github.com/jtn0123/VoltTracker/commit/2df6652bd1da92843564d1f46c8dde2744c22ac7))


## v0.41.0 (2026-09-26)

### ✳️ New

- **trips**: Split a trip at an in-trip park stop
  ([#80](https://github.com/jtn0123/VoltTracker/pull/80),
  [`5f09342`](https://github.com/jtn0123/VoltTracker/commit/5f09342e17e0f956b63285f0a63cbefba34fd666))


## v0.40.1 (2026-09-26)

### 🔺 Fix

- Ev range caption uses the car's estimate; 22437D 0xFFFF is "not available"
  ([#79](https://github.com/jtn0123/VoltTracker/pull/79),
  [`1c14219`](https://github.com/jtn0123/VoltTracker/commit/1c14219d4aa5741a044e9fadab45e0a23c71d365))


## v0.40.0 (2026-09-26)

### ✳️ New

- **controls**: Safety-gated experimental car controls (untested on car)
  ([#77](https://github.com/jtn0123/VoltTracker/pull/77),
  [`e1a5f00`](https://github.com/jtn0123/VoltTracker/commit/e1a5f00d26a37dee5eeae915cf7dabae82a9a990))


## v0.39.0 (2026-09-26)

### ✳️ New

- **trips**: Decode PRNDL gear and split trips on Park (new trips only)
  ([#78](https://github.com/jtn0123/VoltTracker/pull/78),
  [`3ad7a44`](https://github.com/jtn0123/VoltTracker/commit/3ad7a44210a6aeb1fc97877438745ee5c6616729))


## v0.38.0 (2026-09-26)

### 🔺 Fix

- **charge**: An idling engine at a standstill is not an EVSE charge
  ([#74](https://github.com/jtn0123/VoltTracker/pull/74),
  [`1cb74e6`](https://github.com/jtn0123/VoltTracker/commit/1cb74e600123542579338676e38cab2201fdc30d))

- **obd**: End-of-drive drops end cleanly; ignore a redundant CONNECT
  ([#76](https://github.com/jtn0123/VoltTracker/pull/76),
  [`b38f750`](https://github.com/jtn0123/VoltTracker/commit/b38f750af31eab015eb1c4eefbd1b52fc29a8a72))

- **obd**: Re-probe retired PIDs so a parked-then-driven session gets speed back
  ([#73](https://github.com/jtn0123/VoltTracker/pull/73),
  [`132f547`](https://github.com/jtn0123/VoltTracker/commit/132f54727087ed1bbc3e8d8dbc7a3766f03c25de))

### ✳️ New

- **obd**: Listen-only SW-CAN (GMLAN) broadcast reading on OBDLink adapters
  ([#75](https://github.com/jtn0123/VoltTracker/pull/75),
  [`eba6dc3`](https://github.com/jtn0123/VoltTracker/commit/eba6dc3fd5ba7febcf210d03dc460a502f71a214))

- **obd**: Poll the OVMS Volt readings we skipped; read motor temps from their own nodes
  ([#72](https://github.com/jtn0123/VoltTracker/pull/72),
  [`f346b56`](https://github.com/jtn0123/VoltTracker/commit/f346b565619a857aa33212296082b594eccfc9d5))


## v0.37.0 (2026-09-26)

### 🔺 Fix

- **obd**: Read the standard J1979 multi-PID reply so batching works
  ([#70](https://github.com/jtn0123/VoltTracker/pull/70),
  [`d968e9e`](https://github.com/jtn0123/VoltTracker/commit/d968e9e5032caa91d572236a5b1f78cd36089c42))

### ✳️ New

- **obd**: Poll displayed SOC, pack resistance, isolation, motor/inverter temps and charger AC
  ([#71](https://github.com/jtn0123/VoltTracker/pull/71),
  [`6d7ed6f`](https://github.com/jtn0123/VoltTracker/commit/6d7ed6fdb60d607cc78cdb9fb8069b267fc63580))


## v0.36.6 (2026-09-26)

### 🔺 Fix

- **elm**: Don't cut replies short on the adapter's echo
  ([#69](https://github.com/jtn0123/VoltTracker/pull/69),
  [`6c30646`](https://github.com/jtn0123/VoltTracker/commit/6c306469ee211a1011ef6e1eca6db6ff8f066f1d))


## v0.36.5 (2026-09-26)

### 🔺 Fix

- **obd**: Retire refused PIDs and read the GM odometer
  ([#68](https://github.com/jtn0123/VoltTracker/pull/68),
  [`db19f24`](https://github.com/jtn0123/VoltTracker/commit/db19f2459a526f98dbdd2bab3cf7382d241edb90))

### 🔷 Changed

- **obd**: Virtual Volt adapter and per-PID scorecard
  ([#67](https://github.com/jtn0123/VoltTracker/pull/67),
  [`ccdc703`](https://github.com/jtn0123/VoltTracker/commit/ccdc7033c9f860302d7716ecde7507c50ce3ebf2))


## v0.36.4 (2026-09-26)

### 🔺 Fix

- **ui**: Open the full dashboard at launch until Compose reaches parity
  ([#66](https://github.com/jtn0123/VoltTracker/pull/66),
  [`80dbf4b`](https://github.com/jtn0123/VoltTracker/commit/80dbf4b6797caa38a1007bb49795ca199c7e0754))

### 🔷 Changed

- **deps**: Bump actions/setup-java from 5.7.0 to 6.0.0
  ([#40](https://github.com/jtn0123/VoltTracker/pull/40),
  [`b17765e`](https://github.com/jtn0123/VoltTracker/commit/b17765e8e6e850af6d4ccb48685cf248d26b70d1))

- **deps**: Bump agp from 9.2.1 to 9.3.2 in /mobile/android
  ([#38](https://github.com/jtn0123/VoltTracker/pull/38),
  [`daf9568`](https://github.com/jtn0123/VoltTracker/commit/daf956849241bf1a3832bec85f7d480a3bc53a93))

- **deps**: Bump agp from 9.3.2 to 9.4.0 in /mobile/android
  ([#48](https://github.com/jtn0123/VoltTracker/pull/48),
  [`75ec3ac`](https://github.com/jtn0123/VoltTracker/commit/75ec3aca64ca0e38c527157c214967a3279aeb32))

- **deps**: Bump com.diffplug.spotless in /mobile/android
  ([#29](https://github.com/jtn0123/VoltTracker/pull/29),
  [`bd8bf45`](https://github.com/jtn0123/VoltTracker/commit/bd8bf457b6dd67735c8a95d4e465a1251b0b5e8d))

- **deps**: Bump com.diffplug.spotless in /mobile/android
  ([#49](https://github.com/jtn0123/VoltTracker/pull/49),
  [`2a9ab6c`](https://github.com/jtn0123/VoltTracker/commit/2a9ab6cd3926f3d0f34f6c57a148750fc3da134a))

- **deps**: Bump google/osv-scanner-action/osv-scanner-action
  ([#26](https://github.com/jtn0123/VoltTracker/pull/26),
  [`8387488`](https://github.com/jtn0123/VoltTracker/commit/83874885e4594c675b7773e38296db933a6f3dd4))

- **deps**: Bump gradle-wrapper from 9.7.0 to 9.7.1 in /mobile/android
  ([#33](https://github.com/jtn0123/VoltTracker/pull/33),
  [`6c50dbc`](https://github.com/jtn0123/VoltTracker/commit/6c50dbce6139f8164d5d65f4a15b4f719567ec73))

- **deps**: Bump gradle/actions/wrapper-validation from 6.2.0 to 6.3.0
  ([#39](https://github.com/jtn0123/VoltTracker/pull/39),
  [`82b0106`](https://github.com/jtn0123/VoltTracker/commit/82b0106489c76fccd66e31de927513caee005381))

- **deps**: Bump roborazzi from 1.72.0 to 1.73.0 in /mobile/android
  ([#45](https://github.com/jtn0123/VoltTracker/pull/45),
  [`c33f9e7`](https://github.com/jtn0123/VoltTracker/commit/c33f9e79cc68ae7016a923a11c2d543ad8bfd170))

- **deps**: Bump softprops/action-gh-release from 3.0.1 to 3.0.3
  ([#44](https://github.com/jtn0123/VoltTracker/pull/44),
  [`26cd2e6`](https://github.com/jtn0123/VoltTracker/commit/26cd2e6c9b803776ac4f73aa1b804fabdd7acc78))

- **deps**: Bump the androidx group across 1 directory with 2 updates
  ([#23](https://github.com/jtn0123/VoltTracker/pull/23),
  [`f3d0d0b`](https://github.com/jtn0123/VoltTracker/commit/f3d0d0bf6019349256f8146e38c6468b51636440))

- **deps**: Bump the codeql-action group with 2 updates
  ([#43](https://github.com/jtn0123/VoltTracker/pull/43),
  [`fe11e48`](https://github.com/jtn0123/VoltTracker/commit/fe11e4895c3c4471dc73846e4d006cda68d86708))

- **deps-dev**: Bump browserslist ([#46](https://github.com/jtn0123/VoltTracker/pull/46),
  [`abd61aa`](https://github.com/jtn0123/VoltTracker/commit/abd61aa988fe02b96c9a1bcea61ac2861067bfa3))

- **deps-dev**: Bump eslint in /mobile/android/dashboard-tests
  ([#37](https://github.com/jtn0123/VoltTracker/pull/37),
  [`0f64fcd`](https://github.com/jtn0123/VoltTracker/commit/0f64fcd2162c4dc0ac00d6f19989fc998c2385f7))

- **deps-dev**: Bump eslint in /mobile/android/dashboard-tests
  ([#41](https://github.com/jtn0123/VoltTracker/pull/41),
  [`240dfe7`](https://github.com/jtn0123/VoltTracker/commit/240dfe769a1894ae35ffe1824ee9eed2a103b048))

- **deps-dev**: Bump typescript in /mobile/android/dashboard-tests
  ([#11](https://github.com/jtn0123/VoltTracker/pull/11),
  [`a3f5ca5`](https://github.com/jtn0123/VoltTracker/commit/a3f5ca50ab5df563cda19c5022c748bb886f929d))

- **deps-dev**: Bump typescript-eslint ([#42](https://github.com/jtn0123/VoltTracker/pull/42),
  [`3e1240f`](https://github.com/jtn0123/VoltTracker/commit/3e1240ff4ada75d144ded0246d78b03f9e8885ec))

### 🔷 Changed

- Fix emulator smoke renderer crashes and retain diagnostics
  ([#47](https://github.com/jtn0123/VoltTracker/pull/47),
  [`53d41e6`](https://github.com/jtn0123/VoltTracker/commit/53d41e6383815c04a3e00482b2a0bf929dbf0550))


## v0.36.3 (2026-08-25)

### 🔺 Fix

- **dependabot**: Stop reopening the typescript 7 major
  ([#36](https://github.com/jtn0123/VoltTracker/pull/36),
  [`dee145d`](https://github.com/jtn0123/VoltTracker/commit/dee145de02b250c591cc789d523828674d19d3cf))

### 🔷 Changed

- **deps**: Bump actions/checkout from 7.0.0 to 7.0.1
  ([#22](https://github.com/jtn0123/VoltTracker/pull/22),
  [`aef8bf1`](https://github.com/jtn0123/VoltTracker/commit/aef8bf143ac50d952885f3afc7502ad04ffb5a32))

- **deps**: Bump actions/setup-java from 5.5.0 to 5.7.0
  ([#24](https://github.com/jtn0123/VoltTracker/pull/24),
  [`9d73607`](https://github.com/jtn0123/VoltTracker/commit/9d7360745dedafde78faffe9a225dd60854df29c))

- **deps**: Bump org.json:json ([#27](https://github.com/jtn0123/VoltTracker/pull/27),
  [`2b5aa32`](https://github.com/jtn0123/VoltTracker/commit/2b5aa32c86cfca115caa7d0d0d78a4ccf0a9bb7e))

- **deps**: Bump roborazzi from 1.71.0 to 1.72.0 in /mobile/android
  ([#28](https://github.com/jtn0123/VoltTracker/pull/28),
  [`aa8e869`](https://github.com/jtn0123/VoltTracker/commit/aa8e869fb4b76e64d98a3caf0045804e3d9a88c1))

- **deps-dev**: Bump @vitest/coverage-istanbul
  ([#31](https://github.com/jtn0123/VoltTracker/pull/31),
  [`4ca36b9`](https://github.com/jtn0123/VoltTracker/commit/4ca36b94b9b1d28632bc377d42f4a4632d7007b8))

- **deps-dev**: Bump vitest in /mobile/android/dashboard-tests
  ([#32](https://github.com/jtn0123/VoltTracker/pull/32),
  [`82ae80a`](https://github.com/jtn0123/VoltTracker/commit/82ae80ab850068eb38e2f4bee10e706fb2e52a1c))


## v0.36.2 (2026-08-25)

### 🔺 Fix

- **dependabot**: Group github/codeql-action so its sub-actions move together
  ([#30](https://github.com/jtn0123/VoltTracker/pull/30),
  [`0646e63`](https://github.com/jtn0123/VoltTracker/commit/0646e63c7f07bd7bd6e09b4683293434d8314bb9))

### 🔷 Changed

- **deps**: Bump actions/setup-python from 6.3.0 to 7.0.0
  ([#25](https://github.com/jtn0123/VoltTracker/pull/25),
  [`9e0f354`](https://github.com/jtn0123/VoltTracker/commit/9e0f354c95bbc1c2b1d25a492d9750a7e094e9d7))

- **deps**: Bump codeql-action to v4.37.6 (init + analyze together)
  ([#20](https://github.com/jtn0123/VoltTracker/pull/20),
  [`5762d53`](https://github.com/jtn0123/VoltTracker/commit/5762d53ed97d8dc87d5d48b4edd436b3ab59b92e))

- **deps**: Bump com.github.ben-manes.versions in /mobile/android
  ([#1](https://github.com/jtn0123/VoltTracker/pull/1),
  [`da0b15e`](https://github.com/jtn0123/VoltTracker/commit/da0b15eb65a56a616e26cf0002f745048c51a4cc))

- **deps**: Bump com.github.ben-manes.versions in /mobile/android
  ([#18](https://github.com/jtn0123/VoltTracker/pull/18),
  [`b4da62b`](https://github.com/jtn0123/VoltTracker/commit/b4da62b487ea7a8a1528a87c3a0d006752d7ee11))

- **deps**: Bump dorny/paths-filter from 4.0.2 to 4.0.3
  ([#16](https://github.com/jtn0123/VoltTracker/pull/16),
  [`bd41912`](https://github.com/jtn0123/VoltTracker/commit/bd41912a5f0ad8be5b8ebf11d1e8453f0ccd19c8))

- **deps**: Bump gitleaks/gitleaks-action from 2.3.9 to 3.0.0
  ([#7](https://github.com/jtn0123/VoltTracker/pull/7),
  [`0f8617d`](https://github.com/jtn0123/VoltTracker/commit/0f8617dae507321272de635bf4a95de985b1ce61))

- **deps**: Bump google/osv-scanner-action/osv-scanner-action
  ([#6](https://github.com/jtn0123/VoltTracker/pull/6),
  [`8541e1d`](https://github.com/jtn0123/VoltTracker/commit/8541e1d9666bdd5dd6a913dc8498cef97cd3cf51))

- **deps**: Bump gradle-wrapper from 9.6.1 to 9.7.0 in /mobile/android
  ([#19](https://github.com/jtn0123/VoltTracker/pull/19),
  [`9b21052`](https://github.com/jtn0123/VoltTracker/commit/9b21052d754b326940f5894f8a33f5eff9fbd534))

- **deps**: Bump org.jetbrains.kotlin.plugin.compose in /mobile/android
  ([#2](https://github.com/jtn0123/VoltTracker/pull/2),
  [`f092c4e`](https://github.com/jtn0123/VoltTracker/commit/f092c4eb753a0c26b61e123a31da1d5dbce8f93e))

- **deps**: Bump roborazzi from 1.70.0 to 1.71.0 in /mobile/android
  ([#17](https://github.com/jtn0123/VoltTracker/pull/17),
  [`695f4a7`](https://github.com/jtn0123/VoltTracker/commit/695f4a74e79db7d1638a2843fc365f46425898d4))

- **deps**: Bump the codeql-action group with 2 updates
  ([#34](https://github.com/jtn0123/VoltTracker/pull/34),
  [`e4f5855`](https://github.com/jtn0123/VoltTracker/commit/e4f5855433ef13fb28566026babc70a92c4cb19d))

- **deps-dev**: Bump @playwright/test in /mobile/android/dashboard-e2e
  ([#10](https://github.com/jtn0123/VoltTracker/pull/10),
  [`b2017eb`](https://github.com/jtn0123/VoltTracker/commit/b2017eba35ead91d7955fc54fe4f2bffef4f1ea0))

- **deps-dev**: Bump axe-core in /mobile/android/dashboard-e2e
  ([#8](https://github.com/jtn0123/VoltTracker/pull/8),
  [`5ae4875`](https://github.com/jtn0123/VoltTracker/commit/5ae4875093835cbac0b5605636e9798dec2cff1d))

- **deps-dev**: Bump esbuild in /mobile/android/dashboard-tests
  ([#12](https://github.com/jtn0123/VoltTracker/pull/12),
  [`45bfdc6`](https://github.com/jtn0123/VoltTracker/commit/45bfdc64ca4b8b6e4f41bac31fe6e5bb540af7fb))

- **deps-dev**: Bump eslint in /mobile/android/dashboard-tests
  ([#9](https://github.com/jtn0123/VoltTracker/pull/9),
  [`ee7cf23`](https://github.com/jtn0123/VoltTracker/commit/ee7cf2322f7fe74e4870640ccbee2ce36da6bf9c))

- **deps-dev**: Bump typescript-eslint ([#13](https://github.com/jtn0123/VoltTracker/pull/13),
  [`8b8e553`](https://github.com/jtn0123/VoltTracker/commit/8b8e5539e73c83377b08e321c2de46da3bcf3975))

### 🔷 Changed

- **export**: Cover fileBaseName's branches to restore the BRANCH ratchet
  ([#35](https://github.com/jtn0123/VoltTracker/pull/35),
  [`10afe02`](https://github.com/jtn0123/VoltTracker/commit/10afe02b040cef3aee5330758174e40a9fef1fb3))


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
