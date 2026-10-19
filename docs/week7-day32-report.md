# Week 7 / Day 32 — Automated AI Code Review for Gitea

Дата: 2026-10-17 · Ветка: `feature/week7-day32-code-review`
Демо-PR: http://203.0.113.20:8108/example-org/materia/pulls/1

## 1. Требования задания и статус

| Требование | Статус |
|-----------|--------|
| Pipeline запускается на PR (opened/reopened/synchronize) | ✅ webhook + polling fallback |
| Получение diff и изменённых файлов | ✅ Gitea REST |
| RAG по документации | ✅ `doc_chunk` (Day 31) |
| RAG по исходному коду | ✅ `code_chunk`, commit-versioned, hybrid |
| Анализ изменений через LLM | ✅ `CodeReviewAnalyzer` |
| Баги / архитектура / рекомендации | ✅ категории BUG/ARCHITECTURE/SECURITY/RELIABILITY/TEST_COVERAGE |
| Review публикуется в Gitea | ✅ PR comment (идемпотентно) |
| Structured output + валидация | ✅ JSON schema, anchors, dedupe |
| Provider из `/settings → Code Review` | ✅ `LlmTaskType.CODE_REVIEW` |
| DeepSeek и Ollama | ✅ оба провайдера |
| Job persistence + recovery | ✅ `code_review_job` / `code_review_finding` |
| UI `/code-review` + manual mode | ✅ дашборд + dry-run |
| Webhook authentication | ✅ HMAC-SHA256 |
| Idempotency / no duplicate comments | ✅ ключ + маркер комментария |
| Новый commit → новый review; stale suppression | ✅ проверено |
| Prompt-injection defense | ✅ trust boundary в system prompt |
| Тесты + `mvn verify` | ✅ 256 backend-тестов |
| Реальный E2E | ✅ PR #1, два commit SHA |

## 2. Архитектура

```
Gitea PR ──webhook (HMAC)──┐
          polling fallback ┤
                           ▼
                  CodeReviewService (queue, idempotency, stages, metrics)
                           │
    GiteaClient ───────────┤ fetch PR / files / diff / content
                           │
    CodeReviewContextBuilder
       ├── changed file contents @head SHA (Gitea)
       ├── CodeIndexService (tree+blobs → JavaCodeChunker → EmbГ, code_chunk)
       ├── CodeRetrievalService (vector + lexical hybrid)
       └── DocumentationRetrievalService (doc_chunk)
                           │
    CodeReviewAnalyzer ─── LlmTaskResolver.resolve(CODE_REVIEW) → DeepSeek/Ollama
                           │ (ReviewPromptBuilder + ReviewResultParser + ReviewValidator)
                           ▼
    GiteaReviewPublisher (stale check, duplicate marker, Markdown escaping)
                           ▼
                 PR comment + code_review_job/finding metrics
```

## 3. Классы

- `gitea/`: `GiteaClient`, `GiteaApiException`, `GiteaPullRequest`,
  `GiteaChangedFile`, `GiteaComment`, `GiteaTreeEntry`.
- `codereview/diff/`: `UnifiedDiffParser`, `DiffFile`, `DiffHunk`, `DiffLine`.
- `codereview/code/`: `CodeChunk`, `CodeSection`, `JavaCodeChunker`, `PathGlob`,
  `CodeIndexService`, `CodeIndexResult`, `CodeRetrievalService`, `CodeSearchResult`.
- `codereview/`: `CodeReviewService`, `CodeReviewAnalyzer`, `CodeReviewContextBuilder`,
  `ReviewPromptBuilder`, `ReviewResultParser`, `ReviewValidator`, `ReviewFinding`,
  `ReviewResult`, `ReviewCategory`, `ReviewSeverity`, `ReviewStatus`,
  `CodeReviewJob`, `CodeReviewJobRepository`, `CodeReviewFindingRepository`,
  `GiteaReviewPublisher`, `PublishedReview`, `GiteaPullRequestPoller`.
- `codereview/web/`: `CodeReviewApiController`, `CodeReviewWebController`,
  `CodeReviewWebhookService`, `GiteaWebhookVerifier`, `WebhookGuards`.
- `repository/`: `CodeChunkRepository`.

## 4. Новые API

- `POST /api/code-review/webhook/gitea` — HMAC-валидация, async enqueue.
- `GET /api/code-review`, `GET /api/code-review/{id}`, `GET /api/code-review/status`.
- `POST /api/code-review` — manual (`repository`, `prNumber`, `force`, `dryRun`).
- `POST /api/code-review/{id}/retry`, `PUT /api/providers/tasks/code-review`.
- UI: `GET /code-review`, `GET /code-review/{id}`, `POST /code-review/manual`,
  `POST /code-review/{id}/retry`.

## 5. Новые таблицы (миграция V5)

- `code_review_job` — статус, provider/model, tokens, cost, timings, idempotency key.
- `code_review_finding` — категория, severity, файл, строка, confidence.
- `code_chunk` — source RAG по `(repository, commit_sha)`.
- `code_review_cursor` — резерв для polling.

## 6. Gitea integration

- Токен только в заголовке `Authorization: token ...`, никогда в URL.
- Allow-list репозиториев; запрос вне списка → 403 (SSRF/proxy protection).
- Webhook: `X-Gitea-Signature` (HMAC-SHA256), `X-Gitea-Event=pull_request`,
  поддержка `opened/reopened/synchronize/edited/ready_for_review`, дедупликация
  доставок, rate-limit, лимит размера payload.
- Публикация: один общего вида комментарий; stale check перед публикацией;
  маркер `<!-- ai-code-review:repo#pr@sha -->` предотвращает дубли; force
  перепубликация — только явным действием; человеческие комментарии не
  трогаются; auto-merge/approve отсутствуют.
- Сетевая топология: Gitea (homelab) не может достучаться до Windows (фаервол);
  поэтому реализован polling fallback — он и выполнил E2E-триггер.

## 7. RAG (docs + source code)

- Документация: Day 31 `doc_chunk` (README + docs).
- Код: индекс строится из Gitea по commit SHA (tree + blobs), никогда из локальной
  рабочей директории; исключаются `.env`, ключи, `target/`, `data/`, бинарь.
- Chunking: Java по классам/методам (`JavaCodeChunker`), прочее — окна строк.
- Retrieval: гибрид vector cosine + lexical (path/symbol/text), exact symbol search.
- В результате ищется соответствие head SHA; в комментарии указан commit.

## 8. LLM

- Провайдер/модель — только из `LlmTaskSettingsService.resolve(CODE_REVIEW)`.
- Structured JSON, один repair-вызов при невалидном JSON, bounded retry.
- Промпт-инъекции: system prompt — доверенный; diff/code/docs/PR — untrusted,
  обёрнуты в теги; модель не выполняет инструкции из репозитория.
- Валидация: категории/severity, файл из diff, line anchors, dedupe,
  confidence ≥ 0.3, лимиты текста и количества findings.
- Метрики: tokens, cache hit/miss, duration, estimated cost (настраиваемый тариф).

## 9. Тесты

- Добавлено 55 тестов Day 32 (diff parser, code chunker/glob, JSON parser,
  validator, Gitea client, webhook, publisher, orchestrator E2E с SQLite).
- Полный backend: **256 тестов, 0 failures, 0 errors**.
- `./mvnw verify` — BUILD SUCCESS (backend + private-ai-chat).
- Платные DeepSeek-вызовы в автотестах не выполняются (test double / fake LLM).

## 10. Реальный E2E

- PR #1 (`demo/day32-ai-review` → `feature/week7-day32-code-review`).
- Job #1: SHA `4aaf4b8c`, provider `deepseek/deepseek-flash`, 3 findings,
  tokens 3331/730, длительность 27.4 c, опубликован comment id 2. Найдены оба
  заложенных дефекта (NPE, off-by-one) + отсутствие тестов.
- Job #2 (новый commit): SHA `a1b6f2c2`, cache hit 384, 3 findings,
  comment id 4. Дефект границы распознан как исправленный, оставшийся NPE
  по-прежнему найден.
- Повторный manual-запрос для того же SHA вернул существующий job (idempotency),
  число jobs не увеличилось.
- Ollama dry-run: `ollama/qwen3:1.7b`, 2 findings, tokens 3263/396 — локальный
  провайдер также работает.
- Webhook в Gitea создан (id 1); фактический триггер выполнен polling fallback
  (Gitea→Windows inbound недоступен). Polling-интервал 20 c.

## 11. Известные ограничения

- Inline review comments не публикуются (только общий комментарий); API inline
  доступен как расширение.
- Webhook Gitea→Windows требует открытого порта/правила фаервола; по умолчанию
  используется polling.
- Code RAG индексирует до `maxCodeIndexFiles`/`maxCodeIndexChunks`; при
  превышении ревью помечается `partial`.
- Оценка стоимости — estimate по настраиваемому тарифу, не счёт провайдера.
- Демо-класс `DemoPricingCalculator` содержит намеренные дефекты и в основную
  ветку не вливается.

## 12. Commit hashes

- `84cb97d` feat: integrate Gitea PR API client…
- `8249803` feat: implement source code RAG…
- `19e310d` feat: add AI code review orchestrator…
- `eed68b7` feat: add PR webhook trigger, polling fallback and dashboard
- `e0251dc` fix: track unified diff parser sources and nullable manual flags
- demo branch: `4aaf4b8` (defects), `a1b6f2c` (boundary fix)

## 13. Ссылка на демо-PR

http://203.0.113.20:8108/example-org/materia/pulls/1
