# Day 28 — Real Local RAG Verification Report

Date: 2026-10-11. Branch: `fix/week6-day28-day29-real-verification`.
**Result: a genuine, fully local RAG pipeline was verified end-to-end — no mocks.**

```
real MP4 → whisper.cpp transcript → 5 timestamped chunks
        → nomic-embed-text embeddings → persistent SQLite index
        → question → cosine retrieval → context → qwen3:1.7b
        → grounded answer + code-generated source references
```

## 1. Environment / versions

| Component | Version / path |
|---|---|
| Java | 25.0.2 (`C:\tools\jdk-25.0.2-full`) |
| Spring Boot / Spring AI | 4.1.1 / 2.0.1 |
| Ollama | 0.40.2 (`C:\tools\ollama\ollama.exe`) |
| Chat model | `qwen3:1.7b` (1.4 GB) — primary for Days 28–29 |
| Quality baseline model | `qwen3:4b` (2.5 GB) |
| Embedding model | `nomic-embed-text` (768-dim) |
| whisper.cpp / model | b5454 / `ggml-small.bin` |
| FFmpeg | 9.0.2 |
| GPU | RTX 3050 Laptop 4 GB — both models reported 100% GPU (`ollama ps`) |
| Fixture | `demos/fixtures/sample-lecture-ru-long.mp4` (88.4 s, Russian speech) |

## 2. Ollama / model validation

- `qwen3:1.7b` pulled on demand: `ollama pull qwen3:1.7b`.
- Thinking behaviour tested explicitly with `think=true/false`:

| Mode | Behaviour | Warm latency (one sentence) | Verdict |
|---|---|---|---|
| `think=false` | clean `content`, no reasoning leak (unlike qwen3:4b) | ~0.75 s | **chosen** |
| `think=true` | reasoning in `message.thinking`, clean `content` | ~2.8 s | rejected: ~3–4x slower and weaker no-answer handling |

## 3. Audit and defects found

Full audit: `docs/day28-runtime-audit.md`.

| # | Severity | Defect | Fix |
|---|---|---|---|
| C1 | Critical | No-evidence was not enforced: `min-similarity=0.2` let every chunk through, so no-answer questions returned `grounded=true` with 5 fabricated-looking timestamps/sources. | New `NoEvidenceDetector`; on refusal `RagService` returns `grounded=false` and **empty** sources. |
| C2 | Critical (config) | Default `llm-chunk-chars=6000` collapses short lectures into 1 chunk, hiding retrieval quality. | Evaluation indexes with 300/60; documented as a tuning sensitivity. |
| H1 | High | `SourceRef` had no similarity score → retrieval quality unobservable. | Added `double score` from `ScoredChunk`. |
| M1 | Medium | Retrieval ignores chat history (LLM history only). | Documented limitation. |
| M2 | Medium | Segment-level overlap can duplicate whole sentences across chunks. | Documented; harmless for recall. |
| M3 | Medium | Index failure is silent to the user; no reindex path. | Documented. |

Regression tests added: `NoEvidenceDetectorTest`, `RagServiceTest` refusal case + score assertion.

## 4. Real indexing

Started the built application against a fresh storage root with the real tools:

```
java -jar backend/target/lecture-processor-backend-1.0.0-SNAPSHOT.jar \
  --server.port=18081 --app.storage.root=.../target/day28 \
  --app.processing.llm-chunk-chars=300 --app.processing.llm-chunk-overlap-chars=60
```

with env `OLLAMA_CHAT_MODEL=qwen3:1.7b`, `OLLAMA_DISABLE_THINKING=true`,
`FFMPEG_PATH`, `FFPROBE_PATH`, `WHISPER_PATH`, `WHISPER_MODEL_PATH`.

Then via the real REST API:

```
POST /api/lectures            (multipart sample-lecture-ru-long.mp4, clean=true)
POST /api/lectures/{id}/process
```

| Check | Result |
|---|---|
| transcript segments | 14 persisted |
| chunks | 5, each 183–284 chars, timestamps 0–81.8 s |
| embeddings | real, `embedding_dim = 768` |
| vectors persisted | SQLite BLOB |
| restart keeps index | PASS |
| reprocess duplicates | none (5 → 5) |

Job reached `COMPLETED` in ~1 min (whisper ~20 s + 1.7b generation).

## 5. Real evaluation

15 control questions (`demos/day28/test-questions.json`), fixed before running:
5 direct, 2 multi-chunk, 3 terminology, 2 follow-up, 3 unanswerable.
Each question was run through the real Java REST endpoint (RAG) and against a
no-context baseline of the same model; retrieval scores were recomputed from the
persisted embeddings with the real `nomic-embed-text` vectors.

### Metrics (`demos/day28/rag-evaluation.json`)

| Metric | Value |
|---|---|
| Recall@1 | 0.75 |
| Recall@3 | 0.917 |
| Recall@5 | 1.0 |
| No-answer rejection accuracy | 1.0 |
| Source correctness (topK=5) | 1.0 |
| Grounded answer pass rate | 1.0 |
| RAG latency avg / median | 722 / 591 ms |
| Baseline latency avg | 2679 ms |
| Baseline answerable pass rate | 0.833 |
| Baseline no-answer refusal rate | 0.0 |

The one weak retrieval case is B1 ("where are vectors applied?"): the gold chunk
(applications + ML) ranks 4th of 5. All other answerable questions place a gold
chunk in the top 3 (with some ambiguity where a fact spans two chunks).

### Baseline vs RAG

The no-context baseline answered the "weather in Moscow" question with a
plausible-but-fabricated forecast, while RAG refused for every out-of-scope
question and attached no sources. This is the concrete value of retrieval here.

### No-answer behaviour

All three unanswerable questions produced a Russian refusal, e.g.
"Информации о погоде в Москве в предоставленных источниках нет.", with
`grounded=false` and `sources=[]`.

### Source attribution safety

Sources are built in `RagService` from `LectureChunk` rows returned by
`RetrievalService` (id, sequence, start_ms, end_ms, score, excerpt). The model
cannot inject ids, times or filenames. On refusal the source list is emptied.

## 6. Retrieval configuration

| Config | Recall@1 | Recall@3 | Source correctness | B1 | Avg RAG ms |
|---|---|---|---|---|---|
| topK=5 | 0.75 | 0.917 | 1.0 | PASS | 699 |
| topK=3 | 0.75 | 0.917 | 0.917 | FAIL | 714 |

**topK=5 chosen**: latency is identical (retrieval is not the bottleneck) and it
recovers the applications fact that topK=3 misses.

## 7. Commands used

```bash
# build
JAVA_HOME=/c/tools/jdk-25.0.2-full ./mvnw -pl backend -am package -DskipTests
# run
java -jar backend/target/lecture-processor-backend-1.0.0-SNAPSHOT.jar ...
# evaluate (real HTTP + real Ollama)
python scripts/dev/rag-eval.py --base-url http://localhost:18081 \
  --lecture-id <id> --db target/day28/lecture-processor.db \
  --questions demos/day28/test-questions.json \
  --out demos/day28/rag-evaluation.json --model qwen3:1.7b --think false
```

## 8. Acceptance

- [x] real embeddings work
- [x] indexing works and is idempotent across restarts
- [x] retrieval works (Recall@1 0.75, Recall@3 0.917)
- [x] qwen3:1.7b really generates answers
- [x] answers are grounded in lecture context
- [x] timestamps correspond to chunks (from DB, not the model)
- [x] no-answer behaviour correct (3/3, no sources)
- [x] follow-up works with session history
- [x] metrics saved
- [x] regression tests pass

## 9. Commits / tag

- Commit: `559e557` (fix + evaluation + docs)
- Tag: `week6-day28-verified`

## 10. Remaining limitations

- Retrieval does not use chat history as part of the query (M1).
- Segment-level overlap can duplicate a sentence across adjacent chunks (M2).
- No reindex endpoint; an index failure leaves chat empty until reprocess (M3).
- Small corpus (5 chunks) makes Recall@5 trivial; larger real lectures would be
  needed to stress retrieval further.
