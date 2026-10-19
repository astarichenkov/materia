# Week 7 / Day 34 — Autonomous File Agent: implementation report

## 1. Requirements

| Requirement | Result |
| --- | --- |
| UI `/file-agent` | ✅ Thymeleaf page with goal, workspace, history, plan, tool trace, diff, approval, verification |
| Natural-language goal | ✅ `POST /api/file-agent/tasks` and UI form |
| Agent chooses files itself | ✅ deterministic seed search + LLM plan over real candidates; no manual file list |
| Multi-file search/read | ✅ `search_project_files`, `search_code_symbols`, `search_project_documentation`, `read_project_file` |
| Code RAG / Documentation RAG | ✅ documentation RAG reused; local symbol/lexical code search (Gitea code RAG is commit-keyed and not used for working-tree tasks) |
| Analyse ≥3 files | ✅ E2E read 3 files in both scenarios; planner enforces minimum 3 when candidates exist |
| Change proposals | ✅ `file_change_proposal` + `proposed_file_change`, CREATE/MODIFY |
| Real diff | ✅ deterministic `UnifiedDiffBuilder` (Java), not model-supplied patches |
| Approval gate | ✅ CSRF + admin protected approve/reject; no writes before approval |
| Real disk changes | ✅ atomic temp-file write + `ATOMIC_MOVE`, E2E verified SHA-256 |
| Conflict detection | ✅ current SHA-256 vs original; `CONFLICT`, no overwrite |
| Safe rollback | ✅ backup + best-effort restore, `rollback_attempts`, verified by test |
| Protected workspaces | ✅ PROJECT / LECTURE isolation |
| No secrets / no shell | ✅ denylist, extension/size/depth limits; no shell execution from LLM |
| MCP profile isolation | ✅ `file-agent-read` profile; public `/mcp` cannot list/call file tools |
| DeepSeek/Ollama routing | ✅ independent `FILE_AGENT` task; provider/model snapshot per run |
| SQLite persistence | ✅ migration V7 |
| Tool call history | ✅ `file_agent_tool_call` + UI trace |
| Metrics | ✅ duration, tools, files, tokens, cost estimate, approvals, conflicts, rollbacks, verification |
| Docs scenario | ✅ E2E scenario 1 |
| ADR scenario | ✅ E2E scenario 2 |
| Real E2E | ✅ Ollama `qwen3:1.7b`, files on disk verified |
| Unit/integration tests | ✅ 351 backend tests (+28 private-ai-chat), 0 failures |
| `mvn verify` | ✅ BUILD SUCCESS |
| Demo scenario | ✅ `demos/day34/demo-scenario.md` |
| Pushed to Gitea | see Git section |

## 2. Architecture

```
User goal -> FileAgentService (async bounded executor)
          -> FileAgentOrchestrator
             -> seed search (symbols + lexical)
             -> FileAgentPlanner (LLM selects candidates; deterministic fallback)
             -> FileAgentToolExecutor (file-agent-read profile only)
             -> FileAgentContextBuilder (bounded, untrusted blocks)
             -> LLM proposal JSON
             -> FileAgentProposalParser (control-char repair)
             -> FileAgentPatchValidator (paths, hashes, sizes, protected files, diff)
             -> FileAgentResultValidator
             -> AWAITING_APPROVAL
          -> approval (UI/API, CSRF + admin)
          -> FileChangeApplicationService
             -> revalidate paths/hashes -> backups -> atomic write
             -> FileAgentVerificationService -> COMPLETED / FAILED (rollback) / CONFLICT
```

## 3. New classes (package `com.lectureprocessor.fileagent`)

- `FileAgentProperties`, `FileWorkspace`, `FileAgentTaskStatus`, `ProposalStatus`, `FileChangeType`
- workspace: `FileWorkspaceResolver`, `WorkspacePathGuard`, `WorkspaceFileService`,
  `FileWorkspaceRef`, `ResolvedFile`, `FileContent`, `FileMetadata`, `FileSearchHit`, `SymbolHit`
- tools (MCP `file-agent-read`): `SearchProjectFilesTool`, `SearchCodeSymbolsTool`,
  `ReadProjectFileTool`, `GetFileMetadataTool`, `SearchProjectDocumentationTool`
- orchestration: `FileAgentPlanner`, `FileAgentToolExecutor`, `FileAgentContextBuilder`,
  `FileAgentProposalParser`, `FileAgentPatchValidator`, `FileAgentResultValidator`,
  `FileAgentOrchestrator`, `FileAgentService`, `FileAgentRecoveryRunner`
- change lifecycle: `FileChangeApplicationService`, `FileAgentVerificationService`,
  `UnifiedDiffBuilder`, `FileAgentMetricsService`
- persistence models/repositories under `fileagent.model` / `fileagent.repository`
- web: `FileAgentWebController`, `FileAgentApiController`, `FileAgentExceptionHandler`

Reused: `LlmTaskSettingsService`, `LlmProviderRegistry`, `CloudCostService`,
`DocumentationRetrievalService`, `ProjectRootResolver`, `FileStorageService`,
`McpToolRegistry`, `McpProtocolHandler`, `AdminSecurityFilter`, `AdminAccessService`,
`SchemaMigrator`, bounded `ThreadPoolTaskExecutor`.

## 4. SQLite migration

`V7__day34_file_agent.sql` (additive, idempotent):

- `file_agent_task` — status, goal, workspace, plan, relevant files, provider/model,
  counters, timing, tokens, estimated cost, rollback/verification;
- `file_agent_execution` — observable phases;
- `file_agent_tool_call` — tool trace with redacted arguments;
- `file_change_proposal` — versioned immutable proposal, diff, approval/apply timestamps;
- `proposed_file_change` — per-file CREATE/MODIFY, original/new hash and content.

`SchemaMigratorTest` updated for V7 (migrations 2..7, 6 applied versions).

## 5. MCP tools and security

| Tool | Access | Output |
| --- | --- | --- |
| `search_project_files` | file-agent profile | path, score, size, match |
| `search_code_symbols` | file-agent profile | path:line range, kind, symbol |
| `read_project_file` | file-agent profile | numbered lines, SHA-256, truncation flag |
| `get_file_metadata` | file-agent profile | size, type, mtime, hash, flags |
| `search_project_documentation` | file-agent profile | docs RAG snippets |

`McpTool.profile()` defaults to `public`; `McpProtocolHandler` filters both
`tools/list` and `tools/call`. Public `/mcp` never sees or calls file tools.
External `file-agent-read` requires `FILE_AGENT_MCP_KEY` via `X-MCP-Key`; when
unset the profile is unavailable externally. There is no write MCP tool.

## 6. File security

- Two isolated roots: project and one lecture directory.
- Rejects: absolute/UNC/drive paths, `..`, NUL, `.git`/`.secrets`, unsupported
  extensions, oversized/binary/non-UTF-8 files, too deep paths.
- Symlink/junction protection: each existing component checked with
  `NOFOLLOW_LINKS`, canonical `toRealPath()` must stay under the real root; write
  validation repeats before applying.
- Denylist: `.env`, keys/PEM/JKS/P12, credentials, master key, data/target/build/
  node_modules, SQLite, archives, images, DOCX, JAR/class.
- `AGENTS.md`, Maven/CI config are readable but never writable.
- Limits: file bytes, files per task, search results, read lines, depth, steps,
  tool calls, context chars, patch bytes, proposals, goal length, wall timeout.
- No arbitrary shell command execution; only predefined verification profiles.

## 7. Approval, apply and rollback

- Proposal contains full old/new content and original SHA-256.
- Approve requires `AWAITING_APPROVAL`, admin auth and CSRF; transition is
  atomic (`WHERE status = ...`), so duplicate apply is impossible.
- Apply revalidates every path and hash; mismatch → `CONFLICT`, no writes.
- Backups under `<storage>/.file-agent/backups/<task>/<proposal>/`.
- Writes go to a temp file in the target directory then `ATOMIC_MOVE` (fallback
  replace); multi-file atomicity is documented as best-effort.
- Verification failures (hash, Markdown fences, ADR sections) trigger rollback.
- ADR post-processing is deterministic: duplicate H2 sections are removed,
  `Alternatives` and a `References` list of actually read files are appended.

## 8. LLM routing

`LlmTaskType.FILE_AGENT` (`file-agent`) is independent from lecture processing,
developer assistant, code review and support. Default: DeepSeek when a
credential is configured, otherwise Ollama. The task provider/model and tokens
are persisted on every run. Prompts: `file-agent-plan-v1`, `file-agent-proposal-v1`.

## 9. UI / API

- UI: `GET /file-agent` (also `?taskId=`), POST endpoints for create/cancel/
  regenerate/approve/reject.
- API: `POST /api/file-agent/tasks`; `GET /tasks`, `/tasks/{id}`,
  `/tasks/{id}/executions|trace|proposals`; `GET /proposals/{id}`,
  `/proposals/{id}/changes|diff`; `POST /tasks/{id}/cancel|regenerate`,
  `POST /proposals/{id}/approve|reject`; `GET /metrics|tools|status`.
- Protected by `AdminSecurityFilter` (loopback or admin token, CSRF for browser
  state-changing requests). Thymeleaf escapes goal/diff/tool output (XSS test).

## 10. Real E2E (factual)

Environment: Ollama `qwen3:1.7b`, `think=false`, project workspace
`C:/Projects/lecture-processor-e2e` (safe fixture, not the real docs), storage
`target/day34-e2e-storage`, port 18083.

### Scenario 1 — documentation update

- Goal: «Найди все места, где используется LlmTaskSettingsService, и обнови
  документацию о выборе моделей для разных задач.»
- Result: `AWAITING_APPROVAL → COMPLETED`; read 3 files
  (`LlmTaskSettingsService.java`, `README.md`, `docs/llm-routing.md`);
  7 tool calls, 0 failures; provider `ollama/qwen3:1.7b`; proposal #1.
- Diff: `--- a/docs/llm-routing.md` + new `## Service Overview` section.
- Approve: `applied=true`, `filesChanged=1`, SHA-256 verified on disk,
  `PASSED (MARKDOWN)`.
- Evidence: `target/day34-e2e-scenario1.log`, `.json`.

### Scenario 2 — ADR generation

- Goal: «Проанализируй архитектуру обработки лекций и создай ADR с описанием
  MediaSource, Resumable Pipeline и checkpoints.»
- Result: read `MediaSource.java`, existing `ADR-001-existing.md`,
  `PipelineCoordinator.java`; 11 tool calls; proposal #2 CREATE
  `docs/adr/ADR-002-ingestion-pipeline.md` (next number derived from the real
  directory).
- Approve: `applied=true`, file exists on disk, `PASSED (MARKDOWN)`.
- Evidence: `target/day34-e2e-scenario2.log`, `.json`.

Reproduction: `PYTHONIOENCODING=utf-8 python scripts/dev/day34-file-agent-e2e.py ... --approve`
(see `demos/day34/demo-scenario.md`).

## 11. Tests

- File security: traversal, absolute/UNC/drive, protected/secrets, size/binary/
  extension, symlink escape (skipped on Windows when symlink creation is denied),
  write-forbidden `AGENTS.md`.
- Tools: search by name/content, symbol ranges, bounded read/hash, metadata.
- Validator: valid MODIFY/CREATE + diff, path escape, protected, unread MODIFY,
  CREATE existing, plan target allow-list, ADR dedup.
- Parser: fenced JSON, raw newline repair, invalid/empty rejection.
- Orchestrator (real SQLite + real tools + fake LLM): proposal lifecycle,
  approval+write, duplicate apply, conflict, rejection, cancellation, LLM
  failure, regeneration, verification-failure rollback.
- Web/API: rendering, XSS escaping, CSRF required, approval/rejection, diff.
- MCP: public profile isolation, file-agent profile visibility.
- Regression: full suite.

`./mvnw verify` → **BUILD SUCCESS**; backend **351 tests** (0 failures,
1 skipped symlink on Windows), private-ai-chat **28 tests**, total **379**.

## 12. Limits

- Multi-file apply is not transactionally atomic on Windows; rollback is
  best-effort with diagnostics.
- No DELETE/MOVE in Day 34.
- Local `qwen3:1.7b` sometimes produces shallow/duplicated ADR text; duplicates
  are removed deterministically, but content quality is model-dependent.
- Code RAG (`code_chunk`) is keyed by Gitea commit; working-tree file-agent tasks
  use local symbol/lexical search plus docs RAG. This is intentional to avoid
  analysing a different commit than the files being changed.
- Lecture workspace DOCX modification is not automatic; Markdown is the safe
  editable artifact.
- External `file-agent-read` MCP is disabled unless `FILE_AGENT_MCP_KEY` is set.

## 13. Git

Branch: `feature/week7-day34-file-agent`.

Commits:
- `f3b0d0d` feat: add scoped file workspace access
- `24e00f9` feat: add read-only file MCP tools
- `e4ef809` feat: implement autonomous file agent orchestrator
- `5ec38b3` feat: add file change proposals and approval persistence
- `dc3f117` feat: add safe patch application and rollback
- `38c8ab0` feat: add file agent UI and protected API
- `0fa4685` test: cover file agent workflows and security boundaries
- `e61410c` docs: document day34 file agent and add e2e driver

`docs/` and `AGENTS.md` remain locally ignored per repository policy.

Push to Gitea `origin` after verification (no force push).
