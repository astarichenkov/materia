# Materia — архитектура

## Принцип

Materia не превращает любой контент в конспект лекции. Пользователь выбирает
**сценарий** — что сделать с материалом, — а система выводит из него
эффективный профиль обработки, промпт, стратегию сборки и проверки результата.

```
MaterialType  (что за исходник)          — не обязателен, AUTO по умолчанию
ProcessingScenario (что сделать)          — авторитетный каталог
  → ScenarioResolver → ProcessingProfile  (format, detail, prompt, scenario)
  → PipelinePlanner → Stage execution
  → AssemblyPolicy → ScenarioOutputValidator
  → Export (Markdown/DOCX) + OutputVariant (immutable history)
```

## Компоненты

| Компонент | Роль |
|---|---|
| `scenario.ProcessingScenario` | enum-каталог: стабильный строковый ID, версия, название, описание, категория, рекомендуемые формат/детализация/шаблон, источник документа (`LLM`/`TRANSCRIPT`), `AssemblyPolicy`, нужен ли перевод |
| `scenario.ScenarioCatalog` | единственный список сценариев; `require(id)` падает на неизвестном ID |
| `scenario.ScenarioResolver` | сценарий + ручные override → `ProcessingProfile`; пустое поле = рекомендация, не скрытая подмена |
| `scenario.ScenarioCompatibility` | предупреждения и `INCOMPATIBLE` до запуска; агрегирует старую матрицу `TemplateCompatibility` |
| `scenario.ScenarioOutputValidator` | проверки результата по сценарию (рецепт, хронология, академические разделы, усечение) |
| `scenario.TranscriptDocumentBuilder` | документ из сегментов без LLM (verbatim / translation-only) |
| `processing.ProcessingProfile` | эффективный профиль; `scenario` nullable для legacy-строк |
| `text.AssemblyPolicy` | как склеивать чанки: `SEMANTIC`, `CHRONOLOGICAL`, `STRUCTURED`, `DIALOGUE`, `LOSSLESS` |
| `text.NotesAssembler` | реализует политику; старый `assemble(chunks, outputs)` = `SEMANTIC` |
| `pipeline.stage.TextProcessingStage` | выбирает LLM или lossless-ветку до проверки доступности LLM |
| `pipeline.stage.ExportStage` | заголовок документа берётся из профиля (`Рецепт`, `Диалоги`, `Транскрипция`, …) |

## Пайплайн

Checkpoint-based pipeline сохранён. Сценарий входит в fingerprint
`TEXT_PROCESSING` (через `ProcessingProfile.fingerprintPart()`), поэтому:

- смена сценария пересчитывает только текстовый результат и экспорт;
- `DOWNLOADING`, `AUDIO_EXTRACTION`, `TRANSCRIPTION`, `TRANSLATION` переиспользуются;
- предыдущий результат остаётся в `lecture_output_variant`;
- `RerunPlanService` и `PipelinePlanner` используют одни и те же fingerprints,
  поэтому preview совпадает с исполнением.

### Сценарии без LLM

`DocumentSource.TRANSCRIPT` (`verbatim`, `translation`) обходит вызовы модели:

1. берётся сохранённая (или переведённая) транскрипция;
2. `TranscriptDocumentBuilder` собирает Markdown с реальными таймкодами;
3. `notes.json`/`notes.md` пишутся программно, `TEXT_PROCESSING` остаётся
   checkpoint'ом, но LLM не вызывается;
4. `EXPORT` создаёт DOCX/Markdown как обычно.

`translation` использует существующую multilingual-подсистему; если перевод
отсутствует, документ собирается из оригинала и появляется предупреждение.

## Legacy-имена

Сохранены без изменений: пакеты `com.lectureprocessor`, классы `Lecture*`,
таблицы `lecture*`, значения enum, REST `/api/lectures/*`, маршруты
`/lectures/*`, артефакты `notes.md`/`transcript.json`. Это осознанная
совместимость, а не незавершённый рефакторинг.
