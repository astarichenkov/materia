# Day 30 — test results

## Automated tests

Command: `JAVA_HOME=/c/tools/jdk-25.0.2-full /c/tools/apache-maven-3.9.11/bin/mvn verify`

| Module | Tests | Result |
|--------|-------|--------|
| lecture-processor-backend | 68 | PASS |
| private-ai-chat | 28 | PASS |
| **Total** | **96** | **PASS / 0 FAIL** |

private-ai-chat breakdown:

| Test class | Tests | Covers |
|-----------|-------|--------|
| PrivateAiChatApplicationTests (context) | 1 | model wiring |
| ChatServiceTest | 6 | validation, context trimming, queue, failures |
| ChatMemoryStoreTest | 2 | bounded history |
| RateLimiterTest | 2 | per-client fixed window |
| ApiKeyInterceptorTest | 4 | API key auth |
| ClientIdResolverTest | 2 | X-Forwarded-For trust (spoofing) |
| ChatApiControllerTest | 5 | health/chat, 400/429/503 |
| ChatWebControllerTest | 1 | per-client UI rate limit |
| WebSecurityIntegrationTest | 5 | API auth + UI not blocked |
| **Total** | **28** | |

## Server acceptance (`deployment/homelab/verify.sh`)

Real run on `private-ai` (`http://127.0.0.1:8080`) — **9 PASS / 0 FAIL**:

```
PASS  health open (200)
PASS  unauthenticated rejected (401)
PASS  wrong key rejected (401)
PASS  oversized (2001 chars) rejected (400)
PASS  chat returned an answer
PASS  follow-up remembers context (answer mentions Java)
PASS  rate limit returned 429 (5/6)
PASS  Ollama reachable on loopback
PASS  Ollama bound to loopback only (127.0.0.1:11434)
```

## LAN tests (from laptop)

| Check | Result |
|-------|--------|
| `http://203.0.113.10:8080/api/health` | 200 `{"status":"UP","ollamaAvailable":true}` |
| `http://203.0.113.10:11434/api/tags` | REFUSED (exit 7) |
| SSH `203.0.113.10:22` | OPEN |
| `GET http://203.0.113.10:8080/` | 200 |
| `POST /chat` (UI form) | real model answer |
| `POST /api/chat` (key) | real model answer |
| Follow-up context | answer `Java.` |

Real upstream (Spring Boot → Ollama) at the server:

```
msg1: "Меня интересует Java."          -> answer about Java
msg2: "Какой язык я только что назвал? Ответь одним словом." -> "Java."
```

## Limits and stability (real)

| Check | Result |
|-------|--------|
| Missing / wrong key | 401 |
| Correct key | 200 |
| 2001 chars | 400 |
| 2000 chars (exact boundary) | 200, answer "2" |
| 6 rapid requests | 429 returned (rate limit) |
| Sequential 5 requests | 5×200, 0.37–0.61 s |
| Concurrent 5 requests | 5×200, serialized 0.86→5.76 s, queue peak 3, healthy after |
| Restart (`ollama` + `private-ai`) | health 200, chat works, both `enabled` |

## Performance (real)

| Metric | Value |
|--------|-------|
| Cold latency | 7.17 s (model load included) |
| Warm latency | 3.92 s |
| Eval rate | ~14.4 tok/s |
| CPU during generation | ~5.25–6 of 6 cores |
| RAM idle / loaded | 263 MiB / ~2.0 GiB |
| Java RSS | ~253 MB |
| llama-server RSS | ~1.86 GB |

## Homelab deployment

Status: **DONE and VERIFIED** on `203.0.113.10`. See
`docs/DAY30_REAL_DEPLOYMENT_REPORT.md`.
