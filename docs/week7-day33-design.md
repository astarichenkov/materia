# Week 7 / Day 33 — AI Support Assistant: Design

## 1. Goal

A user-facing support assistant inside Lecture Processor that answers product
questions, diagnoses lecture-processing failures from real persisted state, keeps
a ticket conversation with multi-turn context and reports exactly which evidence
(tools, documents, stages) an answer is based on.

No second LLM client, no second database, no competing MCP server. Everything is
reused from the Day 27–32 infrastructure: one SQLite database, one
`LlmProviderRegistry`, one `McpToolRegistry`, one documentation RAG.

## 2. Context sources (audit)

| Source | Persisted where | Used for |
| --- | --- | --- |
| Lecture row | `lecture` (`status`, `error_message`, `duration_ms`, artifact paths, outputs) | did processing succeed, is the transcript/notes artifact present |
| Pipeline job | `pipeline_job` (`state`, `current_stage`, `attempts`, `error`, timestamps) | job status, retry count, last failure |
| Stage checkpoints | `stage_checkpoint` (`stage`, `state`, `attempt`, `error`, `started_at`, `finished_at`, `artifact_manifest_json`) | which stage stopped the pipeline and why |
| Download telemetry | `download_state` (`status`, bytes, percent, error) | download-stage diagnosis |
| Stage timings | `lecture_stage_timing` | LLM vs non-LLM time breakdown |
| Provider credentials | `provider_credential` (`last_success_at`, `last_error`, never the secret) | model health |
| Documentation RAG | `doc_chunk` (README + docs/, local embeddings) | product/FAQ answers |
| Support tickets | `support_ticket`, `support_message`, `support_tool_call` (new, V6) | ticket context and history |

Real errors are already persisted by the pipeline in `stage_checkpoint.error` and
`lecture.error_message`. No new pipeline error storage was required; the support
assistant only reads what exists. If a failure is not represented in the
checkpoints (e.g. a historical model outage), the assistant must say so instead
of inventing a cause.

## 3. Architecture

```text
User / Lecture page
        |
        v
/support UI  +  /api/support REST
        |
        v
SupportAssistantService  (orchestrator, budgets, persistence, metrics)
        |
        +-- SupportIntentRouter        GENERAL_FAQ | JOB_DIAGNOSTICS | TICKET_CONTEXT | MIXED
        +-- SupportContextBuilder      calls only the tools allowed for the intent
        |       |
        |       +-- SupportToolExecutor -> allow-list -> McpToolRegistry
        |       |       get_job_status, get_stage_runs, get_job_diagnostics,
        |       |       get_model_health, get_ticket, list_ticket_messages,
        |       |       search_support_tickets, search_help_docs
        |       +-- DocumentationRetrievalService (docs RAG)
        |
        +-- SupportEvidenceAggregator   facts vs docs vs hypotheses, citations
        +-- SupportPromptBuilder        trusted system prompt + untrusted evidence blocks
        +-- SupportAnswerValidator      bounds, grounded flag, secret redaction
        v
LlmTaskResolver (SUPPORT_ASSISTANT) -> DeepSeek | Ollama
        v
SupportAnswer + citations + tool trace
        v
SupportTicketService persists ticket/messages/tool calls
```

Simple status lookups are resolved deterministically (no LLM call) when the
intent is a pure job-status question and the evidence is sufficient; everything
else goes through one bounded LLM call. A complex request may use more tools but
never more than `maxToolCalls` and never more than `maxLlmCalls`.

## 4. Data model

```
support_ticket(id, scope, owner, lecture_id?, subject, category, status,
               created_at, updated_at, resolved_at?)
support_message(id, ticket_id, role, content, created_at, lecture_id?, job_id?,
               citations_json, tools_json, provider?, model?, grounded?,
               duration_ms?, input_tokens?, output_tokens?, estimated_cost?,
               cost_currency?, tool_calls?, tool_failures?, feedback?)
support_tool_call(id, message_id?, ticket_id?, tool_name, ok, duration_ms?,
               summary?, created_at)
```

`category` ∈ GENERAL, DOWNLOAD, TRANSCRIPTION, LLM, SLIDES, EXPORT,
CONFIGURATION, OTHER. `status` ∈ OPEN, IN_PROGRESS, RESOLVED, CLOSED.

Migrations: base schema in `db/schema.sql` (idempotent) + additive
`V6__day33_support_assistant.sql` applied by `SchemaMigrator` (V2..V6).

## 5. MCP tools

New (all read-only):

| Tool | Input | Output |
| --- | --- | --- |
| `get_ticket` | `ticket_id` | ticket metadata + lecture linkage within the authorized scope |
| `list_ticket_messages` | `ticket_id`, `limit` | bounded conversation history |
| `get_job_diagnostics` | `job_id` or `lecture_id` | status, last stage, errors, retry count, timings, checkpoint availability, warnings |
| `get_model_health` | — | selected provider/model availability + last known credential status (no paid call, no secrets) |
| `search_support_tickets` | `query`, `category?`, `limit?` | matching tickets in scope |

Reused: `get_job_status`, `get_stage_runs`, `search_help_docs`.

### Tool profile

`SupportProperties.allowedTools` is the explicit allow-list enforced by
`SupportToolExecutor`. The support assistant can never call the developer
assistant's git/filesystem tools (`git_current_branch`, `git_status`,
`git_recent_commits`, `git_diff_stat`, `list_project_files`) and has no shell or
write access.

## 6. Security model

There is no full user system. The application is a **trusted local install** by
default, documented explicitly:

* Every ticket carries a `scope` (installation scope, default `local-install`)
  and an `owner` (a random per-session id).
* The service authorizes on the stored `scope`/`owner`, **never** on the ticket
  id — knowing an id is not proof of access.
* `app.support.isolation=install` (default) allows any local session of the
  install to read its tickets; `session` additionally requires the same owner
  cookie for horizontal isolation.
* Tool results are bounded (`maxToolResultChars`) and passed through
  `SecretRedactor` (Authorization/api-key/token/password patterns).
* Support tools are read-only; no retry, delete or settings change is exposed to
  the LLM.
* Prompt-injection: documentation, ticket text and tool output are untrusted
  data, wrapped in labelled blocks; the system prompt is the only trusted
  instruction source.
* CSRF: state-changing `/support` and `/api/support` requests require a
  synchronizer token (`_csrf` / `X-Support-Csrf`).

## 7. RAG

Reuses `doc_chunk` and `DocumentationRetrievalService`. Product/FAQ answers cite
`[source_path#sequence]`. If the index is empty the assistant reports that
documentation is not indexed instead of guessing. Embeddings stay local
(`nomic-embed-text`); the cloud provider is never used for embeddings.

## 8. Diagnostics scenarios

* `JOB_DIAGNOSTICS`: resolve the lecture/job, read `get_job_status` +
  `get_stage_runs` + `get_job_diagnostics`; confirm facts (e.g. transcription
  COMPLETED, TEXT_PROCESSING FAILED) and list what to do next.
* `TICKET_CONTEXT`: load ticket + previous messages; include the linked lecture
  automatically so the user never re-enters ids.
* `MIXED`: combine docs + diagnostics + ticket history.
* Insufficient evidence: no job/stage rows → state that no diagnostic history
  exists and name what to check; never fabricate a cause.

## 9. Testing

Unit + integration: ticket persistence and isolation, MCP tools (missing job,
unauthorized ticket, payload limits, secret redaction), orchestrator routing,
FAQ RAG, multi-turn, LLM routing (DeepSeek/Ollama/failure), MCP failure,
insufficient evidence, prompt injection, context limits, structured validation,
`/support` UI and API with CSRF/XSS checks, and regression (`mvn verify`).

## 10. Local-only documentation

`AGENTS.md` and `docs/` are excluded from publication by `.gitignore`; this
design and `docs/week7-day33-report.md` remain local. `README.md`,
`demos/day33/` and the code are versioned.
