# Week 7 / Day 34 — Autonomous File Agent: Architecture Design

> Status: implemented. This document is the audit + design baseline for the Day 34
> file agent. `docs/` is intentionally git-ignored in this repository (see
> `AGENTS.md`), so the public README carries the user-facing summary.

## 1. AS-IS (before Day 34)

| Area | Existing component | Reused by Day 34 |
| --- | --- | --- |
| LLM routing | `LlmTaskType`, `LlmTaskSettingsService`, `LlmTaskResolver`, `LlmProviderRegistry` | new `FILE_AGENT` task, independent settings |
| RAG | `DocumentationRetrievalService`, `doc_chunk`; `CodeRetrievalService` (Gitea commit index) | documentation RAG + local lexical symbol search |
| MCP | `McpTool`, `McpToolRegistry`, `McpProtocolHandler`, `POST /mcp` | new profiled read-only tools |
| Persistence | SQLite + `SchemaMigrator` (V2..V6) | additive migration V7 |
| Executors | bounded `ThreadPoolTaskExecutor` beans | new single-thread `fileAgentExecutor` |
| Security | `AdminSecurityFilter`, `AdminAccessService` (loopback/admin token + CSRF) | new `/file-agent`, `/api/file-agent` guard |
| Cost/metrics | `CloudCostService`, per-task token columns | task/proposal metrics columns |

Existing developer assistant MCP tools (`list_project_files`, git tools) are
read-only but they only understand the project root. There was no workspace
abstraction, no path denylist beyond `target/`/`.git`, no approval workflow and
no write capability at all.

## 2. TO-BE

```
User goal (UI / API)
      |
      v
FileAgentService ── bounded async executor ──> FileAgentOrchestrator
                                                    |
             +--------------------------------------+
             | 1. validate goal + workspace         |
             | 2. deterministic lexical seed search |
             | 3. LLM plan (select candidates)      |
             | 4. allow-listed read-only tools      |
             | 5. bounded context aggregation       |
             | 6. LLM change proposal (full text)   |
             | 7. proposal validation + diff        |
             | 8. persist AWAITING_APPROVAL         |
             +--------------------------------------+
                                                    |
                                          approval gate (UI/API, CSRF+admin)
                                                    |
                                                    v
                                  FileChangeApplicationService
                                  revalidate paths + hashes
                                  backup -> atomic write -> verify
                                  rollback on failure
                                                    |
                                                    v
                                         COMPLETED / CONFLICT / FAILED
```

The LLM decides **what to search** and **what content to propose**. Java code
decides **where it may read/write**, **which tools are allowed**, **whether a
proposal is valid**, **whether a human approved it** and **how bytes reach disk**.

## 3. File workspaces

| Workspace | Root | Purpose |
| --- | --- | --- |
| `PROJECT_WORKSPACE` | configured `app.file-agent.project-root` or `ProjectRootResolver` | source, tests, README, docs |
| `LECTURE_WORKSPACE` | `<storage>/lectures/<lectureId>` (via `FileStorageService`) | transcript, notes, generated artifacts |

Workspace is part of every tool call and every proposal. A task never switches
workspace mid-flight. Lecture workspace is disabled by default
(`app.file-agent.lecture-workspace-enabled`), so a lecture task cannot silently
reach project sources or vice versa.

Path enforcement (`WorkspacePathGuard`):

1. reject null, blank, NUL, absolute paths, drive letters (`C:`), UNC (`\\`, `//`);
2. reject `..`, normalize to `/`-separated POSIX relative paths;
3. reject paths deeper than `max-depth` and non-regular files;
4. reject symlinks in **any** component and require `toRealPath()` to stay under
   the workspace real root (also covers Windows junctions/reparse points);
5. for writes: recheck every component and the parent real path, never follow a
   link when creating the final file;
6. deny protected globs and binary/large files; enforce extension allow-list;
7. deterministic checks are repeated before every write.

## 4. Protected files

Read/write denylist (glob, case-insensitive), extendable by configuration:

```
**/.env, **/.env.*, **/*.key, **/*.pem, **/*.p12, **/*.jks, **/*.keystore,
**/id_rsa*, **/.git/**, **/.secrets/**, **/master.key, **/*credential*,
**/data/**, **/target/**, **/build/**, **/node_modules/**, **/*.db, **/*.sqlite*,
**/*.class, **/*.jar, **/*.docx, **/*.jpg, **/*.png, **/*.zip
```

`AGENTS.md` is readable as project documentation, **write-forbidden** for the
agent: security policy is not editable through the agent. `pom.xml`, `mvnw`,
`.github/**` and security configs are read-only for proposals in Day 34.

## 5. MCP tools and profile

New read-only tools (profile `file-agent-read`):

| Tool | Purpose |
| --- | --- |
| `search_project_files` | lexical file-name + content search with mask/limit |
| `search_code_symbols` | class/method declaration search with line ranges |
| `read_project_file` | bounded UTF-8 content with line numbers + SHA-256 |
| `get_file_metadata` | size, type, mtime, hash, workspace |
| `search_project_documentation` | existing documentation RAG (`doc_chunk`) |

`McpTool` gained a `profile()` default (`public`). `McpProtocolHandler` filters
`tools/list`/`tools/call` by the requested profile (`X-MCP-Profile` header).
The public `/mcp` surface therefore never sees file tools. `file-agent-read`
requests additionally require `app.file-agent.mcp-read-key` via `X-MCP-Key`;
when the key is unset the profile is unavailable to external clients. The
in-process orchestrator calls the same tools through `FileAgentToolExecutor`
with an explicit allow-list.

There is **no write tool** on the MCP surface. `propose_file_changes` is internal
to the orchestrator and produces an immutable proposal; applying requires a
human approval through the protected UI/API.

## 6. Proposal lifecycle

`CREATED → PLANNING → SEARCHING → ANALYZING → PROPOSING → AWAITING_APPROVAL`
then `APPROVED → APPLYING → VERIFYING → COMPLETED`, or `REJECTED`, `FAILED`,
`CANCELLED`, `CONFLICT`.

A proposal stores the task, workspace, goal, relevant files, plan, model/provider
snapshot, unified diff and per-file `CREATE`/`MODIFY` changes with the original
SHA-256 (null for `CREATE`). Approval is bound to the proposal id and version.
Regeneration creates a new version and invalidates the old approval. A proposal
can be applied at most once (`AWAITING_APPROVAL/APPROVED → APPLYING` is a
one-way state transition).

## 7. Patch validation

Before persisting a proposal:

- path validation for every target, workspace unchanged;
- `CREATE` target must not exist; `MODIFY` target must exist and be readable;
- original SHA-256 must equal the bytes the model was shown;
- content must be valid UTF-8 text, no NUL, within `max-file-bytes` and
  `max-patch-bytes`;
- protected / read-only files are rejected;
- new content must differ from the original and produce a non-empty diff;
- the unified diff is generated by Java from the validated old/new content — the
  model never supplies executable commands or patch commands.

## 8. Apply, conflict detection, rollback

1. transition to `APPLYING` (single writer);
2. re-run path validation for all targets;
3. compare current file hash with the stored original hash (or non-existence for
   `CREATE`); mismatch → `CONFLICT`, nothing is written;
4. copy current files to `<storage>/.file-agent/backups/<proposal>/…`;
5. write each file via a temp file in the same directory + `ATOMIC_MOVE`
   (fallback `REPLACE_EXISTING`), best-effort across multiple files;
6. on any error restore backups (or delete created files), record
   `rollback_attempts`, mark `FAILED`;
7. verify hashes and Markdown structure, persist the verification report;
8. `COMPLETED`.

Multi-file atomicity is best-effort and explicitly documented: Windows has no
cross-file transaction. The backup + restore path is always attempted and the
failure is reported, never hidden.

## 9. Verification profiles

Only predefined verifications run; no shell command is generated by the LLM.

- `MARKDOWN`: non-empty UTF-8, balanced code fences, ADR heading for ADR paths;
- `TEXT`: non-empty, no NUL;
- `JAVA`/`CONFIG`: UTF-8 + protected-path invariants (no compilation is started
  automatically from an agent proposal; CI/Maven remains the gate).

## 10. Limits and observability

Per task: max steps, tool calls, files searched/read, bytes per file, context
characters, proposal bytes, wall-clock timeout, bounded retries and cooperative
cancellation. On limit breach the task finishes with a clear status/message.

Persisted metrics: planning/duration, tool calls/failures, files
searched/read/proposed/changed, input/output tokens, provider/model, estimated
cloud cost, approval/rejection rate, conflicts, rollback attempts, verification
status. Existing `CloudCostService` is reused; no new monitoring stack.

## 11. Test strategy

- unit: path guard (traversal, absolute, UNC, symlink/junction, protected, size
  and count limits), tools, proposal validation, diff, rollback;
- integration: real SQLite (Hikari pool=1), real MCP tools, fake LLM provider —
  orchestration, approval, conflict, duplicate apply, cancellation;
- web: MockMvc for `/file-agent` and `/api/file-agent` (CSRF, admin auth, XSS
  escaping by Thymeleaf, profile MCP isolation);
- regression: existing suite + `./mvnw verify`;
- real E2E: Ollama `qwen3:1.7b` with a temporary project workspace; two scenarios
  (documentation update, ADR creation) with real disk writes and verification.
