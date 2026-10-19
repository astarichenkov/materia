# Day 27 — Runtime integration audit (real MP4 → FFmpeg → whisper.cpp → LLM → DOCX)

Date: 2026-10-10. Environment: Windows (MINGW64), AMD Ryzen 7 5800H, RTX 3050
Mobile 4 GB, 32 GB RAM, Java 25.0.2.

## 1. Code audit — how Day 27 actually works

| Area | Implementation | Verified |
|------|----------------|----------|
| External executables | `app.tools.{ffmpeg,ffprobe,whisper}-path`, `whisper-model-path`, `whisper-language`, `timeout` (`AppProperties.Tools`) | env `FFMPEG_PATH` etc. resolved |
| FFmpeg invocation | `FfmpegService.extractAudio` → `ffmpeg -hide_banner -y -i <video> -vn -ac 1 -ar 16000 -c:a pcm_s16le <wav>`; `probeDurationMs` via ffprobe | ✅ real run |
| whisper.cpp params | `whisper-cli -m <model> -f <wav> -oj -of <base> -l ru` (no shell, arg list) | ✅ real run |
| Transcript JSON | `transcription[]` with `offsets{from,to}` (ms) and `timestamps{from,to}` | ✅ parsed |
| Ollama access | `OllamaLlmProvider` via Spring AI 2.0.1 `OllamaApi` + `ChatClient`; endpoints `/api/chat`, `/api/embed`, `/api/tags` | contract verified with double |
| Timeouts | process timeout `app.tools.timeout` (2h); HTTP read timeout `app.ollama.timeout` (10m) | ✅ |
| Chunking | `TextChunker.chunkSegments(maxChars=6000, overlap=400)` per chunk | ✅ |
| LLM errors | `LlmException`, pipeline marks lecture `FAILED` | ✅ (after fix) |
| Intermediate results | `<storage>/lectures/<id>/{source.*,audio.wav,transcript.json,transcript.md,notes.md,notes.docx}` | ✅ |
| DOCX | Apache POI; headings/paragraphs parsed from Markdown, slides optional | ✅ |
| Endpoints | `POST /api/lectures`, `POST /api/lectures/{id}/process`, `GET /api/lectures/{id}`, `GET .../download/{artifact}` | ✅ |
| Pipeline start | UI upload → `LectureJobService.submit` → bounded executor → `LectureProcessingService.process` | ✅ |

## 2. Issues found (priorities)

| # | Prio | Issue | Status |
|---|------|-------|--------|
| C1 | **Critical** | If Ollama is unreachable, the job hangs in `PROCESSING_TEXT` for minutes (Spring AI retries connection-refused). Observed >190 s with no failure; no clear error. | **FIXED** |
| H1 | **High** | whisper.cpp hallucinates subtitle credits ("Редактор субтитров А.Синецкая Корректор А.Егорова") on silent audio; the pipeline accepted it and reported `COMPLETED`. | **FIXED** |
| H2 | **High** | Real Ollama/Qwen3 could not be executed: only `C:` exists with ~2.7 GB free; `OllamaSetup.exe` alone is 1505 MB and `qwen3:4b` ≈ 2.5 GB. | **BLOCKED (documented)** |
| M1 | Medium | Embedding dimension (`app.ollama.embedding-dimensions=768`) is not validated against the vector returned by the embedding model. | OPEN (documented) |
| M2 | Medium | `indexSafely` intentionally swallows embedding errors (logged WARN) so RAG failure doesn't block notes. | ACCEPTED (by design) |
| L1 | Low | `OllamaLlmProvider.chat` builds a new `ChatClient` per call. | OPEN (cosmetic) |
| L2 | Low | `Transcript.plainText()` / `timestampedText()` not used by the pipeline. | OPEN (cosmetic) |

## 3. Fixes applied (C1, H1)

### C1 — fast-fail on unreachable Ollama
- `LectureProcessingService`: before `PROCESSING_TEXT`, `llm.isAvailable()` is
  checked; if false → `LlmException` with a clear message → job `FAILED`.
- `OllamaLlmProvider`: HTTP client now has a 5 s connect timeout and
  `RetryPolicy.withMaxRetries(0)` (no connection retry storm); generation read
  timeout = `app.ollama.timeout`.
- Result: failure in **~14 s** (FFmpeg+whisper) instead of hanging for minutes.
- Regression test: `OllamaLlmProviderTest` (unreachable port → `isAvailable`
  false, `chat` throws `< 15 s`).

### H1 — transcript sanitizer
- New `TranscriptSanitizer` drops whole segments that are pure hallucination
  credits / non-speech markers (Russian subtitle credits, `[BLANK_AUDIO]`,
  "Продолжение следует", English "subtitles by", etc.) or contain no letters,
  then renumbers segments and preserves the raw JSON.
- Applied in `WhisperCppTranscriptionService` after parsing.
- Result: silent clip → `FAILED: "Транскрипция пустая — в видео не распознана речь"`.
- Tests: `TranscriptSanitizerTest` (6 cases) — no over-filtering of real
  speech.

## 4. Environment verification

| Tool | Found | Action |
|------|-------|--------|
| Java 25 | `C:\tools\jdk-25.0.2-full` | used (env `JAVA_HOME` was broken) |
| FFmpeg | absent | installed **ffmpeg 9.0.2 essentials** → `C:\tools\ffmpeg\...\bin` |
| whisper.cpp | absent | installed **b5454 BLAS win-x64** → `C:\tools\whisper\bin\Release\whisper-cli.exe` |
| Whisper model | absent | installed `ggml-small.bin` (466 MB) |
| Ollama | absent | **not installed — insufficient disk (see H2)** |
| GPU | RTX 3050 4 GB, driver 592.82, CUDA 13.1 | not used (CPU whisper build) |
| Package managers | winget/choco/scoop absent | direct downloads |

## 5. Real end-to-end result

Fixture: original Russian speech generated with Windows SAPI voice
(*Microsoft Irina, ru-RU*) → MP4 (h264+aac, 45 s). No copyrighted media, no
user data modified.

```
POST /api/lectures (sample-lecture-ru.mp4)
 → EXTRACTING_AUDIO   (real ffmpeg 9.0.2)         ✅ audio.wav 16 kHz mono
 → TRANSCRIBING       (real whisper.cpp, CPU)     ✅ 6 segments, 0–38.6 s
 → transcript.json (raw whisper output preserved) ✅
 → PROCESSING_TEXT    (LLM)                       ⚠️ see below
 → EXPORTING          (Markdown + Apache POI DOCX)✅ notes.md, notes.docx
 → COMPLETED
```

- Transcript (real whisper.cpp, Russian):
  `Сегодня мы рассмотрим основы векторной алгебры.` … 6 segments.
- DB: 6 `transcript_segment` rows; 1 `lecture_chunk` with a 768-float embedding
  (3072 bytes).
- `transcript.md` and `notes.docx` generated; DOCX is a valid OOXML package.

### LLM status (honest)

The real LLM stage could **not** be executed because Ollama cannot be installed
(H2). To verify the remaining pipeline wiring (chunking → notes assembly →
DOCX) and the Ollama HTTP contract, a **local Ollama-API test double**
(`scripts/dev/ollama-stub.py`) was used. It is **not** an AI model and this is
**not** a real AI end-to-end pass.

Verified against the double:
- Spring AI 2.0.1 calls `/api/tags`, `/api/chat` (`stream:false`, messages
  `[system,user]`), and `/api/embed` (batch `input`), using **chunked** transfer
  encoding.
- Full run with the double → `COMPLETED`, `notes.md` + `notes.docx` produced.

> **RESOLVED (2026-10-11):** free disk was increased, Ollama 0.40.2 + `qwen3:4b`
> were installed, and two real end-to-end runs passed with job `COMPLETED`.
> See `docs/DAY27_REAL_INTEGRATION_REPORT.md`. The test double is no longer used
> for verification.

## 6. Disk blocker (H2) — RESOLVED

Originally blocked by ~2.7 GB free on `C:`. The user freed disk (31.5 GB free);
Ollama (portable ZIP, 1.4 GB) and `qwen3:4b` (2.5 GB) were then installed and
run successfully. See `docs/DAY27_REAL_INTEGRATION_REPORT.md`.

## 7. Recommended next steps

1. On a host with ≥8 GB free disk: run
   `scripts/install-day27-tools.ps1` then `ollama pull qwen3:4b`.
2. Configure the app (`FFMPEG_PATH`, `WHISPER_MODEL_PATH`, `OLLAMA_BASE_URL`) and
   run `demos/day27/demo-scenario.md`; record real notes/DOCX and timings.
3. Keep the sanitizer patterns updated as new whisper hallucination variants
   appear.
