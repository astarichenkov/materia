# Multilingual lecture processing & smart video discovery — report

Date: 2026-10-18. Branch: `feature/multilingual-video-discovery`.
Design: `docs/multilingual-video-discovery-design.md`.

## 1. Scope delivered

* Automatic language detection from whisper.cpp metadata + manual override.
* Resumable segment translation EN→RU with per-segment persistence.
* `LECTURE_TRANSLATION` task with independent provider/model settings.
* Editable terminology glossary and quality warnings with bounded retry.
* Original / translated / bilingual Markdown and DOCX, slides preserved.
* Local semantic recommendations with measured cosine scores.
* Online discovery on YouTube, VK/VK Video, Rutube via `VideoSearchProvider`.
* Search cache, deduplication, filtering, relevance ranking and one-click import.
* Thymeleaf UI: language controls, translation panel, `/discover`, settings.

## 2. Tests

`JAVA_HOME=/c/tools/jdk-25.0.2-full ./mvnw verify` → **BUILD SUCCESS**, exit 0.
Backend + private-ai-chat: **405 tests, 0 failures, 0 errors** (2026-10-18).

New coverage (26 tests):

* `LanguageDetectorTest` — Whisper language, confidence only when reported,
  manual override, heuristics, RU never retranslated, unknown never auto-cloud.
* `TranslationServiceTest` — ordering/timestamps, glossary warnings, number
  loss, partial retry only for missing segments, model change creates a new
  row, duplicate-translation rejection, invalid JSON at segment level.
* `TranslationPipelineIntegrationTest` — full Spring context with stubbed
  external tools: EN detection → translation → Russian notes → 3 document
  variants; translator failure keeps Whisper and resumes only translation.
* `MultilingualDocumentExportTest` — bilingual Markdown and DOCX with embedded
  slide JPEGs.
* `LocalSimilarityServiceTest` — self/duplicate exclusion, embedding ranking,
  lexical fallback.
* `VideoDiscoveryServiceTest` — query sanitization, source/duplicate exclusion,
  ranking, cached/disabled providers, SSRF HTTPS rejection, import reuse and
  existing ingestion, ISO duration parsing.
* `SchemaMigratorTest` extended for V8 and the new tables.

Real external tools/APIs are not required by the automated suite; DeepSeek is
only tested against a local HTTP double (no paid calls).

## 3. Real E2E — Scenario A: English lecture translation

Video: `https://rutube.ru/video/536b9f4d016e5dd523ac670cc96e284c/`
(«Lecture 1. Introduction», 105 s, English, public Rutube).

Run: `LECTURE_STORAGE_ROOT=target/e2e-week8b`, Ollama `qwen3:1.7b`,
`OLLAMA_DISABLE_THINKING=true`, quality `480p`, slides enabled,
translation mode `TRANSLATE_TO_RUSSIAN`.

Observed:

* Lecture `6488100c-e863-4f12-9022-26c1ea891ef6`, job `COMPLETED`.
* `lecture_language`: `detected_language=en`, `detection_source=WHISPER_DETECTED`,
  confidence `null` (whisper did not report one).
* Final translation row `id=4`: `COMPLETED`, 25/25 segments, 0 failed,
  provider `ollama`, model `qwen3:1.7b`, 25 LLM calls, 17 085 input tokens,
  1 891 output tokens.
* Segment 3 was flagged `WARNING` («мало кириллицы») and retried; the local
  model still copied part of the English text, so the warning remains. All
  other segments are translated and the segment is preserved in exports.
* Files created and non-empty: `transcript.original.md`, `transcript.ru.md`,
  `transcript.bilingual.md`, `notes.md`, `notes.docx`, `notes.original.docx`,
  `notes.ru.docx`, `notes.bilingual.docx`, `translation.json`.
* Verified content: `transcript.original.md` contains the English source;
  `transcript.ru.md` contains Russian translation; `notes.md` contains Russian
  structured notes generated from the translation.

### Translation quality iteration

The first real run (batch 40 segments) showed alignment failures: the local
1.7B model repeated the first translation for several IDs. Fixes:

1. default batch reduced to 4 segments / 2000 chars;
2. explicit prompt rule forbidding merging/duplicating IDs;
3. detection of duplicate translations for different sources → split/retry;
4. low-Cyrillic ratio warning + one single-segment retry.

After the fixes, 24/25 segments are correctly aligned; the one stubborn segment
is flagged rather than silently accepted.

## 4. Real E2E — Scenario B: resume after translator failure

Test double: `target/e2e-week8b/ollama_fail_proxy.py` in front of the real
Ollama. While the marker exists it returns HTTP 500 for translation requests
containing a chosen source phrase. Everything else is forwarded to the real
Ollama; no model output is faked.

Video: «Machine Learning In 5 Minutes» imported through discovery
(lecture `efd4182a-1168-4e3e-af3e-49a14ccef058`, 53 Whisper segments).

Run 1 with failure injection:

* translation `id=8`: `PARTIAL`, 52/53 translated, 1 failed, 24 LLM calls;
* failed segment #1 marked `FAILED` with the provider error;
* job `PARTIAL`; download and transcription checkpoints untouched;
* transcription `finished_at` stayed `2026-10-17T23:33:03.723746300Z`.

Run 2 after removing the failure marker:

* translation `COMPLETED`, 53/53, 0 failed;
* only 3 LLM calls were made (retry of the missing segment plus validation
  retries), not a full re-translation;
* Whisper did not run again (same checkpoint timestamp);
* all four DOCX variants are present.

## 5. Real E2E — Scenario C: similar local lectures

Library (real Rutube downloads + one imported discovery result):

| Lecture | Topic |
|---|---|
| `ml_petroleum` | practical machine learning in petroleum industry |
| `ml_intro` | «Machine Learning | What Is Machine Learning?» |
| `Lecture 1. Introduction` | Unreal Engine 5 course intro |
| colors song | unrelated children's English lesson |
| imported ML video | machine learning in 5 minutes |

`GET /api/discovery/similar/{id}` results:

* target `ml_petroleum`: `ml_intro` score **0.894** (top), then Unreal Engine
  0.729, colors 0.682.
* target `ml_intro`: `ml_petroleum` **0.894** (top).
* target colors: ML lectures 0.682/0.676, Unreal Engine 0.617.

The current lecture is excluded, duplicates are excluded, and the related pair
ranks above unrelated content. Basis: `EMBEDDING_TOPIC_PROFILE`.

## 6. Real E2E — Scenario D: online discovery

Target lecture `ml_intro`, 8 results requested.

Rutube (`RUTUBE_PUBLIC_API`):

* 8 real results with titles, canonical URLs and durations;
* top: «Лекция 1. Введение в машинное обучение.», relevance **0.901**,
  `https://rutube.ru/video/8a6e3700ae1a0079bbb9adc8ec643b1e/`;
* other results include «Machine Learning In 5 Minutes», «What is Machine
  Learning?», full courses;
* `TITLE_DESCRIPTION_EMBEDDING` basis.

YouTube (`YT_DLP_SEARCH`): 8 real search results, top «Introduction to Machine
Learning (Machine Learning: Zero to Hero, Part 1)», relevance **0.897**,
`https://www.youtube.com/watch?v=qu_WEHvGXWk`.

VK (`VK_API_VIDEO_SEARCH`): **unavailable** — no VK user access token is
configured. The UI/API report this explicitly; no results are fabricated.

Cache: repeating the identical Rutube search returned `cached:true` in 1.04 s
versus 2.9 s for the uncached request.

### Platform limitations observed

* YouTube search via yt-dlp works, but downloading on this network is blocked
  by YouTube's «Sign in to confirm you're not a bot» challenge. The application
  does **not** read browser cookies or bypass the challenge; YouTube discovery
  search is verified, YouTube import was not possible here.
* Rutube search API works but requires a browser-like User-Agent; its public
  JSON endpoint is not a formally versioned partner API.
* VK requires a user token (not an application service token) for `video.search`.
* VK Video is represented by the same VK provider; no separate public search
  endpoint without a token was found.

## 7. Real E2E — Scenario E: import a discovered video

Chosen Rutube result: «Machine Learning In 5 Minutes»,
`https://rutube.ru/video/70ae1a22451efcbb87b1b494c4b4a24b/`.

* First `POST /api/discovery/import` →
  `{"created":true,"submitted":true,"lectureId":"efd4182a-…"}`.
* The existing resumable pipeline downloaded, transcribed (53 segments),
  translated and exported it (job `COMPLETED`).
* Second identical call →
  `{"created":false,"submitted":false,"lectureId":"efd4182a-…"}`.
* `discovered_video.imported_lecture_id` points to the created lecture.

## 8. Migration verification

A copy of the real installation database (`data/lecture-processor.db`, 6
lectures, migrations 2–3) was migrated by starting the application against the
copy:

* versions after startup: **2,3,4,5,6,7,8**;
* lecture count unchanged: **6**;
* all 8 new tables created;
* glossary seeded with 14 built-in terms;
* migration is idempotent (already applied in the E2E database restart).

## 9. Security / cost

* Provider credentials are encrypted at rest (existing AES-256-GCM store), never
  returned by an API.
* Discovery HTTP: configured provider bases only, HTTPS (loopback exception),
  redirects disabled, response size and timeout caps, no CAPTCHA/DRM bypass.
* Query sanitization removes URLs, e-mails, phone numbers and long IDs.
* Translation has a per-lecture LLM call cap and soft token budget; real token
  counts and an estimated cost are persisted.
* Cloud translation requires an explicitly configured DeepSeek credential and is
  shown as a privacy warning in `/settings`.

## 10. Known limitations

* DeepSeek translation was **not** exercised live (no separate paid key was
  available); the provider path is covered by existing DeepSeek tests with a
  local HTTP double.
* Local `qwen3:1.7b` occasionally leaves a short segment untranslated; it is
  detected, flagged and preserved. A stronger cloud model avoids this.
* YouTube download/import is blocked by the platform bot challenge on this
  network; search itself is verified.
* VK discovery requires an operator-provided user token, so it is untested live.
* Slides in the real Rutube lecture were not present (talking-head video);
  slide embedding in all DOCX variants is verified by an automated test with a
  generated JPEG.
* Similarity/topic extraction is recomputed lazily on first request; there is no
  background fan-out job.
