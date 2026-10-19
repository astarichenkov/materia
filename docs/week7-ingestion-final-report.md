# Week 7 — Ingestion & Resumable Pipeline: Final Report

Branch: `feature/week7-architecture-ingestion`
Gitea: http://203.0.113.20:8108/example-org/materia
Baseline commit: `8648b53` (`chore: checkpoint before week7 ingestion refactoring`)
Environment: Windows / Git Bash, Java 25 (`C:\tools\jdk-25.0.2-full`),
Maven 3.9.11, FFmpeg 9.0.2, whisper.cpp b5454 (`ggml-small.bin`),
Ollama 0.40.2 (`qwen3:1.7b`), yt-dlp 2026.08.19.

---

## 1. AS-IS / TO-BE

### AS-IS
- `LectureService.create(MultipartFile)` — local uploads only; business pipeline
  coupled to `MultipartFile` / `Lecture.storedFilename`.
- `LectureProcessingService.process()` — one monolithic method
  (`EXTRACTING_AUDIO → TRANSCRIBING → PROCESSING_TEXT → RAG → EXPORTING`).
- `LectureJobService` — in-memory `ConcurrentHashMap` duplicate guard (lost on restart).
- `StaleJobRecovery` — marked every non-terminal lecture `FAILED`; no resume.
- `DefaultProcessExecutor` — buffered, non-cancellable.
- `FfmpegService.extractFrames` — generated all frames, Java `.limit()` afterwards.
- Slide errors swallowed; `COMPLETED` lecture could silently have no slides.
- No versioned DB migrations.

### TO-BE
`MediaSource → yt-dlp → MediaArtifact → Resumable Pipeline → Whisper/LLM/Slides → DOCX`
with persisted per-stage checkpoints, lease-based concurrency, restart recovery,
real cancellation and a URL ingestion UI/API.

Architecture details: `docs/week7-ingestion-architecture.md`.

## 2. Changes

### Reused existing components
- FFmpeg/FFprobe (`FfmpegService`), whisper.cpp (`WhisperCppTranscriptionService`,
  `WhisperTranscriptParser`, `TranscriptSanitizer`), Ollama (`OllamaLlmProvider`),
  `TextProcessingService`, `TextChunker`, `MarkdownBuilder`, `DocxExporter`,
  `SlideExtractionService`, `PerceptualHash`, RAG (`EmbeddingIndexService`),
  SQLite repositories, Thymeleaf/HTMX UI, benchmark/settings infrastructure.

### Added components
- **Media abstraction** (`media`): `MediaSource`, `MediaSourceKind`, `MediaArtifact`,
  `MediaMetadata`, `SourceResolutionResult`.
- **Downloader** (`download`): `VideoDownloader`, `YtDlpVideoDownloader`,
  `VideoMetadataService`, `VideoPlatform` (allow-list), `UrlSafetyValidator`
  (HTTPS-only, DNS/private-IP SSRF guard), `MediaQuality`, `FormatSelector`,
  `YtDlpProgressParser`, `DownloadRequest/Progress/Result/State`.
- **Process execution**: `StreamingProcessExecutor` (streaming progress,
  bounded buffers, cancellation, process-tree kill), `CancellationToken`,
  `ProcessCancelledException`, `CancellationContext` (thread-local token for
  FFmpeg/Whisper via `DefaultProcessExecutor`).
- **Resumable pipeline** (`pipeline`): `PipelineStage`, `StageExecutor`,
  `PipelineCoordinator`, `JobContext`, `StageResult`, `StageCheckpoint`,
  `StageState`, `ArtifactManifest`, `Fingerprints`, `HeavyStageLimiter`,
  `PipelineDataLoader`, `JobState`, `PipelineJob`, `JobProgress`,
  `JobProgressRegistry`, `JobStatusService`, `JobStatusView`, and stage
  implementations under `pipeline.stage`.
- **Persistence**: `MediaSourceRepository`, `PipelineJobRepository`,
  `StageCheckpointRepository`, `DownloadStateRepository`, `SchemaMigrator`.
- **API/UI**: `RemoteLectureService`, URL endpoints, `FromUrlRequest`,
  URL ingestion + progress/cancel/resume UI.

### Behavioural fixes
- Slide frame generation is now bounded by FFmpeg (`-frames:v`); extraction is an
  explicit stage; failures produce `PARTIAL` + warning instead of being hidden.
- `StaleJobRecovery` preserves checkpoints and marks jobs `INTERRUPTED`.
- `LectureRepository.deleteById` explicitly cleans the new tables.
- FFmpeg detection fixed for bare command names (PATH search), so yt-dlp can
  merge A/V streams for videos without progressive formats.

## 3. Database migration

Versioned migration `db/migration/V2__week7_ingestion.sql`, tracked in
`schema_migration` (version 2):

- `media_source` (kind, url, provider, title, duration, quality, audio_only,
  metadata_json, checksum, …)
- `pipeline_job` (state, current_stage, attempts, lease_owner, lease_until, error)
- `stage_checkpoint` (state, fingerprint, attempt, timestamps, error,
  artifact_manifest_json; PK `(lecture_id, stage)`)
- `download_state` (status, bytes, percent, speed, eta, error)

Existing `lecture`/transcript/RAG/benchmark tables and **all existing rows are
preserved**. Migration is idempotent and runs at startup before any repository
use. Covered by `SchemaMigratorTest` (legacy DB with a lecture row → migrate →
row intact, new tables present, re-run idempotent).

`JobStatus` gained `DOWNLOADING, VALIDATING, EXTRACTING_SLIDES, PARTIAL,
INTERRUPTED, CANCELLED` (stored as free text in `lecture.status`, no column change).

## 4. Test results

```
mvn -pl backend test
Tests run: 118, Failures: 0, Errors: 0, Skipped: 0
```

New tests:
- `PipelineCoordinatorIntegrationTest` (full Spring context + real SQLite):
  full local lifecycle + DOCX; **LLM failure does not re-run Whisper** (verify
  transcription called once across two runs); prompt/model change re-runs
  TEXT+EXPORT but not transcription; idempotent second run reuses all stages;
  lease rejects a concurrent claim.
- `SchemaMigratorTest` — legacy DB migration without data loss.
- `VideoPlatformTest` — platform detection, allow-list, URL/SSRF rejection,
  private-address detection.
- `FormatSelectorTest`, `YtDlpProgressParserTest`, `YtDlpMetadataParsingTest`.
- `ArtifactManifestTest`, `CancellationTokenTest`,
  `DefaultProcessExecutorTest.cancellationStopsLongRunningProcess`.

Full build: `./mvnw verify` on Java 25 (backend + private-ai-chat).

## 5. Live smoke tests (real, not mocked)

Real `yt-dlp` (installed 2026.08.19) and real public videos were used.

### Java downloader (`YtDlpVideoDownloader`)
| Platform | URL (public) | Probe | Download |
|----------|--------------|-------|----------|
| YouTube | `youtube.com/watch?v=jNQXAC9IVRw` | OK (title/duration/24 formats) | **OK** — `source.mp4`, 475 989 bytes |
| VK Видео | `vkvideo.ru/video-220754053_456239018` | OK (VK, 2814 s, 53 formats) | not run (47 min video) |
| VK | `vk.com/video-220754053_456239018` | OK (VK, 2814 s, 53 formats) | not run (47 min video) |
| Rutube | `rutube.ru/video/1884d1234b77573a0ec227c881dc69fc/` | OK (Rutube, 1388 s, 24 formats) | real section download OK (487 300 bytes) |

### Real application E2E (HTTP API, running backend)
- `GET /api/lectures/metadata` → YouTube metadata OK.
- `POST /api/lectures/from-url` (YouTube, quality 480p, slides=true) → full
  pipeline **COMPLETED** with real download → FFmpeg → whisper.cpp (`ggml-small`)
  → Ollama `qwen3:1.7b` (think off) → RAG index → slide `frame_00001.jpg` →
  `notes.md` (1038 chars) → `notes.docx` (18 883 bytes), duration 19 014 ms,
  all 9 stage checkpoints `COMPLETED`.
- `POST /{id}/resume` after success → all 9 stages reused from checkpoints.
- `POST /{id}/cancel` during VK download → status `CANCELLED`, no lingering
  `yt-dlp`/`ffmpeg` processes.
- Kill app mid-Rutube-download → restart → lecture/job `INTERRUPTED`,
  `SOURCE_RESOLUTION` checkpoint preserved → resume re-downloaded (partials
  cleaned) and advanced to transcription → cancel stopped whisper in ~1 s.

### Not verified live
- Full pipeline for VK/VK Video/Rutube (only metadata + a bounded Rutube/VK
  section download were verified; full runs were skipped to avoid very long
  CPU transcription and large downloads).
- Private/auth-only videos, DRM, playlists — intentionally out of scope.

## 6. Known limitations

- `yt-dlp` resolves CDN hosts itself; SSRF validation covers the initial URL and
  its DNS, not every subsequent CDN connection.
- Platform extractors change frequently; support for all URLs is best-effort.
- LLM HTTP requests are not aborted mid-flight; cancellation is observed at the
  next stage boundary. FFmpeg/Whisper/yt-dlp process trees are killed promptly.
- Whisper on CPU dominates runtime; the pipeline avoids redundant work but does
  not speed up a single run.
- Progress is stage-based (no invented percentages); only the download stage has
  a byte-level percentage/speed/ETA.
- `SOURCE_RESOLUTION` probes metadata synchronously at lecture creation; for very
  large pages this blocks the HTTP request (bounded by the metadata timeout).

## 7. Recommendations for Day 31

- Reuse `TaskExplorer`-style read-only views via `JobStatusService` /
  `PipelineJobRepository` / `StageCheckpointRepository` — no new persistence.
- Expose `PipelineCoordinator` cancellation and `JobProgress` for assistant-driven
  status/retry.
- Keep the Developer Assistant docs RAG collection separate from lecture RAG.
- Add an MCP read-only tool surface over the existing repositories/services
  (`git_current_branch`, `get_job_status`, `get_stage_runs`, `search_help_docs`).
- Consider adding `--download-sections` support (bounded ingestion) for future
  assistant tests with long public videos.

## 8. Acceptance checklist

- [x] Baseline saved and pushed to Gitea (`8648b53`, backup branch
  `backup/pre-week7-ingestion-20261016-0212`).
- [x] Dedicated working branch `feature/week7-architecture-ingestion`.
- [x] Local uploads unchanged.
- [x] `MediaSource` + `MediaArtifact` abstractions.
- [x] yt-dlp downloader with metadata probe and quality/audio-only.
- [x] URL support (YouTube/VK Video/VK/Rutube) at integration level + live probes.
- [x] Download progress, cancel, retry.
- [x] Pipeline split into stages; checkpoints in SQLite; LLM failure does not
  re-run Whisper; restart-safe resume.
- [x] Bounded slide extraction; slide errors surfaced as `PARTIAL`.
- [x] Old data/API compatible; versioned migration tested.
- [x] URL UI + new API endpoints.
- [x] Automated tests (118 passing).
- [x] `mvn verify` green on Java 25.
- [x] README, architecture doc and AGENTS.md updated.
- [x] Final commits pushed to Gitea (final SHA in the task summary).
