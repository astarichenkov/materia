# Day 30 report — Private AI service

## Goal

Run a private local LLM as an HTTP service with a simple web chat on CPU-only
hardware, independent of the lecture-processing pipeline.

## Delivered

- Independent Maven module `private-ai-chat`.
- Chat service with bounded history and explicit context/output token budgets
  (character limit enforced separately).
- Single generation worker + bounded queue (`ThreadPoolExecutor(1,1,AbortPolicy)`).
- Per-client fixed-window rate limiting (5/min) → HTTP 429.
- API-key authentication from `CHAT_API_KEY` (disabled when unset), never logged.
- `GET /api/health`, `POST /api/chat`, Thymeleaf/HTMX chat page.
- Clean error responses (400/401/429/503) without stack traces.
- Deployment assets for the homelab (scripts + optional Compose).

## Token vs character limits

`maxMessageChars` bounds the request body; `maxContextTokens`/`maxOutputTokens`
bound the model prompt and generation. Context is trimmed from the oldest turns
using a conservative 4 chars/token estimate.

## Security

- Ollama is expected on loopback only.
- Bind address defaults to `127.0.0.1`; exposing on a LAN/VPN is explicit.
- No secrets in the repository; all auth config via environment variables.

## Verification

- 28 automated tests PASS for the module (96 total with the backend).
- **Real homelab deployment VERIFIED** on LXC `private-ai` (`203.0.113.10`):
  real `qwen3:1.7b` CPU inference through Spring Boot, LAN health/chat, auth,
  rate limit (429), input limit (400/200 boundary), sequential and concurrent
  stability, restart recovery, and Ollama loopback isolation.
- Report: `docs/DAY30_REAL_DEPLOYMENT_REPORT.md`; audit: `docs/day30-runtime-audit.md`;
  demo: `demos/day30/`.
