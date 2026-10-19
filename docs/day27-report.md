# Day 27 report — Lecture Processor MVP

## Goal

Integrate a local LLM into a real Java application and build the end-to-end
video → transcript → notes → DOCX pipeline.

## Delivered

- **Backend module** (`backend/`), Spring Boot 4.1.1, Java 25, Maven Wrapper.
- **Lecture management**: upload API + UI, validation, SQLite persistence.
- **Job orchestration**: bounded `ThreadPoolTaskExecutor`, persisted states,
  stale-job recovery on startup.
- **Media**: safe FFmpeg wrapper (audio extraction, duration probe).
- **Transcription**: real whisper.cpp integration + tolerant JSON parser.
- **LLM**: `LlmProvider` abstraction; `OllamaLlmProvider` built on Spring AI
  2.0.1 (`OllamaApi`/`OllamaChatModel`), configurable model/context.
- **Text processing**: chunking with overlap, conservative cleanup mode,
  structured-notes mode with summary, output validation.
- **Documents**: Markdown transcript + notes; DOCX via Apache POI.
- **Web**: REST API and Thymeleaf/HTMX UI.
- **Tests**: 53 backend tests (see `demos/day27/test-results.md`).

## Verification

- `./mvnw verify` → **83/83 PASS** (63 backend + 20 chat).
- **Real AI end-to-end PASSED (2026-10-11)** with actual Ollama 0.40.2 + `qwen3:4b`
  on two original Russian-speech fixtures (45 s and 88 s): FFmpeg → whisper.cpp →
  transcript.json → Qwen3 → notes.md → notes.docx, job `COMPLETED`. See
  `docs/DAY27_REAL_INTEGRATION_REPORT.md`.
- `./mvnw -pl backend test` → 63/63 PASS (was 53; +2 Ollama fast-fail, +6 sanitizer,
  +2 generation-budget).
- **Real integration run performed** (see `docs/day27-runtime-audit.md`): FFmpeg
  9.0.2 and whisper.cpp b5454 were installed locally and the pipeline was run on
  an original 45 s Russian speech MP4. FFmpeg audio extraction and whisper.cpp
  transcription succeeded end-to-end through the application
  (`transcript.json` with 6 timestamped segments).
- Ollama/Qwen3 could not be installed (only ~2.7 GB free; installer 1.5 GB + model
  2.5 GB). The LLM stage was exercised with a clearly-labelled local Ollama-API
  test double to verify wiring/DOCX, **not** as a real AI pass.

### Defects fixed during the real run

| Prio | Defect | Fix |
|------|--------|-----|
| Critical | Job hung >190 s in `PROCESSING_TEXT` when Ollama was down | pre-flight `isAvailable()` + no connection retries + 5 s connect timeout |
| High | whisper.cpp subtitle-credit hallucination accepted as speech on silence | `TranscriptSanitizer` drops credit/marker segments; empty result → `FAILED` |

## Design decisions

- Jackson 3 (`tools.jackson`) is used because Spring Boot 4 ships it.
- Spring AI models are constructed explicitly from configuration so the app
  starts and tests run without Ollama.
- External processes are always invoked via argument lists with timeouts and
  process-tree termination.
