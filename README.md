# Lecture Processor

Локальное AI-приложение для преобразования образовательных видеолекций
в структурированные текстовые документы с использованием Whisper,
локальной LLM (Ollama + Qwen3) и локального RAG.

Основная цепочка (Week 7):

    MediaSource → yt-dlp → MediaArtifact → Resumable Pipeline
      → FFmpeg/Whisper/LLM/Slides → Markdown + DOCX

## Модули

| Модуль | Назначение |
|--------|-----------|
| `backend/` | Модульный монолит: загрузка лекций (файл или URL), обработка, транскрипция, LLM, RAG, DOCX, слайды, бенчмарки. |
| `private-ai-chat/` | Day 30: отдельный минимальный сервис приватного чата с локальной LLM (веб-чат + HTTP API). |

## Требования

- Java 25
- Maven (используется Maven Wrapper `./mvnw`)
- FFmpeg / FFprobe (аудио, кадры слайдов, merge потоков yt-dlp)
- whisper.cpp (`whisper-cli` + модель)
- Ollama с моделью (например `qwen3:1.7b`) и embedding-моделью
  `nomic-embed-text`
- `yt-dlp` — только для загрузки видео по URL (см. ниже)

Внешние инструменты не обязательны для запуска модульных/интеграционных
тестов — они подменяются тестовыми реализациями.

### Установка yt-dlp

`yt-dlp` вызывается как обычный внешний процесс (Python backend не нужен).

```bash
# Вариант 1: pip (проверено: 2026.08.19 / Python 3.14)
pip install --quiet yt-dlp
yt-dlp --version

# Вариант 2: автономный бинарник yt-dlp.exe, положить в PATH
```

Путь к исполняемому файлу задаётся через `YTDLP_PATH` (по умолчанию `yt-dlp`).
Для merge видео+аудио yt-dlp использует FFmpeg; путь задаётся через
`FFMPEG_PATH` (по умолчанию `ffmpeg`, т.е. ищется в PATH).

## Быстрый старт

```bash
# Сборка и все тесты
./mvnw verify

# Запуск backend (Day 27-29)
./mvnw -pl backend spring-boot:run -Dspring-boot.run.profiles=local

# Запуск private-ai-chat (Day 30)
./mvnw -pl private-ai-chat spring-boot:run
```

## Источники медиа

| Источник | Поддержка |
|----------|-----------|
| Локальный файл (multipart upload) | `.mp4 .mkv .mov .webm .avi .m4v` |
| YouTube | `youtube.com`, `youtu.be` |
| VK Видео | `vkvideo.ru` |
| VK | `vk.com` |
| Rutube | `rutube.ru` |

Только публично доступные видео. Авторизация/cookies и обход DRM не
поддерживаются. URL проверяется allow-list'ом платформ, HTTPS-only и защитой
от SSRF (DNS + private/loopback/link-local). Скачивание ограничено по времени,
размеру и дисковому пространству.

### API (совместимо со старыми endpoints)

| Метод | Endpoint | Назначение |
|-------|----------|-----------|
| `POST` | `/api/lectures` | загрузка локального файла (как раньше) |
| `POST` | `/api/lectures/from-url` | создать лекцию по URL (JSON: `url, quality, audioOnly, clean, slides`) |
| `GET` | `/api/lectures/metadata?url=` | предпросмотр метаданных без скачивания |
| `GET` | `/api/lectures/platforms` | список поддерживаемых платформ |
| `GET` | `/api/lectures/{id}/progress` | статус, этапы, прогресс скачивания, ошибки |
| `POST` | `/api/lectures/{id}/cancel` | отмена (немедленно останавливает внешние процессы) |
| `POST` | `/api/lectures/{id}/resume` | повторный запуск/возобновление |
| `POST` | `/api/lectures/{id}/process` | совместимый алиас resume |
| `POST` | `/api/assistant/help` | ассистент разработчика (`/help`), JSON `{question, sessionId}` |
| `GET` | `/api/assistant/status` | статус индекса документации |
| `POST` | `/api/assistant/index` | переиндексировать документацию проекта |
| `GET` | `/api/assistant/tools` | каталог read-only MCP-инструментов |
| `POST` | `/mcp` | MCP JSON-RPC (`initialize`, `tools/list`, `tools/call`) |

## Resumable pipeline

Стадии: `SOURCE_RESOLUTION → DOWNLOADING → MEDIA_VALIDATION → AUDIO_EXTRACTION
→ TRANSCRIPTION → TEXT_PROCESSING → RAG_INDEXING → SLIDE_EXTRACTION → EXPORT`.

- Каждая стадия сохраняет **checkpoint** в SQLite (состояние, fingerprint,
  manifest артефактов, ошибка, время).
- При повторном запуске завершённые стадии пропускаются, если их fingerprint
  не изменился и артефакты валидны. Сбой LLM **не запускает Whisper заново**.
- Изменение модели Whisper, LLM-конфига, prompt'а или параметров слайдов
  инвалидирует только зависимые стадии.
- Запуск защищён БД-lease'ом (повторный параллельный запуск отклоняется),
  а после рестарта приложения незавершённые задачи переводятся в `INTERRUPTED`
  и могут быть возобновлены без повторения дорогих стадий.
- `SLIDE_EXTRACTION` — отдельная стадия с ограничением числа кадров на уровне
  FFmpeg; ошибка слайдов не скрывается: задача завершается `PARTIAL` с
  предупреждением, но документ экспортируется.

## Ассистент разработчика (Day 31)

Локальный ассистент по проекту: документация (`README.md`, `docs/**`) в
отдельном RAG-индексе (`doc_chunk`) + read-only MCP-инструменты над
существующими сервисами и git.

```bash
# backend уже запущен; команда /help доступна в UI и API
curl -X POST -H 'Content-Type: application/json' \
  -d '{"question":"/help Из чего состоит пайплайн?","sessionId":"s1"}' \
  http://127.0.0.1:8080/api/assistant/help
```

- UI: `GET /assistant` (статус индекса, список инструментов, форма `/help`).
- Документация индексируется автоматически при старте, если индекс пуст и
  доступна embedding-модель; вручную — `POST /api/assistant/index`.
- MCP-сервер: `POST /mcp` (JSON-RPC 2.0). Инструменты: `git_current_branch`,
  `git_status`, `git_recent_commits`, `git_diff_stat`, `list_project_files`,
  `get_job_status`, `get_stage_runs`, `search_help_docs` — все read-only.
- Lecture-RAG (`lecture_chunk`) и documentation-RAG (`doc_chunk`) не смешиваются.
- Аутентификация MCP включается переменной `MCP_API_KEY` (заголовок
  `X-MCP-Key`). Без неё endpoint рассчитан только на localhost.

## Облачные LLM-провайдеры (DeepSeek, Day 32 preparation)

Провайдеры выбираются через единый реестр `LlmProviderRegistry`, а модель
назначается **отдельно для каждой задачи** (`LlmTaskSettingsService`):

| Задача | Ключ настройки | По умолчанию |
|--------|----------------|--------------|
| Lecture Processing | `llm.default-config` | локальная Ollama |
| Developer Assistant (`/help`) | `llm.task-config.developer-assistant` | локальная Ollama |
| Code Review (Day 32) | `llm.task-config.code-review` | DeepSeek, если ключ настроен |

Задачи изолированы: смена модели Code Review не меняет обработку лекций и
наоборот. Новый job сохраняет snapshot конфигурации и не меняется на лету.

### Установка отдельного DeepSeek API key (для каждой копии приложения)

У каждой установки **свой** ключ, встроенного глобального ключа нет:

1. Получите ключ на https://platform.deepseek.com (Billing → API keys).
2. Откройте `GET /settings` → карточка **DeepSeek (Cloud)**.
3. Введите ключ и нажмите «Сохранить / заменить ключ» (поле всегда пустое при
   открытии — пустой ввод не стирает уже сохранённый ключ).
4. Нажмите «Проверить соединение» — выполняется один короткий платный запрос.
5. Для удаления — отдельная кнопка «Удалить ключ» с подтверждением.

Endpoint по умолчанию зафиксирован на официальный `https://api.deepseek.com`
(SSRF-защита); модель задаётся `DEEPSEEK_MODEL` (по умолчанию `deepseek-flash`).
Если провайдер отклоняет модель, укажите её точный API id
(например `deepseek-v4-flash`).

### Хранение credentials

- Ключ хранится **зашифрованным at-rest** (AES-256-GCM, уникальный nonce,
  версионированный формат `v1:`) в таблице `provider_credential`.
- Мастер-ключ лежит **вне БД и исходников**: переменная окружения
  `LP_MASTER_KEY` (32 байта, base64) — предпочтительно; иначе fallback-файл
  `<storage>/.secrets/master.key` с ограниченными правами (для production
  обязательно задайте env).
- Ключ **никогда** не возвращается через REST/HTML; показывается только статус
  `API key configured` и маска последних символов.
- Ключ не попадает в логи, benchmark-результаты и git.

### Переключение Ollama → DeepSeek

1. Сохраните ключ (см. выше) и убедитесь, что «Проверить соединение» успешно.
2. В разделе **Назначение моделей** выберите Provider = `DeepSeek (Cloud)` и
   модель для нужной задачи, затем сохраните.
3. Действующая обработка лекций переключается только явным выбором модели;
   после добавления ключа автоматического переключения на облако нет.
4. Embeddings RAG всегда остаются локальными (Ollama) — облачный chat-провайдер
   для них не используется.

### Безопасность административного UI

- Без `LP_ADMIN_TOKEN` страница `/settings` и `/api/providers/**` доступны
  только с localhost (документированное ограничение до появления
  полноценных пользователей).
- С заданным `LP_ADMIN_TOKEN` вход — через форму `/settings/login` (сессия)
  или заголовок `X-Admin-Token`. Для state-changing запросов обязателен
  CSRF-токен.
- Fallback DeepSeek → Ollama по умолчанию **выключен**
  (`LLM_FALLBACK_ENABLED=false`), чтобы облачный сбой не подменялся молча.

### Ошибки DeepSeek (классификация)

`401/403` — неверный ключ; `402` — недостаток средств; `429` — rate limit;
`400` — некорректный запрос/модель; `5xx`/timeout — провайдер недоступен;
пустой ответ; обрезанный ответ (`finish_reason=length`). Сырой HTTP-ответ и
ключ пользователю не показываются.

### Benchmark и расходы

- DeepSeek доступен в `/benchmarks` как отдельный provider; сравнение с Ollama.
- Токены берутся из ответа провайдера (реальные `prompt_tokens`/`completion_tokens`,
  cache hit/miss); для облака локальные GPU/RAM/VRAM-метрики не показываются.
- Расходы — **estimate** по настраиваемому тарифу (`DEEPSEEK_PRICE_*`);
  агрегат: `GET /api/benchmarks/usage` и раздел на странице бенчмарков.

## AI Code Review для Gitea (Day 32)

Автоматический AI-review pull request'ов: webhook (или polling-fallback) →
Gitea diff + commit-consistent code RAG + documentation RAG → DeepSeek/Ollama →
валидированные structured findings → комментарий в PR.

- UI: `GET /code-review` (статус, manual review, история, метрики).
- REST: `GET /api/code-review`, `GET /api/code-review/{id}`,
  `POST /api/code-review` (manual/dry-run), `POST /api/code-review/{id}/retry`,
  `GET /api/code-review/status`.
- Webhook Gitea: `POST /api/code-review/webhook/gitea` (HMAC-SHA256,
  `X-Gitea-Signature`; события `pull_request`: opened/reopened/synchronize).
- Модель выбирается в `/settings → Code Review` (`LlmTaskType.CODE_REVIEW`);
  DeepSeek — основной, Ollama — локальная альтернатива. Fallback не скрывается.
- Безопасность: allow-list репозиториев, секрет webhook только из env,
  prompt-injection trust boundary, SSRF-защита, нет auto-merge/approve.
- Идемпотентность: ключ `repository#pr@headSha#configVersion`; повторная
  доставка не создаёт дубль; старый SHA не публикуется как актуальный.

Запуск и демо-сценарий — `demos/day32/demo-scenario.md`.

```bash
export JAVA_HOME=/c/tools/jdk-25.0.2-full
export GITEA_BASE_URL=http://203.0.113.20:8108
export GITEA_ALLOWED_REPOS=example-org/materia
export GITEA_WEBHOOK_SECRET="$(head -c 24 /dev/urandom | base64 | tr -d '\n=+/' | head -c 32)"
export CODE_REVIEW_POLL_SECONDS=20        # polling fallback
./mvnw -pl backend spring-boot:run
```

## Support Assistant (Day 33)

AI-помощник поддержки: отвечает на вопросы о продукте (documentation RAG),
диагностирует ошибки обработки лекций по реальным persisted данным (MCP) и ведёт
диалог в рамках обращения с сохранением контекста.

- UI: `GET /support` (создание/список обращений, статусы, поиск, чат, источники,
  tool trace, оценки «помогло / не помогло»).
- С лекции: `GET /support/lecture/{lectureId}` — открывает/продолжает обращение,
  связанное с лекцией; job id вводить не нужно.
- REST: `POST /api/support/ask`, `GET|POST /api/support/tickets`,
  `GET /api/support/tickets/{id}[/messages|/trace]`,
  `POST /api/support/tickets/{id}/status`, `POST /api/support/messages/{id}/feedback`,
  `GET /api/support/lecture/{id}/ticket`, `GET /api/support/metrics`, `GET /api/support/tools`.
- Маршрутизация без лишних LLM-вызовов: `GENERAL_FAQ`, `JOB_DIAGNOSTICS`,
  `TICKET_CONTEXT`, `MIXED`; простой status-lookup отвечает детерминированно
  при недоступном провайдере.
- Ответ всегда разделяет подтверждённые факты БД, документацию и предположения;
  citations + tool trace; недостаток данных честно указывается в `limitations`.
- Модель: `SUPPORT_ASSISTANT` (`/settings → Support Assistant`), независимо от
  `LECTURE_PROCESSING`, `DEVELOPER_ASSISTANT`, `CODE_REVIEW`. Если DeepSeek не
  настроен — работает Ollama; embeddings остаются локальными.
- Безопасность: read-only MCP-профиль (без git/filesystem/shell), scope/owner
  тикетов, redaction секретов, лимиты tool calls/размера/времени, trust boundary
  для prompt-injection, CSRF на state-changing запросах.

MCP-инструменты поддержки (read-only): `get_ticket`, `list_ticket_messages`,
`get_job_diagnostics`, `get_model_health`, `search_support_tickets`;
переиспользуются `get_job_status`, `get_stage_runs`, `search_help_docs`.

Демо — `demos/day33/demo-scenario.md`, E2E-скрипт —
`scripts/dev/day33-support-e2e.py`.

```bash
export JAVA_HOME=/c/tools/jdk-25.0.2-full
export PROJECT_ROOT='C:/Projects/lecture-processor'
export OLLAMA_CHAT_MODEL=qwen3:1.7b OLLAMA_DISABLE_THINKING=true
./mvnw -pl backend spring-boot:run     # http://localhost:8080/support
```

## Конфигурация (ingestion)

| Переменная | Назначение | По умолчанию |
|------------|-----------|--------------|
| `YTDLP_PATH` | путь к yt-dlp | `yt-dlp` |
| `DOWNLOAD_MAX_BYTES` | лимит размера скачивания | 8 GiB |
| `DOWNLOAD_ALLOW_HTTP` | разрешить HTTP (не рекомендуется) | `false` |
| `HEAVY_STAGE_PERMITS` | одновременные CPU/GPU-тяжёлые стадии | `1` |
| `LECTURE_STORAGE_ROOT` | корень данных (БД, медиа, артефакты) | `./data` |
| `PROJECT_ROOT` | корень проекта для assistant/MCP (иначе автопоиск) | авто |
| `ASSISTANT_DOCS_PATHS` | пути документации для RAG | `README.md,docs` |
| `ASSISTANT_AUTO_INDEX` | индексировать документацию при старте | `true` |
| `MCP_API_KEY` | общий секрет для `POST /mcp` (пусто = без проверки) | — |
| `SUPPORT_ENABLED` | включить support assistant | `true` |
| `SUPPORT_INSTALL_SCOPE` | scope тикетов текущей установки | `local-install` |
| `SUPPORT_ISOLATION` | `install` или `session` (owner-изоляция) | `install` |
| `SUPPORT_ALLOWED_TOOLS` | allow-list read-only MCP инструментов поддержки | см. application.yml |
| `SUPPORT_MAX_TOOL_CALLS` | лимит tool calls на ответ | `8` |
| `SUPPORT_TIMEOUT` | таймаут генерации ответа | `2m` |
| `DEEPSEEK_ENABLED` | включить облачный provider | `true` |
| `DEEPSEEK_BASE_URL` | официальный endpoint DeepSeek | `https://api.deepseek.com` |
| `DEEPSEEK_MODEL` | модель по умолчанию | `deepseek-flash` |
| `DEEPSEEK_MODELS` | список моделей для UI | `deepseek-flash,…` |
| `DEEPSEEK_MAX_CONCURRENT` | лимит параллельных cloud-запросов | `1` |
| `DEEPSEEK_PRICE_INPUT/OUTPUT/CACHE_HIT` | тариф за 1M токенов (для estimate) | `0.0` |
| `DEEPSEEK_BUDGET_WARNING` | порог предупреждения о расходе | `0.0` (выкл) |
| `LP_MASTER_KEY` | мастер-ключ шифрования credentials (base64, 32 байта) | fallback-файл |
| `LP_ADMIN_TOKEN` | административный токен для `/settings` и `/code-review` | пусто (localhost) |
| `LLM_FALLBACK_ENABLED` | DeepSeek → Ollama fallback | `false` |
| `GITEA_BASE_URL` | Gitea base URL | `http://203.0.113.20:8108` |
| `GITEA_TOKEN` | API-токен Gitea (только env, не коммитить) | — |
| `GITEA_WEBHOOK_SECRET` | HMAC-секрет webhook (только env) | — |
| `GITEA_ALLOWED_REPOS` | allow-list репозиториев `owner/repo` | `example-org/materia` |
| `CODE_REVIEW_ENABLED` | включить AI code review | `true` |
| `CODE_REVIEW_POLLING` | polling-fallback при недоступном webhook | `true` |
| `CODE_REVIEW_POLL_SECONDS` | интервал polling | `60` |
| `CODE_REVIEW_PUBLISH` | публиковать комментарий в PR (false = dry-run) | `true` |
| `CODE_REVIEW_CODE_PATHS` | что индексировать в code RAG | `backend/src,…` |

## Тесты и проверки

```bash
export JAVA_HOME=/c/tools/jdk-25.0.2-full
./mvnw -pl backend test        # unit + интеграционные тесты пайплайна
./mvnw verify                  # полная сборка обоих модулей
```

DeepSeek-тесты используют локальный HTTP test double
(`DeepSeekLlmProviderTest`) и **не** обращаются к платному API. Credentials,
per-task routing и CSRF покрыты unit/integration тестами
(`ProviderCredentialServiceTest`, `LlmTaskSettingsServiceTest`,
`AdminAccessServiceTest`, `ProviderApiControllerTest`). Живой smoke-тест
DeepSeek не выполнялся автоматически (требует отдельного ключа и разрешения).

Живые smoke-тесты yt-dlp (YouTube/VK/Rutube) требуют сети и не выполняются
автоматически в CI. См. `docs/week7-ingestion-final-report.md`.

Демонстрационные сценарии и результаты по дням — в `demos/`.
