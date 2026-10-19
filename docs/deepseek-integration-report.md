# DeepSeek Cloud LLM Integration — Implementation Report

Дата: 2026‑10‑17 · Ветка: `feature/deepseek-cloud-provider`
Статус: реализовано и покрыто тестами; live smoke‑test не выполнялся (нет
разрешения на платные запросы и отдельного ключа).

## 1. Цель

Добавить облачный DeepSeek V4.1 Flash как полноценный `LlmProvider` в
существующую архитектуру провайдеров, реализовать безопасное per‑installation
хранение API‑ключа, UI управления провайдерами и независимое назначение моделей
по задачам (`Lecture Processing`, `Developer Assistant`, `Code Review`).
Подготовка к Week 7 / Day 32 (AI Code Review).

## 2. Reference implementation

Изучен проект `C:\Projects\llm-agent-lab` (Python/OpenAI SDK) как источник
рабочего контракта DeepSeek:

- endpoint `https://api.deepseek.com`, OpenAI‑совместимый `/chat/completions`;
- авторизация `Authorization: Bearer <key>`;
- параметры: `model`, `messages`, `temperature`, `max_tokens`, `response_format`,
  `stop`, tools, non‑thinking через `extra_body={"thinking":{"type":"disabled"}}`;
- ответ: `choices[0].message.content`, отдельный `reasoning_content`,
  `finish_reason`, `usage.prompt_tokens/completion_tokens/total_tokens` и
  `prompt_cache_hit_tokens`/`prompt_cache_miss_tokens`;
- классификация ошибок: 401/403, 402, 429, 400/422, 5xx, timeout, connection.

Секреты reference‑проекта **не** читались/не копировались. Lecture Processor
использует собственный ключ, вводимый через UI.

## 3. Архитектура

```
UI /settings ─┐
REST /api/providers ─┐
                     ▼
        ProviderSettingsController / ProviderApiController
                     │
        AdminSecurityFilter (loopback | X-Admin-Token | session + CSRF)
                     │
        ProviderCredentialService ── CredentialCipher (AES-256-GCM v1)
                     │                     └── MasterKeyStore (env LP_MASTER_KEY | fallback file)
                     ▼
   LlmProviderRegistry ── OllamaLlmProvider (@Primary, LlmProvider + EmbeddingProvider)
                     │   └── DeepSeekLlmProvider (LlmProvider, cloud)
                     ▼
   LlmTaskSettingsService (per‑task routing + snapshot)
        ├── LECTURE_PROCESSING  → llm.default-config (совместимость)
        ├── DEVELOPER_ASSISTANT → llm.task-config.developer-assistant
        └── CODE_REVIEW         → llm.task-config.code-review
                     ▼
   TextProcessingStage · RagService · LectureChatService · DeveloperAssistantService
   (embeddings всегда через EmbeddingProvider = Ollama)
```

Ключевые решения:

- **Разделение chat/embeddings**: новый интерфейс `EmbeddingProvider`;
  `LlmProvider.embed` удалён. DeepSeek физически не может подменить embeddings.
- **`@Primary` на OllamaLlmProvider**: существующие интеграции и тесты,
  ожидающие единственный `LlmProvider`, продолжают получать локальную модель.
- **Routing через `LlmTaskResolver`**: сервисы зависят от интерфейса, тесты
  используют `FixedTaskResolver`.
- **Fallback** DeepSeek → Ollama по умолчанию выключен и всегда отмечается
  флагом `ResolvedLlmTask.fallback` + причиной.

## 4. Новые/изменённые файлы

Новые (main):

- `llm/DeepSeekLlmProvider.java` — HTTP‑клиент, парсинг, классификация ошибок,
  retries, semaphore параллелизма, `testConnection()`.
- `llm/DeepSeekException.java` — категории ошибок.
- `llm/CloudCostService.java` — estimate по настраиваемому тарифу.
- `llm/EmbeddingProvider.java` — отдельная embedding‑capability.
- `llm/LlmTaskType.java`, `LlmTaskResolver.java`, `ResolvedLlmTask.java`,
  `LlmTaskSettingsService.java` — per‑task routing.
- `llm/credential/MasterKeyStore.java`, `CredentialCipher.java`,
  `ProviderCredentialService.java`, `ProviderCredentialRepository.java`,
  `ProviderCredential.java`, `CredentialException.java`.
- `web/ProviderApiController.java` — REST credential/task API.
- `web/ProviderSettingsController.java` + `templates/settings.html`,
  `templates/settings-login.html` — UI.
- `web/security/AdminAccessService.java`, `AdminSecurityFilter.java`.
- `benchmark/BenchmarkUsageService.java` — агрегат токенов/расходов.
- `db/migration/V4__day32_cloud_provider.sql`.

Изменены (main):

- `config/AppProperties.java` — `DeepSeek`, `Security`, `Llm.embeddingProvider/fallbackEnabled`.
- `llm/LlmResult.java` — cache hit/miss и cloud‑флаг.
- `llm/LlmProviderRegistry` (без изменений API) — DeepSeek появляется как cloud.
- `llm/OllamaLlmProvider.java` — `@Primary`, `EmbeddingProvider`.
- `pipeline/stage/TextProcessingStage.java`, `text/TextProcessingService.java` —
  provider на задачу, snapshot в `JobContext`.
- `rag/RagService.java`, `chat/LectureChatService.java` — routing.
- `assistant/DeveloperAssistantService.java` — routing `/help`.
- `assistant/DocumentationIndexService|RetrievalService|IndexRunner`, `rag/RetrievalService`,
  `rag/EmbeddingIndexService` — `EmbeddingProvider`.
- `benchmark/BenchmarkRunService.java` — cloud runs без локальных HW‑метрик.
- `web/BenchmarkController.java`, `web/LectureWebController.java` — usage/стоимость.
- `application.yml`, `db/schema.sql`, `config/SchemaMigrator.java`.
- `templates/benchmarks.html`, `README.md`.

Тесты: `DeepSeekLlmProviderTest`, `ProviderCredentialServiceTest`,
`LlmTaskSettingsServiceTest`, `AdminAccessServiceTest`,
`ProviderApiControllerTest`, `BenchmarkUsageServiceTest`; обновлены
`TestProperties`, `FakeLlmProvider`, `FixedTaskResolver`,
`PipelineCoordinatorIntegrationTest`, `SchemaMigratorTest`, `RagServiceTest`,
`DeveloperAssistantServiceTest`.

## 5. Endpoints

UI:

- `GET /settings` — провайдеры + назначение моделей.
- `GET /settings/login`, `POST /settings/login`, `POST /settings/logout`.
- `POST /settings/tasks` — сохранить конфигурацию задачи.
- `POST /settings/providers/{id}/key` — сохранить/заменить ключ.
- `POST /settings/providers/{id}/key/delete` — удалить ключ.
- `POST /settings/providers/{id}/test` — проверка соединения.

REST (admin, CSRF/X‑Admin‑Token):

- `GET /api/providers`, `GET /api/providers/{id}/models`,
  `GET /api/providers/pricing`.
- `POST /api/providers/{id}/credential`, `DELETE /api/providers/{id}/credential`,
  `POST /api/providers/{id}/test`.
- `GET /api/providers/tasks`, `PUT /api/providers/tasks/{task}`.
- `GET /api/benchmarks/usage`.

Формат per‑task конфигурации (`LlmRunConfig`):
`{provider, model, temperature, maxTokens, contextWindow, thinking, promptTemplate}`,
хранится как JSON в `app_setting`.

## 6. Хранение credentials

- Таблица `provider_credential(provider_id, ciphertext, key_hint, encryption,
  updated_at, last_success_at, last_error)`.
- AES‑256‑GCM, 12‑байтовый random nonce, тег 128 бит, формат `v1:base64(...)`.
- Мастер‑ключ: env `LP_MASTER_KEY` (base64, 32 байта) — предпочтительно; иначе
  файл `<storage>/.secrets/master.key` (создаётся с правами owner‑only там, где
  поддерживается POSIX). Разные установки имеют разные ключи; ciphertext одной
  установки не расшифровывается ключом другой.
- Ключ никогда не возвращается API/HTML, не логируется, не попадает в benchmark.

## 7. Результаты тестов

- `./mvnw -pl backend test` → **201 тест, 0 failures, 0 errors**.
- `./mvnw verify` (оба модуля) → **BUILD SUCCESS**.
- DeepSeek‑тесты используют локальный HTTP test double, платные вызовы не
  выполняются.
- Live smoke‑test DeepSeek: **не проводился** (нет отдельного ключа/разрешения).

## 8. Ограничения

- Административный UI защищён loopback‑адресом при пустом `LP_ADMIN_TOKEN`;
  это временное ограничение до полноценной системы пользователей.
- Кастомный endpoint DeepSeek запрещён (allow‑list только `api.deepseek.com`;
  loopback — для тестовых заглушек).
- Стоимость — estimate по настраиваемому тарифу, не счёт провайдера; тарифы
  DeepSeek по умолчанию `0.0` (показывается `unknown`).
- Cache hit/miss учитывается в `LlmResult`/расчёте; в агрегате бенчмарков
  доступен split только там, где провайдер его вернул.
- Fallback по умолчанию выключен; для Code Review fallback‑результат нельзя
  публиковать как полноценное ревью без отдельного разрешения.

## 9. Реальная проверка (что проверено на этом этапе)

- Полный Spring context стартует с реальными Ollama и DeepSeek beans
  (`PipelineCoordinatorIntegrationTest`).
- Ollama embeddings и lecture pipeline не регрессировали (201 тест).
- Registry показывает DeepSeek как cloud provider; benchmark принимает его
  конфигурацию.
- Локальный smoke (реальный запуск jar, storage в temp, `LP_MASTER_KEY`):
  - `GET /settings` → 200 (карточки Ollama/DeepSeek, назначение моделей);
  - `GET /api/providers` → `ollama, openai, deepseek`;
  - `GET /api/benchmarks/usage` → 200; `GET /benchmarks` → 200 (раздел расходов);
  - жизненный цикл ключа через REST с cookie+CSRF: save → `configured=true`,
    `keyHint=…1234`, сырой ключ в ответе отсутствует; delete → `configured=false`;
    POST без CSRF → 403. Ключ хранился зашифрованным (`v1:`), после удаления записи нет.
  - Миграции V2–V4 применились на чистой БД.
- Live smoke DeepSeek API (реальный платный запрос) не выполнялся.

## 10. Рекомендации для Day 32

- Code Review запускать через `LlmTaskSettingsService.resolve(CODE_REVIEW)`.
- Перед публикацией ревью проверять `ResolvedLlmTask.cloud/fallback` и явно
  запрещать fallback‑результат как финальный.
- Исключать из контекста ревью `.env`, `*.key`, `provider_credential`,
  `AGENTS.md`/`docs` (при публикации) и иные секреты.
- Добавить отдельный бюджет/лимит на review‑прогоны (сейчас есть
  `DEEPSEEK_MAX_CONCURRENT` и `DEEPSEEK_BUDGET_WARNING`).
