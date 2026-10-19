# Day 29 — LLM Optimization Console (UI + измеряемая оптимизация)

Дата: 2026-10-14. Ветка: `fix/day29-llm-optimization-ui`.
База: `week6-day29-verified` (тег не изменялся).

Цель доработки: превратить Day 29 из статической таблицы конфигураций в
**реальную систему настройки и измеримой оптимизации локальной LLM**:
выбор provider/model/prompt, управление `temperature` / `max tokens` /
`context window` / `thinking`, асинхронный запуск benchmark, история,
сравнение baseline↔optimized, recommended config и `/settings`.

---

## 1. Архитектура и изменения

```
                        ┌─────────────────────────────┐
   /benchmarks (UI) ──▶ │ LectureWebController        │
   /settings   (UI)     └──────────────┬──────────────┘
                                       │
                        ┌──────────────▼──────────────┐
                        │ BenchmarkRunService         │  async (benchmarkExecutor)
                        │  submit() / execute()       │
                        └───┬───────────┬─────────────┘
                            │           │
        LlmProviderRegistry │           │ BenchmarkRunRepository  (SQLite benchmark_run)
        PromptTemplates     │           │ LlmSettingsService      (SQLite app_setting)
        BenchmarkFixtureCatalog         │ QualityEvaluator / ResourceMonitor
        BenchmarkPresetService          │ TimingRepository (lecture_stage_timing)
                            │
             ┌──────────────▼──────────────┐
             │ LlmProvider (interface)     │
             │  ├─ OllamaLlmProvider       │  generate() → точные токены/тайминги
             │  └─ CloudLlmProvider (future)│  задел, cloud не реализован
             └─────────────────────────────┘
```

Ключевые решения:

- **Единая модель настроек** `LlmRunConfig` (provider, model, temperature,
  maxTokens, contextWindow, thinking, promptTemplate). UI, benchmark и новые
  jobs используют её.
- **Provider abstraction**: `LlmProvider` расширен `id()/displayName()/cloud()/
  listModels()/generate()`. `LlmProviderRegistry` отдаёт локальные провайдеры +
  облачные «заглушки» из конфигурации (`app.llm.providers`).
- **Model discovery** через Ollama `/api/tags`: имя, family, parameter size,
  **quantization**, context length, capabilities. Embedding-only модели не
  предлагаются как chat-модели.
- **Точные токены/тайминги** через низкоуровневый `OllamaApi.chat`:
  `prompt_eval_count`, `eval_count`, `eval_duration`, `load_duration`,
  `message.thinking`, `done_reason`.
- **Асинхронный запуск**: `POST /api/benchmarks` → `QUEUED`, отдельный
  `benchmarkExecutor` (1 поток), статусы `RUNNING/COMPLETED/FAILED`.
- **Обратная совместимость**: старые `GET /api/benchmarks/config` и
  `POST /api/benchmarks/run` сохранены без изменений.

## 2. Изменения по файлам

Новое (основное):

- `llm/LlmRunConfig`, `LlmModelInfo`, `LlmResult`, `ProviderDescriptor`,
  `LlmProviderRegistry`, `LlmSettingsService`, `LlmSettingsValidator`,
  `InvalidLlmSettingsException`.
- `benchmark/BenchmarkRun`, `BenchmarkRunRequest`, `BenchmarkRunService`,
  `BenchmarkRunRepository`, `BenchmarkPreset`, `BenchmarkPresetService`,
  `BenchmarkComparison`, `QualitySpec`, `QualityResult`, `QualityEvaluator`,
  `BenchmarkFixtureCatalog`, `ResourceMonitor`.
- `repository/AppSettingRepository`, `repository/TimingRepository`.
- `templates/settings.html`, `templates/fragments/benchmark-history.html`,
  `templates/fragments/benchmark-compare.html`.
- `resources/benchmark/fixtures.json`.

Изменено:

- `llm/LlmProvider` (+`id/displayName/cloud/listModels/generate`),
  `llm/LlmOptions` (+`thinking`), `llm/OllamaLlmProvider` (+discovery, +точные
  метрики, +per-request thinking).
- `text/PromptTemplates` (+каталог), `text/TextProcessingService`
  (+перегрузки с явным prompt).
- `job/LectureProcessingService` (использует recommended settings, пишет
  stage timings).
- `web/BenchmarkController` (+providers/models/prompts/presets/runs/compare/
  recommend), `web/LectureWebController` (+`/benchmarks` данные, +`/settings`).
- `config/AppProperties` (+`llm.providers`), `config/AsyncConfig`
  (+`benchmarkExecutor`), `application.yml`, `db/schema.sql`.

## 3. LLM configuration model

```java
record LlmRunConfig(
    String provider, String model,
    Double temperature, Integer maxTokens, Integer contextWindow,
    Boolean thinking, String promptTemplate) { ... }
```

Валидация (`LlmSettingsValidator`): temperature `0.0–2.0`,
max tokens `64–8192`, context `1024–32768`, context > max tokens,
provider/model/prompt не пустые. Ошибка → `InvalidLlmSettingsException`.

## 4. Provider abstraction и cloud-ready

- Локально: `Ollama (local)` — доступен, модели discovery.
- `OpenAI (не настроен)` — в UI `disabled`, `available=false`, `cloud=true`.
- Конфигурация:
  ```yaml
  app:
    llm:
      providers:
        ollama: { enabled: true,  display-name: "Ollama (local)" }
        openai: { enabled: false, display-name: "OpenAI (не настроен)" }
  ```
- Cloud-клиент не реализован (по заданию), но домен benchmark от него не зависит.

## 5. Settings UI и recommended config

`/settings` — provider, model, temperature, max tokens, context, thinking,
default prompt. Сохраняется в `app_setting` (`llm.default-config`) как JSON.

`LectureProcessingService` читает `LlmSettingsService.current()` и применяет
модель/параметры/thinking/prompt к **новым** jobs; старые jobs не меняются.
Кнопка **«Use as recommended»** у benchmark-запуска сохраняет его конфиг.

## 6. Реальные результаты (без моков)

Машина: Ryzen 7 5800H / RTX 3050 Laptop 4 GB / 32 GB RAM, Windows,
Ollama 0.40.x, Java 25. Модели установлены: `qwen3:1.7b` (Q4_K_M),
`qwen3:4b` (Q4_K_M), `nomic-embed-text` (F16). Один и тот же транскрипт
(лекция по векторной алгебре, 1 chunk, prompt `structured-notes-v1`).

| Run | Model | think | ctx | max tok | LLM ms | Full conversion ms | out tok | tok/s | RAM МБ | VRAM МБ | Quality |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **#10 Baseline** | `qwen3:4b` | on | 8192 | 6144 | **582 023** | **594 289** | 6049 | 10.39 | 2180 | 3000 | 4.5 |
| **#9 Optimized** | `qwen3:1.7b` | off | 4096 | 1536 | **7 832** | **20 098** | 266 | 33.96 | 2178 | 3000 | 4.5 |

Полный пайплайн переобработанной лекции (`lecture_stage_timing`, мс):
FFmpeg 495, Whisper 10 329, LLM 8 171, Export 385, Total 20 437.
Для benchmark «Full conversion» = измеренный non-LLM overhead (12 266 мс) +
LLM-время конкретного запуска.

Результаты сохранены: `demos/day29/benchmark-results/console-runs.json`,
`console-comparison.json`.

## 7. Before/after: сравнение

`GET /api/benchmarks/compare?baseline=10&optimized=9`:

```
LLM stage:        582 023 ms → 7 832 ms      speedup ≈ 74.3x
Full conversion:  594 289 ms → 20 098 ms     speedup ≈ 29.6x
Quality:          4.5 → 4.5                   (equals)
RAM:              2180 → 2178 МБ
VRAM:             3000 → 3000 МБ
Model:            qwen3:4b → qwen3:1.7b (оба Q4_K_M)
```

Качество одинаковое (rubric: completeness=5, facts=5/7, hallucination=ok,
structure=4, grammar=5). Экономия — за счёт меньшей модели, отключения
thinking и меньшего контекста/бюджета, а не за счёт качества.

> Комментарий про ресурсы: `VRAM` — общая занятая память GPU, `RAM` — рабочий
> набор процессов `ollama.exe` + `llama-server.exe`. Значения зависят от того,
> какая модель ещё удерживается Ollama (keep-alive), поэтому дельта RAM/VRAM
> может быть около нуля. Метрики показываются, если доступны, иначе `N/A`
> (ранние запуски #1/#2 до фикса парсинга — `RAM N/A`).

## 8. Quantization

Метаданные берутся из Ollama `/api/tags`:

| Модель | Quantization | Context | Capabilities |
|---|---|---:|---|
| `qwen3:1.7b` | **Q4_K_M** | 40960 | completion, tools, thinking |
| `qwen3:4b` | **Q4_K_M** | 262144 | completion, tools, thinking |
| `nomic-embed-text` | F16 | 2048 | embedding |

Quantization записывается в каждый benchmark-результат и виден в UI.
Дополнительные варианты квантования не скачивались (secondary priority).

## 9. Quality comparison

Детерминированный rubric (`QualityEvaluator`, 0–5, среднее по 5 критериям):
completeness, fact preservation, no hallucination, structure, grammar.
Фикстуры — `resources/benchmark/fixtures.json` (required/forbidden/numbers).
LLM-as-judge не является единственным сигналом; manual score опционален.

Baseline и optimized — оба 4.5/5 на одном фикстуре.

## 10. Benchmark UI и API

`/benchmarks`: форма (lecture, provider, model, preset, temperature,
max tokens, context, thinking, prompt, fixture), кнопка запуска, секция
«Последние результаты» (HTMX-поллинг каждые 5 c), чекбоксы сравнения и
блок сравнения. `/settings` — дефолтная конфигурация.

API:

```
GET  /api/benchmarks/providers
GET  /api/benchmarks/models?provider=ollama
GET  /api/benchmarks/prompts
GET  /api/benchmarks/presets
POST /api/benchmarks                      (async, возвращает run c id, QUEUED)
GET  /api/benchmarks[?lectureId&limit]
GET  /api/benchmarks/{id}
GET  /api/benchmarks/compare?baseline&optimized
POST /api/benchmarks/{id}/recommend
GET  /api/benchmarks/config               (legacy)
POST /api/benchmarks/run                  (legacy)
```

## 11. Presets

| Preset | Model | temp | ctx | max tok | thinking | prompt |
|---|---|---:|---:|---:|---|---|
| Baseline | qwen3:4b | 0.1 | 8192 | 6144 | on | structured-notes-v1 |
| Optimized | qwen3:1.7b | 0.1 | 4096 | 1536 | off | structured-notes-v1 |
| Custom | — (не перезаписывает поля) | | | | | |

## 12. Тесты

`./mvnw verify` — **BUILD SUCCESS**: backend **92** теста, private-ai-chat **28**
(итого 120; существующие 68 backend не сломаны).

Новые тесты:

- `LlmSettingsValidatorTest` — границы temperature/max tokens/context, пустые поля.
- `LlmProviderRegistryTest` — provider selection, cloud placeholder, фильтрация
  embedding-моделей, отклонение неизвестного провайдера.
- `QualityEvaluatorTest` — rubric, missing facts, hallucination, пустой ответ.
- `BenchmarkPresetServiceTest` — presets + каталог prompts.
- `BenchmarkRunServiceTest` — request → execute → persistence, parameter
  propagation, quantization discovery, failure, comparison, recommended config
  (реальная SQLite-схема).

## 13. Совместимость и ограничения

- Day 27/28/30 не изменялись; тег `week6-day29-verified` не перемещался.
- RAM/VRAM зависят от residency моделей (keep-alive Ollama).
- `thinkingTokens` — оценка по длине reasoning-текста (Ollama отдаёт общий
  `eval_count`); для `think=false` = 0.
- Full conversion для benchmark — оценка: non-LLM overhead последнего
  pipeline-прогона + LLM конкретного run.
- Cloud provider не реализован (только UI-заглушка).

## 14. Итог

Day 29 теперь позволяет: выбрать provider/model/prompt, настроить temperature /
max tokens / context / thinking, выбрать лекцию, запустить benchmark из UI,
увидеть LLM-время, полное время конвертации, токены, tok/s, quality, RAM/VRAM,
quantization, сравнить baseline и optimized (speedup **74x** по LLM,
**29.6x** по полной конвертации при равном качестве) и сохранить
recommended config, который используют новые jobs.
