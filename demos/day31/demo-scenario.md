# Day 31 — Ассистент разработчика: сценарий демо

Цель: показать, что ассистент понимает проект через документацию (RAG),
читает контекст через MCP (git-ветка) и отвечает на `/help`.

## 0. Подготовка

```bash
export JAVA_HOME=/c/tools/jdk-25.0.2-full
export PATH="$JAVA_HOME/bin:$PATH"
# Ollama с qwen3:1.7b и nomic-embed-text уже запущена
```

## 1. Проверить ветку (MCP-инструмент вручную)

```bash
git branch --show-current        # feature/week7-day31
```

## 2. Запустить backend

```bash
PROJECT_ROOT='C:/Projects/lecture-processor' \
LECTURE_STORAGE_ROOT='C:/Projects/lecture-processor/backend/target/e2e-day31' \
OLLAMA_CHAT_MODEL=qwen3:1.7b OLLAMA_DISABLE_THINKING=true \
SERVER_PORT=18081 \
./mvnw -pl backend spring-boot:run
```

В логе должно появиться:

```
Documentation RAG auto-indexed: 19 documents, 235 chunks
```

## 3. Статус документации

```bash
curl -s http://127.0.0.1:18081/api/assistant/status
```

Показать `sourceCount`, `chunkCount`, список источников (README + docs).

## 4. Команда `/help` в UI

Открыть `http://127.0.0.1:18081/assistant` и спросить:

> `/help Из чего состоит пайплайн обработки лекции?`

Показать: ответ, блок **MCP-инструменты** (`git_current_branch ✓`,
`git_status ✓`), блок **Источники** с путями и номерами чанков.

## 5. `/help` через API

```bash
curl -X POST -H 'Content-Type: application/json' \
  -d '{"question":"/help Как запустить backend?","sessionId":"demo"}' \
  http://127.0.0.1:18081/api/assistant/help
```

## 6. MCP-контракт (для внешних агентов)

```bash
# каталог сервера
curl -s http://127.0.0.1:18081/mcp

# список инструментов
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
  http://127.0.0.1:18081/mcp

# git branch через MCP
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"git_current_branch","arguments":{}}}' \
  http://127.0.0.1:18081/mcp

# поиск по документации
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_help_docs","arguments":{"query":"resumable pipeline checkpoints","top_k":3}}}' \
  http://127.0.0.1:18081/mcp
```

## 7. Переиндексация документации

```bash
curl -i -X POST http://127.0.0.1:18081/assistant/reindex   # 302
```

## Ожидаемый результат

- README + `docs` проиндексированы в отдельную коллекцию (`doc_chunk`).
- MCP возвращает текущую git-ветку и список инструментов.
- `/help` отвечает по документации и проекту, с цитатами и tool-call трейсом.
