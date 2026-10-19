# Multilingual Lecture Processing & Smart Video Discovery — Design

Status: implemented (Week 8 milestone after Day 34).
Branch: `feature/multilingual-video-discovery`.

## 1. AS-IS (audit result)

The project is a Spring Boot 4.1 modular monolith with SQLite and a resumable
stage pipeline. Relevant components:

| Concern | Existing component | Reuse decision |
|---|---|---|
| Source ingestion | `VideoPlatform`, `UrlSafetyValidator`, `YtDlpVideoDownloader`, `SourceResolutionStage`, `DownloadingStage` | Reuse for discovery import as-is |
| Transcription | `WhisperCppTranscriptionService`, `WhisperTranscriptParser`, `TranscriptSanitizer` | Add per-lecture language selection + language metadata extraction |
| Pipeline | `PipelineStage` enum, `PipelineCoordinator`, `StageCheckpointRepository`, `ArtifactManifest`, `Fingerprints` | Insert `TRANSLATION` stage between `TRANSCRIPTION` and `TEXT_PROCESSING` |
| LLM routing | `LlmProviderRegistry`, `LlmTaskSettingsService`, `LlmTaskType`, `LlmRunConfig` | Add `LECTURE_TRANSLATION` task |
| Embeddings | `EmbeddingProvider`, `EmbeddingCodec`, `VectorMath`, `lecture_chunk` | Reuse for topic vectors (new `lecture_topic.embedding`) |
| RAG | `RetrievalService`, `EmbeddingIndexService` | Reuse embeddings; similarity search is a separate exact cosine scan |
| Export | `MarkdownBuilder`, `DocxExporter`, POI | Extend with original / translated / bilingual documents |
| Slides | `SlideExtractionStage`, `slide` table, `SlideImage` | Reused unchanged, images embedded in every export variant |
| Settings/credentials | `/settings`, `ProviderCredentialService` (AES-256-GCM) | Reuse for YouTube/VK discovery keys |
| UI | Thymeleaf/HTMX templates | Extend `index`, `lecture`, `settings`; add `discover` |

Whisper already emits a detected language in its JSON (`result.language`,
`params.language`), so no extra LLM call is needed for detection.

## 2. TO-BE

### 2.1 Multilingual pipeline

```
MediaSource -> Download -> FFmpeg -> Whisper (+language)
    -> Original transcript (unchanged, always preserved)
    -> TRANSLATION stage (optional, resumable per segment)
    -> TEXT_PROCESSING (Russian notes when translation present)
    -> RAG_INDEXING (original transcript)
    -> SLIDE_EXTRACTION
    -> EXPORT (original / translated / bilingual Markdown + DOCX)
```

Language modes: `AUTO`, `ENGLISH`, `RUSSIAN`.
Only the first two are offered by the UI; the architecture resolves language
codes from a small enum so more languages can be added without pipeline changes.

Translation modes: `ORIGINAL_ONLY`, `TRANSLATE_TO_RUSSIAN`, `BILINGUAL`.
Russian source is never translated into Russian. English source with
auto-translate on defaults to `TRANSLATE_TO_RUSSIAN`.

The original `transcript.json`, `transcript_segment` rows and
`transcript.original.md` are never overwritten by translation.

### 2.2 Translation

`TranslationService` translates batches of consecutive Whisper segments through
the resolved `LECTURE_TRANSLATION` provider. The LLM returns strict JSON:

```json
{"segments":[{"id":12,"text":"..."},{"id":13,"text":"..."}]}
```

Validation: all requested IDs present exactly once, order preserved, no empty
translations, timestamps copied from source. Invalid batches are retried once,
then halved; failed segments keep their source text and are marked `FAILED`
without losing already-translated segments.

Checkpoint identity (`source_fingerprint` + `settings_hash`) covers transcript
content, source/target language, provider, model, prompt version, glossary
version and translation settings. On resume only missing/failed segments are
sent to the LLM. A changed transcript creates a new translation row; a changed
model or glossary creates a new settings hash.

Glossary: built-in technical terms (Spring Boot, `LlmProviderRegistry`,
embedding, retrieval, fine-tuning, ...) plus editable rows in
`translation_glossary`. Code identifiers, API names, formulas, commands and URLs
are explicitly preserved by the prompt and by a quality warning when a glossary
term is missing from the translation.

Quality checks (non-blocking warnings, segments flagged):
segment count/ID/order, empty output, Russian target script check, number and
formula preservation, glossary term preservation, duplicated translations.

### 2.3 Documents

Existing names stay backward compatible (`notes.md`, `notes.docx` = primary
document in the lecture's "working" language). Additional artifacts:

| Artifact | Content |
|---|---|
| `transcript.md` | original transcript |
| `transcript.original.md` | explicit original-language transcript |
| `transcript.ru.md` | timestamped Russian translation |
| `transcript.bilingual.md` | original + translation per segment |
| `notes.md` / `notes.docx` | structured notes (Russian when translated) |
| `notes.original.docx` | original transcript as a document |
| `notes.ru.docx` | Russian structured notes |
| `notes.bilingual.docx` | bilingual blocks + slides |
| `translation.json` | machine-readable translation result |

Slide JPEGs are inserted into every DOCX variant.

### 2.4 Local similarity

`TopicExtractionService` builds a topic profile per lecture from title, notes
summary, key topics/headings, and frequent terms; it embeds the combined profile
with the existing `EmbeddingProvider` and stores it in `lecture_topic`.
`LocalSimilarityService` computes exact cosine similarity against every other
processed lecture, excludes the current lecture and duplicates (same canonical
URL / provider external id / checksum), and persists the ranked result (+ common
terms) in `lecture_similarity`. The score is an actual cosine similarity, not a
generated percentage.

### 2.5 Online discovery

`VideoSearchProvider` implementations:

* `YouTubeVideoSearchProvider` — YouTube Data API v3 when a key is configured;
  otherwise the supported `yt-dlp "ytsearchN:<query>"` mechanism. The response
  records which mechanism was used (`basis`).
* `VkVideoSearchProvider` — VK API `video.search` with a user access token.
  Without a token the provider reports itself unavailable (explicit limitation).
* `RutubeVideoSearchProvider` — Rutube public search API; if the endpoint is
  unavailable the provider reports the error instead of fabricating results.

Search flow: derive 1–3 short topical queries from the topic profile (Russian
and English where useful, personal data excluded), query each enabled provider,
normalize canonical URLs/provider IDs, deduplicate, exclude the source video,
filter (platform/language/duration/date/limit), rank by embedding cosine of
lecture topic vs. result title+description with a capped keyword bonus. Online
results are ranked by title/description only — the UI labels this basis
explicitly, never claiming transcript verification.

SQLite cache (`video_discovery_cache`) is keyed by provider + query + filters
with configurable TTL; errors are cached for a shorter period.

One-click import calls the existing `RemoteLectureService.createFromUrl` and
`LectureJobService.submit`; already imported URLs return the existing lecture.

## 3. Database (migration V8)

Additive only:

* `lecture_language` — requested/detected language, source, translation mode.
* `lecture_translation` — one row per source fingerprint + settings hash.
* `translation_segment` — per-segment source/translation/status.
* `translation_glossary` — editable terminology.
* `lecture_topic` — topic profile + embedding.
* `lecture_similarity` — ranked local recommendations.
* `video_discovery_cache` — provider query cache with TTL.
* `discovered_video` — normalized provider results and import links.

The old tables are untouched. `schema.sql` gains the same idempotent DDL so
fresh installations and existing databases converge.

## 4. API / UI

| Endpoint | Purpose |
|---|---|
| `GET /discover` | discovery UI |
| `GET /api/discovery/similar/{lectureId}` | local similar lectures |
| `POST /api/discovery/search` | online search (body: lectureId or query + filters) |
| `GET /api/discovery/providers` | provider status/limitations |
| `POST /api/discovery/import` | import a discovered video |
| `GET /api/lectures/{id}/translation` | translation status/segments |
| `POST /api/lectures/{id}/language` | manual language override |
| `GET /api/lectures/{id}/download/{artifact}` | original/translated/bilingual docs |

Settings: `LECTURE_TRANSLATION` task assignment, translation defaults, glossary
management, discovery provider credentials (encrypted at rest), cache TTL and
limits.

## 5. Risks and mitigations

* **Cloud cost** — batching, per-segment resume, token usage persisted, bounded
  calls, explicit cloud warning, configurable prices.
* **Partial translation** — never overwrites the original; status `PARTIAL`;
  retry only failed segments.
* **Platform search fragility** — provider availability and method are exposed
  to the UI; no HTML scraping, no CAPTCHA/DRM bypass; unavailable platforms are
  documented, not faked.
* **Duplicate imports** — canonical URL + existing `media_source` lookup.
* **Migration safety** — additive `CREATE TABLE IF NOT EXISTS`; verified against
  a populated database.

## 6. Acceptance criteria

See the task's section 24; the implementation report
(`docs/multilingual-video-discovery-report.md`) records actual evidence.
