package com.lectureprocessor.fileagent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.fileagent.model.ChangeProposalDraft;
import com.lectureprocessor.fileagent.model.FileAgentContext;
import com.lectureprocessor.fileagent.model.FileAgentExecution;
import com.lectureprocessor.fileagent.model.FileAgentPlan;
import com.lectureprocessor.fileagent.model.FileAgentTask;
import com.lectureprocessor.fileagent.model.FileChangeProposal;
import com.lectureprocessor.fileagent.model.ProposedFileChange;
import com.lectureprocessor.fileagent.model.ValidatedChange;
import com.lectureprocessor.fileagent.model.ValidatedProposal;
import com.lectureprocessor.fileagent.repository.FileAgentExecutionRepository;
import com.lectureprocessor.fileagent.repository.FileAgentTaskRepository;
import com.lectureprocessor.fileagent.repository.FileChangeProposalRepository;
import com.lectureprocessor.fileagent.repository.ProposedFileChangeRepository;
import com.lectureprocessor.fileagent.workspace.FileContent;
import com.lectureprocessor.fileagent.workspace.FileSearchHit;
import com.lectureprocessor.fileagent.workspace.FileWorkspaceRef;
import com.lectureprocessor.fileagent.workspace.FileWorkspaceResolver;
import com.lectureprocessor.fileagent.workspace.WorkspaceFileService;
import com.lectureprocessor.llm.CloudCostService;
import com.lectureprocessor.llm.LlmProvider;
import com.lectureprocessor.llm.LlmResult;
import com.lectureprocessor.llm.LlmTaskResolver;
import com.lectureprocessor.llm.LlmTaskType;
import com.lectureprocessor.llm.ResolvedLlmTask;
import com.lectureprocessor.process.CancellationToken;
import com.lectureprocessor.text.PromptTemplates;

/**
 * Deterministic orchestration of one file-agent task. The model plans which
 * candidates to read and proposes content; every security-relevant decision is
 * made here or in {@link FileAgentPatchValidator}.
 */
@Service
public class FileAgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(FileAgentOrchestrator.class);

    private final FileAgentProperties properties;
    private final FileAgentTaskRepository tasks;
    private final FileAgentExecutionRepository executions;
    private final FileAgentToolExecutor tools;
    private final FileWorkspaceResolver resolver;
    private final WorkspaceFileService files;
    private final FileAgentPlanner planner;
    private final FileAgentContextBuilder contextBuilder;
    private final FileAgentProposalParser proposalParser;
    private final FileAgentPatchValidator patchValidator;
    private final FileAgentResultValidator resultValidator;
    private final FileChangeProposalRepository proposals;
    private final ProposedFileChangeRepository changes;
    private final LlmTaskResolver taskResolver;
    private final PromptTemplates prompts;
    private final CloudCostService costs;

    public FileAgentOrchestrator(FileAgentProperties properties, FileAgentTaskRepository tasks,
            FileAgentExecutionRepository executions, FileAgentToolExecutor tools,
            FileWorkspaceResolver resolver, WorkspaceFileService files, FileAgentPlanner planner,
            FileAgentContextBuilder contextBuilder, FileAgentProposalParser proposalParser,
            FileAgentPatchValidator patchValidator, FileAgentResultValidator resultValidator,
            FileChangeProposalRepository proposals, ProposedFileChangeRepository changes,
            LlmTaskResolver taskResolver, PromptTemplates prompts, CloudCostService costs) {
        this.properties = properties;
        this.tasks = tasks;
        this.executions = executions;
        this.tools = tools;
        this.resolver = resolver;
        this.files = files;
        this.planner = planner;
        this.contextBuilder = contextBuilder;
        this.proposalParser = proposalParser;
        this.patchValidator = patchValidator;
        this.resultValidator = resultValidator;
        this.proposals = proposals;
        this.changes = changes;
        this.taskResolver = taskResolver;
        this.prompts = prompts;
        this.costs = costs;
    }

    public void run(String taskId, CancellationToken cancellation) {
        RunState state = new RunState(taskId, cancellation);
        FileAgentTask task = tasks.findById(taskId)
                .orElseThrow(() -> new FileAgentException("Task не найден: " + taskId));
        state.start();
        try {
            execute(task, state);
        } catch (CancelledException e) {
            finish(taskId, state, FileAgentTaskStatus.CANCELLED, "Задача отменена пользователем", null);
        } catch (FileAgentException e) {
            log.debug("File agent task {} failed: {}", taskId, e.getMessage());
            finish(taskId, state, FileAgentTaskStatus.FAILED, e.getMessage(), null);
        } catch (RuntimeException e) {
            log.warn("File agent task {} failed unexpectedly", taskId, e);
            finish(taskId, state, FileAgentTaskStatus.FAILED,
                    "Внутренняя ошибка: " + e.getMessage(), null);
        }
    }

    private void execute(FileAgentTask initial, RunState state) {
        transition(initial, FileAgentTaskStatus.PLANNING, state);
        FileWorkspaceRef workspace = resolver.resolve(initial.workspace(), initial.lectureId());
        FileAgentPlan seed = planner.seed(initial.goal(), workspace);

        phase(state, "PLANNING", "started", "Определение scope и начального плана");
        transition(initial, FileAgentTaskStatus.SEARCHING, state);
        List<String> searchTexts = new ArrayList<>();
        List<String> symbolTexts = new ArrayList<>();
        List<String> docTexts = new ArrayList<>();
        Set<String> candidates = new LinkedHashSet<>();
        Map<String, Object> baseArgs = baseArguments(initial);

        for (String symbol : seed.symbols().stream().limit(3).toList()) {
            checkCancelled(state);
            Map<String, Object> args = new LinkedHashMap<>(baseArgs);
            args.put("symbol", symbol);
            args.put("limit", 10);
            var result = runTool(initial.id(), "search_code_symbols", args, state);
            symbolTexts.add(symbol + ":\n" + result.text());
            candidates.addAll(parsePaths(result.text(), true));
        }
        for (String query : seed.queries().stream().limit(3).toList()) {
            checkCancelled(state);
            Map<String, Object> args = new LinkedHashMap<>(baseArgs);
            args.put("query", query);
            args.put("limit", 15);
            var result = runTool(initial.id(), "search_project_files", args, state);
            searchTexts.add(query + ":\n" + result.text());
            candidates.addAll(parsePaths(result.text(), false));
        }
        Map<String, Object> docsArgs = new LinkedHashMap<>();
        docsArgs.put("query", initial.goal());
        docsArgs.put("top_k", 5);
        var docResult = runTool(initial.id(), "search_project_documentation", docsArgs, state);
        if (docResult.ok()) {
            docTexts.add(docResult.text());
        }
        if (candidates.size() < 3) {
            // Deterministic fallback candidate seed; reading still goes through tools.
            files.list(workspace, "", 120).stream()
                    .filter(path -> path.endsWith(".md") || path.endsWith(".java")
                            || path.endsWith(".sql") || path.endsWith(".yml"))
                    .limit(20)
                    .forEach(candidates::add);
        }
        List<FileSearchHit> hits = new ArrayList<>();
        int rank = 0;
        for (String path : candidates) {
            hits.add(new FileSearchHit(path, Math.max(0.1, 1.0 - rank++ * 0.02), null, 0));
        }

        checkCancelled(state);
        FileAgentPlanner.PlanResult planResult = planner.refine(initial.goal(), seed, hits, workspace);
        FileAgentPlan plan = ensureMinimumFiles(planResult.plan(), candidates);
        String provider = planResult.provider();
        String model = planResult.model();
        if (provider != null) {
            snapshotProvider(initial.id(), provider, model);
        }
        if (planResult.fallbackReason() != null) {
            log.debug("File agent plan fallback for {}: {}", initial.id(), planResult.fallbackReason());
        }

        transition(initial, FileAgentTaskStatus.ANALYZING, state);
        Map<String, FileContent> readFiles = new LinkedHashMap<>();
        for (String path : plan.readFiles().stream().limit(properties.maxFilesPerTask()).toList()) {
            checkCancelled(state);
            Map<String, Object> args = new LinkedHashMap<>(baseArgs);
            args.put("path", path);
            args.put("start_line", 1);
            args.put("max_lines", Math.max(100, properties.maxReadLines()));
            var result = runTool(initial.id(), "read_project_file", args, state);
            if (!result.ok()) {
                continue;
            }
            try {
                readFiles.put(path, files.readFull(workspace, path));
            } catch (FileAgentException e) {
                log.debug("Skipping unreadable candidate {}: {}", path, e.getMessage());
            }
        }
        if (readFiles.isEmpty()) {
            throw new FileAgentException("Агенту не удалось прочитать ни один файл по цели. "
                    + "Уточните цель или индексируйте документацию.");
        }
        // Additional RAG from the refined plan, bounded.
        for (String query : plan.queries().stream().skip(3).limit(2).toList()) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("query", query);
            args.put("top_k", 3);
            var result = runTool(initial.id(), "search_project_documentation", args, state);
            if (result.ok()) {
                docTexts.add(result.text());
            }
        }

        transition(initial, FileAgentTaskStatus.PROPOSING, state);
        state.planningMs = state.elapsedMs();
        phase(state, "PROPOSING", "started", "Генерация предложения изменений");
        FileAgentContext context = contextBuilder.build(initial.goal(), plan, searchTexts, symbolTexts,
                docTexts, readFiles);
        ResolvedLlmTask resolved = taskResolver.resolve(LlmTaskType.FILE_AGENT);
        if (resolved == null || !resolved.provider().isAvailable()) {
            throw new FileAgentException("LLM provider для File Agent недоступен");
        }
        provider = resolved.providerId();
        model = resolved.model();
        snapshotProvider(initial.id(), provider, model);
        LlmResult result = generate(context, resolved, state, null);
        ChangeProposalDraft draft;
        ValidatedProposal validated;
        try {
            draft = proposalParser.parse(result.content());
            validated = validate(workspace, draft, readFiles, plan);
        } catch (FileAgentException first) {
            phase(state, "PROPOSING", "retry", "Первое предложение отклонено: " + first.getMessage());
            LlmResult retry = generate(context, resolved, state, first.getMessage());
            result = merge(result, retry);
            draft = proposalParser.parse(retry.content());
            validated = validate(workspace, draft, readFiles, plan);
        }
        resultValidator.validate(draft, validated);

        FileChangeProposal proposal = persistProposal(initial, validated, plan, result, provider, model,
                state);
        FileAgentTask latest = tasks.findById(initial.id()).orElse(initial);
        FileAgentTask awaiting = new FileAgentTask(latest.id(), latest.workspace(), latest.lectureId(),
                latest.goal(), FileAgentTaskStatus.AWAITING_APPROVAL, plan.toText(),
                List.copyOf(readFiles.keySet()), provider, model, null,
                "Предложение готово к подтверждению", candidates.size(), readFiles.size(),
                validated.changes().size(), 0, state.toolCalls.get(), state.toolFailures.get(),
                state.planningMs, state.elapsedMs(), result.inputTokens(), result.outputTokens(),
                estimatedCost(result), currency(), latest.rollbackAttempts(), null,
                latest.createdAt(), Instant.now(), null);
        tasks.update(awaiting);
        phase(state, "PROPOSING", "completed",
                "Proposal #" + proposal.id() + " ожидает подтверждения");
    }

    private ValidatedProposal validate(FileWorkspaceRef workspace, ChangeProposalDraft draft,
            Map<String, FileContent> readFiles, FileAgentPlan plan) {
        return patchValidator.validate(workspace, draft, readFiles, plan.targetPaths());
    }

    private FileChangeProposal persistProposal(FileAgentTask task, ValidatedProposal validated,
            FileAgentPlan plan, LlmResult result, String provider, String model, RunState state) {
        int version = proposals.findByTask(task.id()).size() + 1;
        FileChangeProposal proposal = proposals.insert(new FileChangeProposal(null, task.id(), version,
                ProposalStatus.AWAITING_APPROVAL, task.workspace(), task.lectureId(),
                validated.summary(), validated.diff(), provider, model, null, null, null,
                String.join("\n", validated.notes()), Instant.now(), null, null));
        for (ValidatedChange change : validated.changes()) {
            changes.insert(proposal.id(), new ProposedFileChange(null, proposal.id(), change.path(),
                    change.type(), change.originalHash(), change.newHash(), change.originalContent(),
                    change.newContent(), change.reason()));
        }
        phase(state, "PROPOSAL", "persisted", "Сохранён proposal #" + proposal.id()
                + " (version " + version + ")");
        return proposal;
    }

    private LlmResult generate(FileAgentContext context, ResolvedLlmTask resolved, RunState state,
            String previousError) {
        checkCancelled(state);
        String system = prompts.get(PromptTemplates.FILE_AGENT_PROPOSAL);
        String user = context.promptText();
        if (previousError != null) {
            user += "\n\nПредыдущий ответ отклонён валидатором: " + previousError
                    + "\nВерни исправленный JSON строго по схеме.";
        }
        long timeoutMs = Math.max(1000, properties.timeout().toMillis());
        if (state.elapsedMs() > timeoutMs) {
            throw new FileAgentException("Превышен лимит времени выполнения задачи");
        }
        LlmProvider provider = resolved.provider();
        LlmResult result = provider.generate(system, user, resolved.options());
        if (result == null || result.content() == null || result.content().isBlank()) {
            throw new FileAgentException("Модель вернула пустое предложение");
        }
        if (result.content().length() > properties.maxPatchBytes()) {
            throw new FileAgentException("Предложение модели превышает лимит размера");
        }
        return result;
    }

    private static LlmResult merge(LlmResult first, LlmResult second) {
        return new LlmResult(second.content(), first.inputTokens() + second.inputTokens(),
                first.outputTokens() + second.outputTokens(),
                first.thinkingTokens() + second.thinkingTokens(),
                first.durationMs() + second.durationMs(), second.evalTokensPerSecond(),
                second.loadMs(), first.estimated() && second.estimated(), second.truncated(),
                first.cacheHitTokens() + second.cacheHitTokens(),
                first.cacheMissTokens() + second.cacheMissTokens(), second.cloud());
    }

    private FileAgentToolExecutor.ToolExecution runTool(String taskId, String toolName,
            Map<String, Object> arguments, RunState state) {
        checkCancelled(state);
        state.toolCalls.incrementAndGet();
        var result = tools.execute(taskId, toolName, arguments, state.toolBudget);
        if (!result.ok()) {
            state.toolFailures.incrementAndGet();
        }
        return result;
    }

    private FileAgentPlan ensureMinimumFiles(FileAgentPlan plan, Set<String> candidates) {
        List<String> selected = new ArrayList<>(plan.readFiles());
        for (String candidate : candidates) {
            if (selected.size() >= 3) {
                break;
            }
            if (!selected.contains(candidate)) {
                selected.add(candidate);
            }
        }
        return plan.withReadFiles(selected.stream().limit(properties.maxFilesPerTask()).toList());
    }

    private void snapshotProvider(String taskId, String provider, String model) {
        tasks.findById(taskId).ifPresent(task -> tasks.update(new FileAgentTask(task.id(),
                task.workspace(), task.lectureId(), task.goal(), task.status(), task.plan(),
                task.relevantFiles(), provider, model, task.error(), task.resultSummary(),
                task.filesSearched(), task.filesRead(), task.filesProposed(), task.filesChanged(),
                task.toolCalls(), task.toolFailures(), task.planningMs(), task.durationMs(),
                task.inputTokens(), task.outputTokens(), task.estimatedCost(), task.costCurrency(),
                task.rollbackAttempts(), task.verificationStatus(), task.createdAt(),
                Instant.now(), task.completedAt())));
    }

    private void transition(FileAgentTask task, FileAgentTaskStatus status, RunState state) {
        checkCancelled(state);
        if (state.steps.incrementAndGet() > Math.max(1, properties.maxSteps())) {
            throw new FileAgentException("Превышен лимит шагов агента (" + properties.maxSteps() + ")");
        }
        FileAgentTask current = tasks.findById(task.id()).orElse(task);
        tasks.update(current.withStatus(status, Instant.now()));
        phase(state, status.name(), "entered", null);
    }

    private void finish(String taskId, RunState state, FileAgentTaskStatus status, String message,
            String verification) {
        tasks.findById(taskId).ifPresent(task -> {
            FileAgentTask finished = new FileAgentTask(task.id(), task.workspace(), task.lectureId(),
                    task.goal(), status, task.plan(), task.relevantFiles(), task.provider(),
                    task.model(), status == FileAgentTaskStatus.COMPLETED ? null : message,
                    message, task.filesSearched(), task.filesRead(), task.filesProposed(),
                    task.filesChanged(), state.toolCalls.get(), state.toolFailures.get(),
                    state.planningMs, state.elapsedMs(), task.inputTokens(), task.outputTokens(),
                    task.estimatedCost(), task.costCurrency(), task.rollbackAttempts(),
                    verification == null ? task.verificationStatus() : verification,
                    task.createdAt(), Instant.now(),
                    status.terminal() ? Instant.now() : null);
            tasks.update(finished);
            phase(state, status.name(), "finished", message);
        });
    }

    private void phase(RunState state, String phase, String status, String detail) {
        if ("started".equals(status)) {
            state.phaseStarted = System.currentTimeMillis();
        }
        long duration = state.phaseStarted == 0 ? 0
                : System.currentTimeMillis() - state.phaseStarted;
        executions.insert(new FileAgentExecution(null, state.taskId, phase, status, detail,
                Instant.now(), Instant.now(), duration));
        if ("completed".equals(status) || "finished".equals(status)) {
            state.phaseStarted = System.currentTimeMillis();
        }
    }

    private Map<String, Object> baseArguments(FileAgentTask task) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("workspace", task.workspace().name());
        if (task.lectureId() != null) {
            args.put("lecture_id", task.lectureId());
        }
        return args;
    }

    private static List<String> parsePaths(String text, boolean symbolFormat) {
        List<String> paths = new ArrayList<>();
        if (text == null) {
            return paths;
        }
        for (String line : text.split("\n")) {
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("(") || stripped.startsWith("…")) {
                continue;
            }
            String path = stripped;
            int tab = path.indexOf('\t');
            if (tab > 0) {
                path = path.substring(0, tab);
            }
            if (symbolFormat) {
                int colon = path.indexOf(':');
                if (colon > 0) {
                    path = path.substring(0, colon);
                }
            }
            if (path.contains("/") || path.endsWith(".md") || path.endsWith(".java")
                    || path.endsWith(".sql") || path.endsWith(".yml")) {
                paths.add(path);
            }
        }
        return paths;
    }

    private void checkCancelled(RunState state) {
        if (state.cancellation.isCancelled()) {
            throw new CancelledException();
        }
        if (state.elapsedMs() > Math.max(1000, properties.timeout().toMillis())) {
            throw new FileAgentException("Превышен лимит времени выполнения ("
                    + properties.timeout().toSeconds() + " c)");
        }
    }

    private Double estimatedCost(LlmResult result) {
        if (!result.cloud()) {
            return null;
        }
        var estimate = costs.estimate(result.inputTokens(), result.outputTokens(),
                result.cacheHitTokens(), result.cacheMissTokens());
        return estimate.priced() ? estimate.amount() : null;
    }

    private String currency() {
        return costs.currency();
    }

    private static final class RunState {
        private final String taskId;
        private final CancellationToken cancellation;
        private final AtomicInteger toolBudget = new AtomicInteger();
        private final AtomicInteger toolCalls = new AtomicInteger();
        private final AtomicInteger toolFailures = new AtomicInteger();
        private final AtomicInteger steps = new AtomicInteger();
        private long startedAt;
        private long phaseStarted;
        private long planningMs;

        private RunState(String taskId, CancellationToken cancellation) {
            this.taskId = taskId;
            this.cancellation = cancellation;
        }

        private void start() {
            startedAt = System.currentTimeMillis();
            phaseStarted = startedAt;
        }

        private long elapsedMs() {
            return startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt;
        }
    }

    private static final class CancelledException extends RuntimeException {
    }
}
