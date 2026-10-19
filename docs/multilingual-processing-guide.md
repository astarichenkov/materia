# Multilingual lecture processing — guide

Week 8 adds automatic language detection, resumable EN→RU translation, a
terminology glossary and original / translated / bilingual documents.

## 1. Language modes

| Mode | Behaviour |
|---|---|
| `AUTO` (default) | whisper.cpp runs with `-l auto`; its reported language is stored (`WHISPER_DETECTED`). |
| `ENGLISH` | whisper.cpp runs with `-l en`; source is recorded as a manual override. |
| `RUSSIAN` | whisper.cpp runs with `-l ru`; Russian source is never translated into Russian. |

The detected language, confidence (only if the tool reports it — never invented)
and detection source are stored in `lecture_language`. A manual correction can
change just the label (translation is invalidated, Whisper is not re-run) or the
requested recognition language (transcription and downstream are invalidated).

UI: lecture card → «Язык и перевод» / «Ручная коррекция определённого языка».
API: `GET|POST /api/lectures/{id}/language`,
`POST /api/lectures/{id}/language/detected`.

## 2. Translation modes

| Mode | Result |
|---|---|
| `ORIGINAL_ONLY` | only original transcript / notes |
| `TRANSLATE_TO_RUSSIAN` | original + Russian transcript and Russian structured notes |
| `BILINGUAL` | original + Russian + bilingual documents |

Defaults are configured by `app.translation.*` / environment variables
(`TRANSLATION_DEFAULT_LANGUAGE_MODE`, `TRANSLATION_DEFAULT_MODE`,
`TRANSLATION_AUTO`). An unknown language never triggers automatic cloud
translation; it stays `ORIGINAL_ONLY` unless bilingual output was requested.

## 3. Pipeline

```
... → TRANSCRIPTION → TRANSLATION → TEXT_PROCESSING → RAG_INDEXING
    → SLIDE_EXTRACTION → EXPORT
```

`TRANSLATION` is optional: a provider outage does not discard the transcript and
does not fail the whole job. The job becomes `PARTIAL` and the original documents
are still produced.

Only Whisper segments are translated. The stage never rewrites
`transcript.json`, `transcript_segment` or `transcript.original.md`.

## 4. Checkpoints and resume

A translation row is identified by
`(lecture_id, source_fingerprint, settings_hash)`. The fingerprint covers:

* transcript content (sequence, timestamps, text);
* source/target language;
* provider, model, temperature, max tokens, context window, thinking;
* prompt version, glossary version;
* batch size settings.

Every successful batch is persisted immediately in `translation_segment`.
On resume only `PENDING`/`FAILED` segments are sent to the LLM. Changing the
transcript creates a new translation row; changing the model or glossary creates
a new settings hash. Old translations are never mixed with a new source.

DOCX template changes only invalidate `EXPORT`. A translation failure never
re-runs `DOWNLOADING` or `TRANSCRIPTION`.

## 5. Model routing

`LECTURE_TRANSLATION` is an independent task assignment in `/settings`.

* Default: DeepSeek (`deepseek-flash`) when a credential is configured,
  otherwise local Ollama.
* Change it in `/settings → Назначение моделей по задачам → Lecture Translation`.
* Token usage and an estimated cloud cost (configurable tariff) are stored in
  `lecture_translation` and shown on the lecture card.

Cloud mode sends transcript text to the provider. The settings page displays a
warning; embeddings for RAG always stay local.

## 6. Segmented output and validation

The prompt returns strict JSON:

```json
{"segments":[{"id":12,"text":"..."}]}
```

The application validates:

* every requested ID is present exactly once;
* no duplicate IDs or empty translations;
* different source segments did not receive the same translation (local 1.7B
  models do this when a batch is too large; such a batch is split and retried);
* timestamps and order are copied from the source.

Failed batches are split until a single segment is isolated. A single segment
that still fails is marked `FAILED`; all other segments remain translated.

## 7. Quality checks

After translation the following are checked and stored as warnings on the
segment and in the translation row:

* target language script (Cyrillic ratio for Russian);
* numbers preserved;
* glossary terminology preserved;
* suspicious translation length;
* empty translations.

Flagged segments get one bounded retry as a single-segment request. If the retry
does not help, the warning is kept and the segment is still exported (never
dropped). There is no invented semantic score.

## 8. Documents

| File | Contents |
|---|---|
| `transcript.md`, `transcript.original.md` | original transcript |
| `transcript.ru.md` | timestamped Russian translation |
| `transcript.bilingual.md` | original + translation per segment |
| `notes.md`, `notes.docx` | structured notes (Russian when translation exists) |
| `notes.original.docx` | original transcript document |
| `notes.ru.docx` | Russian structured notes |
| `notes.bilingual.docx` | bilingual blocks |
| `translation.json` | machine-readable translation result |

Slides are embedded in every DOCX variant and keep the original timeline.

## 9. Configuration

| Variable | Default | Purpose |
|---|---|---|
| `TRANSLATION_ENABLED` | `true` | enable the stage |
| `TRANSLATION_DEFAULT_LANGUAGE_MODE` | `AUTO` | source language default |
| `TRANSLATION_DEFAULT_MODE` | `TRANSLATE_TO_RUSSIAN` | document set |
| `TRANSLATION_TARGET_LANGUAGE` | `ru` | target |
| `TRANSLATION_AUTO` | `true` | auto-translate English |
| `TRANSLATION_MAX_BATCH_CHARS` | `2000` | prompt size per request |
| `TRANSLATION_MAX_SEGMENTS` | `4` | segments per request |
| `TRANSLATION_MAX_LLM_CALLS` | `200` | cost guard per lecture |
| `TRANSLATION_TOKEN_BUDGET` | `200000` | soft token guard |
| `TRANSLATION_TIMEOUT` | `3m` | per-request timeout |

## 10. Troubleshooting

* **Translation partially failed** — open the lecture and press «Возобновить».
  Only failed/flagged segments are retried; Whisper is reused.
* **Language is wrong** — force English/Russian in the lecture card and run
  «Обработать заново»; alternatively correct only the detected label to avoid
  re-transcription.
* **Provider unavailable** — the translation stage is optional, documents are
  produced in the original language and the job is `PARTIAL`.
* **Cloud cost** — check stored token usage and the estimated cost; reduce
  `TRANSLATION_MAX_SEGMENTS` only increases call count, not quality, unless the
  model duplicates segments.
