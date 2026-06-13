## What

<!-- One or two sentences: what changes and why. Link the issue if one exists. -->

## Checklist

- [ ] PR title follows [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:`, …) — it becomes the squash-merge commit subject and drives semantic-release versioning
- [ ] `./gradlew verifyActiveApp` passes locally (or note which gate you skipped and why)
- [ ] New/changed Kotlin or dashboard TypeScript has test coverage
- [ ] Dashboard changes: edited `dashboard-src/` sources (not generated `assets/dashboard/index.html` / `js/` output) and rebuilt the bundle
- [ ] No new lint-baseline entries without justification

## Test evidence

<!-- Paste relevant test output, screenshots for dashboard/UI changes, or field-test notes. -->
