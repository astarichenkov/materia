# Day 34 — демонстрация Autonomous File Agent (3–5 минут)

Цель: показать агента, который **сам** выбирает файлы, читает их через
read-only MCP-инструменты, анализирует несколько источников, формирует реальный
diff и меняет файлы только после подтверждения пользователя.

## Подготовка (до записи)

```bash
export JAVA_HOME=/c/tools/jdk-25.0.2-full
# Безопасный E2E-workspace (не рабочая документация проекта):
export PROJECT_ROOT='C:/Projects/lecture-processor-e2e'
export FILE_AGENT_PROJECT_ROOT='C:/Projects/lecture-processor-e2e'
export LECTURE_STORAGE_ROOT='C:/Projects/lecture-processor/target/day34-e2e-storage'
export OLLAMA_CHAT_MODEL=qwen3:1.7b OLLAMA_DISABLE_THINKING=true
export SERVER_PORT=18083
export LP_ADMIN_TOKEN='day34-e2e-admin'   # только для локального E2E
export FILE_AGENT_TIMEOUT=6m
./mvnw -pl backend spring-boot:run
```

`LP_ADMIN_TOKEN` нужен для неинтерактивного E2E; в браузере на localhost
страница `/file-agent` открывается и без него (CSRF-форма). Для внешнего
доступа обязательно задайте `LP_ADMIN_TOKEN` и `FILE_AGENT_MCP_KEY`.

Контрольный прогон без записи:

```bash
PYTHONIOENCODING=utf-8 python scripts/dev/day34-file-agent-e2e.py \
  --base-url http://127.0.0.1:18083 --admin-token "$LP_ADMIN_TOKEN" \
  --project-root C:/Projects/lecture-processor-e2e \
  --goal "Найди все места, где используется LlmTaskSettingsService, и обнови документацию о выборе моделей." \
  --approve
```

## Сценарий 1 — обновление документации (2 минуты)

| # | Действие | Что показать |
|---|----------|--------------|
| 1 | Открыть `http://localhost:18083/file-agent` | Метрики, форма цели, workspace `PROJECT_WORKSPACE`, история задач, список read-only инструментов |
| 2 | Ввести цель: «Найди все места, где используется `LlmTaskSettingsService`, и обнови документацию о выборе моделей для разных задач» → Analyze | Статусы `CREATED → PLANNING → SEARCHING → ANALYZING → PROPOSING → AWAITING_APPROVAL`; страница обновляется сама |
| 3 | Показать «План агента» и «Найденные / прочитанные файлы» | Агент сам выбрал 3+ файла: Java-класс, README, docs |
| 4 | Показать Tool trace | `search_code_symbols ✓`, `search_project_files ✓`, `search_project_documentation ✓` (RAG), `read_project_file ✓` ×3; модель `ollama/qwen3:1.7b` |
| 5 | Показать Diff preview | Реальный unified diff: `--- a/docs/llm-routing.md`, hunk `@@`, добавленный раздел про `LlmTaskSettingsService` |
| 6 | Нажать «Применить изменения» | Сервер повторно проверяет путь/хэши, пишет файл атомарно, прогоняет `MARKDOWN` verification → `COMPLETED`; `filesChanged=1`, `verification=PASSED` |
| 7 | Открыть изменённый файл в редакторе | Файл на диске действительно изменён; `sha256` совпадает с ответом apply |

## Сценарий 2 — генерация ADR (2 минуты)

| # | Действие | Что показать |
|---|----------|--------------|
| 1 | Ввести цель: «Проанализируй архитектуру обработки лекций и создай ADR с описанием `MediaSource`, Resumable Pipeline и checkpoints» → Analyze | Агент ищет `MediaSource`, `PipelineCoordinator`, `StageCheckpointRepository`, читает `docs/architecture/ingestion.md` и существующий ADR |
| 2 | Показать план | `proposalKind=ADR`; агент сам определил следующий номер по каталогу `docs/adr/` → `ADR-002-ingestion-pipeline.md` (не выдумал `ADR-001`) |
| 3 | Показать Tool trace | 3+ прочитанных файла, поиск по символам, RAG, `read_project_file` |
| 4 | Показать diff | `CREATE` нового файла, заголовки Status/Context/Decision/Consequences, ссылки на реальные файлы |
| 5 | Нажать «Применить изменения» | Атомарная запись, `MARKDOWN` verification, `COMPLETED` |
| 6 | Проверить `docs/adr/` | Новый ADR существует; после перезапуска приложения задача и proposal остаются в SQLite (`file_agent_task`, `file_change_proposal`) |

## Что важно проговорить

- **Зачем нужен File Agent.** Обычный ассистент отвечает текстом; File Agent
  выполняет многошаговую работу с файлами: поиск → чтение → анализ → план →
  diff → approval → запись → verification.
- **Почему это агент, а не редактор.** Пользователь формулирует только цель.
  Агент сам выбирает инструменты и файлы; LLM предлагает план и содержимое,
  Java детерминированно проверяет права, пути, размеры, хэши и diff.
- **Как выбираются файлы.** Гибрид: symbol search + lexical path/content search
  + документация RAG; LLM выбирает 3–6 кандидатов, затем `read_project_file`
  читает их. Никакого заранее заданного списка файлов нет.
- **MCP.** File tools объявлены в отдельном профиле `file-agent-read`:
  `search_project_files`, `search_code_symbols`, `read_project_file`,
  `get_file_metadata`, `search_project_documentation`. Публичный `/mcp` их не
  видит; внешний доступ к профилю требует `FILE_AGENT_MCP_KEY` (`X-MCP-Key`).
- **Безопасность файловой системы.** Два изолированных workspace
  (`PROJECT_WORKSPACE`, `LECTURE_WORKSPACE`), canonical path + symlink/junction
  checks, запрет absolute/UNC/`..`, denylist секретов, build/data, бинарников,
  лимиты размера/количества/глубины. `AGENTS.md` читается, но не пишется.
- **Approval gate.** Без явного approve (CSRF + admin) ни один существующий файл
  не меняется. Approval привязан к immutable proposal id+version; повторное
  применение запрещено; при изменении файла после diff — `CONFLICT`.
- **Rollback.** Перед записью создаются backup-копии; при ошибке записи или
  verification выполняется best-effort rollback, счётчик попыток сохраняется.
- **Воспроизводимость.** Модель и параметры (`FILE_AGENT` task) независимы от
  других задач; provider/model snapshot и tool trace сохраняются в SQLite;
  E2E-скрипт повторяет оба сценария одной командой.

## Ограничения (проговорить честно)

- Multi-file применение не является транзакционным на уровне файловой системы
  Windows: это best-effort с backup/rollback и прозрачной диагностикой.
- Агент не выполняет shell-команды и не запускает тесты по требованию LLM;
  verification — только заранее определённые проверки (Markdown/UTF-8/hash).
- В Day 34 нет DELETE/MOVE; только CREATE и MODIFY.
- Локальная `qwen3:1.7b` иногда повторяет разделы ADR; включена детерминированная
  нормализация (первый раздел каждого заголовка сохраняется).
