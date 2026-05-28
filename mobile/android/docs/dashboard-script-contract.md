# Dashboard Script Contract

The production dashboard enters through one ES module bootstrap:

1. `js/bootstrap.js`

The bootstrap module imports the side-effecting dashboard files in this order:

1. `js/core.js`
2. `js/panels.js`
3. `js/map.js`
4. `js/scrubber.js`
5. `js/drive.js`
6. `js/telemetry.js`
7. `js/actions.js`
8. `js/troubleshooter.js`
9. `js/connection-status.js`
10. `js/connection-tools.js`

The large DTC dictionaries, `js/dtc-causes.js` and `js/dtc-lookup.js`, are not
startup scripts. `core.js` exposes `VD.ensureDtcData()`, which loads those files
only when a DTC view/action needs descriptions, causes, or example rows.

`mobile/android/dashboard-tests/script-order.test.js` parses both
`app/src/main/dashboard-src/index.template.html` and the generated
`app/src/main/assets/dashboard/index.html` to catch entry-point drift, then
parses `js/bootstrap.js` to catch import-order drift. When a new dashboard
script is added, update `bootstrap.js`, run `./gradlew generateDashboardHtml`,
then update the test's expected imports in the same change.

The jsdom loader in `dashboard-tests/setup/load-dashboard.js` intentionally
loads a small bootstrap subset by default and lets individual tests opt into
additional production scripts with `extras`. This keeps focused tests quiet
while the script-order test protects the full WebView order.
