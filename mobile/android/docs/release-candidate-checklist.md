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

## End-User Dogfood Gate

This is a release gate, not an optional polish note. Use at least three people
who did not build the feature. A facilitator may observe and record hesitation,
but may not tell participants where to tap. Any repeated wrong turn or request
for help is a release blocker until it is fixed and re-tested.

- End-user dogfood participants:
- Setup without facilitator help:
- First drive without facilitator help:
- Trip review without facilitator help:
- Charge review without facilitator help:
- Code scan without facilitator help:
- Repeated hesitation issues resolved:
- Dogfood evidence:

For each workflow, record time to completion, wrong turns, exact words that
caused hesitation, and the final screenshot or recording. Use `yes` only after
all participants complete that workflow without instruction.

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
