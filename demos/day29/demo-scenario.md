# Demo scenario — Day 29

День 29: **оптимизация локальной LLM под конкретную задачу** через
Benchmark / Optimization Console (`/benchmarks`) и `/settings`.

Ключевой посыл видео:

> Мы не просто сменили модель — мы измерили параметры и выбрали оптимальную
> конфигурацию под конкретное железо.

## Подготовка

1. Запустить Ollama и проверить модели: `ollama ps`, `ollama list`
   (`qwen3:1.7b`, `qwen3:4b`, `nomic-embed-text`).
2. Запустить backend:
   ```bash
   export JAVA_HOME=/c/tools/jdk-25.0.2-full
   OLLAMA_CHAT_MODEL=qwen3:1.7b OLLAMA_DISABLE_THINKING=true \
     LECTURE_STORAGE_ROOT=C:/Projects/lecture-processor/data \
     ./mvnw -pl backend spring-boot:run      # http://localhost:8080
   ```

## Part A — Benchmark Console

1. Открыть `http://localhost:8080/benchmarks`.
2. **Lecture:** выбрать обработанную лекцию (её transcript используется,
   Whisper повторно не запускается).
3. **Provider:** `Ollama (local)`; показать, что `OpenAI (не настроен)` —
   disabled (cloud-ready архитектура).
4. **Preset:** выбрать `Базовый (Baseline)`:
   `qwen3:4b`, temp 0.1, context 8192, max tokens 6144, thinking on,
   `structured-notes-v1`. Форма автоматически заполняет все поля.
5. Нажать **«Запустить benchmark»** (страница не блокируется, статус QUEUED → RUNNING).
6. Показать результат baseline в таблице: LLM-время, полное время конвертации,
   токены, токены/с, RAM/VRAM, quality, quantization.
7. **Preset:** `Optimized` — показать изменённые параметры:
   `qwen3:1.7b`, temp 0.1, context 4096, max tokens 1536, thinking off,
   тот же prompt.
8. Запустить оптимизированный benchmark.
9. Отметить два запуска чекбоксами «Сравнить» → **«Сравнить выбранные»**.
10. Показать сравнение: Speedup (LLM stage), Full conversion speedup,
    quality Δ, RAM/VRAM Δ.
11. Нажать **«Use as recommended»** у оптимизированного запуска.
12. Открыть `/settings` — показать, что рекомендованная конфигурация сохранена
    и будет использоваться новыми jobs.

## Part B — Полный пайплайн и слайды

1. `/settings` содержит рекомендованные параметры; новые лекции обрабатываются с ними.
2. Обработать лекцию и показать тайминги стадий (FFmpeg / Whisper / LLM / Export /
   Total) — они пишутся в `lecture_stage_timing` и подтягиваются в benchmark.
3. Обработать лекцию с флагом «извлечь слайды»; показать
   `data/lectures/<id>/slides/` (только уникальные кадры) и DOCX с картинками.

## Expected result

- На `/benchmarks` есть рабочая форма (provider/model/preset/параметры/prompt),
  кнопка запуска, таблица истории и режим сравнения.
- Benchmark выполняется асинхронно; API возвращает `id`, UI поллит историю.
- Результаты сохраняются в SQLite (`benchmark_run`) и видны в UI.
- Есть рекомендация конфигурации и `/settings`, влияющая на новые jobs.
- Quantization (`Q4_K_M`) и VRAM/RAM показываются, если доступны, иначе `N/A`.

## Local proof

- Все модели запускаются через Ollama на loopback (`127.0.0.1:11434`).
- Реальные прогоны baseline `qwen3:4b` и optimized `qwen3:1.7b` выполнены на
  одном транскрипте; числа в `demos/day29/benchmark-results/console-*.json`.

## Known limitations

- RAM — working set процессов `ollama.exe` + `llama-server.exe`, не всей системы.
- VRAM — общая занятая память GPU (`nvidia-smi`), общая для всех процессов.
- `thinkingTokens` — оценка по длине reasoning-текста (Ollama отдаёт общий `eval_count`).
- Для уже обработанной до этого дня лекции полное время конвертации может быть
  `N/A`, пока лекция не переобработана (тогда появляются stage timings).
