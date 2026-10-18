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

## Verification

- `./mvnw verify` — run at the end of the session (see final report / journal).
- E2E on an isolated storage root and port 18110 with real FFmpeg, whisper.cpp,
  qwen3:1.7b:
  - fresh upload → COMPLETED, thumbnail READY (`VIDEO_FRAME`), variant 1 archived;
  - format change → only `TEXT_PROCESSING` and `EXPORT` re-run; source/audio/
    transcript files unchanged (byte-level mtime comparison);
  - old and new Markdown/DOCX variants download with HTTP 200;
  - manual notes edit → rerun → HUMAN revision preserved; restore works; stale
    save returns 409;
  - server restart → thumbnail still served, mtime unchanged (no regeneration);
  - Playwright: all existing pages + 4 new cover/profile/plan/mobile tests pass.

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
