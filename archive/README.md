# Archived: Volt Tracker web receiver

This directory holds the **deprecated** self-hosted web application — a Flask +
PostgreSQL/TimescaleDB service that ingested Torque Pro telemetry uploads and
served an analytics dashboard.

It has been superseded by the standalone Android app in `mobile/android/`, which
connects to the OBD adapter directly, keeps all data on-device, and needs no
server. The app's own "Back up data" feature exports the database to a file, so
the server's role as a data sink is no longer needed.

Nothing in here is maintained. It is kept for reference and git history only.

Contents:

- `receiver/` — the Flask app (routes, services, models, migrations).
- `tests/`, `e2e/` — the web app's Python and Playwright test suites.
- `ci-workflows/` — the GitHub Actions workflows that ran against it. These are
  inert here: GitHub only runs workflows from the repo-root `.github/workflows/`.
- `db/`, `torque-config/`, `scripts/`, build/lint config, and design docs.
