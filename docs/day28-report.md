# Day 28 report — Local LLM + RAG

## Goal

Add fully local retrieval-augmented generation to the Day 27 application.

## Delivered

- Timestamp-aware `SegmentChunk` chunking with configurable size/overlap.
- `EmbeddingIndexService`: batch embedding via Ollama, L2-normalized vectors
  stored in SQLite (`lecture_chunk.embedding` as little-endian BLOB).
- `RetrievalService`: exact cosine top-K with a minimum-similarity threshold;
  replaceable behind the service.
- `RagService`: numbered context assembly, grounded answer, no-evidence path.
- Source attribution built from retrieval metadata only.
- Per-lecture chat with bounded history (`LectureChatService`), REST + HTMX UI.
- 12-question evaluation protocol including unanswerable questions.

## Why SQLite + in-Java cosine

For per-lecture collections (tens to low hundreds of chunks) exact search is
fast and avoids adding a vector database. The index format is isolated in
`ChunkRepository`/`RetrievalService`, so switching to a dedicated store later
requires no business-logic changes.

## Verification

- Unit tests for indexing, retrieval ordering, source construction and refusal
  (see `demos/day28/test-results.md`).
- Real with/without-RAG evaluation: SKIPPED (Ollama unavailable), protocol
  provided and not misreported.
