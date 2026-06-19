# Summary

<!-- What does this change and why? Link any related issue. -->

## Testing performed

<!-- Check what you ran locally. `./gradlew verifyActiveApp` (from mobile/android/,
     after `npm --prefix dashboard-tests ci`) runs the whole gate in one shot. -->

- [ ] Android unit tests — `./gradlew :app:testDebugUnitTest`
- [ ] Formatting — `./gradlew :app:spotlessCheck`
- [ ] Android Lint — `./gradlew :app:lintDebug`
- [ ] Dashboard tests — `npm --prefix dashboard-tests run lint && npm --prefix dashboard-tests run typecheck && npm --prefix dashboard-tests test`
- [ ] Dashboard e2e — `npx playwright test` in `mobile/android/dashboard-e2e/`
- [ ] Performance baseline updated or explicitly not affected
- [ ] Privacy scan considered — no real VIN, GPS route, or private device endpoint data
- [ ] Not applicable (docs/CI-only change)

## Notes for reviewers

<!-- Anything that needs a closer look: tricky edge cases, screenshots for UI
     changes, baseline updates for dashboard-visual, coverage-floor bumps. -->

---

PR titles must follow Conventional Commits (`feat: …`, `fix: …`, `docs: …`, …) —
the `pr-title-lint` workflow enforces this. The repo squash-merges, so the PR
title becomes the commit subject on `main` and drives semantic-release version
bumps (`feat` = minor, `fix`/`perf` = patch, `BREAKING CHANGE` = major).
