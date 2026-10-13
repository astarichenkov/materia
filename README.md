# Lecture Processor

Локальное AI-приложение для преобразования образовательных видеолекций
в структурированные текстовые документы с использованием Whisper,
локальной LLM (Ollama + Qwen3) и локального RAG.

Основная цепочка:

    MP4 → FFmpeg → Whisper → Transcript JSON → Local LLM → Structured Notes → Markdown/DOCX

## Модули

| Модуль | Назначение |
|--------|-----------|
| `backend/` | Модульный монолит: загрузка лекций, обработка, транскрипция, LLM, RAG, DOCX, слайды, бенчмарки. |
| `private-ai-chat/` | Day 30: отдельный минимальный сервис приватного чата с локальной LLM (веб-чат + HTTP API). |

## Требования

- Java 25
- Maven (используется Maven Wrapper `./mvnw`)
- FFmpeg (аудио и кадры слайдов)
- whisper.cpp (`whisper-cli` + модель)
- Ollama с моделями `qwen3:4b` (основная) и embedding-моделью
  `nomic-embed-text` (Day 28)

Внешние инструменты не обязательны для запуска модульных/интеграционных
тестов — они подменяются тестовыми реализациями.

## Быстрый старт

```bash
# Сборка и все тесты
./mvnw verify

# Запуск backend (Day 27-29)
./mvnw -pl backend spring-boot:run -Dspring-boot.run.profiles=local

# Запуск private-ai-chat (Day 30)
./mvnw -pl private-ai-chat spring-boot:run
```

Демонстрационные сценарии и результаты по дням — в `demos/`.
