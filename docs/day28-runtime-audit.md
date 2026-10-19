# Day 28 — Local RAG runtime audit

Date: 2026-10-11. Branch: `fix/week6-day28-day29-real-verification`.
Scope: real behaviour of the existing Day 28 RAG implementation, verified against
a running application, real `nomic-embed-text` embeddings and real `qwen3:1.7b`
generation (no mocks).

## Actual architecture (as implemented)

```
upload → LectureProcessingService
  → TranscriptionService (whisper.cpp) → TranscriptSegment rows (SQLite)
  → EmbeddingIndexService.index()
        TextChunker.chunkSegments(maxChars, overlap)   [char-based, timestamp-aware]
        LlmProvider.embed(batch=16) via Ollama/nomic-embed-text, L2-normalized
        ChunkRepository.replaceAll() → lecture_chunk(text, embedding BLOB, embedding_dim)
  → RetrievalService.retrieve(lectureId, question, topK, minSimilarity)
        embed(question) → exact cosine over all stored chunks → filter ≥ threshold → sort → limit
  → RagService.answer()
        numbered context "[n] (hh:mm:ss–hh:mm:ss) text"
        PromptTemplates RAG_ANSWER = rag-answer-v1
        LlmProvider.chat(qwen3) → answer
        SourceRef built ONLY from retrieval metadata (id, sequence, start/end, excerpt)
  → LectureChatService persists chat_message (question, answer, sources_json)
```

Key classes: `TextChunker`, `EmbeddingIndexService`, `RetrievalService`,
`RagService`, `ChunkRepository`, `OllamaLlmProvider`, `LectureChatService`.

## Verification environment

| Item | Value |
|---|---|
| Ollama | 0.40.2 |
| Chat model | `qwen3:1.7b` (also `qwen3:4b` for limited comparison) |
| Embedding model | `nomic-embed-text` (768-dim) |
| Index | SQLite `lecture_chunk`, 5 chunks for the 88 s fixture |
| Chunking for evaluation | 300 chars / 60 overlap |
| GPU | RTX 3050 Laptop 4 GB — both models 100% GPU resident |

## Critical issues

### C1. No-evidence was not enforced; no-answer returned fabricated sources (FIXED)
With the default `min-similarity: 0.2`, every chunk passed the filter: unrelated
questions scored 0.63–0.79, relevant 0.75–0.86. `RagService` therefore always
returned 5 sources and `grounded=true`, even when the model honestly refused.
The REST response for "Какая сегодня погода в Москве?" carried 5 timestamps and
chunk ids that had nothing to do with the question — exactly what the Day 28
acceptance criteria forbid.

Fix: `NoEvidenceDetector` (see `rag/NoEvidenceDetector.java`) classifies Russian
refusal phrasings; when the answer is a refusal, `RagService` returns
`grounded=false` with an **empty** source list. Source metadata remains fully
code-controlled. Regression tests added (`NoEvidenceDetectorTest`, `RagServiceTest`).

### C2. Default chunk size hid retrieval quality (config finding)
`llm-chunk-chars: 6000` collapses both Day 27 fixtures (~450–1000 chars) into a
**single** chunk, making Recall@k meaningless. The evaluation therefore indexed
with `llm-chunk-chars=300 / overlap=60` (5 chunks), which is also a realistic
size for granular slide/segment grounding. Documented as a tuning sensitivity,
not an overfit: the recommendation is to keep chunks small enough that a chunk
maps to a coherent sub-topic.

## High issues

### H1. Source references had no similarity score (FIXED)
`SourceRef` exposed only id/sequence/timestamps/excerpt, so retrieval quality was
unobservable through the API. Added `double score`, populated from `ScoredChunk`.
`RagServiceTest` asserts the score is propagated.

## Medium issues

- **M1. Retrieval query ignores chat history.** History is passed to the LLM but
  not to `RetrievalService.retrieve`, so a pronoun-only follow-up ("А почему?")
  could retrieve poorly. Keyword-bearing follow-ups (D1/D2) worked. Left as a
  documented limitation; a query-rewrite step would be a larger change.
- **M2. Segment-level overlap duplicates whole sentences.** `overlapTail` carries
  whole transcript segments, so a sentence can appear in two adjacent chunks
  (observed: chunk 0 and chunk 1 both contain the collinear-definition sentence).
  Acceptable for recall, but it can surface the same fact twice.
- **M3. Index failure is silent for the user.** `indexSafely` logs a warning and
  the lecture still completes; chat is then empty with no reindex path.

## Low issues

- `app.ollama.embedding-dimensions: 768` is never validated against the actual
  vector length returned by the embedding model.
- `EmbeddingIndexService.isIndexed` is defined but never called.
- `OllamaLlmProvider.sanitize` strips Qwen ` thinking` blocks, but with Ollama's
  reasoning separation the clean answer already arrives in `message.content`;
  the regex is effectively dead code for qwen3 (harmless safety net).
- Exact cosine is O(n·d) per query; fine for tens–hundreds of chunks, no ANN.

## Not done (deliberately)

No cosmetic refactor, no external vector DB, no reranker, no OCR/multimodal.
Only correctness defects that affect acceptance were fixed.
