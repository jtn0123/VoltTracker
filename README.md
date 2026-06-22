# Changelog / release-notes templates

These Jinja2 templates customize how **python-semantic-release** (PSR) renders
`CHANGELOG.md` and each GitHub Release body. PSR auto-discovers this directory
because `template_dir = "templates"` (the default) in
`pyproject.toml → [tool.semantic_release.changelog]`.

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
