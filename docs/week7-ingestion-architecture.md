# Week 7 — Ingestion & Resumable Pipeline Architecture

Status: implemented in `feature/week7-architecture-ingestion`.
Scope: architecture audit (AS-IS), target architecture (TO-BE), DB migration plan,
risks and acceptance criteria for the Week 7 / Day 31 preparation refactoring.

---

## 1. AS-IS architecture

Modular monolith `backend` (Java 25, Spring Boot 4.1.1, SQLite, Thymeleaf/HTMX).

### 1.1 Ingestion

- `LectureService.create(MultipartFile, clean, slides)` — validates extension/size,
  streams the upload into `<root>/lectures/<id>/source.<ext>` via
  `FileStorageService.saveSource`, and inserts a single `lecture` row.
- Only local multipart uploads are supported. The business pipeline is coupled to
  `MultipartFile` and to `Lecture.storedFilename`.
- `LectureApiController.upload(...)` (`POST /api/lectures`) and
  `LectureWebController.upload(...)` (`POST /lectures`) are the entry points.

### 1.2 Processing

- `LectureJobService.submit(id)` — bounded `ThreadPoolTaskExecutor`
  (`lectureJobExecutor`, 2 workers, queue 20) + an **in-memory**
  `ConcurrentHashMap` guard against duplicate runs.
- `LectureProcessingService.process(id)` — a single monolithic method that runs:
  `EXTRACTING_AUDIO → TRANSCRIBING → PROCESSING_TEXT → (RAG index) → EXPORTING`.
  - FFmpeg audio extraction and duration probe.
  - whisper.cpp transcription → `transcript.json` + `transcript_segment` rows.
  - Ollama LLM (`cleanTranscript` + `generateStructuredNotes`).
  - Optional embedding index for RAG.
  - `transcript.md`, `notes.md`, `notes.docx`; slides inside EXPORTING.
- Stage timings recorded in `lecture_stage_timing`.

### 1.3 Recovery / cancellation

- `StaleJobRecovery` (an `ApplicationRunner`) marks **every** non-terminal lecture
  `FAILED` on startup with "restart processing". No checkpoint reuse.
- No cancellation at all. External processes are not tracked per job.

### 1.4 External process execution

- `DefaultProcessExecutor` — `ProcessBuilder` without a shell, captures full
  stdout/stderr into memory, enforces a timeout, kills the process tree on
  timeout. No streaming, no progress callbacks, no cancellation token.

### 1.5 Slides

- `SlideExtractionService.extract` calls `FfmpegService.extractFrames` which asks
  FFmpeg to write **all** frames at `fps=1/interval`, then applies
  `.limit(candidateLimit)` in Java. Unbounded candidate generation.
- Errors are swallowed by `extractSlidesSafely` (logged, `List.of()` returned).
  A lecture can be `COMPLETED` with silently missing slides.

### 1.6 Persistence

- Tables: `lecture`, `transcript_segment`, `lecture_chunk`, `chat_message`,
  `slide`, `benchmark_run`, `app_setting`, `lecture_stage_timing`.
- All created via `CREATE TABLE IF NOT EXISTS` in `db/schema.sql`
  (`spring.sql.init.mode=always`). There is no versioned migration mechanism and
  `CREATE TABLE IF NOT EXISTS` cannot evolve existing tables.

## 2. Problems

1. Ingestion coupled to `MultipartFile`; no remote source, no metadata, no artifact model.
2. Monolithic pipeline — any failure (e.g. LLM) forces a full reprocess including
   expensive FFmpeg + Whisper work.
3. Duplicate-run protection is process-local (lost on restart) and not atomic across
   the DB.
4. Restart recovery discards valid intermediate results and never resumes.
5. No cancellation; a "Cancelled" state cannot be trusted.
6. No download progress / metadata, no yt-dlp integration, no SSRF protection.
7. Unbounded slide frame extraction; slide errors hidden; no partial-success status.
8. No versioned DB migrations.
9. Stage checkpoints are not persisted, so "is this artifact still valid?" cannot be answered.

## 3. TO-BE architecture

```
MediaSource ──resolve──▶ MediaArtifact ──▶ PipelineCoordinator
                                              │
   ┌──────────────┬──────────────┬────────────┴───────────┐
   ▼              ▼              ▼                        ▼
SOURCE_RESOLUTION DOWNLOADING  MEDIA_VALIDATION ... EXPORT ──▶ COMPLETED
   (LOCAL_FILE)   (REMOTE_URL)      │
                                    ├─ AUDIO_EXTRACTION
                                    ├─ TRANSCRIPTION
                                    ├─ TEXT_PROCESSING
                                    ├─ RAG_INDEXING (optional, non-fatal)
                                    ├─ SLIDE_EXTRACTION
                                    └─ EXPORT
```

### 3.1 Media source abstraction (`media`)

- `MediaSourceKind { LOCAL_FILE, REMOTE_URL }`.
- `MediaSource` — lecture id, kind, original name/url, platform, quality, audioOnly.
- `MediaMetadata` — title, provider, externalId, uploader, durationMs, formats,
  resolution, estimated size, originalUrl.
- `MediaArtifact` — lectureId, relative path, media type, size, duration,
  source, checksum.
- `SourceResolutionResult` — resolved artifact + metadata + warnings.
- A `MediaSource` row is persisted per lecture (`media_source` table).
- Resolution (local existence check / remote allow-list + SSRF + metadata probe)
  is implemented by `SourceResolutionStage`; a separate resolver class was not
  needed because resolution is a pipeline stage with its own checkpoint.

`PipelineDataLoader` rebuilds source/artifact/transcript/notes from persisted
state so a stage skipped by a valid checkpoint still restores the data required
by downstream stages.

### 3.2 Download (`download`)

- `VideoDownloader` / `YtDlpVideoDownloader` — `ProcessBuilder` + installed `yt-dlp`.
- `VideoMetadataService` — `yt-dlp -J` machine-readable probe.
- `DownloadRequest` / `DownloadProgress` / `DownloadResult`.
- `VideoPlatform` — allow-list: YouTube (`youtube.com`, `youtu.be`),
  `vkvideo.ru`, `vk.com`, `rutube.ru`.
- `UrlSafetyValidator` — HTTPS required, host allow-list, DNS resolution and
  rejection of loopback/private/link-local/multicast addresses, bounded time/size.
- Quality: AUTO / 480p / 720p / 1080p, nearest-available fallback; audio-only mode.
  FFmpeg availability is detected from an explicit path or PATH search; when
  available, yt-dlp merges separate video/audio streams, otherwise it falls back
  to progressive-only formats.
- Progress via `--progress-template` machine-readable lines; cancellation destroys
  the process tree; temp file + atomic move.

### 3.3 Resumable pipeline (`pipeline`)

- `PipelineStage` enum: `SOURCE_RESOLUTION, DOWNLOADING, MEDIA_VALIDATION,
  AUDIO_EXTRACTION, TRANSCRIPTION, TEXT_PROCESSING, RAG_INDEXING,
  SLIDE_EXTRACTION, EXPORT`.
- `StageExecutor` — `id()`, `inputFingerprint(JobContext)`, `execute(JobContext)`.
- `StageResult` — status (`COMPLETED`/`SKIPPED`/`FAILED`/`CANCELLED`), artifact
  manifest, error, timings.
- `StageCheckpoint` — persisted per (lecture, stage): state, fingerprint, attempt,
  timestamps, error, artifact manifest JSON.
- `ArtifactManifest` — list of expected artifacts + existence/size validation.
- `JobContext` — mutable per-run holder: lecture, artifact, config, cancellation.
- `PipelineCoordinator` — claims a DB lease, runs stages in order, skips valid
  checkpoints, updates `lecture.status`, handles cancel/retry.

#### Checkpoint validity and invalidation

A checkpoint is reused only when **state == COMPLETED**, its stored fingerprint
equals the freshly computed `inputFingerprint`, and every artifact in its manifest
still exists (and is non-empty). Otherwise the stage and its dependents run again.

Fingerprint inputs:
- `AUDIO_EXTRACTION`: source checksum + audio params.
- `TRANSCRIPTION`: audio checksum + whisper model + language.
- `TEXT_PROCESSING`: transcript hash + LLM config (provider/model/temp/max/context/
  prompt/clean).
- `RAG_INDEXING`: transcript hash + embedding model + chunk size/overlap.
- `SLIDE_EXTRACTION`: source checksum + interval/max/hash threshold.
- `EXPORT`: notes hash + cleaned text hash + docx template version + slide manifest.

### 3.4 Persistence / migrations

New tables (added by versioned migration `V2__week7_ingestion.sql`, tracked in
`schema_migration`):

- `media_source(lecture_id PK, kind, original_url, original_filename, provider,
  external_id, title, uploader, duration_ms, quality, audio_only, metadata_json,
  checksum, created_at)`
- `pipeline_job(lecture_id PK, state, current_stage, attempts, lease_owner,
  lease_until, error, created_at, updated_at)`
- `stage_checkpoint(lecture_id, stage, state, fingerprint, attempt, started_at,
  finished_at, error, artifact_manifest_json, PRIMARY KEY(lecture_id, stage))`
- `download_state(lecture_id PK, status, bytes_downloaded, bytes_total, percent,
  speed_bps, eta_seconds, error, updated_at)`

Existing `lecture` rows and APIs remain compatible; `job_status` gains
`CANCELLED`. The `lecture.status` column is a free-text column, so no schema change
is needed for the new enum value.

### 3.5 Concurrency and recovery

- Atomic claim/lease in `pipeline_job`:
  `UPDATE ... SET state='RUNNING', lease_owner=?, lease_until=? WHERE lecture_id=?
   AND (state NOT IN ('RUNNING') OR lease_until < now)`. SQLite pool size is 1 so
  the statement is serialized. In-memory map remains only as a fast path.
- CPU/GPU stages serialized by a `Semaphore` shared by transcription and LLM
  (configurable permits, default 1) so Whisper/Ollama/benchmarks do not
  oversubscribe RAM/VRAM.
- On startup, `PipelineRecovery` finds jobs whose lease expired / state is RUNNING
  or non-terminal and leaves them `RETRYABLE` (state `INTERRUPTED`), so the next
  `resume` reuses checkpoints. Valid completed stages are never discarded.

### 3.6 Cancellation

- `CancellationToken` per job; `PipelineCoordinator.cancel(id)` flips it.
- `StreamingProcessExecutor` (yt-dlp) polls the token directly and destroys the
  process tree on cancel.
- `DefaultProcessExecutor` (FFmpeg/Whisper) observes a worker-thread
  `CancellationContext` set by the coordinator, so those process trees are also
  terminated promptly.
- The coordinator persists `CANCELLED` only after the external process has
  actually exited / been killed — it is never reported while work continues at
  the OS level. Completed stage checkpoints are preserved.
- LLM HTTP calls are not forcibly aborted; cancellation is observed at the next
  stage boundary.

### 3.7 API / UI

- `POST /api/lectures/from-url` (JSON), `GET /api/media/metadata?url=...`,
  `GET /api/lectures/{id}/progress`, `POST /api/lectures/{id}/cancel`,
  `POST /api/lectures/{id}/resume`.
- Existing endpoints unchanged.
- UI: source selector (Local file / Video URL), URL + quality + audio-only +
  slides, metadata preview, stage-based progress, download speed/ETA, cancel and
  retry, artifact links.

## 4. Compatibility

- Local upload flow and all existing endpoints keep working unchanged.
- Old `lecture` rows migrate transparently; missing `media_source` rows are
  lazily created as `LOCAL_FILE`.
- `GET /lectures/{id}` still returns a `Lecture`; new data is exposed via new
  endpoints.
- Old schema is preserved; only additive `CREATE TABLE IF NOT EXISTS` + version row.

## 5. Risks

- Platform extractors change often; support is "best effort" and verified only by
  live smoke tests performed here.
- `yt-dlp` resolves CDN hosts itself; Java-side SSRF validation covers the
  original URL and its DNS, not every subsequent connection (documented limitation).
- SQLite is single-connection; long transactions must be avoided. Leases are
  short statements, not held for the whole stage.
- CPU-only Whisper/Ollama remain the dominant cost; pipeline only avoids
  *redundant* work, it does not make a single run faster.

## 6. Acceptance criteria

See the final report `docs/week7-ingestion-final-report.md` and the task checklist:
local files unchanged; MediaSource + MediaArtifact; yt-dlp downloader with
metadata/quality/audio-only/progress/cancel/retry; stage checkpoints in SQLite;
LLM failure does not re-run Whisper; restart-safe resume; bounded slide
extraction; slide errors surfaced; old data/API compatible; URL UI; automated
tests; `mvn verify` green on Java 25; docs + AGENTS.md updated; Gitea push.
