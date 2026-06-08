# Release-Candidate Checklist

Use this before tagging or requesting final release approval. The goal is to
state exactly what the candidate proved and what still depends on CI, emulator,
phone, adapter, or real-car evidence.

## Candidate Identity

- Branch:
- Commit:
- Version / expected tag:
- APK under test:
- Phone or emulator:
- WebView version:
- OBD adapter:
- Vehicle state:

## Required Local Proof

- `scripts/release-preflight.sh`:
- `python .github/scripts/semantic_release_dry_run.py` or Release dry run workflow:
- Generated dashboard clean:
- Bundle budget:
- Dependency audit status:

## Runtime Proof Reached

Record the highest validation level reached. Use `docs/validation-matrix.md` for
what each level proves and does not prove.

- Desktop dashboard:
- Emulator WebView:
- Physical phone:
- Real adapter:
- Real car / OBD:

## Evidence To Attach

- JSONL session log:
- SQLite database pull:
- Screenshots or screen recording:
- Playwright / emulator smoke artifacts:
- Notes for unreproduced or intentionally skipped checks:

## Release Decision

- Ready to tag:
- Blocking issues:
- Follow-up issues:
- Reviewer:
- Date:
