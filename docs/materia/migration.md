# Materia — миграция и совместимость

## Изменения БД

Единственная миграция: `V12__materia_scenarios.sql`.

```sql
ALTER TABLE lecture_processing_profile ADD COLUMN scenario TEXT;
ALTER TABLE lecture_output_variant ADD COLUMN scenario TEXT;
```

- Обе колонки nullable. Старые строки остаются `scenario = NULL` и
  отображаются как legacy-профиль («Без сценария»), им не присваивается
  «Рецепт» или «Конспект» без основания.
- Миграция аддитивная, существующие строки, артефакты и checkpoint'ы не
  меняются.
- `schema.sql` не создаёт эти колонки: на чистой установке их добавляет та же
  миграция, поэтому нет конфликта `ADD COLUMN` с уже существующей колонкой.
- Повторный запуск `SchemaMigrator` идемпотентен по `schema_migration`.

## Что НЕ менялось

- Java-пакеты `com.lectureprocessor` и классы `Lecture*`.
- Таблицы `lecture`, `lecture_*` и их значения.
- Значения persisted enum: `MaterialType`, `OutputFormat` (старые константы
  сохранены, новые добавлены в конец), `DetailLevel`, `JobStatus`, `StageState`.
- REST API `/api/lectures/**`, `/api/library/**`, `/api/assistant/**`,
  `/api/support/**`, `/api/code-review/**`, `/api/file-agent/**`, `/mcp`.
- Маршруты `/lectures/**`, включая download/variants/progress/editor/chat.
- Пути хранения `lectures/<id>/…`, `data/`, `backend/data/`.

## Новые маршруты

`/materials`, `/materials/{id}`, `/materials/{id}/editor`,
`/materials/{id}/chat` — только redirect-алиасы на существующие `/lectures/*`.
Второй реализации нет.

## Проверки

- Чистая установка: `schema.sql` + миграции V2…V12.
- Обновление существующей БД: `SchemaMigratorTest` (legacy v1 → все миграции,
  данные сохраняются, колонки `scenario` появляются).
- Старый материал без профиля: `ProcessingProfileService.legacy()` сохраняет
  сконфигурированный prompt; повторный запуск не меняет результат.
- Старый документ: редактор и экспорт работают без `scenario`.
- Новый сценарий на старом материале: переиспользуются транскрипция, перевод и
  медиа, создаётся новый вариант.
