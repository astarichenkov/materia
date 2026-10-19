# Nightly roadmap progress

- Started: 2026-10-19 (local, nightly autonomous sprint)
- Base branch: `feature/ux4-import-wizard` @ `1c570ab` (UX-4 not yet merged into `develop`)
- Working branch: `feature/nightly-roadmap`
- No merges to `develop`, no default-branch changes, no force push, no GitHub push.

## Tasks

| # | Task | Status | Notes |
|---|------|--------|-------|
| 0 | Audit (git, roadmap, code) | DONE | Pipeline stages, checkpoints, prompts, editor, library inspected |
| 1 | LIBRARY-1 thumbnails + cache + backfill + API | DONE | commit ad33afc |
| 2 | LIBRARY-1 card/list library UI | DONE | commit ad33afc |
| 3 | PROMPT-1A audit doc | DONE | docs/nightly/prompt-pipeline-audit.md |
| 4 | UX-5 processing profile + rerun plan preview | DONE | commit ad33afc |
| 5 | PIPELINE-1 selective rerun + variants history | DONE | commit ad33afc + fingerprint fix |
| 6 | PROMPT-1B output format presets | DONE | 8 prompts |
| 7 | Tests + verify + E2E | IN_PROGRESS | real E2E passed; full verify pending |
| 8 | Editor UX-6 polish (sticky toolbar/tabs) | NOT STARTED | |
| 9 | MEDIA-1 local audio import | NOT STARTED | |
| 10 | PROMPT-1C template management UI | NOT STARTED | |

## Changed files

(updated after each step)

## Test results

targeted unit tests green; E2E on port 18110 green (see NIGHTLY_STATUS.md)

## Blockers

(none yet)
