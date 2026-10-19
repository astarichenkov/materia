# Day 30 — Real Private AI Homelab Deployment Report

Status: **VERIFIED** (real LAN deployment, real CPU inference, real limit tests).

Branch: `fix/week6-day30-real-deployment`
Tag: `week6-day30-verified`
Date: 2026-10-11

## Environment

| Item | Value |
|------|-------|
| Host | Proxmox LXC `private-ai`, `203.0.113.10` |
| OS | Debian GNU/Linux 12 (bookworm), kernel 7.0.2-6-pve (x86_64) |
| vCPU | 6 (Intel i5-10500 class, no discrete GPU) |
| RAM | 8.0 GiB |
| Swap | none (ZFS subvol — a swap file is not supported; see limitations) |
| Disk | 24 GiB (used ~3.7 GiB) |
| Java | Temurin 25.0.4.1+1 JRE (`/opt/java/current`) |
| Ollama | 0.40.3 (official Linux install), systemd service |
| Model | `qwen3:1.7b` (1.4 GB) |
| Spring Boot | 4.1.1, Java 25, Spring AI 2.0.1 |

## Deployment

Directories / users:

```
/opt/private-ai/private-ai-chat.jar     private-ai:private-ai  0640
/etc/private-ai/application.yml         root:root              0644 (non-secret)
/etc/private-ai/private-ai.env          root:root              0600 (CHAT_API_KEY)
/var/lib/private-ai, /var/log/private-ai
system user: private-ai (uid 996, no login shell)
```

systemd:

- `private-ai.service` — user `private-ai`, `EnvironmentFile=/etc/private-ai/private-ai.env`,
  `ExecStart=/opt/java/current/bin/java -Xms128m -Xmx512m -XX:+UseSerialGC -jar ... --spring.config.additional-location=file:/etc/private-ai/`,
  `Restart=on-failure`, `RestartSec=5`, `after/wants ollama.service`.
- Hardening that works in this unprivileged LXC: `NoNewPrivileges`, `PrivateTmp`,
  `ProtectHome`, `ProtectSystem=full`, `RestrictSUIDSGID`, `LockPersonality`.
- `ollama.service` drop-in pins loopback + single worker:
  `OLLAMA_HOST=127.0.0.1:11434`, `OLLAMA_NUM_PARALLEL=1`, `OLLAMA_MAX_LOADED_MODELS=1`,
  `OLLAMA_MAX_QUEUE=5`, `OLLAMA_CONTEXT_LENGTH=4096`, `OLLAMA_KEEP_ALIVE=10m`, `OLLAMA_NO_CLOUD=1`.

Security model:

```
LAN client ──:8080──▶ private-ai-chat (0.0.0.0, systemd, user private-ai)
                          │  127.0.0.1:11434 only
                          ▼
                       Ollama ──▶ qwen3:1.7b (CPU)
```

`POST /api/chat` requires `X-API-Key` (or `Authorization: Bearer`). `GET /api/health`
and the browser UI (`/`, `/chat`) are open on the private LAN. Ollama is bound to
loopback and is not reachable from the LAN.

## Network

| Port (from LAN client) | Result |
|------------------------|--------|
| 22                     | OPEN |
| 8080                   | OPEN (`GET /api/health` → 200) |
| 11434                  | REFUSED (`curl` exit 7, no listener) |

Inside the LXC: `ss -ltnp` shows `127.0.0.1:11434` (Ollama) and `*:8080` (Spring Boot).

## Real AI verification

Direct Ollama (server, CPU):

```
qwen3:1.7b  100% CPU, context 4096, runner llamacpp
```

Spring Boot → Ollama, real answer over the LAN (laptop → 203.0.113.10:8080):

```
msg1: "Меня интересует Java."
  -> "Java — это объектно-ориентированный язык программирования ..."
msg2: "Какой язык я только что назвал? Ответь одним словом."
  -> "Java."
```

Web UI (browser path, no key): `GET /` → 200, `POST /chat` → real model answer.

## Limits

| Limit | Target | Verified result |
|-------|--------|-----------------|
| Auth | key required on API | no key → 401, wrong key → 401, correct key → 200 |
| Rate limit | 5 req/min/client | 6 rapid requests → 5×429 observed (accounting for consumed window) |
| Input | 2000 chars | 2001 chars → 400; exactly 2000 chars → 200 (answer "2") |
| Context | 4096 tokens | `numCtx=4096`; history trimmed by token budget (`trimToContext`) |
| Output | 512 tokens | `numPredict=512` |
| Active generations | 1 | concurrent test serialized (staircase latencies) |
| Queue | bounded (10) | queue depth peaked at 3 then drained; no crash, no extra generation |

## Stability

Sequential — 5 real requests, all HTTP 200:

```
seq 1 HTTP=200 latency=0.374s
seq 2 HTTP=200 latency=0.475s
seq 3 HTTP=200 latency=0.482s
seq 4 HTTP=200 latency=0.607s
seq 5 HTTP=200 latency=0.485s
```

Concurrent — 5 requests issued together, all HTTP 200 (no 429/503, no crash):

```
con 2 HTTP=200 latency=0.862s
con 5 HTTP=200 latency=1.990s
con 3 HTTP=200 latency=3.172s   (queue peak: 3)
con 4 HTTP=200 latency=4.589s
con 1 HTTP=200 latency=5.756s
health after: 200 {"status":"UP","ollamaAvailable":true}
```

Restart — `systemctl restart ollama && systemctl restart private-ai`:
health returns 200, chat works; `is-enabled` = enabled for both.

## Performance (measured, not estimated)

| Metric | Value |
|--------|-------|
| Cold end-to-end latency (model load included) | 7.17 s |
| Warm end-to-end latency (short sentence) | 3.92 s |
| Ollama eval rate | ~14.4 tok/s |
| Ollama prompt eval rate | ~88–95 tok/s |
| CPU during generation | llama-server 525–600 % (~5.25–6 of 6 cores) |
| RAM idle (model unloaded) | 263 MiB used |
| RAM loaded (model resident) | ~2.0 GiB used |
| Java (private-ai) RSS | ~253 MB (Xmx512m) |
| ollama server process RSS | ~25 MB |
| llama-server runner RSS | ~1.86 GB (model weights) |
| Load average during generation | ~1.0–1.5 |

`think=false` is enabled: with Qwen3 thinking on, warm latency roughly doubled
and produced a reasoning trace that is never shown to the user.

## Bugs found and fixed

| # | Severity | Issue | Root cause | Fix |
|---|----------|-------|-----------|-----|
| C1 | Critical | Browser UI returned 401 when `CHAT_API_KEY` was set | `ApiKeyInterceptor` covered `/chat` (the HTMX POST) | Interceptor now covers `/api/chat` only; UI is open on the private LAN |
| C2 | Critical | 2–5× slower CPU inference | Ollama `think` option never set (Qwen3 thinking on) | Configurable `app.ollama.think` (default `false`) wired via `disableThinking()` |
| H1 | High | One global UI rate-limit bucket for all users | `tryAcquire("ui")` literal | UI keys on the real client address |
| H2 | High | Rate limit bypassable by spoofing `X-Forwarded-For` | Header trusted unconditionally | `ClientIdResolver`; only trusted when `app.security.trust-forwarded-for=true` |
| M1 | Medium | Generation timeout not configurable | Hardcoded `2m` | `app.ollama.timeout` ← `${CHAT_TIMEOUT:2m}` |
| D1 | High (deployment) | Service started on port 8081, external config ignored | `/etc/private-ai` was `0750 root:root`, so user `private-ai` could not read `application.yml`; `additional-location` silently skipped | Directory `0755`; `private-ai.env` stays `0600 root` |
| D2 | High (deployment) | systemd service crash-looped with `status=226/NAMESPACE` | `ProtectSystem=strict`, `ProtectKernelTunables`, `ProtectControlGroups` need mount namespaces unavailable in an unprivileged LXC | Use only verified options (`ProtectSystem=full`, `PrivateTmp`, `ProtectHome`, …) |

Regression tests added: `WebSecurityIntegrationTest` (C1), `ClientIdResolverTest`
(H2), `ChatWebControllerTest` (H1), plus updated `OllamaLlmClient` think handling.

## Tests

| Suite | Command | Result |
|-------|---------|--------|
| Full regression | `JAVA_HOME=/c/tools/jdk-25.0.2-full /c/tools/apache-maven-3.9.11/bin/mvn verify` | backend 68 PASS + private-ai-chat 28 PASS = **96 PASS / 0 FAIL** |
| Server acceptance | `deployment/homelab/verify.sh http://127.0.0.1:8080` | **9 PASS / 0 FAIL** |
| LAN network | curl / `/dev/tcp` from laptop | 22 OPEN, 8080 OPEN, 11434 REFUSED |

## Security confirmation

- No secret is committed: the API key exists only in `/etc/private-ai/private-ai.env`
  (`0600 root:root`) and is never printed by scripts or this report.
- Ollama is bound to `127.0.0.1:11434` and is unreachable from the LAN.
- `POST /api/chat` requires authentication; missing/wrong keys return 401.
- No router port forwarding; the service is private-LAN only.

## Known limitations

- **Swap is not active**: the LXC root is a ZFS subvol where `swapon` rejects a
  swap file. Adding swap requires a host-side zvol, which is out of scope
  (must not touch the Proxmox host). With 8 GiB RAM and a 1.9 GB model this is
  not a constraint in practice.
- In-memory chat sessions and rate-limit state are lost on restart.
- `Future.cancel(true)` cannot force-interrupt a blocking Ollama HTTP call; a
  hung generation occupies the single worker until the read timeout.
- Rate limiting is per source IP; the browser UI and API from one host share a
  single 5/min bucket per surface (separate buckets for UI vs API).
- Only `qwen3:1.7b` was tested; larger models were intentionally not installed.
