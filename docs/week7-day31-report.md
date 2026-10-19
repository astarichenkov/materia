# Week 7 / Day 31 — Ассистент разработчика (реализация)

Ветка: `feature/week7-day31`
База: `6e3c130` (`feature/week7-architecture-ingestion`, Week 7 ingestion)
Gitea: `http://203.0.113.20:8108/example-org/materia`
Окружение: Windows / Git Bash, Java 25 (`C:\tools\jdk-25.0.2-full`),
Maven 3.9.11, Ollama 0.40.x (`qwen3:1.7b` + `nomic-embed-text`),
git 2.50.0.

---

## 1. Требования учебного задания (day_31.txt)

- Использовать RAG для подключения к документации проекта:
  `README`, `docs`, схемы данных / API.
- Через MCP подключить ассистента к проекту:
  минимум — получение текущей git-ветки; по желанию — список файлов или diff.
- Реализовать команду `/help`: ассистент отвечает на вопросы о проекте,
  используя документацию и контекст проекта.
- Минимальный результат: README + `docs` в RAG; MCP хотя бы git branch;
  `/help` отвечает на вопросы о структуре проекта.
- Формат: видео + код.

## 2. Что уже существовало

- `PipelineCoordinator`, стадийный resumable pipeline, checkpoints, DB lease.
- Lecture RAG: `EmbeddingIndexService` / `RetrievalService` / `RagService`
  (таблица `lecture_chunk`), локальные эмбеддинги через `LlmProvider`.
- `LlmProvider` (Ollama + Spring AI), `PromptTemplates`, bounded executors.
- `JobStatusService`, `PipelineJobRepository`, `StageCheckpointRepository`.
- Версионный `SchemaMigrator` (V2), Thymeleaf/HTMX UI, `GlobalExceptionHandler`.
- Разделение lecture-RAG и documentation-RAG уже было зафиксировано как
  рекомендация в `docs/week7-ingestion-final-report.md`.

## 3. Что реализовано

### 3.1 Documentation RAG (отдельная коллекция)

- Миграция `V3__day31_developer_assistant.sql` — таблица `doc_chunk`
  (`source_path`, `source_title`, `heading`, `sequence`, `text`, `embedding`,
  `embedding_dim`, `indexed_at`) + индекс. **Не смешивается** с `lecture_chunk`.
- `ProjectRootResolver` — определяет корень проекта (конфиг или подъём вверх от
  рабочего каталога), запрещает чтение вне корня.
- `DocumentationCorpusService` — читает `README.md` и `docs/**` (фильтр
  расширений `.md/.txt/.sql`, исключения `AGENTS.md`, `.git`, `target`,
  `node_modules`, `snapshots`), лимит размера файла.
- `MarkdownChunker` — чанкинг по заголовкам с ограничением размера и overlap.
- `DocumentationIndexService` / `DocumentationIndexStatus` — эмбеддинг и
  атомарная замена индекса (`DocChunkRepository.replaceAll`).
- `DocumentationIndexRunner` — автоиндексация при старте, если индекс пуст и
  локальная embedding-модель доступна (иначе только предупреждение).
- `DocumentationRetrievalService` — точный cosine-поиск, никогда не читает
  `lecture_chunk`.

### 3.2 MCP-сервер (read-only)

- `McpProtocolHandler` — JSON-RPC 2.0 MCP: `initialize`, `ping`, `tools/list`,
  `tools/call`, корректная обработка notifications и ошибок (`-32601`).
- `McpController` — `POST /mcp` (внешние MCP-клиенты), `GET /mcp` (описание).
  Опциональный `X-MCP-Key` (`app.mcp.api-key`).
- `McpToolRegistry` + `McpTool` интерфейс со схемами аргументов.
- Инструменты (все read-only, вывод ограничен `app.mcp.max-output-chars`):
  - `git_current_branch` — текущая ветка;
  - `git_status` — `--short --branch`;
  - `git_recent_commits` — `--oneline`, count 1..30;
  - `git_diff_stat` — только `diff --stat` (полный diff не отдаётся);
  - `list_project_files` — список файлов, root-confined, limit ≤ 500;
  - `get_job_status` — статус лекции/список задач;
  - `get_stage_runs` — checkpoints стадий;
  - `search_help_docs` — семантический поиск по документации.
- `GitCommandRunner` — запуск git без shell, фиксированный вектор аргументов,
  таймаут 15 с, убийство дерева процессов, bounded output.

### 3.3 Команда `/help`

- `DeveloperAssistantService`:
  - принимает `/help <вопрос>` (или вопрос без команды);
  - `/help` без аргументов возвращает детерминированную справку со списком
    MCP-инструментов (без вызова LLM);
  - собирает контекст: фрагменты documentation RAG + `git_current_branch` +
    `git_status`, полученные **через MCP** (`McpProtocolToolClient` — тот же
    JSON-RPC обработчик, что и HTTP-endpoint);
  - генерирует ответ локальной LLM на отдельном bounded executor с таймаутом;
  - возвращает `AssistantAnswer` (ответ, цитаты из документации,
    использованные MCP-инструменты, флаг `grounded`).
- `AssistantSessionStore` — ограниченная in-memory история сессии.
- Промпт `developer-help-v1` (анти-галлюцинации, ссылки на источники,
  запрет опасных операций).
- UI: `/assistant` (статус индекса, список инструментов, форма `/help` через
  HTMX, кнопка переиндексации), ссылка с главной страницы.

### 3.4 API

| Метод | Endpoint | Назначение |
|-------|----------|-----------|
| `POST` | `/api/assistant/help` | `{question, sessionId}` → `AssistantAnswer` |
| `GET`  | `/api/assistant/status` | статус documentation RAG |
| `POST` | `/api/assistant/index` | принудительная переиндексация |
| `GET`  | `/api/assistant/tools` | каталог MCP-инструментов |
| `POST` | `/api/assistant/clear` | очистить историю сессии |
| `POST` | `/mcp` | MCP JSON-RPC (initialize/tools/list/tools/call) |
| `GET`  | `/mcp` | описание MCP-сервера и список инструментов |
| `GET`  | `/assistant` | веб-страница ассистента |

## 4. Архитектура и поток данных

```
/help <вопрос>
   │
   ├─ DocumentationRetrievalService ──▶ doc_chunk (cosine, эмбеддинги Ollama)
   │
   ├─ McpProtocolToolClient ──▶ McpProtocolHandler ──▶ MCP tools
   │                                                 ├── git_current_branch
   │                                                 └── git_status
   │
   └─ LlmProvider.chat(developer-help-v1, docs + git context)
          │
          └──▶ AssistantAnswer {answer, citations[], toolCalls[], grounded}
```

Внешние MCP-клиенты используют тот же `McpProtocolHandler` через `POST /mcp`,
поэтому контракт и набор инструментов совпадают для UI и внешних агентов.

## 5. Тесты

Автоматические (`mvn verify`, Java 25):

- backend — **160** тестов (было 118; +42), 0 failures/errors;
- private-ai-chat — 28 тестов (без изменений).

Новые тесты:

- `MarkdownChunkerTest` — заголовки, ограничение размера, пустой ввод;
- `DocumentationCorpusServiceTest` — README + docs, исключение `AGENTS.md` и
  `target/`, запрет выхода за корень проекта;
- `DocumentationIndexServiceTest` — индексация в отдельную коллекцию,
  статус;
- `DocumentationRetrievalServiceTest` — порядок по cosine, пустой индекс;
- `McpProtocolHandlerTest` — initialize, tools/list, tools/call,
  unknown tool/method, notification без ответа;
- `GitToolsTest` — фиксированные read-only аргументы (`diff --stat`),
  clamp count, недоступный git;
- `GitCommandRunnerTest` — реальный read-only git, отклонение пустых аргументов;
- `DeveloperAssistantServiceTest` — `/help` с цитатами и MCP-вызовами,
  справка без LLM, недоступная модель, ошибки инструментов, история;
- `AssistantApiControllerTest` — 200/400/503, статус, каталог инструментов;
- `McpControllerTest` — JSON-RPC ответ, notification 202, проверка API-ключа;
- `SchemaMigratorTest` — V3 применяется, повторный запуск идемпотентен,
  старые данные целы.

## 6. Реальные проверки (не mock)

Backend запущен с реальной Ollama (`qwen3:1.7b`, `nomic-embed-text`) и реальным
git на порту 18081, `PROJECT_ROOT=C:\Projects\lecture-processor`:

- **Автоиндексация документации**: `GET /api/assistant/status` →
  19 источников, 235 чанков (README + `docs/**`), запись в лог:
  `Documentation RAG indexed: 19 documents, 235 chunks`.
- **MCP**: `initialize` вернул `protocolVersion=2025-06-18`;
  `tools/list` вернул 8 инструментов со схемами;
  `tools/call git_current_branch` → `feature/week7-day31` (isError=false);
  `tools/call git_status` → реальный список изменённых файлов;
  неизвестный метод → JSON-RPC `-32601`.
- **`/help` через API** с реальным вопросом о пайплайне → ответ LLM,
  `grounded=true`, `toolCalls=[git_current_branch ✓, git_status ✓]`,
  6 цитат из `docs/*` и `README.md`.
- **`/help` через UI-фрагмент** (`POST /assistant/help`) → HTML с блоком
  ответа, MCP-инструментами и источниками.
- **`GET /assistant`** → 200, форма `/help` с session id.
- **`POST /assistant/reindex`** → 302 на `/assistant`.
- **`search_help_docs`** через MCP вернул реальные фрагменты `docs/*`.

Реальная транскрибация/скачивание видео не запускались: Day 31 их не меняет,
а checkpoint'ы и пайплайн не затрагивались.

## 7. Сценарий демонстрационного видео

1. `git branch --show-current` → `feature/week7-day31`.
2. Запуск:
   ```bash
   export JAVA_HOME=/c/tools/jdk-25.0.2-full
   export PATH="$JAVA_HOME/bin:$PATH"
   PROJECT_ROOT='C:/Projects/lecture-processor' \
   LECTURE_STORAGE_ROOT='C:/Projects/lecture-processor/backend/target/e2e-day31' \
   OLLAMA_CHAT_MODEL=qwen3:1.7b OLLAMA_DISABLE_THINKING=true SERVER_PORT=18081 \
   ./mvnw -pl backend spring-boot:run
   ```
3. Показать лог `Documentation RAG indexed: N documents, M chunks`.
4. Открыть `http://127.0.0.1:18081/assistant`, задать
   `/help Из чего состоит пайплайн обработки лекции?` — показать ответ,
   MCP-инструменты и источники.
5. Показать MCP из терминала:
   ```bash
   curl -s -X POST -H 'Content-Type: application/json' \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"git_current_branch","arguments":{}}}' \
     http://127.0.0.1:18081/mcp
   ```
6. Показать `GET /mcp` (каталог инструментов) и `GET /api/assistant/status`.

## 8. Известные ограничения

- MCP-сервер по умолчанию без аутентификации на localhost; для внешнего
  доступа обязательно задать `MCP_API_KEY`.
- MCP-транспорт — HTTP JSON-RPC без SSE/streaming; это покрывает
  `initialize` / `tools/*`, но не все возможности спецификации MCP
  (resources, prompts, sampling).
- `git_diff_stat` отдаёт только статистику, не полный diff (осознанно).
- `list_project_files` ограничен по глубине и количеству.
- Ответ `/help` зависит от локальной LLM (`qwen3:1.7b`) и качества
  документации; при недоступной Ollama API возвращает 503, а справка `/help`
  без аргументов работает без LLM.
- Documentation RAG не отслеживает изменения файлов автоматически: при
  изменении docs нужно `POST /api/assistant/index` (или рестарт с пустым
  индексом).
- `docs/` и `AGENTS.md` остаются локальными (`.gitignore`, политика
  безопасности репозитория) и не публикуются.

## 9. Что готово для Day 32

- Готовый MCP read-only surface для внешних агентов (git/jobs/docs).
- Отдельная documentation RAG-коллекция для RAG-ассистента.
- `AssistantAnswer` с цитатами и tool-call трейсом — база для более сложных
  агентских сценариев (суб-агенты, цепочки, ревью).
- Bounded executor и таймауты для длительных LLM-операций.
- Расширяемый набор MCP-инструментов (достаточно добавить новый `McpTool`).
