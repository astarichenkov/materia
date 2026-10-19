# Week 7 / Day 33 — AI Support Assistant (RAG + MCP + Ticket Context)

## 1. Требования задания и статус

| Требование | Статус |
| --- | --- |
| Мини-сервис поддержки | ✅ `SupportAssistantService` + `/support` UI + `/api/support` |
| Вопросы о продукте | ✅ Documentation RAG (`doc_chunk`) |
| RAG по FAQ/документации | ✅ `DocumentationRetrievalService`, citations |
| Контекст пользователя/тикета | ✅ `support_ticket` / `support_message`, scope/owner |
| MCP CRM / JSON пользователей и тикетов | ✅ support MCP tools, реальное чтение SQLite |
| Видео и код | ✅ `demos/day33/demo-scenario.md`, код в Gitea |

## 2. Архитектура

```
/support UI  +  /api/support
        |
        v
SupportAssistantService
   ├─ SupportIntentRouter      GENERAL_FAQ | JOB_DIAGNOSTICS | TICKET_CONTEXT | MIXED
   ├─ SupportContextBuilder    only allow-listed tools for the intent
   │     ├─ SupportToolExecutor (allow-list) → McpToolRegistry
   │     └─ DocumentationRetrievalService (docs RAG)
   ├─ SupportEvidenceAggregator facts vs docs, citations, tool trace
   ├─ SupportPromptBuilder      trusted system prompt + untrusted evidence blocks
   ├─ SupportAnswerValidator    JSON envelope, bounds, redaction, grounded
   └─ SupportDeterministicAnswerer (LLM-free status lookup)
        |
        v
LlmTaskResolver(SUPPORT_ASSISTANT) → DeepSeek | Ollama
        |
        v
persist ticket/messages/tool calls + metrics
```

## 3. Классы

- Persistence: `SupportTicket`, `SupportMessage`, `SupportToolCall`,
  `SupportTicketRepository`, `SupportMessageRepository`, `SupportToolCallRepository`.
- Service: `SupportTicketService`, `SupportMetricsService`, `SupportAccessService`,
  `SupportScopeGuard`, `SupportAccessContext`, `SupportPrincipal`.
- Orchestrator: `SupportAssistantService`, `SupportIntentRouter`,
  `SupportContextBuilder`, `SupportEvidenceAggregator`, `SupportPromptBuilder`,
  `SupportAnswerValidator`, `SupportDeterministicAnswerer`, `SupportToolExecutor`.
- MCP tools: `GetTicketTool`, `ListTicketMessagesTool`, `GetJobDiagnosticsTool`,
  `GetModelHealthTool`, `SearchSupportTicketsTool`; reused `get_job_status`,
  `get_stage_runs`, `search_help_docs`.
- Web: `SupportApiController`, `SupportWebController`, `SupportExceptionHandler`.
- Config: `SupportProperties` (`app.support`), `LlmTaskType.SUPPORT_ASSISTANT`,
  prompt `support-assistant-v1`.

## 4. SQLite и миграции

- `V6__day33_support_assistant.sql` (additive) + те же таблицы идемпотентно в
  `db/schema.sql`: `support_ticket`, `support_message`, `support_tool_call`.
- `SchemaMigrator` дополнен версией 6 (V2..V6).
- `SchemaMigratorTest` проверяет миграцию уже заполненной legacy-БД без потери
  данных и наличие новых таблиц.

## 5. MCP

Новые read-only инструменты: `get_ticket`, `list_ticket_messages`,
`get_job_diagnostics`, `get_model_health`, `search_support_tickets`.
Профиль поддержки — `app.support.allowed-tools`; git/filesystem/shell недоступны.
Все результаты ограничены по размеру и проходят `SecretRedactor`.

## 6. RAG

`doc_chunk` (README + docs), локальные embeddings `nomic-embed-text`, citations
`[путь#sequence]`. Если индекс пуст — ассистент сообщает об этом.

## 7. LLM

Отдельный task `SUPPORT_ASSISTANT` (`/settings → Support Assistant`), по
умолчанию Ollama, при настроенном DeepSeek может быть выбран cloud. Изменение
задачи не влияет на `LECTURE_PROCESSING`, `DEVELOPER_ASSISTANT`, `CODE_REVIEW`.
Сохраняются provider/model/tokens/стоимость (estimate).

## 8. UI / API

- `GET /support`, `GET /support/tickets/{id}`, `GET /support/lecture/{id}`,
  `POST /support/ask`, `POST /support/tickets`, `POST /support/tickets/{id}/close|/resolve`,
  `POST /support/messages/{id}/feedback`.
- `POST /api/support/ask`, `GET|POST /api/support/tickets`,
  `GET /api/support/tickets/{id}[/messages|/trace]`,
  `POST /api/support/tickets/{id}/status`, `POST /api/support/messages/{id}/feedback`,
  `GET /api/support/lecture/{id}/ticket`, `GET /api/support/metrics`,
  `GET /api/support/tools`, `GET /api/support/status`.

## 9. Тесты

- `SupportTicketServiceTest` (9): CRUD, история, статусы, изоляция scope/owner,
  конкурентные записи, поиск.
- `SupportToolsTest` (7): get_ticket/messages/diagnostics, missing job, redaction,
  payload limits, scope isolation.
- `SupportAssistantServiceTest` (11): FAQ RAG, diagnostics, mixed, multi-turn,
  insufficient evidence, deterministic fallback, provider unavailable,
  prompt injection, cloud routing, MCP failure, лимит вопроса.
- `SupportToolExecutorTest` (4), `SupportIntentRouterTest` (5),
  `SupportAnswerValidatorTest` (5), `GetModelHealthToolTest` (1).
- Web: `SupportApiControllerTest` (8), `SupportWebControllerTest` (6, включая XSS).
- Полный прогон: `./mvnw -pl backend test` — **312 тестов, 0 failures/errors**.

## 10. Реальный E2E (проверено)

Запуск: `SERVER_PORT=18082 LECTURE_STORAGE_ROOT=.../e2e-day33 OLLAMA_CHAT_MODEL=qwen3:1.7b`.
Фикстура: лекция `e2e-day33-failed` (TRANSCRIPTION COMPLETED, TEXT_PROCESSING
FAILED, `api_key=should-not-leak` в error).

- Scenario 1 FAQ: 6 citations `[DOC]`, tools `search_help_docs ✓`, grounded true.
- Scenario 2: `get_job_status/get_stage_runs/get_job_diagnostics ✓`,
  ответ назвал `TEXT_PROCESSING`; секрет замаскирован `api_key=[REDACTED]`.
- Scenario 3: продолжение тикета, ответ про checkpoint Whisper, tools +ticket.
- Scenario 4: DeepSeek credential не настроен (`configured=false`), платных
  запросов не делалось; routing `support-assistant → ollama/qwen3:1.7b`
  подтверждён через `/api/providers/tasks`.
- Scenario 5: `grounded=false`, limitations перечисляют отсутствие диагностики.
- MCP: `tools/list` содержит support-инструменты; raw `tools/call
  get_job_diagnostics` вернул тот же JSON.
- CSRF: POST `/api/support/ask` без токена → `403`.
- Feedback: `👍` → `helped=1`; metrics агрегируются.
- Проверено также `GET /support` (200) и `GET /support/lecture/{id}` (302).

## 11. Ограничения

- Нет полноценной пользовательской auth: по умолчанию модель локального
  доверенного install (`scope=local-install`, owner cookie); multi-user — через
  `app.support.isolation=session` и будущую авторизацию.
- DeepSeek-сравнение в E2E не выполнялось (нет отдельного ключа/разрешения);
  routing и cost-путь покрыты unit-тестами.
- Support-инструменты видны в общем `/mcp` `tools/list`; ограничение доступа —
  на уровне `SupportToolExecutor` (ассистент) и scope (тикеты).
- Локальная документация (`docs/`, `AGENTS.md`) не публикуется (`.gitignore`).

## 12. Git

Ветка `feature/week7-day33-support-assistant`.
Коммиты: persistence, orchestrator+routing, dashboard+UI+tests.
