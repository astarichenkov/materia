# Day 32 — Automated AI Code Review for Gitea (demo scenario)

This scenario demonstrates a real, end-to-end automated AI code review:
Gitea pull request → webhook/polling trigger → commit-consistent code + docs RAG
→ DeepSeek/Ollama → validated structured findings → published Gitea comment.

## 1. Prerequisites

- Java 25 (`JAVA_HOME=/c/tools/jdk-25.0.2-full`) and Maven 3.9+.
- Ollama running locally with `qwen3:1.7b` and `nomic-embed-text` (embeddings).
- A Gitea instance with a personal access token in the environment as
  `GITEA_TOKEN` (scopes: `read:repository`, `write:issue`). Never commit it.
- The reviewer repository is explicitly allow-listed
  (`GITEA_ALLOWED_REPOS=your-org/materia`).
- DeepSeek is optional but recommended for the primary reviewer; the API key is
  entered per installation in `/settings` (Day 32 preparation) and is never
  stored in git or returned by an API. Without it, the review falls back to the
  local Ollama model configured in `/settings → Code Review`.

## 2. Start the backend

```bash
export JAVA_HOME=/c/tools/jdk-25.0.2-full
export PATH="$JAVA_HOME/bin:/c/tools/ffmpeg/ffmpeg-9.0.2-essentials_build/bin:/c/tools/whisper/bin/Release:$PATH"

# Gitea integration (secrets come from the environment, not from files)
export GITEA_BASE_URL=https://gitea.example.com
export GITEA_ALLOWED_REPOS=your-org/materia
export GITEA_WEBHOOK_SECRET="$(head -c 24 /dev/urandom | base64 | tr -d '\n=+/' | head -c 32)"
printf '%s' "$GITEA_WEBHOOK_SECRET" > /tmp/lp-day32-webhook.secret   # reuse for the Gitea hook

# Local reviewer model (used when DeepSeek is not configured)
export OLLAMA_CHAT_MODEL=qwen3:1.7b OLLAMA_DISABLE_THINKING=true
export CODE_REVIEW_POLL_SECONDS=20

./mvnw -pl backend spring-boot:run
```

Open `http://127.0.0.1:8080/code-review`. Gitea status should show
`token configured` and `webhook secret configured`. The admin surface is
loopback-only unless `LP_ADMIN_TOKEN` is set; all mutating calls use CSRF.

## 3. Configure the webhook (optional when polling is on)

```bash
SECRET="$(cat /tmp/lp-day32-webhook.secret)"
curl -s -X POST -H "Authorization: token $GITEA_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"type\":\"gitea\",\"active\":true,\"events\":[\"pull_request\"],\"config\":{\"url\":\"http://<WINDOWS_LAN_IP>:8080/api/code-review/webhook/gitea\",\"content_type\":\"json\",\"secret\":\"$SECRET\"}}" \
  https://gitea.example.com/api/v1/repos/your-org/materia/hooks
```

The webhook endpoint is public (Gitea must reach it) but **rejects** any request
with a missing/invalid `X-Gitea-Signature` (HMAC-SHA256). If Gitea cannot reach
the Windows host (firewall), the built-in **polling fallback** detects new PR
head SHAs every `CODE_REVIEW_POLL_SECONDS` and enqueues the same review. Both
paths are idempotent by `(repository, PR, head SHA, config version)`.

## 4. Create the demo PR

From the integration branch:

```bash
git checkout -b demo/day32-ai-review
# add backend/src/main/java/com/lectureprocessor/demo/DemoPricingCalculator.java
#   with two intentional defects:
#   1) hasCoupon(): NPE when the coupon list contains null
#   2) discountPercent(): `items > 10` instead of `items >= 10`
git commit -am "demo(day32): add pricing calculator with intentional defects"
git push -u origin demo/day32-ai-review
```

Create the PR (base = the Day 32 feature branch) via the Gitea API or UI:

```bash
curl -s -X POST -H "Authorization: token $GITEA_TOKEN" -H 'Content-Type: application/json' \
  -d '{"head":"demo/day32-ai-review","base":"feature/week7-day32-code-review",
       "title":"Day 32 demo: pricing calculator","body":"Intentional defects, do not merge."}' \
  https://gitea.example.com/api/v1/repos/your-org/materia/pulls
```

## 5. Expected result

Within ~1–2 minutes (code index for the head SHA + one model call) a comment
appears on the PR:

```markdown
## AI Code Review
Model: `deepseek / deepseek-flash`
Commit: `4aaf4b8c` · Risk: **LOW**
### Summary ...
### Medium priority
1. NullPointerException when coupon list contains a null entry — DemoPricingCalculator.java:32
2. Off-by-one boundary in discountPercent — DemoPricingCalculator.java:41
### Recommendations ...
_Automated review. Human verification recommended._
```

Verified live: PR #1, review jobs #1 (SHA `4aaf4b8c`, comment id 2) and #2
(SHA `a1b6f2c2`, comment id 4). The second review correctly reported the
remaining null defect and recognised that the boundary defect was fixed.

## 6. Re-review on a new commit

Push any new commit to the demo branch (e.g. fix one defect). The poller/webhook
detects the new head SHA and enqueues a fresh review; the old SHA is never
published as current (stale suppression), and the same SHA is never reviewed
twice (duplicate suppression / DB idempotency key). The dashboard at
`/code-review` lists every run with status, provider/model, findings, tokens,
estimated cost and duration.

## 7. Troubleshooting

- **No review appears**: check `GET /api/code-review/status`; ensure the repo is
  allow-listed and `GITEA_TOKEN` is present. Check the app log for
  `Polling detected PR ...`.
- **Webhook does not arrive**: Gitea→Windows inbound is commonly blocked. Rely
  on polling (`CODE_REVIEW_POLLING=true`) or open a firewall rule for port 8080
  from the Gitea host only.
- **`401` webhook**: the secret in Gitea and `GITEA_WEBHOOK_SECRET` differ.
- **Ollama review returns no findings / invalid JSON**: the analyzer retries
  once with a strict JSON instruction; if it still fails the job is `FAILED`
  with a clear error and nothing is published.
- **Code index**: `/code-review` shows whether the code index is available; if
  the embedding model is down the review is marked `partial`.

## 8. Video plan (3–5 minutes)

1. 0:00 — Show the PR in Gitea and the two intentional defects in the diff.
2. 0:30 — Show `/code-review` dashboard: Gitea status, webhook/polling, model
   (`deepseek / deepseek-flash`).
3. 1:00 — Show the app log: polling/webhook trigger → code index (150 files,
   997 chunks) → DeepSeek call with token usage.
4. 1:45 — Open the published PR comment: both defects flagged with files/lines.
5. 2:45 — Push a fix commit; show the new review run and the updated comment.
6. 3:45 — Show token metrics, duration and estimated cost on the dashboard.
7. 4:15 — Summarise the trust boundary (prompt-injection defense), idempotency
   and the manual "Analyze PR" / dry-run mode.

## 9. Explanation for the reviewer

- **What is implemented**: a Gitea PR webhook + polling trigger, a Gitea REST
  client, commit-versioned source-code RAG (separate `code_chunk` table), a
  hybrid retriever (vector + symbol/path lexical), an LLM orchestrator using the
  `CODE_REVIEW` model from `/settings`, a strict JSON validator, and a Gitea
  publisher with duplicate/stale protection.
- **Where RAG happens**: changed files are fetched from Gitea at the head SHA;
  related code is retrieved from a per-commit index of the repository (built
  from Gitea tree/blobs, never from the local working directory); documentation
  is retrieved from the Day 31 `doc_chunk` index.
- **How the agent runs**: the webhook only validates and enqueues; the review
  executor performs fetch → retrieve → analyse → validate → publish with bounded
  retries and a per-stage timeout.
- **Why this architecture**: reuse of the existing LLM/credentials/RAG stack,
  explicit provider selection, no second API-key system, and strong safety
  boundaries (allow-list, HMAC, prompt-injection isolation, no auto-merge).
- **Quality**: measured against a seeded PR with known defects (2/2 found by
  DeepSeek); the same pipeline runs on Ollama for local comparison, and findings
  are never taken as ground truth without human verification.
