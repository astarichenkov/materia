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

## Тесты и проверки

```bash
export JAVA_HOME=/c/tools/jdk-25.0.2-full
./mvnw -pl backend test        # unit + интеграционные тесты пайплайна
./mvnw verify                  # полная сборка обоих модулей
```

Живые smoke-тесты yt-dlp (YouTube/VK/Rutube) требуют сети и не выполняются
автоматически в CI. См. `docs/week7-ingestion-final-report.md`.

Демонстрационные сценарии и результаты по дням — в `demos/`.
