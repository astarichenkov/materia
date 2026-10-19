# Nightly roadmap status

Committing summary of the autonomous nightly sprint. The detailed journal and
the prompt/pipeline audit live in `docs/nightly/` (not versioned: `docs/` is
ignored by project policy).

- Branch: `feature/nightly-roadmap`
- Base: `feature/ux4-import-wizard` @ `1c570ab` (UX-4 is not merged into `develop` yet)
- Scope: library thumbnails, processing profiles, selective stage reruns,
  output format presets, variant history.

## Completed

1. **LIBRARY-1 (DONE)** — cached lecture thumbnails.
   - `thumbnail` package: cache table `lecture_thumbnail`, remote metadata
     thumbnail (SSRF-checked, size-capped, cached locally), FFmpeg candidate
     frames for local video with an informativeness heuristic, `NO_SOURCE` for
     audio-only, neutral placeholders in the UI.
   - Generation runs in a background queue or a pipeline stage, never inside the
     `/library` request. Bounded idempotent startup backfill (batch 10) plus an
     explicit `POST /api/library/thumbnails/backfill?limit=N` (max 50).
   - `GET /api/lectures/{id}/thumbnail` serves the cached JPEG by lecture id only.
   - `/library` has card/list views with 16:9 covers; dashboard and lecture card
     reuse the same cover.
2. **UX-5 / PIPELINE-1 (DONE for the core scenario)** — output profile and
   selective re-run.
   - `ProcessingProfile` (material type, output format, detail level, prompt
     template), persisted per lecture; legacy lectures keep the configured
     prompt, so nothing changes silently.
   - `RerunPlanService` previews `REUSE/RERUN/SKIP/BLOCKED` with reasons,
     reused/produced artifacts and a manual-edit warning. Plan is computed
     server-side and starts nothing.
   - `POST /lectures/{id}/rerun` saves the profile and submits the job.
   - Only stages whose own fingerprint changed run: a format-only change
     re-runs `TEXT_PROCESSING` + `EXPORT`, never download/Whisper.
3. **PIPELINE-1 fix** — `SOURCE_RESOLUTION` fingerprint was unstable for a
   locally uploaded lecture without a `media_source` row (first run created the
   row, second run invalidated everything and re-ran Whisper). Fixed and covered
   by an integration test. The coordinator no longer invalidates downstream
   checkpoints unconditionally.
4. **PROMPT-1B (DONE)** — 8 output-format prompt presets with explicit
   reliability rules (academic, brief, how-to, interview Q&A, methodical guide,
   article, FAQ, meeting protocol) plus the existing cleaned-transcript v2.
5. **Variant history (DONE)** — `lecture_output_variant` archives every produced
   document with profile/prompt/model provenance; `notes.md` and `notes.docx`
   are downloadable per variant and survive re-generation.
6. **PROMPT-1A (DONE)** — audit documented in
   `docs/nightly/prompt-pipeline-audit.md` (stage/dependency matrix).
7. **MEDIA-1 (DONE)** — local audio (MP3/M4A/WAV/FLAC): accepted by upload and
   the wizard, slides forced off, `NO_SOURCE` cover placeholder, pipeline
   verified with a real WAV fixture (transcript + DOCX, zero slides).
8. **UX-6 (PARTIAL)** — editor tabs are accessible (`role=tab`, arrow/Home/End
   navigation), sticky status bar shows versions and a live dirty indicator,
   `beforeunload` warns only on unsaved changes. Revision history, 409 conflict,
   restore and export already existed and are verified by E2E.
9. **UX-10 (PARTIAL)** — pipeline errors are sanitized (no absolute server
   paths, bounded length); corrupted-media E2E renders a friendly FAILED card
   with a retry action. Backfill idempotency verified live.

## Verification

- `./mvnw verify -Dspring-boot.repackage.skip=true` — BUILD SUCCESS,
  502 backend tests (1 skipped) + 28 private-ai tests. Repackage is skipped only
  because the owner's running application holds the jar file; it is unrelated to
  the code changes.
- E2E on an isolated storage root and port 18110 with real FFmpeg, whisper.cpp,
  qwen3:1.7b:
  - fresh upload → COMPLETED, thumbnail READY (`VIDEO_FRAME`), variant 1 archived;
  - format change → only `TEXT_PROCESSING` and `EXPORT` re-run; source/audio/
    transcript files unchanged (byte-level mtime comparison);
  - old and new Markdown/DOCX variants download with HTTP 200;
  - manual notes edit → rerun → HUMAN revision preserved; restore works; stale
    save returns 409;
  - server restart → thumbnail still served, mtime unchanged (no regeneration);
  - Playwright: 29/29 passed (including real wizard upload and the new
  cover/profile/plan/mobile/editor tests); HTTP smoke `scripts/smoke-ui.py` OK.

## Not done / known limitations

- Cross-chunk terminology/context propagation for long documents is still not
  implemented (only per-chunk structure + final summary).
- Editor UX-6 refinements beyond the existing revision/conflict/restore flow
  (sticky toolbar, tab keyboard navigation) were not part of this session.
- Local audio import (MEDIA-1), Discovery polish (UX-7), AI-tool polish (UX-8),
  settings split (UX-9) were not started.
- Advanced prompt management UI (PROMPT-1C: user copies, comparison on one
  transcript) is not started; the profile form exposes existing templates only.
- Cloud (DeepSeek) real calls were not performed; tests use local/mock providers.
- `POST /lectures`, `/lectures/from-url` and the new `/lectures/{id}/rerun`
  remain without CSRF, consistent with the pre-existing lecture endpoints; this
  is a documented risk, not a regression.

---

# Nightly Sprint 2 — prompt quality & regeneration progress

- Branch: `feature/nightly-prompt-quality` (base `feature/nightly-roadmap` @ `95f7b1b`).
- Journal: `docs/nightly/quality-progress.md`; baseline: `docs/nightly/prompt-quality-baseline.md`.

## Done

1. **PROGRESS-1 (DONE)** — run-scoped progress. New table `pipeline_run`
   (migration V11) records the stages of each run; `JobStatusView.RunView`
   exposes run id, state, per-stage states and real chunk progress
   (`Обработка текста: 7 из 18 фрагментов`). A new run never inherits the old
   100%; 100% is shown only after the run succeeds.
2. **BUG-2 (DONE)** — "обработка остановлена" is no longer rendered for
   COMPLETED/PARTIAL; success message is "Готово: документы созданы".
3. **PIPELINE-1b (DONE)** — `PipelinePlanner` is shared by preview and execution.
   Text-processing fingerprint uses the resolved provider/model/temperature, not
   the global default. Plan propagates dependent stages (TEXT → EXPORT) and
   no-op plans create no job and no duplicate variant.
4. **PROMPT-QUALITY (DONE)** — `NotesAssembler` merges headings, removes
   exact/near-duplicate paragraphs, repeated questions/bullets and echoed
   instructions. Quality report is persisted to `notes-quality.json` and shown
   on the lecture card. Format contracts are appended to the system prompt;
   transcript stays in `<transcript>` as data only.
5. **Compatibility matrix (DONE)** — `TemplateCompatibility` warns about
   interview/FAQ templates on non-interview material and mismatched formats,
   recommends a template, never switches silently.
6. **PROMPT-1C (DONE)** — `/settings/prompts`: built-in catalogue, copy, edit
   with versioning, restore, delete, validation. Custom templates are usable in
   the lecture profile and in the pipeline.
7. **Rerun CSRF (DONE)** — `/lectures/{id}/rerun` requires the session
   `_csrf` token; the lecture form now sends it.

## Verification

- `./mvnw verify -Dspring-boot.repackage.skip=true` — BUILD SUCCESS,
  508 backend tests (1 skipped) + 28 private-ai tests (2026-10-19).
  Repackage skipped only because the owner's app holds the jar.
- Real E2E on isolated :18120 / `target/e2e-nightly2` with real FFmpeg,
  whisper.cpp and qwen3:1.7b: fresh pipeline → format change preview →
  rerun (`TEXT_PROCESSING,EXPORT`) → observed chunk progress → COMPLETED →
  new variant 2, old variant 1 preserved, no repeated Baseline Q&A; no-op rerun
  created nothing; custom template version 2 used by the pipeline.
- `scripts/smoke-ui.py` OK; Playwright 25 passed / 4 skipped.

## Not done / limitations

- CSRF for `/process`, `/resume`, `/delete`, `/language` (unchanged from before).
- UX-6 side-by-side diff / variant comparison and retention policy are not
  started.
- Quality heuristics are signals, not a hard gate; a small local model may still
  produce imperfect structure, which is reported in the quality panel.
