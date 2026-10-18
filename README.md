# Lecture Processor

Локальное AI-приложение для преобразования образовательных видеолекций
в структурированные текстовые документы с использованием Whisper,
локальной LLM (Ollama + Qwen3) и локального RAG.

Основная цепочка (Week 7 + Week 8):

    MediaSource → yt-dlp → MediaArtifact → Resumable Pipeline
      → FFmpeg/Whisper/(Translation)/LLM/Slides → Markdown + DOCX
      → локальные рекомендации + online video discovery

Week 8: автоматическое определение языка, возобновляемый перевод EN→RU,
глоссарий, оригинальные/русские/двуязычные документы и умный поиск похожих
видео (`/discover`).

## Модули

| Модуль | Назначение |
|--------|-----------|
| `backend/` | Модульный монолит: загрузка лекций (файл или URL), обработка, транскрипция, мультиязычный перевод, LLM, RAG, DOCX, слайды, discovery, бенчмарки. |
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
`FFMPEG_PATH` (по умолчанию `ffmpeg`, т.е. ищется в PATH). `FFMPEG_PATH` может
указывать и на сам бинарник, и на каталог `bin` — в обоих случаях путь
передаётся yt-dlp через `--ffmpeg-location`.

#### JavaScript runtime и YouTube

Для части форматов YouTube yt-dlp нужен JavaScript runtime (Node.js или Deno).
Приложение определяет его автоматически (`YTDLP_JS_RUNTIME=auto`): Node.js, затем
Deno; если runtime нет — yt-dlp работает без него, а `/diagnostics` показывает
предупреждение. Явно: `YTDLP_JS_RUNTIME=node` и опционально
`YTDLP_JS_RUNTIME_PATH` (файл или каталог).

`--remote-components` (загрузка исполняемых EJS-компонентов с GitHub) **выключен**
по умолчанию; включение — только осознанно, через
`YTDLP_REMOTE_COMPONENTS=ejs:github`.

#### Cookies (по умолчанию выключены)

Публичные видео импортируются без cookies. При необходимости администратор может
включить авторизованный режим:

```bash
# cookies из локального браузера (Chromium-совместимые, включая Yandex Browser)
YTDLP_COOKIES_MODE=browser \
YTDLP_COOKIES_BROWSER=chrome \
YTDLP_COOKIES_PROFILE='C:\Users\<user>\AppData\Local\Yandex\YandexBrowser\User Data\Default'

# cookies-файл в формате Netscape (никогда не коммитить)
YTDLP_COOKIES_MODE=file YTDLP_COOKIES_FILE=/path/to/cookies.txt
```

Cookies не обязательны, не входят в тестовые фикстуры и не обходят CAPTCHA/DRM.

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
| `POST` | `/api/lectures/from-url` | создать лекцию по URL (JSON: `url, quality, audioOnly, clean, slides, languageMode, translationMode, autoTranslate`) |
| `GET` | `/api/lectures/{id}/language` | язык, режим перевода и обнаруженный язык |
| `POST` | `/api/lectures/{id}/language` | вручную задать язык/режим перевода |
| `POST` | `/api/lectures/{id}/language/detected` | скорректировать определённый язык |
| `GET` | `/api/lectures/{id}/translation` | статус перевода и сегменты |
| `GET` | `/api/lectures/{id}/download/{artifact}` | оригинал / перевод / двуязычные документы (`transcript.ru.md`, `notes.ru.docx`, `notes.bilingual.docx`, …) |
| `GET` | `/discover` | UI локальных рекомендаций и онлайн-поиска |
| `POST` | `/api/discovery/search` | поиск видео у реальных провайдеров |
| `GET` | `/api/discovery/similar/{lectureId}` | похожие лекции (cosine) |
| `GET` | `/api/discovery/providers` | статус и ограничения провайдеров |
| `POST` | `/api/discovery/import` | добавить найденное видео в библиотеку |
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
| `GET` | `/file-agent` | UI автономного файлового агента (Day 34) |
| `POST` | `/api/file-agent/tasks` | создать задачу File Agent (`goal`, `workspace`, `lectureId`) |
| `GET` | `/api/file-agent/tasks[/{id}]` | история/статус задач, метрики, tool trace |
| `POST` | `/api/file-agent/proposals/{id}/approve` | подтвердить и применить proposal |
| `POST` | `/api/file-agent/proposals/{id}/reject` | отклонить proposal |
| `POST` | `/mcp` | MCP JSON-RPC (`initialize`, `tools/list`, `tools/call`) |

## Resumable pipeline

Стадии: `SOURCE_RESOLUTION → DOWNLOADING → MEDIA_VALIDATION → AUDIO_EXTRACTION
→ TRANSCRIPTION → TRANSLATION → TEXT_PROCESSING → RAG_INDEXING
→ SLIDE_EXTRACTION → EXPORT`.

`TRANSLATION` — необязательная стадия: сбой переводчика сохраняет оригинал,
задача становится `PARTIAL`, а Whisper и скачивание не повторяются.

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

## Мультиязычная обработка (Week 8)

* `AUTO / ENGLISH / RUSSIAN` для исходного языка; язык определяется по JSON
  whisper.cpp (`result.language`), confidence сохраняется только если инструмент
  его реально вернул.
* Перевод EN→RU по сегментам с сохранением timestamps, ID и порядка; русская
  лекция повторно не переводится.
* Отдельная задача `LECTURE_TRANSLATION` в `/settings` (DeepSeek Flash, если
  настроен ключ, иначе Ollama).
* Возобновление: каждая успешная партия сегментов сохраняется сразу; повтор
  переводит только отсутствующие/сомнительные сегменты.
* Глоссарий терминов (`/settings → Глоссарий перевода`) и проверки качества
  (кириллица, числа, термины, пустые сегменты) — без выдуманного «процента
  уверенности».
* Документы: оригинал, русский перевод, двуязычный вариант (Markdown + DOCX),
  слайды сохраняются во всех вариантах.

Подробно: `docs/multilingual-processing-guide.md`,
`docs/translation-glossary-guide.md`, дизайн — `docs/multilingual-video-discovery-design.md`.

## Поиск похожих видео (Week 8)

* Локальные рекомендации: topic profile (title + summary + key topics +
  transcript) → локальные embeddings → cosine similarity. Текущая лекция и
  дубликаты исключены.
* Онлайн: `YouTubeVideoSearchProvider` (Data API v3 или поддерживаемый
  `yt-dlp ytsearchN:`), `VkVideoSearchProvider` (VK API `video.search`, нужен
  user token), `RutubeVideoSearchProvider` (публичный search API).
* Двухступенчатое ранжирование: retrieval по запросам → cosine по
  title/description с ограниченным keyword-bonus. Для онлайн-результатов
  basis явно показывает, что транскрипт не проверялся.
* SQLite-кэш с TTL, дедупликация по canonical URL/provider id, фильтры,
  one-click import через существующий yt-dlp pipeline.

Подробно: `docs/video-discovery-guide.md`.

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
| Support Assistant (Day 33) | `llm.task-config.support-assistant` | локальная Ollama |
| File Agent (Day 34) | `llm.task-config.file-agent` | DeepSeek, если ключ настроен, иначе Ollama |

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


## Autonomous File Agent (Day 34)

Агент выполняет многошаговые задачи над файлами: сам ищет и читает несколько
файлов (lexical/symbol search + documentation RAG), анализирует их, формирует
план, предлагает изменения с реальным unified diff и **меняет файлы только
после подтверждения** пользователя.

```text
goal → plan → search/read tools → context → proposal → diff preview
     → approval (CSRF + admin) → revalidate hashes → atomic write
     → verification → COMPLETED / CONFLICT / FAILED (rollback)
```

- UI: `GET /file-agent` — цель, workspace, история, статус, модель, план,
  найденные/прочитанные файлы, tool trace, diff, кнопки Analyze / Cancel /
  Regenerate / Approve / Reject.
- REST: `POST /api/file-agent/tasks`, `GET /api/file-agent/tasks[/{id}]`,
  `GET .../trace`, `GET .../proposals`, `GET /api/file-agent/proposals/{id}/diff`,
  `POST .../cancel`, `POST .../regenerate`, `POST .../approve`, `POST .../reject`,
  `GET /api/file-agent/metrics|tools|status`.
- LLM-задача `FILE_AGENT` настраивается отдельно в `/settings → File Agent`;
  provider/model snapshot сохраняется для каждого запуска.
- Изменения: только `CREATE` и `MODIFY`; approval привязан к immutable
  proposal id+version, повторное применение запрещено. Если файл изменился
  после подготовки diff — `CONFLICT`, файл не перезаписывается.
- Workspace isolation: `PROJECT_WORKSPACE` (репозиторий) и
  `LECTURE_WORKSPACE` (`<storage>/lectures/<id>`). Canonical path checks,
  запрет absolute/UNC/`..`, symlink/junction escape protection, denylist
  секретов/build/data/бинарников, лимиты размера/количества/глубины.
- MCP: read-only profile `file-agent-read` — `search_project_files`,
  `search_code_symbols`, `read_project_file`, `get_file_metadata`,
  `search_project_documentation`. Публичный `/mcp` их не видит; внешний доступ
  к профилю требует `FILE_AGENT_MCP_KEY` (заголовок `X-MCP-Key`). Write-MCP
  инструмента нет — запись только через approval UI/API.
- Persistence: SQLite migration `V7__day34_file_agent.sql` (`file_agent_task`,
  `file_agent_execution`, `file_agent_tool_call`, `file_change_proposal`,
  `proposed_file_change`); tool trace, план, diff, хэши, метрики и стоимость.
- Verification: только предопределённые проверки (UTF-8/hash, Markdown,
  обязательные разделы ADR); произвольные shell-команды от LLM не запускаются.
- Rollback: backup перед записью + best-effort восстановление при ошибке;
  multi-file атомарность на Windows не гарантируется и это явно показывается.

Запуск с безопасным E2E-workspace:

```bash
export JAVA_HOME=/c/tools/jdk-25.0.2-full
export PROJECT_ROOT='C:/Projects/lecture-processor-e2e'
export FILE_AGENT_PROJECT_ROOT='C:/Projects/lecture-processor-e2e'
export LECTURE_STORAGE_ROOT='C:/Projects/lecture-processor/target/day34-e2e-storage'
export OLLAMA_CHAT_MODEL=qwen3:1.7b OLLAMA_DISABLE_THINKING=true
export SERVER_PORT=18083
export LP_ADMIN_TOKEN='...'           # для неинтерактивного E2E
./mvnw -pl backend spring-boot:run
```

Демо — `demos/day34/demo-scenario.md`, E2E — `scripts/dev/day34-file-agent-e2e.py`.

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
| `TRANSLATION_ENABLED` | включить стадию перевода | `true` |
| `TRANSLATION_DEFAULT_LANGUAGE_MODE` | язык источника по умолчанию | `AUTO` |
| `TRANSLATION_DEFAULT_MODE` | режим документов | `TRANSLATE_TO_RUSSIAN` |
| `TRANSLATION_AUTO` | авто-перевод английских лекций | `true` |
| `TRANSLATION_MAX_SEGMENTS` | сегментов на один LLM-запрос | `4` |
| `TRANSLATION_MAX_LLM_CALLS` | лимит LLM-вызовов на лекцию | `200` |
| `DISCOVERY_ENABLED` | включить discovery | `true` |
| `DISCOVERY_PROVIDERS` | провайдеры поиска | `youtube,vkontakte,rutube` |
| `DISCOVERY_CACHE_TTL` | TTL успешного поиска | `24h` |
| `DISCOVERY_MAX_RESULTS` | лимит результатов | `20` |
| `DISCOVERY_MIN_SIMILARITY` | порог локальных рекомендаций | `0.25` |
| `YTDLP_PATH` | путь к yt-dlp | `yt-dlp` |
| `YTDLP_JS_RUNTIME` | JS runtime для yt-dlp: `auto`/`node`/`deno`/`disabled` | `auto` |
| `YTDLP_JS_RUNTIME_PATH` | путь к runtime (файл или каталог) | авто (PATH) |
| `YTDLP_REMOTE_COMPONENTS` | явное разрешение EJS-компонентов yt-dlp | выключено |
| `YTDLP_COOKIES_MODE` | `none`/`browser`/`file` | `none` |
| `YTDLP_COOKIES_BROWSER` | браузер для cookies (например `chrome`) | — |
| `YTDLP_COOKIES_PROFILE` | профиль/путь user-data для cookies | — |
| `YTDLP_COOKIES_FILE` | cookies.txt для режима `file` | — |
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

## Lecture Editor и повторный экспорт (Day 35)

Редактор позволяет довести обработанную лекцию до качественного документа
без повторного скачивания, FFmpeg и Whisper.

- `GET /lectures/{id}/editor`: вкладки «Оригинал», «Перевод», «Конспект»,
  «Слайды», «История изменений».
- Каждая правка — новая неизменяемая ревизия с источником
  (`WHISPER`/`TRANSLATION_LLM`/`TEXT_PROCESSING_LLM`/`HUMAN`/`AI_ASSISTED`),
  SHA-256 и родительской ревизией. История: сравнение (diff) и восстановление
  версии как новой ревизии.
- Optimistic locking: клиент присылает `expectedRevision`; при конфликте —
  `409 REVISION_CONFLICT`, данные не перезаписываются.
- Ручные исправления перевода (`MANUALLY_CORRECTED`) и проверенные сегменты
  (`REVIEWED`) имеют приоритет и не перезаписываются автоматическим переводом.
  `STALE` означает «оригинал изменён»: устаревший перевод не выдаётся за
  актуальный и не попадает в экспорт как текущий.
- Правка оригинала не запускает Whisper: инвалидируются только
  `TEXT_PROCESSING`/`EXPORT`; затронутые сегменты перевода помечаются `STALE`.
- Повторный перевод сегмента использует задачу `LECTURE_TRANSLATION`, соседние
  сегменты и глоссарий; результат — предложение, сохраняется только явным
  принятием. Облачный повтор WARNING-сегментов по умолчанию **выключен**
  (двойное согласие: `EDITOR_ALLOW_CLOUD_RETRY` + переключатель в UI).
- Экспорт `ORIGINAL`/`RUSSIAN`/`BILINGUAL`/`NOTES` собирается из последних
  ревизий и уже извлечённых слайдов, пишется атомарно и регистрируется в
  `lecture_export` (id ревизий + хеш → статус «устарел»).
- Безопасный Markdown-предпросмотр: серверный рендер, сырой HTML не проходит,
  ссылки/изображения только `http(s)`, CSRF `X-Editor-Csrf`.
- Библиотека `GET /` ищет по названию/теме с фильтрами языка, статуса, наличия
  перевода и сортировкой.
- `GET /diagnostics` проверяет Java, хранилище/диск, SQLite, FFmpeg, Whisper,
  yt-dlp и Ollama и показывает, чего не хватает.

Таблицы: `lecture_document_revision`, `lecture_segment_correction`,
`lecture_document_state`, `lecture_export` (миграция V9).

Подробно: `docs/editor-guide.md`, установка — `docs/installation-windows.md`,
отчёт — `docs/week7-day35-report.md`, демо — `demos/day35/demo-scenario.md`.

## Интерфейс и навигация (UX-2)

Единая оболочка приложения — `templates/fragments/layout.html`: desktop sidebar,
mobile drawer, topbar, breadcrumbs, flash-сообщения. Страницы подключают общие
ассеты через `fragments/layout :: assets`; inline `<style>` и CDN больше не
используются.

Статические ресурсы лежат в `backend/src/main/resources/static/`:

- `css/tokens.css`, `base.css`, `components.css`, `pages.css`;
- `js/app.js` (drawer, `<details>`, confirm, HTMX error notice);
- `vendor/htmx.min.js` — htmx **1.9.12**, SHA-256
  `6431769c4eac98fd9ae82b893a467ab9d154bc756ce86a39947e6d7c656a3f1c`
  (файл начинается с license-комментария). Внешний CDN в runtime не нужен.

Навигация: Обзор `/`, Библиотека `/library`, Добавить материал `/add`, Задачи
обработки `/jobs`, Поиск видео `/discover`; свёрнутые группы «Инструменты
разработчика» и «Система». Support Assistant намеренно отсутствует в меню,
карточке и редакторе, но прямой `/support` продолжает работать.

Главная `/` — рабочая панель: основное действие «Добавить материал», быстрый
ввод ссылки (передаётся на `/add` без дублирования логики скачивания),
«Сейчас обрабатывается», «Требует внимания» и последние материалы. Формы
загрузки файла и ссылки находятся на `/add`. Библиотека `/library` использует
серверную фильтрацию, сортировку и пагинацию (12 материалов на страницу);
параметры `q`, `language`, `status`, `hasTranslation`, `sort`, `page`
сохраняются в ссылках переключения страниц.

Единый компонент статусов — `templates/fragments/status-badge.html`: показывает
человеческую подпись и сохраняет исходный статус в `data-status`.

Согласованность отмены: терминальный job никогда не показывает выполняющийся
этап. `JobStatusService` вычисляет эффективное состояние из `lecture` +
`pipeline_job`, а исходные значения сохраняет в `data-status`,
`data-job-status`, `data-lecture-status` и `StageView.rawState`. При отмене
активный `stage_checkpoint` переводится в `CANCELLED`, при рестарте зависшие
`RUNNING` — в `INTERRUPTED`. Исторические противоречивые записи не
переписываются: UI показывает «Отменено»/«Прервано» и сохраняет сырое
значение в «Деталях».

Проверки интерфейса:

```bash
export JAVA_HOME=/c/tools/jdk-25.0.2-full
./mvnw verify

# изолированный storage root, чтобы не трогать пользовательские данные
LECTURE_STORAGE_ROOT='C:/Projects/lecture-processor/target/ux2-e2e' ASSISTANT_AUTO_INDEX=false CODE_REVIEW_ENABLED=false FILE_AGENT_ENABLED=false SERVER_PORT=18095 ./mvnw -pl backend spring-boot:run

python scripts/smoke-ui.py --base-url http://127.0.0.1:18095
cd e2e && LP_BASE_URL=http://127.0.0.1:18095 npx playwright test
```

Для заполненной библиотеки задайте `LP_E2E_LECTURE=<id>`, чтобы Playwright
дополнительно проверил карточку и редактор.

## Тесты и проверки

```bash
export JAVA_HOME=/c/tools/jdk-25.0.2-full
./mvnw -pl backend test        # unit + интеграционные тесты пайплайна
./mvnw verify                  # полная сборка обоих модулей (см. ниже актуальное число)
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
Week 8: `demos/multilingual-video-discovery/demo-scenario.md`,
отчёт с реальными E2E — `docs/multilingual-video-discovery-report.md`.
