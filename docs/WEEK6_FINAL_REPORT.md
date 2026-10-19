# Week 6 — Final report (Lecture Processor)

> This file is finalized after the per-day commits/tags are created; commit
> hashes and the Gitea URL are filled in with real values (see the table below).

## 1. Executive summary

Lecture Processor is a local AI application that turns educational lecture
videos into structured Markdown/DOCX notes using FFmpeg, whisper.cpp, a local
Ollama/Qwen3 LLM and local RAG. A separate service (`private-ai-chat`) exposes
a private local chat over HTTP. Everything is a modular monolith (plus one small
independent chat service); no cloud AI is required.

Implementation is complete for Days 27–30. 73 automated tests pass. Real
end-to-end runs, benchmarks and homelab deployment could not be executed on this
machine because FFmpeg, whisper.cpp and Ollama are not installed; those items
are explicitly marked SKIPPED.

## 2. Architecture

```
backend (modular monolith)
  web/          REST + Thymeleaf/HTMX UI
  lecture/      upload, validation, lookup
  job/          bounded executor, pipeline, stale-job recovery
  video/        FFmpeg wrapper
  transcription/ whisper.cpp + JSON parser
  llm/          LlmProvider + Ollama (Spring AI)
  text/         chunking, prompt templates, cleaning/notes
  document/     Markdown + DOCX (+ slides)
  slides/       frame extraction + perceptual-hash dedup
  rag/          embeddings index, cosine retrieval, grounded answers
  benchmark/    reproducible LLM benchmark runner
  repository/   Spring JDBC + SQLite

private-ai-chat (independent)
  web/          /api/health, /api/chat, HTMX UI
  service/      bounded worker + context/output budgets
  llm/          Ollama client
  ratelimit/    per-client fixed window
  security/     API-key authentication
```

## 3. Technology versions (verified on Maven Central / installed)

| Component | Version |
|-----------|---------|
| Java | 25.0.2 (BellSoft) |
| Spring Boot | 4.1.1 |
| Spring AI | 2.0.1 |
| Maven | 3.9.11 (wrapper) |
| SQLite JDBC | 3.53.4.0 |
| Apache POI | 5.5.1 |
| Jackson | 3.1.5 (`tools.jackson`) |
| Git | 2.50.0 |

## 4. Day 27 implementation

Full pipeline: upload → FFmpeg audio → whisper.cpp → transcript JSON →
chunked Qwen3 processing → structured Markdown → DOCX. Persisted job states,
bounded executor, safe subprocess execution, REST + HTMX UI. See
`docs/day27-report.md`.

## 5. Day 28 implementation

Timestamp-aware chunking, local Ollama embeddings, persistent SQLite vector
index, exact cosine retrieval, grounded RAG answers with real source
attribution, per-lecture chat and a 12-question evaluation protocol. See
`docs/day28-report.md`.

## 6. Day 29 implementation

Reproducible benchmark runner (configs A–D, JSON/CSV/Markdown), versioned
prompts, deterministic quality checks, slide frame extraction with dHash
deduplication and DOCX image placement. See `docs/day29-report.md`.

## 7. Day 30 implementation

Independent `private-ai-chat`: HTTP API + web chat, session history, API-key
auth, 5 req/min rate limit, 2000-char message limit, context/output token
budgets, single worker + bounded queue, timeout, clean errors. See
`docs/day30-report.md`.

## 8. Git commits and tags

| Day | Status | Tag | Commit |
|-----|--------|-----|--------|
| 27 | COMPLETE (tests PASS; real tools SKIPPED) | `week6-day27` | `55f82f2` |
| 28 | COMPLETE (tests PASS; real eval SKIPPED) | `week6-day28` | `05da320` |
| 29 | COMPLETE (tests PASS; real benchmark SKIPPED) | `week6-day29` | `eed7835` |
| 30 | COMPLETE (tests PASS; network deploy SKIPPED) | `week6-day30` | `5ff7b4c` (tagged; follows `cc463cd`) |

> Day 27 baseline commit contains the Day 28–30 source code as well (single
> implementation session); the Day 28/29/30 commits add their documentation,
> demos and deployment assets. See `docs/IMPLEMENTATION_STATUS.md`.

## 9. Gitea publishing status

Repository: `http://203.0.113.20:8108/example-org/materia`
Branch: `feature/week6-lecture-processor`
Push result: recorded at publish time (see below). Tags listed in section 8.

## 10. Test results

- Backend: **53** tests PASS (`demos/day27..29/test-results.md`).
- Private AI chat: **20** tests PASS (`demos/day30/test-results.md`).
- Total: **73** PASS.
- Real external-tool runs: SKIPPED (see below).

## 11. Real end-to-end verification

Status: **PARTIAL**.

Verified at runtime on this machine:
- Backend starts; `/actuator/health` = UP; UI and `/api/lectures` return 200.
- Upload validation returns 400 with a clear message.
- Valid upload → job submitted → pipeline reaches FFmpeg and fails with a clear
  `FAILED` status and error `Не удалось запустить процесс: ffmpeg`
  (proves job orchestration, persistence and error handling).
- Chat service starts; `/api/health` = UP; chat with Ollama down returns
  HTTP 503 in ~0.4 s; oversized input returns 400; rate limit returns 429.

Not verified (SKIPPED, tools absent): real MP4 → transcript → notes → DOCX,
real RAG answers, real benchmarks, homelab remote access.

## 12. Benchmarks

Status: **SKIPPED**. `BenchmarkRunner` runs and correctly emits `SKIPPED`
entries when Ollama is unavailable (unit-tested). No measurements are invented.
Fill `demos/day29/benchmark-results/` after running with models present.

## 13. Hardware configuration

Development machine: AMD Ryzen 7 5800H, RTX 3050 Mobile (4 GB VRAM), 32 GB RAM,
Windows. Target mini-PC: Ryzen 7 7840HS / Radeon 780M / 32 GB. Homelab:
i5-10500 / 16 GB / CPU only.

## 14. Known limitations

- Real media/AI runs require FFmpeg, whisper.cpp and Ollama; not run here.
- Exact in-Java cosine search targets small per-lecture collections.
- Slide alignment uses timestamps only (no OCR / multimodal reasoning).
- Benchmark "quality" uses deterministic checks; subjective quality is manual.
- In-memory chat sessions (private-ai-chat) are lost on restart.
- Intermediate tags may include later-day source files: the project was
  implemented as one session and tags mark per-day milestone commits; see
  `docs/IMPLEMENTATION_STATUS.md`.

## 15. Reproduction steps

```bash
export JAVA_HOME=/path/to/jdk-25
./mvnw verify                          # 73 tests
./scripts/create-snapshot.sh day29     # restore a milestone
./mvnw -pl backend spring-boot:run     # Days 27–29
./mvnw -pl private-ai-chat spring-boot:run  # Day 30
```

## 16. Recommended next steps

1. Install FFmpeg, whisper.cpp (multilingual model) and Ollama on a machine with
   a GPU; run the Day 27/28/29 scenarios and record real results.
2. Run benchmarks A–D and fill `demos/day29/benchmark-results/`.
3. Deploy `private-ai-chat` to the homelab and run `deployment/homelab/verify.sh`
   from a second machine.
4. Optionally replace exact cosine search with a dedicated vector store.
