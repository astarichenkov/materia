# Day 27 — Real AI Integration Report (Ollama + Qwen3 4B)

Date: 2026-10-10/11. Machine: Windows, AMD Ryzen 7 5800H, NVIDIA RTX 3050 Ti
Laptop (4 GB, driver 592.82, CUDA 13.1), 32 GB RAM, Java 25.0.2.

**Result: a genuine, fully local end-to-end run passed.**

```
MP4 → FFmpeg → whisper.cpp → transcript.json → Ollama/Qwen3 4B → notes.md → notes.docx
Job status = COMPLETED.  No mock LLM used.
```

## 1. Environment / versions

| Component | Version / path |
|-----------|----------------|
| Ollama | 0.40.2 (portable `ollama-windows-amd64.zip`) → `C:\tools\ollama\ollama.exe` |
| Qwen3 | `qwen3:4b` (2.5 GB, `359d7dd4bcda`) |
| Embedding model | `nomic-embed-text` (274 MB) |
| FFmpeg | 9.0.2 essentials → `C:\tools\ffmpeg\ffmpeg-9.0.2-essentials_build\bin` |
| whisper.cpp | b5454 CPU/BLAS → `C:\tools\whisper\bin\Release\whisper-cli.exe` |
| Whisper model | `ggml-small.bin` (466 MB) |
| Spring Boot / Spring AI | 4.1.1 / 2.0.1 |
| Free disk before / after install | 31.5 GB → 25.9 GB |

Ollama was installed without touching system PATH or the registry (portable
ZIP), listening on `127.0.0.1:11434` only.

## 2. Ollama verification

```
C:\tools\ollama\ollama.exe serve
C:\tools\ollama\ollama.exe pull qwen3:4b
C:\tools\ollama\ollama.exe pull nomic-embed-text
C:\tools\ollama\ollama.exe list
C:\tools\ollama\ollama.exe ps
curl http://localhost:11434/api/tags
```
- `api/tags` → 200 with both models.
- Real Russian generation via `/api/chat` verified.
- `ollama ps` during inference: `qwen3:4b  4.1 GB  45%/55% CPU/GPU  ctx=8192` —
  the 4 GB GPU cannot hold all layers, so ~half runs on CPU (expected).
- Peak observed VRAM: **2738 MiB / 4096 MiB**.

## 3. Spring AI integration — critical finding and fix

The decisive issue was **thinking mode**.

| Mode | Behaviour with `qwen3:4b` | Verdict |
|------|---------------------------|---------|
| `think=false` (non-thinking) | Model writes its chain-of-thought/meta-commentary **into `content`** ("Хорошо, мне нужно…", "Но по условию задачи…") and the answer gets truncated | ❌ unusable |
| `think=true` + small budget (1024–3072) | Budget consumed entirely by reasoning; `content` empty → pipeline `FAILED` | ❌ |
| `think=true` + adequate budget (≥4096) | Ollama cleanly separates reasoning into `message.thinking` (ignored by the app) and the final clean Markdown into `message.content` | ✅ **chosen** |

Fixes applied (`application.yml`, `LlmOptions`, `OllamaLlmProvider`):
- `app.ollama.disable-thinking` default changed to **false** (thinking enabled).
- `app.ollama.timeout` raised to **60m** (CPU/GPU generation is slow).
- Generation budget: `numPredict=6144`, `numCtx=8192` (so reasoning does not
  starve the answer; the app only ever reads `content`).
- Regression test `LlmOptionsTest` guards the budget.

Other integration checks (all PASS): HTTP connection, model compatible with the
chat API, prompt passed correctly, no reasoning tags in the document, timeouts,
error handling caught (`LlmException`), Russian quality.

## 4. End-to-end runs (real)

Server started with:

```
set FFMPEG_PATH=C:\tools\ffmpeg\ffmpeg-9.0.2-essentials_build\bin\ffmpeg.exe
set FFPROBE_PATH=C:\tools\ffmpeg\ffmpeg-9.0.2-essentials_build\bin\ffprobe.exe
set WHISPER_PATH=C:\tools\whisper\bin\Release\whisper-cli.exe
set WHISPER_MODEL_PATH=C:\tools\whisper\models\ggml-small.bin
java -jar backend/target/lecture-processor-backend-1.0.0-SNAPSHOT.jar --server.port=18080 --app.storage.root=./target/e2e-final
```

Then via the real API: `POST /api/lectures` (multipart) → `POST /api/lectures/{id}/process`.

### Run 1 — short lecture (45.0 s, `sample-lecture-ru.mp4`)

| Stage | Tool | Time |
|-------|------|------|
| Audio extraction + probe | FFmpeg 9.0.2 | 0.2 s |
| Transcription (6 segments, ru) | whisper.cpp (CPU) | 12.8 s |
| Clean + structured notes + summary + index | **Qwen3 4B** | 1215.5 s |
| Markdown + DOCX export | Apache POI | 0.6 s |
| **Total** | | **≈ 1249 s (~20.8 min)** |

### Run 2 — longer lecture (88.4 s, `sample-lecture-ru-long.mp4`)

| Stage | Tool | Time |
|-------|------|------|
| Audio extraction | FFmpeg | ~0.2 s |
| Transcription (14 segments, ru) | whisper.cpp (CPU) | ~22.6 s |
| Clean + structured notes + summary + index | **Qwen3 4B** | 770 s |
| Markdown + DOCX export | Apache POI | ~0.03 s |
| **Total** | | **≈ 793 s (~13.2 min)** |

Both jobs reached **`COMPLETED`**.

> Why so slow: qwen3:4b is a reasoning model; each generation spends
> thousands of reasoning tokens, and ~55% of the layers run on CPU because the
> 4 GB GPU cannot hold the model + KV cache.

## 5. Output quality (checked)

Run 1 `notes.md`:
```
## Резюме
Вектор — это направленный отрезок, характеризующийся длиной и направлением.
Скалярное произведение векторов определяется как произведение их длин и косинуса
угла между ними, при этом векторы перпендикулярны, если это произведение равно нулю.
…
## Основы векторной алгебры
**Вектор** — направленный отрезок, который характеризуется длиной и направлением.
**Скалярное произведение** — произведение их длин и косинуса угла между ними.
…
```

Checklist:

| Criterion | Result |
|-----------|--------|
| Punctuation corrected | ✅ ("Вектор — это …", "длин и косинуса" instead of "длиннокосину") |
| Facts preserved | ✅ all 6 statements from the source retained |
| Logical sections | ✅ `## Резюме`, `## Основы векторной алгебры`, `###` subsections in run 2 |
| No hallucinations | ✅ only source facts |
| No repetitions | ✅ |
| No truncated answers | ✅ (`done_reason=stop`) |
| Cyrillic in DOCX | ✅ correct |
| No reasoning / technical sections | ✅ (reasoning stays in `message.thinking`, ignored) |
| Both `clean` and `structured notes` | ✅ exercised in both runs |

`transcript.json` preserves the raw whisper.cpp output; the DB holds 6 (run 1)
and 14 (run 2) `transcript_segment` rows and the embedded chunks.

## 6. Regression tests

`./mvnw verify` → **BUILD SUCCESS**, **83 tests PASS**
(63 backend + 20 private-ai-chat). Previously 81; added:
- `LlmOptionsTest` (2) — reasoning-safe generation budget.
- `OllamaLlmProviderTest` (2, from the earlier runtime audit) — fast-fail.

Days 28–30 functionality untouched; existing tags `week6-day27`…`week6-day30`
unchanged.

## 7. Reproduction

```powershell
# 1. Tools (FFmpeg + whisper.cpp + model)
./scripts/install-day27-tools.ps1
# 2. Ollama (portable) — download ollama-windows-amd64.zip, extract, then:
C:\tools\ollama\ollama.exe serve      # keep running (loopback only)
C:\tools\ollama\ollama.exe pull qwen3:4b
C:\tools\ollama\ollama.exe pull nomic-embed-text
# 3. Fixture (original Russian speech, no copyrighted media)
./scripts/generate-speech-fixture.ps1
# 4. App + E2E
./mvnw -pl backend spring-boot:run
# upload demos/fixtures/sample-lecture-ru.mp4 with "clean" checked, then process
```

## 8. Conclusion

All completion criteria are met with a real local Ollama/Qwen3 4B:
Ollama runs, Qwen3 responds in Russian, whisper.cpp transcribes Russian speech,
Spring Boot uses the real model, MP4 → DOCX succeeds, output is meaningful,
job = `COMPLETED`, 83 automated tests pass, and the changes are published.

Tag **`week6-day27-verified`** marks this verified state.
