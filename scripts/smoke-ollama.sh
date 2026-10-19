#!/usr/bin/env bash
# Smoke test for local Ollama chat + embeddings. Skips when Ollama is absent.
# Usage: ./scripts/smoke-ollama.sh [chat-model] [embedding-model]
set -euo pipefail

BASE="${OLLAMA_BASE_URL:-http://localhost:11434}"
CHAT_MODEL="${1:-${OLLAMA_CHAT_MODEL:-qwen3:4b}}"
EMBED_MODEL="${2:-${OLLAMA_EMBEDDING_MODEL:-nomic-embed-text}}"

if ! curl -sf "$BASE/api/tags" >/dev/null 2>&1; then
  echo "SKIPPED: Ollama not reachable at $BASE"; exit 0
fi

echo "Chat model: $CHAT_MODEL"
curl -sf "$BASE/api/chat" -H 'Content-Type: application/json' \
  -d "{\"model\":\"$CHAT_MODEL\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"Ответь одним словом: привет\"}]}" \
  | head -c 500; echo

echo "Embedding model: $EMBED_MODEL"
curl -sf "$BASE/api/embeddings" -H 'Content-Type: application/json' \
  -d "{\"model\":\"$EMBED_MODEL\",\"prompt\":\"тестовый текст\"}" \
  | head -c 200; echo
echo "PASS: Ollama responded"
