# Day 33 — демонстрация AI Support Assistant (3–5 минут)

Цель: показать работающего support-ассистента, который отвечает по документации
(RAG), диагностирует реальную ошибку обработки лекции (MCP), ведёт диалог по
обращению и честно сообщает, когда данных не хватает.

## Подготовка (до записи)

```bash
export JAVA_HOME=/c/tools/jdk-25.0.2-full
export PROJECT_ROOT='C:/Projects/lecture-processor'
export LECTURE_STORAGE_ROOT='C:/Projects/lecture-processor/backend/target/e2e-day33'
export OLLAMA_CHAT_MODEL=qwen3:1.7b OLLAMA_DISABLE_THINKING=true SERVER_PORT=18082
./mvnw -pl backend spring-boot:run
```

При старте документация индексируется в `doc_chunk` (README + docs). Если индекс
пуст — `POST /api/assistant/index`. Для сценария диагностики в БД подготовлена
лекция `e2e-day33-failed` (TRANSCRIPTION COMPLETED, TEXT_PROCESSING FAILED).

Контрольный прогон: `PYTHONIOENCODING=utf-8 python scripts/dev/day33-support-e2e.py http://127.0.0.1:18082`.

## Шаги

| # | Действие | Ожидаемый результат |
|---|----------|---------------------|
| 1 | Открыть `http://localhost:18082/support` | Страница «Поддержка Lecture Processor»: метрики, создание обращения, список, чат, список read-only инструментов |
| 2 | Задать FAQ-вопрос: «Как подключить DeepSeek и выбрать его для обработки лекций?» | Ответ по документации; в блоке «Источники» — `[DOC] README.md#16/18/20`, `docs/deepseek-integration-report.md#1`; tool trace: `search_help_docs ✓`; `grounded: true` |
| 3 | Показать RAG | Citations ведут на реальные файлы проекта; ответ не выдумывает несуществующие endpoints |
| 4 | На странице лекции нажать «Помощь по этой лекции» (`/support/lecture/e2e-day33-failed`) | Редирект на обращение, связанное с лекцией; job id не вводится вручную |
| 5 | Спросить: «Почему обработка этой лекции остановилась?» | Ответ: `TEXT_PROCESSING` FAILED; tool trace: `get_job_status ✓`, `get_stage_runs ✓`, `get_job_diagnostics ✓`; citations `[JOB]`; секреты в ошибке замаскированы (`api_key=[REDACTED]`) |
| 6 | Показать MCP | В `support_tool_call` сохранён trace; внешний `POST /mcp` с `tools/call get_job_diagnostics` возвращает тот же JSON |
| 7 | Продолжить диалог: «А Whisper придётся запускать заново?» | Ответ использует предыдущий контекст (тикет + состояние checkpoint): распознавание уже завершено, повторять не нужно; tool trace добавляет `get_ticket ✓`, `list_ticket_messages ✓` |
| 8 | Показать использованную LLM | В сообщении и метриках: `ollama / qwen3:1.7b`; `SUPPORT_ASSISTANT` настраивается отдельно в `/settings → Support Assistant` |
| 9 | Спросить о несуществующей задаче: «Почему задача e2e-nonexistent не обработалась?» | Ответ: «Подтверждённых данных нет…»; `grounded: false`; `limitations` перечисляют отсутствие диагностики |
| 10 | Поставить оценку «👍 Помогло» | Метрики: `helped` увеличился; статус обращения можно перевести в RESOLVED |
| 11 | Показать тесты и архитектуру | `./mvnw verify` зелёный; на слайде — схема orchestrator → intent router → RAG/MCP → LLM → persisted conversation |

## Что важно проговорить

- **RAG**: документация (`README`, `docs`) чанкуется и индексируется локальными
  embeddings (`nomic-embed-text`) в отдельную таблицу `doc_chunk`; вопрос ищет
  ближайшие фрагменты, ответ цитирует источники.
- **MCP**: read-only инструменты дают структурированный JSON о задаче, стадиях,
  ошибках и обращении. Support-профиль ограничен allow-list — git/filesystem/shell
  инструменты ассистенту недоступны.
- **Контекст тикета**: обращение хранит `scope`/`owner`, историю, citations, tool
  trace и метрики; доступ проверяется по scope, а не по id.
- **Решения orchestrator**: intent router детерминированно выбирает
  FAQ / DIAGNOSTICS / TICKET / MIXED и список инструментов; простой status-lookup
  может быть отвечен без LLM; для сложного — один ограниченный LLM-вызов.
- **Честность**: ответ разделяет подтверждённые факты, документацию и
  предположения; при недостатке данных это явно написано, причина не выдумывается.

## Автоматический прогон (без записи)

```bash
python scripts/dev/day33-support-e2e.py http://127.0.0.1:18082
```

Скрипт проверяет сценарии 1, 2, 3, 5 и печатает метрики.
