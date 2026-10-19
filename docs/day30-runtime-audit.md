# Day 30 — runtime audit of `private-ai-chat`

Audit performed before the real homelab deployment (branch
`fix/week6-day30-real-deployment`). Focus: real deployment and runtime
defects, not stylistic issues.

## Reviewed artifacts

- `private-ai-chat/pom.xml` (Java 25, Spring Boot 4.1.1, Spring AI 2.0.1)
- `application.yml`, `ChatProperties`, `WebConfig`, `ApiKeyInterceptor`
- `RateLimiter`, `ChatMemoryStore`, `ChatService`, `OllamaLlmClient`
- `ChatApiController`, `ChatWebController`, `ApiExceptionHandler`
- Thymeleaf templates (`index.html`, `fragments/answer.html`)
- Unit / slice tests under `private-ai-chat/src/test`

## Findings

### Critical

| # | Finding | Detail |
|---|---------|--------|
| C1 | Web UI was unusable when `CHAT_API_KEY` is set | `ApiKeyInterceptor` was registered for `/chat` (the HTMX UI POST) as well as `/api/chat`. A browser cannot attach `X-API-Key`, so every UI submit returned `401`. Day 30 requires a working browser chat while the API stays authenticated. |
| C2 | Qwen3 thinking mode enabled by default → 2–5× slower CPU inference | `OllamaLlmClient` never set the Ollama `think` option, so `qwen3:1.7b` generated a full reasoning trace before every answer (observed 8 s warm vs 4.5 s with thinking disabled). On a CPU-only LXC this directly degrades UX and increases timeout risk. |

### High

| # | Finding | Detail |
|---|---------|--------|
| H1 | UI rate-limit bucket shared by all users | `ChatWebController` called `rateLimiter.tryAcquire("ui")` — a single global bucket for every browser client, not per client. One user could exhaust the quota for everyone. |
| H2 | Rate limit bypassable via spoofed `X-Forwarded-For` | `ChatApiController.clientId` trusted `X-Forwarded-For` unconditionally. With no reverse proxy in front, any LAN client could send random header values and evade the 5/min limit entirely. |

### Medium

| # | Finding | Detail |
|---|---------|--------|
| M1 | Generation timeout not externally configurable | `app.ollama.timeout` was hardcoded to `2m` in `application.yml`; operators could not tune it for slower CPU hosts via environment. |
| M2 | `isAvailable()` performs a live HTTP probe on every chat request | Adds a small latency and an extra connection per request. Acceptable for a single-instance private service; noted, not changed. |
| M3 | `Future.cancel(true)` cannot guarantee the blocking Ollama HTTP call is interrupted | A hung generation can occupy the single worker until the read timeout fires. Bounded by the Ollama read timeout; documented limitation. |

### Low

| # | Finding | Detail |
|---|---------|--------|
| L1 | In-memory sessions and rate-limit state are lost on restart | Expected for this design; documented. |
| L2 | `/api/chat/ping` is not covered by the `/api/chat` Ant pattern | Not authenticated, but exposes only queue size. No action. |
| L3 | Ollama default `keep_alive` differs from the deployment policy | Deployment pins `OLLAMA_KEEP_ALIVE=10m` at the server level; no code change needed. |

## Limits check (Day 30 targets)

| Limit | Target | Implementation | Verdict |
|-------|--------|----------------|---------|
| Rate limit | 5 req/min/client | `RateLimiter` fixed window per client id | OK (UI bucket fixed, H1) |
| Max input | 2000 chars | `ChatService.validate` (`> maxMessageChars` → 400) | OK, boundary is exact |
| Max context | 4096 tokens | `OllamaChatOptions.numCtx` + `trimToContext` | OK |
| Max output | 512 tokens | `OllamaChatOptions.numPredict` | OK |
| Active generations | 1 | `ThreadPoolExecutor(1, 1, ...)` | OK |
| Queue | bounded | `ArrayBlockingQueue(queueCapacity=10)` + `AbortPolicy` | OK |
| Model | qwen3:1.7b | `app.ollama.model` | OK |

Character limit and token budget are correctly kept as separate limits.

## Fixes applied

1. **C1** — `ApiKeyInterceptor` is now registered for `/api/**` only. The
   browser UI is reachable on the private LAN; the machine API requires the
   key. This preserves the existing authentication mechanism for the API and
   makes the required web chat functional. See `WebConfig`.
2. **C2** — added configurable `app.ollama.think` (`CHAT_THINK`, default
   `false`) and wired `disableThinking()` into `OllamaLlmClient` for the CPU
   deployment.
3. **H1** — the UI rate limiter now keys on the real client address instead of
   a shared literal.
4. **H2** — added `ClientIdResolver`: `X-Forwarded-For` is honoured only when
   `app.security.trust-forwarded-for=true` (default `false`). Rate limiting is
   otherwise keyed strictly on the socket remote address, closing the spoofing
   bypass.
5. **M1** — `app.ollama.timeout` is now `${CHAT_TIMEOUT:2m}`.

Regression tests were added for C1/H1 (`WebSecurityIntegrationTest`,
`ChatWebControllerTest`) and H2 (`ClientIdResolverTest`). All other limits were
confirmed as implemented and covered by existing tests.
