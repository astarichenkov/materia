# Day 30 — Private AI chat service

Отдельный минимальный сервис приватного локального чата. Не зависит от
конвейера обработки видео (без Whisper/FFmpeg/DOCX/RAG).

```
Browser / Remote Client → Spring Boot API → Auth + Rate limit → ChatService → Ollama → Qwen3 1.7B (CPU)
```

## Что реализовано

- Отдельный Maven-модуль `private-ai-chat` (Java 25, Spring Boot 4.1.1).
- Веб-чат на Thymeleaf/HTMX (`/`) и HTTP API (`/api/health`, `/api/chat`).
- История диалога на сессию с ограничением (`ChatMemoryStore`).
- Простая аутентификация по API-ключу из `CHAT_API_KEY` (`X-API-Key` или
  `Authorization: Bearer`).
- Rate limit 5 запросов/мин на клиента → HTTP 429.
- Лимит сообщения 2000 символов → HTTP 400.
- Контроль контекста и max output tokens (независимо от лимита символов).
- Один активный воркер генерации + ограниченная очередь → 429 при перегрузке.
- Таймаут генерации; понятные ошибки без stack trace.
- Структурированное логирование.

## Конфигурация (env)

| Переменная | По умолчанию | Назначение |
|-----------|--------------|-----------|
| `CHAT_PORT` | 8081 | порт (в проде 8080) |
| `CHAT_BIND_ADDRESS` | 127.0.0.1 | интерфейс (в проде 0.0.0.0 — только доверенная сеть) |
| `OLLAMA_BASE_URL` | http://localhost:11434 | локальная Ollama |
| `CHAT_MODEL` | qwen3:1.7b | модель |
| `CHAT_TIMEOUT` | 2m | таймаут генерации |
| `CHAT_THINK` | false | reasoning Qwen3 (на CPU выключать) |
| `CHAT_API_KEY` | (пусто) | API-ключ; пусто = без аутентификации |
| `CHAT_TRUST_FORWARDED_FOR` | false | доверять `X-Forwarded-For` только за trusted proxy |

## Реальный деплой

Day 30 реально развёрнут на LXC `private-ai` (`203.0.113.10`): CPU-инференс
`qwen3:1.7b`, systemd-сервисы, LAN-доступ только на `:8080`, Ollama — loopback.
Отчёт: `docs/DAY30_REAL_DEPLOYMENT_REPORT.md`.
