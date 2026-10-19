# PROMPT-1A — аудит шаблонов и зависимостей пайплайна

Дата: 2026-10-19. Источник истины — код `backend/src/main/java` на ветке
`feature/nightly-roadmap` (база `feature/ux4-import-wizard` @ `1c570ab`).

## 1. Где заканчивается Whisper и начинается LLM

| Стадия | Инструмент | Вход | Выход |
|---|---|---|---|
| `SOURCE_RESOLUTION` | — | `lecture` + `media_source` | `MediaArtifact` |
| `DOWNLOADING` | yt-dlp | URL + качество | `source.<ext>` |
| `MEDIA_VALIDATION` | FFprobe | медиафайл | длительность + кэш обложки (best-effort) |
| `AUDIO_EXTRACTION` | FFmpeg | видео/аудио | `audio.wav` (16 kHz mono) |
| `TRANSCRIPTION` | whisper.cpp | `audio.wav` | `transcript.json` + `transcript_segment` + язык |
| `TRANSLATION` | LLM (Ollama/DeepSeek) | `transcript.json` + `lecture_language` | `lecture_translation` + `translation_segment` |
| `TEXT_PROCESSING` | LLM | исходный или переведённый транскрипт + профиль | `notes.json`, `notes.md`, `cleaned.txt`, вариант в истории |
| `RAG_INDEXING` | эмбеддинги Ollama | `transcript.json` | `lecture_chunk` |
| `SLIDE_EXTRACTION` | FFmpeg + dHash | медиафайл | `slide` + `slides/*.jpg` |
| `EXPORT` | Markdown/DOCX | `notes.json`, транскрипт, перевод, слайды | `notes.docx`, `transcript*.md`, варианты DOCX |

Whisper заканчивается на `TRANSCRIPTION`; первая LLM-стадия — `TRANSLATION`
(если включена), затем `TEXT_PROCESSING`.

## 2. Матрица «этап → вход → prompt → модель → выход → зависимые»

| Этап | Входные артефакты | Prompt template | Модель | Выход | Зависимые этапы |
|---|---|---|---|---|---|
| `TRANSLATION` | `transcript.json`, язык/режим перевода, глоссарий | `lecture-translation-v1` (`app.translation.prompt-template`) | задача `LECTURE_TRANSLATION` (`LlmTaskResolver`) | `lecture_translation`, `translation_segment` | `TEXT_PROCESSING`, `EXPORT` (для переведённых документов) |
| `TEXT_PROCESSING` | транскрипт (перевод, если режим требует), профиль лекции | профиль: `notes-academic-v1`, `notes-brief-v1`, `notes-howto-v1`, `notes-interview-v1`, `notes-methodical-v1`, `notes-article-v1`, `notes-faq-v1`, `notes-meeting-v1`, `clean-transcript-v2`; legacy — `llm.default-config.promptTemplate` | задача `LECTURE_PROCESSING` | `notes.json`, `notes.md`, `cleaned.txt`, `lecture_output_variant` | `EXPORT` |
| `RAG_INDEXING` | `transcript.json` | — (эмбеддинги) | `nomic-embed-text` | `lecture_chunk` | чат по лекции, рекомендации (не DOCX) |
| `SLIDE_EXTRACTION` | медиафайл | — (FFmpeg + pHash) | — | `slide`, `slides/*.jpg` | `EXPORT` (вставка изображений) |
| `EXPORT` | `notes.json`, транскрипт, перевод, слайды | — (шаблон DOCX `v2-multilingual`) | — | `notes.docx`, `transcript*.md`, `notes.*.docx`, вариант архива | нет |

## 3. Какая стадия «чистит» транскрипцию

`TEXT_PROCESSING` — и только при `lecture.cleanTranscript = true` (иначе
`cleaned.txt` не создаётся). Очистка идёт промптом `clean-transcript-v1`
(legacy) либо выбранным форматом `Очищенная транскрипция` →
`clean-transcript-v2`. Оригинальный `transcript.json` не перезаписывается.

## 4. Из какого текста создаётся конспект

`TextProcessingStage.translatedTranscript(...)`: если режим перевода требует
перевод и есть `COMPLETED/PARTIAL` перевод на `ru`, конспект строится по
**переведённым сегментам**; отсутствующие сегменты заменяются оригиналом.
Иначе — по оригинальному транскрипту.

## 5. От чего зависит перевод

От `transcript.json`, определённого языка (`lecture_language`), режима
(`ORIGINAL_ONLY` / `TRANSLATE_TO_RUSSIAN` / `BILINGUAL`), модели задачи
`LECTURE_TRANSLATION`, версии промпта, размера батча и версии глоссария. Всё это
входит в fingerprint `TranslationStage`, поэтому смена любого параметра
пересчитывает только перевод и downstream.

## 6. Когда создаются изображения слайдов

`SLIDE_EXTRACTION` — только если `lecture.extractSlides = true`. Извлекается
больше кандидатов, дедупликация по dHash со **всеми** сохранёнными хешами.
Слайды не зависят от LLM и от формата документа.

## 7. Какие стадии реально нужны для DOCX

Обязательный путь: `SOURCE_RESOLUTION → DOWNLOADING (local no-op) →
MEDIA_VALIDATION → AUDIO_EXTRACTION → TRANSCRIPTION → TEXT_PROCESSING →
EXPORT`. `TRANSLATION` нужен только для переведённых документов,
`SLIDE_EXTRACTION` — только для вставки изображений, `RAG_INDEXING` для DOCX
не нужен.

## 8. Checkpoints

`stage_checkpoint` (миграция V2): `stage`, `state`, `fingerprint`, `attempt`,
`artifact_manifest_json`, timestamps. Валидность = `COMPLETED` + совпадение
fingerprint + все **обязательные** артефакты манифеста существуют и непусты.
Fingerprint каждой стадии строится из её собственных входов:

- `SOURCE_RESOLUTION` — поля `media_source` (или канонические поля локальной
  лекции, если строки ещё нет);
- `DOWNLOADING` — URL/качество/audioOnly;
- `MEDIA_VALIDATION` — ключ медиафайла;
- `AUDIO_EXTRACTION` — ключ медиафайла + версия параметров;
- `TRANSCRIPTION` — ключ `audio.wav`, модель whisper, язык, версия санитайзера;
- `TRANSLATION` — ключ транскрипта, язык/режим, провайдер/модель/параметры,
  версия промпта/глоссария/батча;
- `TEXT_PROCESSING` — ключ транскрипта, состояние перевода, полный LLM-конфиг,
  `cleanTranscript`, профиль (тип/формат/детализация/prompt);
- `RAG_INDEXING` — ключ транскрипта, embedding-модель, размер чанка;
- `SLIDE_EXTRACTION` — ключ медиафайла, флаг, параметры слайдов;
- `EXPORT` — ключи `notes.json`/`cleaned.txt`, версия шаблона DOCX, слайды,
  перевод.

Координатор больше **не инвалидирует** все downstream-чекпоинты скопом:
каждая стадия сама обнаруживает изменение своего входа. Проверено
интеграционным тестом и реальным E2E (см. отчёт): смена формата перезапускает
только `TEXT_PROCESSING` и `EXPORT`.

## 9. Защита пользовательских ревизий

- `lecture_document_revision` — неизменяемые версии TRANSCRIPT / TRANSLATION /
  NOTES; `DocumentRevisionService.syncMachineRevision` **не перезаписывает**
  последнюю HUMAN/AI_ASSISTED ревизию.
- `lecture_segment_correction` — построчные правки поверх модели.
- `lecture_document_state` — CURRENT/STALE.
- `lecture_output_variant` (новое) — архив каждого машинного варианта с
  профилем, prompt и моделью; рабочие `notes.md/docx` могут перезаписываться,
  но старый вариант остаётся скачиваемым.
- `TextProcessingStage` архивирует вариант **до** `EXPORT`, поэтому даже сбой
  экспорта не уничтожает результат.

## 10. Какие стадии безопасно повторять отдельно

| Изменение | Перезапуск |
|---|---|
| формат/шаблон/детализация | `TEXT_PROCESSING → EXPORT` |
| prompt очистки транскрипции | `TEXT_PROCESSING → EXPORT` |
| модель/параметры LLM обработки текста | `TEXT_PROCESSING → EXPORT` |
| язык перевода / модель перевода | `TRANSLATION → TEXT_PROCESSING (если конспект по переводу) → EXPORT` |
| модель Whisper / язык распознавания | `TRANSCRIPTION → TRANSLATION → TEXT_PROCESSING → RAG → EXPORT` |
| только оформление DOCX | `EXPORT` |
| слайды (вкл/выкл) | `SLIDE_EXTRACTION → EXPORT` |
| ничего не менялось | все `REUSE`, задача не нужна |

## 11. Повторный запуск и история

`RerunPlanService` вычисляет план **до** запуска: для каждой стадии
`REUSE / RERUN / SKIP / BLOCKED` + причина. План не запускает работу.
`POST /lectures/{id}/rerun` сохраняет профиль и ставит job; активный job
блокирует только стадии, которые действительно надо выполнить.

## 12. Ограничения, зафиксированные честно

- `TEXT_PROCESSING` обрабатывает чанки независимо; связность длинных
  документов обеспечивается только финальным «Резюме» и структурой промпта.
  Межчанковые термины/сущности не передаются (кандидат на PROMPT-1C).
- Локальная модель может дублировать разделы; `FileAgent`-подобной
  нормализации для конспектов нет.
- `TRANSLATION` не прерывает HTTP к Ollama при отмене (известное ограничение).
- Cloud-проверки требуют настроенного ключа; в этой сессии не выполнялись.
