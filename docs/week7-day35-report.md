# Week 7 — Day 35: Lecture Editor, Quality Control & Production Hardening

Дата выполнения: 2026-10-18
Ветка: `feature/week7-day35-lecture-editor`
Базовый HEAD: `3c74a8a1cb3aabfd1750ab26a4973b7f26f4d363`
(`feature/multilingual-video-discovery`)

## 1. Итог

Реализован полноценный редактор результатов обработки лекции:

- редактирование оригинала, русского перевода и Markdown-конспекта;
- неизменяемые ревизии с optimistic locking и историей/сравнением/восстановлением;
- ручная проверка WARNING-сегментов и повторный перевод одного сегмента;
- защита человеческих правок от автоматической перезаписи;
- `STALE`-статусы зависимых документов вместо выдачи устаревших данных за актуальные;
- повторный экспорт DOCX/Markdown из сохранённых ревизий **без Whisper**;
- сохранение слайдов и изображений в DOCX;
- улучшенная библиотека лекций и страница диагностики установки.

## 2. Требования

| Требование | Статус | Доказательство |
|-----------|--------|----------------|
| `/lectures/{id}/editor` | ✅ | реальный HTTP 200 + E2E |
| Редактор транскрипции | ✅ | `LectureEditorServiceTest`, E2E long |
| Редактор русского перевода | ✅ | E2E сценарий A, `MANUALLY_CORRECTED` |
| Редактор конспекта | ✅ | E2E сценарий B, DOCX содержит правку |
| История версий | ✅ | `history()` API + UI, тесты ревизий |
| Сравнение версий | ✅ | `LineDiff`, E2E diff 38 строк |
| Восстановление версии | ✅ | E2E restore → новая ревизия 3 |
| Optimistic locking | ✅ | E2E `409 REVISION_CONFLICT` |
| Ручная проверка WARNING | ✅ | `markReviewed`, `REVIEWED` |
| Retry перевода сегмента | ✅ | реальный Ollama в E2E (`qwen3:1.7b`) |
| Human corrections защищены | ✅ | `TranslationServiceTest.preservesHumanCorrectionsOnResume` |
| Сохранение после рестарта | ✅ | рестарт приложения, правка на месте |
| Повторная генерация DOCX | ✅ | 4 варианта в E2E |
| Whisper не запускается | ✅ | checkpoint `TRANSCRIPTION` от 2026-10-16 не изменился |
| Слайды сохраняются | ✅ | DOCX media=2 во всех вариантах |
| Original/Russian/Bilingual export | ✅ | E2E, все `COMPLETED` |
| Инвалидизация зависимых артефактов | ✅ | `STALE` + удаление `TEXT_PROCESSING`/`EXPORT` checkpoint |
| Пользователь видит устаревшие данные | ✅ | `documentStates`, `quality.stale`, export `stale` |
| Поиск/фильтрация библиотеки | ✅ | `GET /?q=CSI&hasTranslation=false` в E2E |
| Диагностика зависимостей | ✅ | `/api/diagnostics`: java/storage/sqlite/ffmpeg/whisper/yt-dlp/ollama |
| Тесты безопасности | ✅ | CSRF, 404, 409, XSS, размеры, неизвестный тип |
| `mvn verify` | ✅ | см. раздел Testing |
| Реальный E2E коррекции | ✅ | сценарий A |
| E2E редактирования конспекта | ✅ | сценарий B |
| Длинный материал | ⚠️ | 746 сегментов / 44.7 мин проверено; слайдов и перевода у этого источника нет — ограничение ниже |
| Demo и инструкции | ✅ | `demos/day35/`, `docs/editor-guide.md`, `docs/installation-windows.md` |
| Изменения в Gitea | ✅ | см. раздел Git |

## 3. Архитектура

```
/lectures/{id}/editor (Thymeleaf)
        │ fetch + X-Editor-Csrf
        ▼
LectureEditorApiController
        │
        ├── LectureEditorService ── DocumentRevisionService ── lecture_document_revision
        │        │                       │ content hash + optimistic lock
        │        │                       └ retention (не удаляет ревизию #1)
        │        ├── SegmentRepository / TranslationRepository
        │        ├── SegmentCorrectionRepository (audit правок)
        │        ├── DocumentStateRepository (STALE/CURRENT)
        │        └── StageCheckpointRepository.invalidateFrom(...)
        │
        ├── DocumentExportService ── DocxExporter + SlideRepository + lecture_export
        │        (только сохранённые ревизии; атомарная замена файла)
        │
        ├── SegmentRetranslationService (LlmTaskType.LECTURE_TRANSLATION)
        └── SafeMarkdownRenderer (server-side XSS-safe preview)
```

Ключевые инварианты:

1. `transcript.json` (сырой Whisper) не перезаписывается.
2. Ревизия #1 каждого документа не удаляется retention-политикой.
3. Правка оригинала инвалидирует только `TEXT_PROCESSING` и downstream, никогда
   `TRANSCRIPTION`.
4. Правка перевода/конспекта не запускает пайплайн сама по себе: она помечает
   зависимые документы и экспорт устаревшими.
5. Экспорт не вызывает download/FFmpeg/Whisper/translation/text-processing.

## 4. Persistence

Миграция `V9__day35_lecture_editor.sql` (+ те же таблицы в `db/schema.sql`):

| Таблица | Назначение |
|---------|-----------|
| `lecture_document_revision` | immutable ревизии: тип, номер, JSON/Markdown, SHA-256, parent, source, description |
| `lecture_segment_correction` | original/corrected текст сегмента, review status, provider/model, note |
| `lecture_document_state` | `CURRENT`/`STALE` + причина |
| `lecture_export` | вариант, артефакт, хеш, id исходных ревизий, размер, status/error |

`translation_segment.status` расширен значениями `MANUALLY_CORRECTED`,
`REVIEWED`, `STALE` (TEXT, без изменения схемы).

## 5. UI/API

UI: `/lectures/{id}/editor` — 5 вкладок, фильтры перевода, preview, история,
diff, restore, экспорт, переключатель cloud retry.
Карточка лекции и библиотека содержат прямые ссылки в редактор.

API (все изменяющие запросы требуют `X-Editor-Csrf`):

- `GET /api/lectures/{id}/editor`
- `POST .../transcript`, `POST .../translation`
- `POST .../translation/segments/{seq}/review|retry|accept`
- `POST .../notes`, `POST .../notes/regenerate`, `POST .../notes/preview`
- `GET .../revisions`, `GET .../revisions/{id}`, `POST .../revisions/{id}/restore`
- `GET .../compare?type=&from=&to=`
- `POST .../export`, `GET .../exports`, `PUT .../settings/cloud-retry`

Дополнительно: `GET /diagnostics`, `GET /api/diagnostics`,
`GET /` c фильтрами библиотеки.

## 6. Translation Quality

- Статусы: `OK`, `WARNING`, `MANUALLY_CORRECTED`, `REVIEWED`, `STALE`,
  `PENDING`, `TRANSLATED`, `FAILED`.
- Quality summary: total / translated / warnings / manually corrected / reviewed /
  stale / pending. В UI явно подписано, что это журнал проверок, а не точность.
- Retry сегмента: реальный вызов `LECTURE_TRANSLATION` с контекстом соседей и
  глоссарием; результат — preview, сохранение только по кнопке «Принять».
- Cloud retry: по умолчанию OFF; включается только при
  `EDITOR_ALLOW_CLOUD_RETRY=true` **и** пользовательском переключателе
  (`app_setting.editor.allow-cloud-retry`).
- Человеческие правки не перезаписываются: `applyQualityChecks` их пропускает,
  а resume-логика считает `done()`.

## 7. Documents

| Вариант | Артефакты | Слайды |
|---------|-----------|--------|
| ORIGINAL | `notes.original.docx`, `transcript.original.md` | вставляются по таймкодам |
| RUSSIAN | `notes.ru.docx`, `transcript.ru.md` | вставляются |
| BILINGUAL | `notes.bilingual.docx`, `transcript.bilingual.md` | вставляются |
| NOTES | `notes.docx`, `notes.md` | приложение «Слайды» |

DOCX пишется во временный файл и атомарно заменяет целевой. При ошибке старый
файл остаётся, а в `lecture_export` сохраняется `FAILED` (проверено тестом
`failedExportDoesNotReplacePreviousDocument`).

## 8. Реальный E2E

Приложение запущено на `http://127.0.0.1:18085` с реальной БД
`target/e2e-week8c` (английская лекция «Lecture 1. Introduction», 25 сегментов,
2 слайда, перевод COMPLETED), Ollama `qwen3:1.7b`.

### Scenario A — ручная коррекция перевода

1. `GET /lectures/{id}/editor` → 25 сегментов, 25 переводов, 2 слайда.
2. Найден WARNING, RU исправлен, `POST /translation` → revision 2
   (`HUMAN`, `MANUALLY_CORRECTED`).
3. Повторный POST с `expectedRevision=0` → **409 REVISION_CONFLICT**.
4. Экспорт `RUSSIAN` → `COMPLETED`, `notes.ru.docx` содержит
   `[проверено редактором Day35]`, media=2.
5. Приложение перезапущено → правка и ревизии на месте
   (`TRANSLATION` revision 2 HUMAN).
6. `TRANSCRIPTION` checkpoint `2026-10-17T23:11:28Z` не изменился, в логе
   рестарта нет Whisper/transcription → распознавание не перезапускалось.

### Scenario B — редактирование конспекта

1. Markdown изменён (заголовок + абзац) → revision 2 (`HUMAN`).
2. Preview содержит заголовок, сырой `<script>` отсутствует.
3. `NOTES` export → `notes.docx` содержит правку, media=2.
4. История: `[2 HUMAN, 1 TEXT_PROCESSING_LLM]`; diff 38 строк (4 added).
5. Restore revision 1 → revision 3; повторный `NOTES` export больше не содержит
   E2E-заголовок (восстановленная версия).
6. `BILINGUAL` DOCX содержит исправленный перевод; `ORIGINAL` DOCX — 746/25
   сегментов исходника.

### Дополнительно — real segment retry

На лекции `efd4182a…` (52 сегмента, перевод Ollama) выполнен
`POST .../translation/segments/2/retry` → реальный провайдер `ollama` /
`qwen3:1.7b`, `inputTokens=775`, `outputTokens=167`, предложение сохранено
только в ответе (не в БД) — подтверждён preview-контракт.

### Stale после правки оригинала

Правка сегмента оригинала → сегмент 0 перевода `STALE`
(«оригинал изменён: перевод устарел»), `quality.stale=1`,
`NOTES=STALE`, `TRANSLATION=STALE`. `RUSSIAN` export прошёл, но устаревший
русский текст в DOCX не попал (использован исходный текст).

## 9. Длинная лекция

Источник: `CSI_ Miami.mp4` (существующий локальный fixture, 44.7 мин,
746 сегментов, английский). У источника **нет** извлечённых слайдов, перевода и
перевод не выполнялся.

Результаты:

- `GET /api/lectures/{id}/editor` — 43 мс, страница 0.23 с;
- правка сегмента #100 → revision 2 за 0.06 с;
- `ORIGINAL` export 746 сегментов — 0.39 с, 16 636 байт, правка в DOCX;
- `TRANSCRIPTION` checkpoint не изменился, стадии не запускались;
- `NOTES` помечен `STALE`, `TRANSLATION` остался `CURRENT` (перевода нет).

Ограничение: подходящей длинной лекции **с презентационными слайдами** в
локальном наборе нет, а YouTube на этой сети отдаёт bot-check. Поэтому
сохранение слайдов проверено на реальных 2 слайдах короткой английской лекции
(все четыре DOCX), а длинный материал — на 746 сегментах без слайдов. Полный
дорогостоящий прогон длинной лекции (Whisper+перевод) не запускался повторно.

## 10. Testing

```bash
export JAVA_HOME=/c/tools/jdk-25.0.2-full
./mvnw verify
```

- Backend: **411 тестов, 0 failures, 0 errors, 1 skipped**.
- `private-ai-chat`: **28 тестов, 0 failures**.
- Итого `./mvnw verify` — **439 тестов, BUILD SUCCESS**.
- Добавлены: `LectureEditorServiceTest` (13), `DocumentExportServiceTest` (6),
  `SafeMarkdownRendererTest` (4), `LectureEditorApiControllerTest` (9),
  расширен `TranslationServiceTest` (+2), обновлён `SchemaMigratorTest` (V9).
- Покрыто: revisions/conflict/restore/retention, human priority, stale,
  notes preview/XSS, DOCX variants + slides, atomic failure, CSRF/404/409,
  неизвестный тип, oversized text, миграции.
- Реальные платные DeepSeek-вызовы в тестах не выполнялись.

## 11. Security

- CSRF (`X-Editor-Csrf`/`_csrf`) на всех мутациях; в тестах проверен 403.
- Каждый запрос проверяет существование лекции; пути файлов из запроса не
  принимаются; скачивание — allow-list имён артефактов.
- Оптимистическая блокировка исключает молчаливую перезапись.
- Лимиты размера сегмента/конспекта/пакета/описания на сервере.
- Markdown preview: экранирование входа, белый список тегов, только абсолютные
  http(s) ссылки/картинки; `javascript:`/`data:`/относительные запрещены.
- Облачный retry требует явного двойного согласия.

## 12. Git

- Ветка: `feature/week7-day35-lecture-editor`.
- Коммиты:
  - `7f43385 feat: add lecture document revisions and editor persistence`
  - `cb94073 feat: add transcript, translation and notes editor with quality review`
  - `9b62eeb feat: add revision-aware export, library filters and deployment diagnostics`
  - `633f4ed feat: make revision cleanup explicit and keep full history on save`
- Push: `git push origin HEAD` → Gitea origin, новая ветка
  `feature/week7-day35-lecture-editor` (без force, без merge).
- HEAD: `633f4ed`.
- `docs/` и `AGENTS.md` обновлены локально, но не публикуются (политика
  `.gitignore`); код, README и `demos/day35/` — в origin.

## 13. Ограничения

1. Длинная лекция со слайдами недоступна в локальном наборе; проверено
   раздельно (слайды на короткой, длинный текст на 44.7-мин).
2. UI редактора — серверный Thymeleaf + vanilla JS без фреймворка; нет
   real-time collaborative editing (optimistic locking этого и не требует).
3. `TRANSLATION` ревизия синхронизируется с конвейером только для
   machine-ревизий; человеческие (`HUMAN`/`AI_ASSISTED`) не перетираются.
4. Повторный полный pipeline после правок не запускается автоматически: экспорт
   редактора и есть основной путь; `TEXT_PROCESSING`/`EXPORT` инвалидируются.
5. Нет объективной метрики точности перевода — только журнал проверок.
6. `docs/` и `AGENTS.md` не публикуются в публичный GitHub (существующая
   политика репозитория); в Gitea origin они версионируются.

## 14. WEEK 7 — FINAL READINESS

Проект готов к реальному использованию в локальной установке: полный цикл
«видео → расшифровка → перевод → конспект → проверка → исправление → DOCX»
замкнут, дорогие стадии не повторяются, данные и ревизии сохраняются после
рестарта, установка диагностируется. Остаются естественные ограничения:
качество локальной модели перевода (исправляется вручную/повторным переводом),
отсутствие многопользовательской авторизации (single-install trust model) и
необходимость внешних бинарников (FFmpeg/Whisper/Ollama), проверяемых
`/diagnostics`.
