# Changelog / release-notes templates

The Jinja2 templates in the repo-root [`templates/`](../templates/) directory
customize how **python-semantic-release** (PSR) renders `CHANGELOG.md` and each
GitHub Release body. PSR auto-discovers `templates/` because
`template_dir = "templates"` (the default) in
`pyproject.toml → [tool.semantic_release.changelog]`.

> ⚠️ This doc lives in `docs/`, NOT in `templates/`, on purpose: PSR renders
> every top-level file in `template_dir` to the repo root, so a
> `templates/README.md` is written over the project's root `README.md` on every
> release (this happened in v0.22.1). Keep non-`.j2` docs out of `templates/`.

They are a verbatim copy of PSR 9.21's bundled `angular/md` templates with a
single file modified: **`.components/changes.md.j2`**. The goal is a compact,
scannable style (inspired by the InkyPi releases) instead of PSR's default
verbose sections that dump the entire commit body.

## What changed vs the stock templates

`.components/changes.md.j2`:

1. **Emoji section headings** keyed off the conventional-commit type:
   - `feat`  → `### ✳️ New`
   - `fix`   → `### 🔺 Fix`
   - everything else (`perf`, `refactor`, `build`, `docs`, …) → `### 🔷 Changed`
2. **No body dump.** Only the one-line commit summary (subject + PR/commit
   links) is rendered, so long PR descriptions no longer bloat the changelog.
3. **Optional impact note.** If a commit body contains a paragraph beginning with
   `Impact:`, that one line is surfaced as an indented italic sub-bullet under
   the entry — a short "why it matters" without the noise of the full body.

Example output:

```
### ✳️ New

- **dashboard**: add a hide-outliers toggle to the efficiency chart ([#244](…))
  - _lets you drop noisy GPS spikes so the efficiency trend reflects real driving_
```

## Maintenance

Because this is a copy, a PSR major upgrade may ship template changes we don't
pick up automatically. When bumping PSR, diff the new bundled
`angular/md/.components/changes.md.j2` against this one and re-apply the three
tweaks above if the upstream file moved on.
