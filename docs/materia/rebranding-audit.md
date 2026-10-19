# Materia — аудит перед ребрендингом и переходом к сценариям

Дата: 2026-10-19. База: ветка `develop`, коммит `bbe6af9`
(UX-4 + Nightly Sprint 1/2). Работа ведётся в
`feature/materia-rebranding-scenarios`.

## 1. Где используется старый бренд

| Место | Файлы | Тип |
|---|---|---|
| Заголовки вкладок | `templates/*.html` (`<title>`) | пользовательский UX |
| Appbar/логотип | `fragments/layout.html` (`LP`, «Lecture Processor») | UX |
| Главная | `templates/index.html` (h1, hero) | UX |
| Страницы ошибок | `templates/error.html` | UX |
| Support / File Agent / Developer Assistant | `support.html`, `developer-help-v1.txt`, `DeveloperAssistantService` | UX / промпт |
| README и deployment | `README.md`, `deployment/**`, `scripts/run-local.ps1` | документация |
| MCP server info | `web/McpController.java` | техническое описание |
| Java-пакеты/классы | `com.lectureprocessor.*`, `Lecture*`, `LectureController` | **legacy, не трогаем** |
| REST API | `/api/lectures/*`, `/lectures/*` | **legacy, сохраняем** |
| Таблицы | `lecture`, `lecture_*` | **legacy, сохраняем** |

Вывод: бренд зашит в шаблонах и нескольких сервисных строках. Переименование
пакетов/таблиц/эндпоинтов сейчас не требуется и опасно (миграции, данные
пользователя, внешние интеграции).

## 2. «Лекция»: UX-термин vs технический идентификатор

- UX-термин (меняем на «материал»): заголовки, кнопки, подписи полей, ошибки,
  пустые состояния, сообщения о статусах, тексты промптов по умолчанию.
- Технический идентификатор (не меняем): `Lecture`, `lecture_id`,
  `lecture_*` таблицы, `/api/lectures`, `LectureChatService`,
  `LECTURE_PROCESSING`, имена артефактов (`notes.md`, `transcript.json`).
- Слово «лекция» остаётся осмысленным типом контента:
  `MaterialType.ACADEMIC_LECTURE` («Академическая лекция»), академический
  prompt/сценарий.

## 3. Тип материала → формат → сценарий → шаблон

Текущая модель (до изменений):

```
MaterialType (что за исходник)  ─┐
OutputFormat (структура)        ─┼→ ProcessingProfile → PromptTemplates
DetailLevel                     ─┘        │
                                           └→ fingerprint TEXT_PROCESSING
```

- `OutputFormat.defaultPrompt()` задаёт рекомендованный шаблон.
- `ProcessingProfile.resolvedPrompt()` берёт явный шаблон, иначе дефолт формата.
- `TemplateCompatibility` предупреждает о несоответствии, но **не блокирует**
  запуск; часть комбинаций помечена `INCOMPATIBLE`.
- `TextProcessingStage` добавляет к системному промпту `structureContract()`
  формата и `instructions()` профиля, поэтому формат выигрывает у шаблона.

Проблема: нет сущности «что пользователь хочет сделать». `OutputFormat`
смешивает сценарий (рецепт/диалог) и структуру (краткий/подробный), а UI
предлагает технический выбор формата.

## 4. Почему шаблоны могут давать неподходящий результат

1. Нет сценария: пользователь выбирает «Brief notes» для кулинарного видео —
   промпт требует академические «главные идеи», модель добавляет выводы.
2. `NotesAssembler` **всегда** дедуплицирует блоки, вопросы и пули и всегда
   добавляет `# Конспект лекции` + `## Резюме`. Для рецепта повтор ингредиента
   значим, для диалога повтор реплики — часть речи, для дословной транскрипции
   удаление повторов недопустимо.
3. `TextProcessingService.buildMarkdown()` жёстко пишет «Конспект лекции» и
   всегда делает дополнительный LLM-вызов `summarize()`.
4. `TemplateCompatibility` не знает о сценарии и не проверяет фактический
   контракт результата.
5. `VERBATIM`/translation-only сценариев нет: `TEXT_PROCESSING` всегда вызывает
   LLM.
6. Пользовательский шаблон влияет на fingerprint через `resolvedPrompt()`, но
   при изменении тела копии под тем же именем fingerprint не меняется —
   version/содержимое не входят в него (см. раздел 7).

## 5. API и данные, которые обязаны сохраниться

- `POST /lectures`, `POST /lectures/from-url`, `GET /lectures/preview`,
  `GET /lectures/{id}`, `/status`, `/process`, `/resume`, `/cancel`,
  `/delete`, `/chat`, `/rerun`, `/rerun-plan`, `/language*`.
- `/api/lectures/**` (включая download/variants/progress), `/api/library/**`,
  `/api/assistant/**`, `/api/support/**`, `/api/code-review/**`,
  `/api/file-agent/**`, `/mcp`.
- Таблицы `lecture`, `lecture_translation`, `translation_segment`,
  `stage_checkpoint`, `pipeline_run`, `lecture_processing_profile`,
  `lecture_output_variant`, `lecture_document_revision`,
  `lecture_segment_correction`, `prompt_template`.
- Persisted enum values: `MaterialType`, `OutputFormat`, `DetailLevel`,
  `JobStatus`, `StageState`, `TranslationMode`, `DocumentType`.
- Пути: `lectures/<id>/…`, `data/`, `backend/data/`.

Добавлять можно только новые enum-константы в конец и новые nullable колонки.

## 6. Какие тесты нужны

- Unit: каталог сценариев (стабильные ID, уникальность), резолвер
  (сценарий → профиль, ручные override), совместимость шаблонов
  (fail-fast), стратегии сборки, lossless-документ, валидатор результата.
- Integration: смена сценария не запускает Whisper; VERBATIM/TRANSLATION не
  вызывают LLM; смена только шаблона инвалидирует `TEXT_PROCESSING`, но не
  `TRANSCRIPTION`; старый профиль без сценария продолжает работать.
- Web: `/add` показывает карточки сценариев, `POST /lectures` сохраняет
  сценарий, карточка материала отображает сценарий и вариант, редактор
  использует подпись сценария.
- E2E/UI: бренд и универсальная терминология, мобильная ширина, CSRF,
  отсутствие «Lecture Processor» и неоправданных «лекций».

## 7. Текущие ограничения, важные для реализации

- `PromptTemplateService` хранит только версию в БД; fingerprint использует
  имя шаблона. Нужно добавить версию/хэш тела в `resolvedPrompt()` или в
  fingerprint-строку.
- `ProcessingProfile` — record из 5 полей, добавляем `scenario` (nullable).
- `TextProcessingStage` проверяет доступность LLM до выбора режима; для
  сценариев без LLM проверку нужно перенести.
- `ExportStage` требует `notes.json`; lossless-сценарии должны создавать его
  программно из сегментов, без LLM.
- `NotesAssembler` менять аккуратно: существующие тесты фиксируют текущую
  семантику дедупликации.
- `RerunPlan.hasIncompatibleTemplate()` уже существует, но endpoint
  `/lectures/{id}/rerun` его не проверяет — включаем fail-fast.
