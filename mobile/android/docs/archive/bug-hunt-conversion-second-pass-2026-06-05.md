# Conversion Bug Hunt - Second Pass - 2026-06-05

Scope: second post-migration audit after the Kotlin + TypeScript conversion. Focused on TypeScript reliability issues left by JavaScript-style indexed access in dashboard route, chart, and scrubber code.

Status: 20 validated findings, 20 fixed in this pass.

## Findings

| # | Area | Layman explanation | Evidence | Impact | Ease |
|---|------|--------------------|----------|--------|------|
| 1 | View heading metadata fallback | A bad view key could make the dashboard title code read from missing metadata. | `npm --prefix mobile/android/dashboard-tests run typecheck -- --noUncheckedIndexedAccess` flagged `core.ts`; fixed with a guarded metadata fallback at `app/src/main/dashboard-src/js/core.ts:437`. | Low | Easy |
| 2 | View heading icon fallback | The screen icon path had the same unchecked lookup, so a malformed view could try to set an undefined SVG path. | Strict indexed-access probe flagged `core.ts`; fixed with an `iconPath` guard at `app/src/main/dashboard-src/js/core.ts:442`. | Low | Easy |
| 3 | Speed trace accepted untyped samples | Converted TS still treated `speedHistory` values as `any`, allowing non-numeric dashboard data through the live chart path. | Replaced the `any` mapper with `unknown` + `Number(...)` at `app/src/main/dashboard-src/js/drive.ts:194`. | Medium | Easy |
| 4 | Speed trace latest label read past array end | The mph label read the last sample by index without a narrowed local. | Strict indexed-access probe flagged `drive.ts`; fixed with `latestSample` at `app/src/main/dashboard-src/js/drive.ts:196`. | Low | Easy |
| 5 | Speed trace area close used unchecked first/last points | The canvas fill path assumed the generated point list had first and last points. | Strict indexed-access probe flagged `drive.ts`; fixed with `firstPoint` / `latest` guards at `app/src/main/dashboard-src/js/drive.ts:231`. | Medium | Easy |
| 6 | Live SOC segments used unchecked next point | The SOC chart loop skipped the last item, but TypeScript could not prove the next point existed. | Strict indexed-access probe flagged `drive.ts`; fixed with a `next` guard at `app/src/main/dashboard-src/js/drive.ts:397`. | Low | Easy |
| 7 | Signal-stage metadata fallback | A malformed signal stage could make the UI read a missing `label`/`hint`. | Strict indexed-access probe flagged `panels.ts`; fixed with a metadata guard at `app/src/main/dashboard-src/js/panels.ts:332`. | Low | Easy |
| 8 | Route start/end markers used unchecked coordinates | Map route start/end markers assumed the `latlngs` array had both endpoints. | Strict indexed-access probe flagged `map.ts`; fixed with `firstLatLng` / `lastLatLng` guards at `app/src/main/dashboard-src/js/map.ts:352`. | Medium | Easy |
| 9 | Speed heat bands used unchecked route pairs | The heat layer read `drawable[i - 1]`, `drawable[i]`, and matching lat/lng pairs without local guards. | Strict indexed-access probe flagged `map.ts`; fixed with guarded locals at `app/src/main/dashboard-src/js/map.ts:365`. | Medium | Easy |
| 10 | Speed heat bucket lookup was unchecked | The route color bucket came from a string key and was pushed to without narrowing. | Strict indexed-access probe flagged `map.ts`; fixed with a `bucket` guard and typed segment buckets at `app/src/main/dashboard-src/js/map.ts:372`. | Low | Easy |
| 11 | Efficiency bands used unchecked route pairs | The efficiency layer had the same unchecked segment pair reads as the speed layer. | Strict indexed-access probe flagged `map.ts`; fixed with guarded locals and typed segment arrays at `app/src/main/dashboard-src/js/map.ts:397`. | Medium | Easy |
| 12 | Route fit key used empty object fallback | `routeFitKey` fell back to `{}`, then formatted `undefined` latitude/longitude as numbers. | Strict indexed-access probe flagged `map.ts`; fixed with explicit first/last guards at `app/src/main/dashboard-src/js/map.ts:433`. | Medium | Easy |
| 13 | Route distance used unchecked adjacent points | Distance accumulation assumed every adjacent route pair existed. | Strict indexed-access probe flagged `map.ts`; fixed with guarded pair reads at `app/src/main/dashboard-src/js/map.ts:504`. | Medium | Easy |
| 14 | Stop detection used unchecked adjacent points and stop bounds | Stop detection and stop insertion assumed adjacent points, start/end points, and midpoint existed. | Strict indexed-access probe flagged `map.ts`; fixed with guards in `detectStops` and `addStop` at `app/src/main/dashboard-src/js/map.ts:521`. | Medium | Easy |
| 15 | Sample route generator assumed non-empty slices | Demo route generation read `slice[0]`, `points[0]`, and the last point without checking. | Strict indexed-access probe flagged `map.ts`; fixed with explicit empty-slice/empty-points errors at `app/src/main/dashboard-src/js/map.ts:562`. | Low | Easy |
| 16 | Sample elevation/power loops used unchecked points | The sample route's elevation and synthetic power loops indexed route points repeatedly without narrowed locals. | Strict indexed-access probe flagged `map.ts`; fixed with guarded point locals at `app/src/main/dashboard-src/js/map.ts:583` and `app/src/main/dashboard-src/js/map.ts:614`. | Low | Easy |
| 17 | SOC interpolation used unchecked track endpoints | The scrubber interpolation read `track[0]`, last, previous, and current samples without guards. | Strict indexed-access probe flagged `scrubber.ts`; fixed with guarded endpoint/pair locals at `app/src/main/dashboard-src/js/scrubber.ts:104`. | Medium | Easy |
| 18 | Scrub data build used unchecked point arrays | Distance, speed, mph/elevation/SOC/efficiency, grade, and derived distance fields all relied on unchecked indexed reads. | Strict indexed-access probe flagged `scrubber.ts`; fixed with guarded locals through `buildScrubData` at `app/src/main/dashboard-src/js/scrubber.ts:155`. | Medium | Medium |
| 19 | Scrub sample and SVG path reads were unchecked | Cursor sampling and SVG path generation read neighboring/current scrub points by index without guards. | Strict indexed-access probe flagged `scrubber.ts`; fixed with guarded `next` / `a` / `b` / `point` locals at `app/src/main/dashboard-src/js/scrubber.ts:235`. | Medium | Easy |
| 20 | Scrubber marker/play/jump controls used unchecked scrub points | Render, play-duration, and map-tap jumping assumed scrub data endpoints and best match existed. | Strict indexed-access probe flagged `scrubber.ts`; fixed with guarded point locals at `app/src/main/dashboard-src/js/scrubber.ts:620`. | Medium | Easy |

## Validation Run

- Baseline probe before fixes: `npm --prefix mobile/android/dashboard-tests run typecheck -- --noUncheckedIndexedAccess` failed in `core.ts`, `drive.ts`, `panels.ts`, `map.ts`, and `scrubber.ts`.
- Focused typecheck after fixes: `npm --prefix mobile/android/dashboard-tests run typecheck` passed.
- Strict indexed-access probe after fixes: `npm --prefix mobile/android/dashboard-tests run typecheck -- --noUncheckedIndexedAccess` passed.
- Final gate: `./gradlew verifyActiveApp` passed after the fixes; dashboard lint/typecheck/Vitest, Playwright e2e, Android lint/unit tests, JaCoCo coverage, bundle budget, generated-dashboard drift, and migration straggler guard were all green.

## Discarded Candidates

- Remaining Kotlin `Any` values in JSON/log/WebView bridge seams were rechecked and are intentional open-value boundaries, not migration leftovers.
- Existing Java unit tests remain by policy; this pass did not rewrite stable test files for language-only churn.
- Generated dashboard assets remain classic `.js` files because the WebView ABI ships built JavaScript even though editable source is TypeScript.
