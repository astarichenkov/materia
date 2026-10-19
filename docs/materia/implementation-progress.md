# Materia — прогресс реализации

Обновлено: 2026-10-19. Ветка `feature/materia-rebranding-scenarios`
(база `develop` @ `bbe6af9`).

## Статус фаз

| Фаза | Статус | Коммит |
|---|---|---|
| 1. Audit | готово | `docs/materia/rebranding-audit.md` |
| 2. Branding | готово | см. `git log` |
| 3. Scenario architecture | готово | `scenario/**`, `ProcessingProfile` |
| 4. Prompt system | готово | 5 новых prompt-файлов, assembly policies, validator |
| 5. Pipeline | готово | no-LLM ветка, scenario fingerprint, EXPORT title |
| 6. UI | готово | wizard, карточка, библиотека, редактор, studio |
| 7. Tests | готово | unit + integration + Playwright + live E2E |
| 8. Hardening/docs | готово | migration V12, docs/materia/* |

## Что сделано

### Branding
- `templates/**` (`materia`, «материал», заголовки, ошибки, Support).
- `README.md`, `scripts/run-local.ps1`, `deployment/windows/*`.
- Промпты ассистентов и MCP-описание.

### Scenario architecture
- `scenario/ProcessingScenario` (13 сценариев, стабильные ID, версия).
- `ScenarioCatalog`, `ScenarioResolver`, `ScenarioCompatibility`,
  `ScenarioOutputValidator`, `TranscriptDocumentBuilder`.
- `ProcessingProfile.scenario` (nullable), `documentLabel()`, `usesLlm()`.
- `OutputFormat`: новые `SHORT_SUMMARY`, `RECIPE`, `DIALOGUE`,
  `VERBATIM_TRANSCRIPT`, `TRANSLATION_ONLY`; `usesLlm()`, `documentLabel()`.

### Prompts / assembly
- Шаблоны: `scenario-notes-v1`, `scenario-summary-v1`, `scenario-recipe-v1`,
  `scenario-dialogue-v1`, `scenario-instruction-v1`.
- `AssemblyPolicy` + `NotesAssembler.assemble(..., policy)`.
- `TextProcessingService` принимает policy, title, includeSummary.
- `TextQualityReport.withWarnings`.
- Fail-fast несовместимых комбинаций в `/lectures/{id}/rerun`.

### Pipeline
- `TextProcessingStage`: lossless-ветка до проверки LLM; LLM недоступна только
  для генеративных сценариев.
- `ExportStage`: заголовок документа из профиля.
- `RerunPlanService`: `ScenarioCompatibility`, scenario в applied profile.
- Scenario входит в fingerprint `TEXT_PROCESSING`.

### DB
- `V12__materia_scenarios.sql`: nullable `scenario` в
  `lecture_processing_profile` и `lecture_output_variant`.
- `SchemaMigrator` V12, `schema.sql` не менялся (во избежание конфликта ALTER).

### UI
- Импорт: карточки «Что сделать с материалом?», сводка (сценарий, результат,
  LLM/без LLM), сохранение сценария до запуска.
- Карточка материала: выбор сценария, документ по сценарию, история вариантов
  со сценарием, fallback для legacy.
- Библиотека: сценарий и формат в карточке.
- Редактор: подпись документа из сценария.
- `/settings/prompts`: таблица «сценарий → шаблон», рекомендации.
- `/materials/*` — redirect-алиасы.

## Тесты

- backend 553 (1 skipped), private-ai-chat 28, `mvn verify` успешен.
- Playwright 30/30 (включая upload).
- `scripts/smoke-ui.py` OK.
- Реальные сценарии на Ollama/Whisper: recipe, dialogue, short-summary,
  verbatim, translation; смена scenario без повторного Whisper.
- Подробности: `docs/materia/test-report.md`.

## Осталось / ограничения

- Live-проверка DeepSeek не выполнялась (нет ключа/разрешения).
- Diarization не реализована.
- Валидатор качества эвристический, не блокирует документ с предупреждениями.
- Prompt-qualitiy на `qwen3:1.7b` проверено на коротких материалах; длинные
  многочанковые сценарии требуют отдельного benchmark.
- `scenario` не добавлен в `schema.sql`: на чистой БД его создаёт V12.

## Следующий шаг

1. Прогнать сценарии на длинном многочанковом материале (recipe/dialogue).
2. Добавить benchmark качества по сценариям (ingredient recall, dialogue order).
3. При наличии разрешения — live DeepSeek для `SUPPORT_ASSISTANT`/`CODE_REVIEW`.
4. Рассмотреть diarization как отдельную опциональную стадию.
