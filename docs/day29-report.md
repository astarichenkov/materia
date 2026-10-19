# Day 29 report — LLM optimization + presentation slides

## Part A — benchmark and prompt optimization

- Reproducible `BenchmarkRunner` over configurations A–D (model, context,
  prompt template), with a configurable source text and key facts.
- Metrics recorded: input/output tokens (estimated ~4 chars/token), total time,
  tokens/s, peak JVM heap delta, error count, output validity, key-fact hits.
- Hardware metrics that cannot be measured in-process (VRAM) are marked
  `unknown`; unavailable models are marked `SKIPPED`.
- Results persisted as JSON + CSV + Markdown.
- Versioned prompt templates; `structured-notes-v1` is the recommended default
  because it produces sectioned, definition-rich output suitable for DOCX,
  while `basic-summary-v1` is retained as the baseline.

## Part B — slides

- Frame sampling via FFmpeg at a configurable interval.
- dHash + Hamming-distance deduplication with a configurable threshold.
- Maximum image limit.
- Time-based alignment with notes sections; conservative chronological fallback.
- DOCX image insertion using Apache POI.

## Verification

- Automated tests for dedup, alignment metadata, DOCX images, benchmark
  recording/serialization (see `demos/day29/test-results.md`).
- Real benchmark/FFmpeg runs: SKIPPED (tools absent) and reported as such.

## Limitations

- Subjective answer quality cannot be fully automated; deterministic checks and
  the provided protocol are used instead.
- Slide text is not OCR'd; only visual dedup and placement are implemented.
