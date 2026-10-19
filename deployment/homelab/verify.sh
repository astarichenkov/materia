#!/usr/bin/env bash
#
# Day 30 — real acceptance checks for a deployed private-ai-chat service.
#
# Runs ON the server. Never prints the API key. Idempotent, but the per-client
# rate limit is a 60 s fixed window: if the service was just exercised, wait a
# minute before running this, otherwise the non-rate-limit checks may see 429.
#
# Checks: health, API auth (missing/wrong/correct key), oversized input,
# real chat, follow-up context, per-client rate limiting, Ollama isolation.
#
# Usage: ./verify.sh [base-url]
#        BASE default http://127.0.0.1:8080
#        API key is read from /etc/private-ai/private-ai.env or $CHAT_API_KEY.
#
set -uo pipefail

BASE="${1:-http://127.0.0.1:8080}"
ENV_FILE=/etc/private-ai/private-ai.env
if [ -z "${CHAT_API_KEY:-}" ] && [ -r "${ENV_FILE}" ]; then
  # shellcheck disable=SC1090
  . "${ENV_FILE}"
fi
KEY="${CHAT_API_KEY:-}"

pass=0; fail=0
ok()   { printf 'PASS  %s\n' "$1"; pass=$((pass+1)); }
bad()  { printf 'FAIL  %s (%s)\n' "$1" "$2"; fail=$((fail+1)); }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
body() { curl -s "$@"; }

echo "== health and security boundary =="
c="$(code "$BASE/api/health")"; [ "$c" = 200 ] && ok "health open (200)" || bad "health open" "got $c"

if [ -n "$KEY" ]; then
  c="$(code -H 'Content-Type: application/json' -d '{"message":"hi"}' "$BASE/api/chat")"
  [ "$c" = 401 ] && ok "unauthenticated rejected (401)" || bad "unauthenticated rejected" "got $c"

  c="$(code -H 'X-API-Key: definitely-wrong' -H 'Content-Type: application/json' \
        -d '{"message":"hi"}' "$BASE/api/chat")"
  [ "$c" = 401 ] && ok "wrong key rejected (401)" || bad "wrong key rejected" "got $c"
else
  echo "WARN  CHAT_API_KEY not set; skipping auth checks"
fi

AUTH=()
[ -n "$KEY" ] && AUTH=(-H "X-API-Key: $KEY")

echo "== input limit =="
c="$(code "${AUTH[@]}" -H 'Content-Type: application/json' \
      -d "{\"message\":\"$(printf 'x%.0s' {1..2001})\"}" "$BASE/api/chat")"
[ "$c" = 400 ] && ok "oversized (2001 chars) rejected (400)" || bad "oversized rejected" "got $c"

echo "== real chat via Spring Boot -> Ollama =="
resp="$(body "${AUTH[@]}" -H 'Content-Type: application/json' \
      -d '{"sessionId":"verify-1","message":"Скажи ровно одно слово: привет"}' "$BASE/api/chat")"
if printf '%s' "$resp" | grep -q '"answer"'; then
  ok "chat returned an answer"
else
  bad "chat returned an answer" "$resp"
fi

echo "== follow-up context =="
body "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"sessionId":"verify-ctx","message":"Меня интересует Java."}' "$BASE/api/chat" >/dev/null
resp2="$(body "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"sessionId":"verify-ctx","message":"Какой язык я только что назвал? Ответь одним словом."}' "$BASE/api/chat")"
if printf '%s' "$resp2" | grep -qi 'java'; then
  ok "follow-up remembers context (answer mentions Java)"
else
  bad "follow-up context" "$resp2"
fi

echo "== per-client rate limit =="
limited=0
for i in $(seq 1 6); do
  c="$(code "${AUTH[@]}" -H 'Content-Type: application/json' \
        -d '{"message":"rate"}' "$BASE/api/chat")"
  [ "$c" = 429 ] && limited=$((limited+1))
done
[ "$limited" -ge 1 ] && ok "rate limit returned 429 ($limited/6)" || bad "rate limit 429" "none of 6"

echo "== Ollama isolation =="
if curl -fsS http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then
  ok "Ollama reachable on loopback"
else
  bad "Ollama loopback" "unreachable"
fi
listeners="$(ss -ltn 2>/dev/null | awk '$4 ~ /:11434$/ {print $4}')"
if [ -n "$listeners" ] && ! printf '%s\n' "$listeners" | grep -qvE '^(127\.0\.0\.1|\[::1\]):11434$'; then
  ok "Ollama bound to loopback only ($listeners)"
else
  bad "Ollama isolation" "listeners: ${listeners:-none}"
fi

echo "---"
echo "Passed: $pass  Failed: $fail"
[ "$fail" -eq 0 ]
