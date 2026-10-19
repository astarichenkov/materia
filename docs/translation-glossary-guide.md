# Translation glossary — guide

The glossary constrains terminology during EN→RU translation. It is stored in
SQLite (`translation_glossary`) and is editable in `/settings → Глоссарий
перевода`.

## Built-in terms

The V8 migration seeds terms such as:

| Term | Translation |
|---|---|
| embedding | эмбеддинг |
| retrieval | поиск релевантной информации |
| fine-tuning | дообучение |
| prompt | промпт |
| token | токен |
| pipeline | конвейер |
| checkpoint | контрольная точка |
| inference | инференс |
| latency | задержка |
| chunk | чанк |

Built-in entries can be disabled but not deleted. New entries can be added and
deleted from the UI (requires admin access to `/settings`).

## What is never translated

The prompt explicitly keeps code identifiers, class/function names, API names,
formulas, commands, URLs and technology names unchanged. Glossary entries with
the same source and target (e.g. `Spring Boot → Spring Boot`) enforce this.

## Versioning

`TranslationGlossaryService.version()` hashes the enabled terms for the
source/target pair. The value is part of the translation checkpoint identity, so
adding/changing a term invalidates translation (and downstream notes/export) but
never the transcript or media download.

## Quality enforcement

After translation, every glossary term present in a source segment must appear
in the translated segment. If it does not, the segment is marked `WARNING`
(«термин … переведён не по глоссарию») and retried once as a single segment.
The warning is persisted even if the retry cannot fix it.

## Adding terms through SQL (advanced)

```sql
INSERT INTO translation_glossary
    (term, translation, source_language, target_language,
     case_sensitive, enabled, is_builtin, updated_at)
VALUES ('vector store', 'векторное хранилище', 'en', 'ru', 0, 1, 0, '2026-01-01T00:00:00Z');
```

Prefer the UI: it applies the same constraints and records the update time.
