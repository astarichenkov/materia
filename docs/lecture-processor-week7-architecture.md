# Lecture Processor — Architecture Review & Week 7 Plan

## Source-based findings

Project: Java 25 / Spring Boot modular monolith (`backend`), plus independent `private-ai-chat` service. This is not a Python backend. The reviewed ZIP contains existing FFmpeg, whisper.cpp, Ollama, RAG, SQLite, DOCX, slide extraction, benchmark and asynchronous job infrastructure. No yt-dlp, MCP server, or Gitea review implementation was identified in the reviewed source tree.

### Key findings
1. `LectureService.create(MultipartFile)` and `LectureProcessingService.process()` assume a locally available media artifact. Introduce a `MediaSource` abstraction with FILE and REMOTE_URL kinds, resolving both into a validated media artifact.
2. `StaleJobRecovery` marks all nonterminal jobs FAILED on startup. Distinguish interrupted/retryable stages and persist checkpoints, rather than always restarting the entire pipeline.
3. `DefaultProcessExecutor` buffers all stdout/stderr and does not expose streaming progress or per-job cancellation. Introduce a streaming/cancellable extension for yt-dlp rather than changing all existing callers.
4. Slide extraction currently happens inside EXPORTING and errors are swallowed. Make EXTRACTING_SLIDES an explicit resumable stage and communicate partial success.
5. `FfmpegService.extractFrames()` generates frames before applying Java `.limit(maxFrames)`: enforce candidate bounds in FFmpeg itself and clean transient frames.
6. `LectureJobService` uses an in-memory running set, not persisted job leases; duplicate submission after restarts or in multi-instance deployment needs explicit concurrency control.
7. `Lecture` combines media metadata, processing lifecycle, and output data. Avoid bloating the record with downloader-specific fields; use related tables.

## Recommended logical architecture

UI/REST -> LectureApplicationService -> JobCoordinator -> StageRunner

Input: LocalUploadSourceResolver OR RemoteUrlSourceResolver -> MediaArtifact

Stages: SOURCE_RESOLUTION -> DOWNLOAD (remote only) -> MEDIA_VALIDATION -> AUDIO_EXTRACTION -> TRANSCRIPTION -> TEXT_PROCESSING -> SLIDE_EXTRACTION (if selected) -> EXPORT -> COMPLETE

Auxiliary: RAG indexing as independently tracked optional stage; product Support Agent with read-only diagnostic tools; developer RAG/MCP assistant; Gitea PR reviewer. Keep them logically isolated from lecture processing and privileged filesystem operations.

### Interfaces (illustrative)

```java
interface MediaSourceResolver {
    boolean supports(MediaSource source);
    ResolvedMedia resolve(MediaSource source, JobContext context);
}

interface PipelineStage {
    StageId id();
    StageResult execute(JobContext context);
}

interface StreamingProcessExecutor {
    ProcessResult execute(ProcessSpec spec, ProgressListener progress, CancellationToken cancellation);
}
```

These are proposal-level contracts, not existing code.

## Persistence plan

Preserve the existing `lecture` table and public upload route. Add:
- `lecture_source(lecture_id, kind, original_url, provider, external_id, title, duration_ms, metadata_json, created_at)`
- `lecture_job(lecture_id, state, attempts, lease_owner, lease_until, requested_at, started_at, finished_at)`
- `lecture_stage_run(id, lecture_id, stage, state, attempt, started_at, finished_at, error_code, error_message, artifact_manifest_json)`
- `lecture_download(lecture_id, status, bytes_downloaded, bytes_total, selected_format, file_path, checksum, error_code, updated_at)`

Use migration scripts (Flyway/Liquibase or explicitly versioned manual SQL). `CREATE TABLE IF NOT EXISTS` alone does not migrate columns. Keep the existing API compatible; introduce `POST /api/lectures/from-url` and later optional metadata preview. Include cancellation endpoint only when cancellation works end-to-end.

## yt-dlp details

Use a Java adapter invoking the installed `yt-dlp` binary via `ProcessBuilder` argument lists. Call metadata probe and then download; parse structured progress, enforce execution time/byte quotas, block SSRF with provider host allowlist + DNS/IP/redirect restrictions, sanitize output paths, stage downloads into temp files and atomically finalize. First support public YouTube, vkvideo.ru, vk.com and rutube.ru URLs. Handle upstream extractor failures distinctly from source validation failures. If slides are selected, prefer video+audio; audio-only requires slides off. Treat auth/cookies and subtitles as separate later phases. Avoid promising 100% supported URLs: providers change frequently.

## Stage reliability rules

- Each stage checks whether output manifest and its expected version exist and validate; only skip if valid.
- Never rerun Whisper simply because the LLM step failed.
- Persist stage progress and failure code separately from high-level lecture status.
- Use per-job cancellation tokens and kill external descendant processes.
- Do not delete files while a job is running; coordinate deletion through the job service.
- CPU/GPU-heavy stages have separate concurrency controls; do not run arbitrary LLM benchmark and transcription workloads concurrently by default.
- Slides absent due to a failure should be clearly surfaced as PARTIAL rather than silently reported as normal success.

## Cloud LLM providers and per-task routing (Day 32 preparation)

- `LlmProvider` remains the chat/generation abstraction; `EmbeddingProvider` is a
  separate capability so a cloud chat provider can never be substituted for
  embeddings.
- `LlmProviderRegistry` discovers real provider beans. `OllamaLlmProvider` is
  `@Primary` (local default); `DeepSeekLlmProvider` is a cloud provider.
- `LlmTaskSettingsService` maps `LlmTaskType` (`LECTURE_PROCESSING`,
  `DEVELOPER_ASSISTANT`, `CODE_REVIEW`) to an `LlmRunConfig`, validated and stored
  per task in `app_setting`. Tasks are isolated and jobs snapshot the resolved
  config.
- Credentials live in `provider_credential`, encrypted with AES-256-GCM
  (`v1:` format). The master key is external (`LP_MASTER_KEY`) or a restricted
  fallback file; never in the database or source control.
- `AdminSecurityFilter` guards `/settings` and `/api/providers/**`
  (loopback by default, `X-Admin-Token`/session, CSRF for browser writes).
- Cloud cost is always an estimate from configurable pricing; cloud runs carry no
  fabricated GPU/RAM metrics.

## Revised sequence

### Preparatory milestone: stabilize ingestion and stages
- Introduce `MediaSource` abstraction and downloaded media metadata.
- Split pipeline into stage services with checkpoints, stage metrics, and retry by stage.
- Add yt-dlp URL import (YouTube, VK Video/VK, Rutube), observable progress and failure semantics.
- Regression tests for local upload, interrupted download, unsupported provider, errors and failure recovery.

### Day 31: Developer assistant
- Separate developer docs RAG collection from lecture transcript RAG; ingest README, docs, API and schema.
- MCP read-only tool: `git_current_branch`, optionally status/diff and file listing. `/help` endpoint/UI with real source references.

### Day 32: Gitea PR review
- Gitea webhook/Actions trigger -> PR diff -> retrieval of code/docs -> structured findings -> idempotent PR comment. Scoped API token and webhook auth. Test with seeded defects.

### Day 33: Support assistant
- Use actual job/stage metadata; read-only MCP tools `get_job_status`, `get_stage_runs`, `get_job_error`, `get_model_status`, `search_help_docs`. Controlled retry is a separate explicit user action.

### Day 34: File agent
- Workspace-scoped file reads/searches and draft edits; a preview diff + approval before mutation. Scenarios: edit notes with versioning, fix docs from project changes. Preserve DOCX images and original transcript.

### Day 35: Integration
- Demonstrate URL->yt-dlp->FFmpeg->Whisper->LLM->slides->DOCX end-to-end. Test existing local upload, failures/recovery, and report success rate, stage duration p50/p95, retry rate and resource utilization. Introduce agents only where tool choice or reasoning benefits.

## Acceptance criteria
- 4 platforms supported for publicly accessible test samples; successes measured using optional live smoke tests, not mocked tests alone.
- Existing local file flow remains intact.
- When LLM fails, transcript remains and only unfinished downstream stages rerun.
- Progress is reported for download, cancellation truly stops it and cleans partial files.
- Consistent metadata and media artifacts are linked to a lecture.
- At least 10 documentation QA evaluations, 3 seeded PR reviews, 5 support diagnostic cases and 2 reproducible file-agent tasks.

## Verification limitations

The review is static. The current environment has Java 21 whereas README requires Java 25. Build/integration tests and live yt-dlp site tests were not executed. No code changes have been applied to the application sources in this review; implement and test the design incrementally in your repository.

## Day 34 — Autonomous File Agent

`FileAgentService` → bounded `fileAgentExecutor` → `FileAgentOrchestrator`:
seed lexical/symbol search → `FileAgentPlanner` (LLM selects real candidates) →
read-only MCP profile `file-agent-read` → bounded context → LLM proposal JSON →
`FileAgentProposalParser` → `FileAgentPatchValidator` (path/hash/size/diff) →
immutable proposal in SQLite → approval (admin + CSRF) →
`FileChangeApplicationService` (revalidate, backup, atomic write, rollback) →
`FileAgentVerificationService`.

Workspaces: `PROJECT_WORKSPACE` and one `LECTURE_WORKSPACE`, isolated by
canonical path checks, protected-file denylist and size/count/depth limits.
No write MCP tool exists; LLM never receives direct filesystem write access.
See `docs/week7-day34-design.md` and `docs/week7-day34-report.md`.

## Week 8 extension: multilingual processing & discovery

The modular monolith is unchanged. Two new subsystems live inside `backend`:

* `com.lectureprocessor.multilingual` — language metadata, glossary,
  segment translation, translation quality and the optional `TRANSLATION`
  pipeline stage between transcription and text processing. Persistence:
  `lecture_language`, `lecture_translation`, `translation_segment`,
  `translation_glossary` (migration V8).
* `com.lectureprocessor.discovery` — topic profiles (`lecture_topic`),
  measured local cosine recommendations (`lecture_similarity`),
  `VideoSearchProvider` implementations, SQLite search cache
  (`video_discovery_cache`) and normalized results (`discovered_video`).

Both reuse existing infrastructure: `LlmProviderRegistry`/`LlmTaskSettingsService`
for `LECTURE_TRANSLATION`, `EmbeddingProvider` for topic/online ranking,
`ProviderCredentialService` for provider keys, `RemoteLectureService` +
`LectureJobService` for import, and the existing resumable pipeline/checkpoints.
No new HTTP client stack, vector database or task executor was introduced.
