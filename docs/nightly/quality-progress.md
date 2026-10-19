# Nightly Sprint 2 — quality progress journal

Branch: `feature/nightly-prompt-quality`
Base: `feature/nightly-roadmap` @ `95f7b1b` (includes UX-2/3/4, nightly roadmap)

## Task log

| # | Task | Status | Root cause / notes |
|---|------|--------|--------------------|
| 0 | Git + baseline audit | DONE | `docs/nightly/prompt-quality-baseline.md` |
| 1 | PROGRESS-1 run-scoped progress | DONE | Progress was lecture-scoped (all completed checkpoints / all stages). Added `pipeline_run` (V11), `PipelineRun`, `PipelineRunRepository`, `JobStatusView.RunView`, run block in `status.html`, real chunk progress from `TextProcessingStage`. |
| 2 | BUG-2 false terminal message | DONE | Template printed "обработка остановлена" for every terminal state. Now only for non-COMPLETED/PARTIAL; success message is "Готово: документы созданы". |
| 3 | PIPELINE-1b plan == execution | DONE | `PipelinePlanner` is now used by both `RerunPlanService` and `PipelineCoordinator`. Text fingerprint uses the resolved task config (provider/model/temperature), not the global default. Plan propagates dependent stages (TEXT → EXPORT) and no-op plans produce nothing. |
| 4 | PROMPT-QUALITY dedupe | DONE | `NotesAssembler` merges headings, removes exact/near-duplicate paragraphs, repeated questions/bullets, echoed instructions; quality report persisted to `notes-quality.json` and shown on the lecture card. |
| 5 | Format contract + compatibility | DONE | `OutputFormat.structureContract()` appended to the system prompt; `TemplateCompatibility` warns (never silently switches) for interview/FAQ on non-interview material or mismatched formats. |
| 6 | Prompt separation | DONE | System message = template + format contract + task parameters + reliability rules. User message = `<transcript>…</transcript>` only. |
| 7 | PROMPT-1C template studio | DONE | V11 `prompt_template`, `PromptTemplateRepository/Service`, `/settings/prompts` UI: catalog, copy built-in, edit (version++), restore, delete, validation. Custom bodies are registered in `PromptTemplates` and selectable in the lecture profile. |
| 8 | CSRF for rerun | DONE | `/lectures/{id}/rerun` requires the session CSRF token (`_csrf` in the form). Other lecture endpoints keep their previous model (documented gap). |
| 9 | Tests | DONE | 508 backend tests; new regression tests for progress, message, dedupe, compatibility, no-op plan, planner/execution parity. |
| 10 | Real E2E | DONE | Isolated port 18120, storage `target/e2e-nightly2`, real FFmpeg/whisper/qwen3:1.7b; see below. |
| 11 | UX-6 additional polish | PARTIAL | Existing editor tabs/conflict/restore remain; no new side-by-side view this session. |
| 12 | Retention/variant cleanup | NOT STARTED | Needs an agreed policy; no automatic deletion was added. |
| 13 | Packaged JAR check | PARTIAL | `./mvnw verify -Dspring-boot.repackage.skip=true` green; user app on :8080 holds the jar so repackage was skipped by design. |

## Real E2E (2026-10-19)

Lecture `14f68477-3f91-4918-a726-0f8eb8a98722`
(`demos/fixtures/sample-lecture-ru.mp4`, 45 s).

1. Fresh pipeline → `COMPLETED`, variant 1 (`DETAILED_NOTES`, `structured-notes-v1`).
2. Preview `WEBINAR × BRIEF_NOTES × notes-interview-v1`:
   `TEXT_PROCESSING=RERUN`, `EXPORT=RERUN`, transcript `REUSE`, compatibility warning shown.
3. `POST /lectures/{id}/rerun` → new run id
   `6955e72f-ff74-4369-b90f-7273d90d0c69`, planned stages exactly
   `TEXT_PROCESSING,EXPORT`.
4. Observed progress samples:
   `RUNNING TEXT_PROCESSING 0% → 1 из 1 фрагментов → EXPORT → COMPLETED 100%`.
   Whisper/audio checkpoints were reused (audio/source mtimes unchanged).
5. New variant 2 archived; variant 1 still downloadable; new notes have **0**
   repeated `Baseline?` questions and 5 unique sections.
6. No-op rerun with the same profile: `data-plan-noop="true"`, variant count
   stayed 3, `pipeline_run` count stayed 3 (no job, no duplicate document).
7. Custom template copy (`notes-custom-notes-brief-v1-…`) → update (version 2)
   → rerun: variant 3 references the custom template, run stages
   `TEXT_PROCESSING,EXPORT`; the custom body is used by the LLM.
8. `scripts/smoke-ui.py` OK, Playwright 25 passed / 4 skipped.

## Known limitations

- Local `qwen3:1.7b` can still echo a language or produce a section that the
  format contract does not require; compatibility warnings and quality report
  surface this, but a big model is better for interview/FAQ.
- `EXPORT` progress percentage uses stage/chunk data; per-chunk progress exists
  only for TEXT_PROCESSING (export is one atomic step).
- Retention of `lecture_output_variant` is unbounded; no automatic deletion.
- CSRF is enforced only for rerun (plus pre-existing editor/support/settings);
  other lecture mutation endpoints remain as before.

## Next steps

1. Extend CSRF coverage to `/process`, `/resume`, `/delete`, `/language`.
2. Side-by-side diff and variant comparison in the editor (UX-6).
3. Retention: size accounting, archive/confirm-delete policy.
4. Optional outline pass for very long lectures when a larger local model is
   configured.
