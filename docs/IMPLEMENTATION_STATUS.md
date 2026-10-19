# Implementation status — Week 6

| Field | Value |
|-------|-------|
| Current day | **Day 30 COMPLETE (real homelab deployment VERIFIED)** |
| Current stage | All four stages implemented, tested, documented |
| Last successful test | `mvn verify` — 96 PASS (68 backend + 28 chat); server `verify.sh` 9/9 |
| Build | Spring Boot 4.1.1, Java 25, Spring AI 2.0.1 |
| Next action | none; Week 6 complete |

### Day 28 checkpoint (2026-10-11)

- Status: **COMPLETE / VERIFIED** (real local RAG, no mocks).
- Model: `qwen3:1.7b` think=false, embeddings `nomic-embed-text` (768).
- Metrics: Recall@1 0.75, Recall@3 0.917, Recall@5 1.0; no-answer 3/3;
  grounded pass 12/12; RAG latency avg 722 ms.
- Fixes: no-evidence enforcement (`NoEvidenceDetector`) + `SourceRef.score`.
- Report: `docs/DAY28_REAL_RAG_REPORT.md`, audit `docs/day28-runtime-audit.md`.
- Commit: `559e557`. Tag: `week6-day28-verified`.
- Next action: Day 29 — LLM optimization benchmark + slide extraction.

### Day 29 checkpoint (2026-10-11)

- Status: **COMPLETE / VERIFIED** (real benchmark + real slide extraction).
- Benchmark: qwen3:1.7b baseline/optimized/thinking + qwen3:4b limited; qwen3:8b skipped.
- Recommended: qwen3:1.7b, thinking off, numCtx 4096, numPredict 1536,
  temp 0.1, generation chunks 6000/400, RAG chunks 400/60, sequential.
- Before/after notes: qwen3:4b thinking ~187 s → qwen3:1.7b think=off ~5.8 s (~32x).
- Fixes: real-JPEG dHash (PerceptualHash), non-adjacent slide dedup,
  decoupled RAG/generation chunk sizes.
- Slides: synthetic fixture 14 candidates → 5 slides, dups removed, DOCX 5 images.
- Report: `docs/DAY29_REAL_OPTIMIZATION_REPORT.md`;
  data `demos/day29/benchmark-results/`.
- Tag: `week6-day29-verified`.

### Day 30 checkpoint (2026-10-11)

- Status: **COMPLETE / VERIFIED** — real LAN deployment on LXC `private-ai`
  (`203.0.113.10`), real CPU inference, real limit/stability tests.
- Server: Debian 12, 6 vCPU, 8 GB RAM; Java 25.0.4.1 (Temurin), Ollama 0.40.3,
  `qwen3:1.7b`, systemd `private-ai.service` (user `private-ai`).
- Boundary: Spring Boot `0.0.0.0:8080` (API key on `/api/chat`), Ollama
  `127.0.0.1:11434` (LAN-refused).
- Real results: chat + context OK; 401/400/429 as specified; 5 sequential and
  5 concurrent requests stable (queue peak 3); cold 7.17 s, warm 3.92 s,
  ~14.4 tok/s, llama-server ~5.5–6 cores, RAM ~2 GB loaded.
- Fixes: UI no longer blocked by API key (C1), Qwen3 thinking off (C2), per-client
  UI rate limit (H1), X-Forwarded-For spoofing (H2), external config
  permissions + LXC systemd hardening (deployment).
- Report: `docs/DAY30_REAL_DEPLOYMENT_REPORT.md`, audit `docs/day30-runtime-audit.md`.
- Tag: `week6-day30-verified`.

## Completed subtasks

### Day 27 — MVP
- [x] Upload + validation + SQLite persistence + bounded job executor
- [x] Safe FFmpeg wrapper, real whisper.cpp integration + JSON parser
- [x] `LlmProvider` + real Spring AI Ollama implementation
- [x] Chunked text processing (clean + structured notes), validation
- [x] Markdown + DOCX export, REST API, HTMX UI
- [x] Tests + report + demo + tag + push

### Day 28 — Local LLM + RAG
- [x] Timestamp-aware chunking, local embeddings, persistent SQLite index
- [x] Exact cosine retrieval, RAG service, real source attribution
- [x] Per-lecture chat with bounded history, no-evidence response
- [x] 12-question evaluation protocol + tests + report + tag + push

### Day 29 — Optimization + slides
- [x] Benchmark runner + configs A–D, JSON/CSV/Markdown output
- [x] Versioned prompts, deterministic quality checks
- [x] Frame extraction, dHash dedup, time-based DOCX alignment
- [x] Tests + report + demo + tag + push

### Day 30 — Private AI service
- [x] Separate `private-ai-chat` module (Java 25, Boot 4.1.1)
- [x] Chat API/UI, history, API-key auth, rate limiting, token budgets
- [x] Single worker + bounded queue, timeout, clean errors
- [x] Deployment scripts + tests + report + tag
- [x] **Real deployment on LXC `private-ai`, LAN + CPU inference verified**

## Real integration progress (2026-10-10)

- [x] FFmpeg 9.0.2 installed; real audio extraction PASS
- [x] whisper.cpp b5454 (CPU/BLAS) + `ggml-small.bin`; real Russian transcription PASS
- [x] Full pipeline wiring PASS (LLM via labelled local test double)
- [x] Defects fixed: stuck-on-Ollama-down (Critical), silence hallucination (High)
- [x] **Real Ollama 0.40.2 + Qwen3 4B generation — PASS (2026-10-11)**
- [x] **Full real MP4→DOCX AI E2E, 2 runs, job COMPLETED**
- [ ] Real RAG evaluation / benchmarks / homelab deploy
- [x] **Real homelab deployment of `private-ai-chat` — PASS (2026-10-11)**

Day 27 real AI E2E: `docs/DAY27_REAL_INTEGRATION_REPORT.md` (tag `week6-day27-verified`).
Runtime audit (incl. historical disk blocker): `docs/day27-runtime-audit.md`.

## Blockers

None that prevent build/test. External tools (FFmpeg, whisper.cpp, Ollama)
and homelab access are unavailable here.

## Notes / decisions

- Spring Boot 4 ships Jackson 3 (`tools.jackson`): code uses that API.
- Spring AI 2.0.1 targets Spring Boot 4.1.1; models are built explicitly so the
  app starts without Ollama.
- SQLite is used via Spring JDBC (no JPA dialect risk); storage root is created
  before schema init by `StorageRootInitializer`.
- Tagged commits: `week6-day27` … `week6-day30-verified`.
