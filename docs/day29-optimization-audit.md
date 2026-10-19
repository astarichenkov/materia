# Day 29 — аудит реализации «Оптимизация локальной LLM»

Дата: 2026-10-11. Ветка аудита: `fix/day29-llm-optimization-ui`.
База: `week6-day29-verified` (тег остаётся нетронутым).

Цель аудита — зафиксировать, что уже есть в Day 29, что не соответствует
заданию «оптимизация локальной LLM под конкретную задачу» и что нужно
доработать для полноценной Benchmark / Optimization Console.

---

## 1. Что уже реализовано

| Область | Артефакт | Состояние |
|---|---|---|
| Конфигурации бенчмарка | `BenchmarkConfig`, `BenchmarkConfiguration`, `benchmark-config.json` (A–D) | Есть, статический JSON |
| Прогон бенчмарка | `BenchmarkRunner`, `BenchmarkService` | Реально вызывает LLM, пишет JSON/CSV/MD |
| Метрики | время, оценка токенов (~4 симв./токен), токены/с, peak RAM JVM, key-fact hits | Частично |
| UI | `/benchmarks` (`benchmarks.html`) | Только таблица конфигураций, запуск через `curl` |
| API | `GET /api/benchmarks/config`, `POST /api/benchmarks/run?lectureId=` | Есть, но без параметров |
| LLM-абстракция | `LlmProvider`, `OllamaLlmProvider`, `LlmOptions` | Есть, но без выбора модели/провайдера и без thinking per-request |
| Prompt templates | `PromptTemplates` + `resources/prompts/*.txt` (`basic-summary-v1`, `clean-transcript-v1/v2`, `structured-notes-v1/v2`, `summary-v1`, `rag-answer-v1`) | Versioned, но нет каталога/выбора в UI |
| Реальные замеры Day 29 | `demos/day29/benchmark-results/`, `scripts/dev/llm-benchmark.py` | Реальные цифры получены Python-скриптом вне приложения |
| Slide extraction | `SlideExtractionService`, `PerceptualHash` | Реализовано и verified, трогать не нужно |
| RAG Day 28, deploy Day 30 | — | Реализовано, не трогать |

## 2. Что не соответствует заданию

1. **Нет единой модели конфигурации LLM.** Параметры размазаны: `BenchmarkConfiguration`
   (benchmark), `LlmOptions` (рантайм), `AppProperties.Ollama` (дефолт).
   UI и benchmark не используют одну модель настроек.
2. **Нет выбора модели из UI.** Список моделей захардкожен в `benchmark-config.json`.
   Нет discovery через `/api/tags`.
3. **Нет выбора провайдера.** Только Ollama, нет архитектурной точки расширения
   под cloud.
4. **Нет управления `temperature` / `maxTokens` / `contextWindow` / `thinking`
   из UI.** В UI этих полей нет вообще.
5. **Thinking нельзя включить/выключить per-request.** `disableThinking` — глобальный
   флаг в `AppProperties`, зашит в конструктор `OllamaChatModel`.
6. **Нет выбора prompt template в UI** и нет каталога prompt-ов.
7. **Нет выбора lecture/test input в UI.** Запуск — только `curl` с `lectureId`.
8. **Нет реального token usage.** Токены оцениваются по длине текста (~4 симв./токен),
   хотя Ollama отдаёт точные `prompt_eval_count` / `eval_count` / `eval_duration`.
9. **Нет thinking-токенов.**
10. **Нет метрики полного времени конвертации** и пофазных таймингов (FFmpeg/Whisper/
    LLM/Export). `Lecture` хранит только `durationMs`.
11. **Нет таблицы результатов в БД.** Результаты — только файлы в
    `<storage>/benchmarks/<timestamp>/`.
12. **Нет сравнения запусков** и расчёта speedup.
13. **Нет presets** (baseline/optimized).
14. **Нет recommended config** и `/settings`.
15. **Нет quality rubric** с оценкой 0–5 (есть только key-fact hits и «outputValid»).
16. **Нет VRAM/CPU-GPU split метрик** (есть заглушка `vramNote = unknown`).
17. **Прогон синхронный в HTTP-потоке** (`POST /run` блокирует запрос на минуты).
18. **Нет quantization metadata** в результате (захардкожено в JSON).

## 3. Что можно переиспользовать

- `LlmProvider` / `OllamaLlmProvider` — расширить, не ломая бизнес-логику.
- `PromptTemplates` + ресурсы `prompts/*.txt` — добавить каталог.
- `TextChunker` / `SegmentChunk` — переиспользовать для chunking в benchmark.
- `ThreadPoolTaskExecutor` (`AsyncConfig`) — переиспользовать для асинхронного
  benchmark-джоба (можно выделить отдельный executor).
- `BenchmarkRunner` — сохранить, переиспользовать идею замера и добавить provider.
- `JdbcTemplate`-репозитории и `schema.sql` — добавить таблицы.
- Существующий `@ConfigurationProperties AppProperties` — расширить.
- Реальные значения Day 29 (`qwen3:1.7b`, think=false, numCtx=4096,
  numPredict=1536, temp=0.1, prompt `structured-notes-v1`) — как Optimized preset.

## 4. Требуемые изменения (объём)

| # | Изменение | Приоритет |
|---|---|---|
| C1 | `LlmRunConfig` — единая модель настроек | Обязательно |
| C2 | `LlmModelInfo` + discovery моделей через `/api/tags` | Обязательно |
| C3 | Provider abstraction: `id()`, `listModels()`, cloud placeholder | Обязательно |
| C4 | Per-request `thinking` в `LlmOptions` | Обязательно |
| C5 | `PromptCatalog` — выбор prompt в UI | Обязательно |
| C6 | Presets Baseline/Optimized/Custom | Обязательно |
| C7 | Таблица `benchmark_run` + репозиторий | Обязательно |
| C8 | Асинхронный запуск benchmark из UI (QUEUED→RUNNING→COMPLETED/FAILED) | Обязательно |
| C9 | Точные токены/тайминги через `OllamaApi.ChatResponse` | Обязательно |
| C10 | Quality rubric 0–5 (deterministic + manual) | Обязательно |
| C11 | Сравнение двух запусков + speedup | Обязательно |
| C12 | Recommended config (`app_setting`) и его использование новыми jobs | Обязательно |
| C13 | Тайминги полного пайплайна (LLM + total) | Обязательно (LLM+total), пофазно — по возможности |
| C14 | RAM/VRAM через `nvidia-smi`, `N/A` если недоступно | Желательно |
| C15 | Quantization из metadata Ollama | Желательно |
| C16 | Реконструкция UI `/benchmarks` + `/settings` | Обязательно |
| C17 | API: models/prompts/presets/runs/compare/recommend | Обязательно |
| C18 | Тесты | Обязательно |
| C19 | Обновить demo + отчёт | Обязательно |

## 5. Решения по дизайну

- **Не переписывать** Day 27–30: `LlmProvider.chat()` сохраняется, новые
  возможности добавляются как `default`-методы и новые бины.
- **Backward compatibility** `POST /api/benchmarks/run` сохраняется: старый
  контракт продолжает работать, новый API — рядом (`/api/benchmarks`).
- **Одна модель настроек** `LlmRunConfig` используется и UI, и benchmark, и
  (при сохранении recommended) новыми lecture jobs.
- **Провайдеры**: `LlmProviderRegistry` собирает бины `LlmProvider`; cloud
  провайдер описан конфигурацией `app.llm.providers.openai.enabled=false` и в UI
  показывается как *unavailable* без реализации.
- **Токены**: `OllamaLlmProvider` реализует детальный `generate()` через
  `OllamaApi.chat(...)`, получая точные `promptEvalCount`, `evalCount`,
  `evalDuration`, `totalDuration` и `message.thinking`.
- **Персистентность**: SQLite-таблицы `benchmark_run` и `app_setting`.
- **Timing полного пайплайна**: добавляется легковесная таблица/поля
  (`lecture_stage_timing`) для LLM/total, чтобы UI показывал «LLM stage vs Full
  conversion».
- **Quality**: детерминированные проверки (ключевые слова, числа, отсутствие
  запрещённых галлюцинаций, структура, непустота) дают 0–5; manual score — опция.

## 6. Границы (out of scope)

- Реализация cloud provider (OpenAI/OpenRouter) — только точка расширения.
- OCR-выравнивание слайдов и сложный CSS.
- Дополнительное квантование/скачивание больших моделей: используем фактические
  метаданные уже установленных моделей.

## 7. Критерий готовности

Все пункты §4 «Обязательно» реализованы, `./mvnw verify` зелёный (96+ существующих
тестов), выполнены реальные baseline (`qwen3:4b`) и optimized (`qwen3:1.7b`)
прогоны с одинаковым транскриптом, в UI видны время LLM, total, токены, tok/s,
качество, speedup, quantization и recommended config.
