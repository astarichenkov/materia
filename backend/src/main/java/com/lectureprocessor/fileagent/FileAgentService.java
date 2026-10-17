package com.lectureprocessor.fileagent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import com.lectureprocessor.fileagent.model.FileAgentExecution;
import com.lectureprocessor.fileagent.model.FileAgentTask;
import com.lectureprocessor.fileagent.model.FileAgentToolCall;
import com.lectureprocessor.fileagent.model.FileChangeProposal;
import com.lectureprocessor.fileagent.model.ProposedFileChange;
import com.lectureprocessor.fileagent.repository.FileAgentExecutionRepository;
import com.lectureprocessor.fileagent.repository.FileAgentTaskRepository;
import com.lectureprocessor.fileagent.repository.FileAgentToolCallRepository;
import com.lectureprocessor.fileagent.repository.FileChangeProposalRepository;
import com.lectureprocessor.fileagent.repository.ProposedFileChangeRepository;
import com.lectureprocessor.fileagent.workspace.FileWorkspaceRef;
import com.lectureprocessor.fileagent.workspace.FileWorkspaceResolver;
import com.lectureprocessor.process.CancellationToken;

/**
 * Application facade for the Day 34 file agent: task creation, bounded async
 * execution, approval/rejection, regeneration, cancellation and reporting.
 */
@Service
public class FileAgentService {

    private final FileAgentProperties properties;
    private final FileAgentOrchestrator orchestrator;
    private final FileWorkspaceResolver resolver;
    private final FileAgentTaskRepository tasks;
    private final FileAgentExecutionRepository executions;
    private final FileAgentToolCallRepository toolCalls;
    private final FileChangeProposalRepository proposals;
    private final ProposedFileChangeRepository changes;
    private final FileChangeApplicationService application;
    private final FileAgentToolExecutor tools;
    private final FileAgentMetricsService metrics;
    private final ThreadPoolTaskExecutor executor;
    private final ConcurrentMap<String, CancellationToken> running = new ConcurrentHashMap<>();

    public FileAgentService(FileAgentProperties properties, FileAgentOrchestrator orchestrator,
            FileWorkspaceResolver resolver, FileAgentTaskRepository tasks,
            FileAgentExecutionRepository executions, FileAgentToolCallRepository toolCalls,
            FileChangeProposalRepository proposals, ProposedFileChangeRepository changes,
            FileChangeApplicationService application, FileAgentToolExecutor tools,
            FileAgentMetricsService metrics,
            @Qualifier("fileAgentExecutor") ThreadPoolTaskExecutor executor) {
        this.properties = properties;
        this.orchestrator = orchestrator;
        this.resolver = resolver;
        this.tasks = tasks;
        this.executions = executions;
        this.toolCalls = toolCalls;
        this.proposals = proposals;
        this.changes = changes;
        this.application = application;
        this.tools = tools;
        this.metrics = metrics;
        this.executor = executor;
    }

    public FileAgentTask createAndStart(FileWorkspace workspace, String lectureId, String goal) {
        FileAgentTask task = create(workspace, lectureId, goal);
        start(task.id());
        return task;
    }

    public FileAgentTask create(FileWorkspace workspace, String lectureId, String goal) {
        requireEnabled();
        String validatedGoal = validateGoal(goal);
        FileWorkspaceRef ref = resolver.resolve(workspace, lectureId);
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        FileAgentTask task = new FileAgentTask(id, workspace,
                workspace == FileWorkspace.LECTURE ? ref.lectureId() : null, validatedGoal,
                FileAgentTaskStatus.CREATED, null, List.of(), null, null, null, null,
                0, 0, 0, 0, 0, 0, 0, 0, null, null, null, null, 0, null, now, now, null);
        tasks.insert(task);
        executions.insert(new FileAgentExecution(null, id, "CREATED", "created",
                "Задача создана", now, now, 0));
        return task;
    }

    public void start(String taskId) {
        FileAgentTask task = tasks.findById(taskId)
                .orElseThrow(() -> new FileAgentException("Task не найден: " + taskId));
        if (task.status() != FileAgentTaskStatus.CREATED) {
            throw new FileAgentException("Запустить можно только новую задачу (CREATED)");
        }
        CancellationToken token = new CancellationToken();
        running.put(taskId, token);
        try {
            executor.execute(() -> {
                try {
                    orchestrator.run(taskId, token);
                } finally {
                    running.remove(taskId);
                }
            });
        } catch (TaskRejectedException e) {
            running.remove(taskId);
            throw new FileAgentException("Очередь File Agent переполнена, попробуйте позже");
        }
    }

    public void cancel(String taskId) {
        CancellationToken token = running.get(taskId);
        if (token != null) {
            token.cancel();
            return;
        }
        FileAgentTask task = get(taskId);
        if (!task.status().terminal()) {
            FileAgentTask cancelled = task.withStatus(FileAgentTaskStatus.CANCELLED, Instant.now());
            cancelled = new FileAgentTask(cancelled.id(), cancelled.workspace(), cancelled.lectureId(),
                    cancelled.goal(), cancelled.status(), cancelled.plan(), cancelled.relevantFiles(),
                    cancelled.provider(), cancelled.model(), "Отменено пользователем",
                    cancelled.resultSummary(), cancelled.filesSearched(), cancelled.filesRead(),
                    cancelled.filesProposed(), cancelled.filesChanged(), cancelled.toolCalls(),
                    cancelled.toolFailures(), cancelled.planningMs(), cancelled.durationMs(),
                    cancelled.inputTokens(), cancelled.outputTokens(), cancelled.estimatedCost(),
                    cancelled.costCurrency(), cancelled.rollbackAttempts(),
                    cancelled.verificationStatus(), cancelled.createdAt(), Instant.now(), Instant.now());
            tasks.update(cancelled);
        }
    }

    public FileChangeApplicationService.ApplyResult approveAndApply(long proposalId, String approvedBy) {
        FileChangeProposal proposal = proposals.findById(proposalId)
                .orElseThrow(() -> new FileAgentException("Proposal не найден: " + proposalId));
        if (proposal.status() != ProposalStatus.AWAITING_APPROVAL) {
            throw new FileAgentException("Подтвердить можно только предложение AWAITING_APPROVAL");
        }
        if (!proposals.transition(proposalId, ProposalStatus.AWAITING_APPROVAL,
                ProposalStatus.APPROVED, null)) {
            throw new FileAgentException("Proposal уже подтверждён или изменён");
        }
        proposals.updateStatus(proposalId, ProposalStatus.APPROVED, null, null,
                approvedBy == null || approvedBy.isBlank() ? "admin" : approvedBy, null,
                Instant.now(), null);
        tasks.findById(proposal.taskId()).ifPresent(task ->
                tasks.update(task.withStatus(FileAgentTaskStatus.APPROVED, Instant.now())));
        return application.apply(proposalId);
    }

    public void reject(long proposalId, String reason) {
        application.reject(proposalId, reason == null || reason.isBlank()
                ? "Отклонено пользователем" : reason);
    }

    /** Creates a new task with the same goal; old pending proposals are invalidated. */
    public FileAgentTask regenerate(String taskId, String reason) {
        FileAgentTask previous = get(taskId);
        for (FileChangeProposal proposal : proposals.findByTask(taskId)) {
            if (proposal.status() == ProposalStatus.AWAITING_APPROVAL) {
                proposals.updateStatus(proposal.id(), ProposalStatus.REJECTED,
                        reason == null ? "Перегенерировано" : reason, null, null, null, null, null);
            }
        }
        FileAgentTask next = create(previous.workspace(), previous.lectureId(), previous.goal());
        start(next.id());
        return next;
    }

    public FileAgentTask get(String taskId) {
        return tasks.findById(taskId)
                .orElseThrow(() -> new FileAgentException("Task не найден: " + taskId));
    }

    public List<FileAgentTask> list(int limit) {
        return tasks.list(limit);
    }

    public List<FileAgentExecution> executions(String taskId) {
        return executions.findByTask(taskId);
    }

    public List<FileAgentToolCall> toolTrace(String taskId) {
        return toolCalls.findByTask(taskId);
    }

    public List<FileChangeProposal> proposals(String taskId) {
        return proposals.findByTask(taskId);
    }

    public List<ProposedFileChange> changes(long proposalId) {
        return changes.findByProposal(proposalId);
    }

    public FileChangeProposal latestProposal(String taskId) {
        return proposals.latestByTask(taskId)
                .orElseThrow(() -> new FileAgentException("У задачи нет предложений"));
    }

    public FileChangeProposal proposal(long proposalId) {
        return proposals.findById(proposalId)
                .orElseThrow(() -> new FileAgentException("Proposal не найден: " + proposalId));
    }

    public FileAgentMetrics metrics() {
        return metrics.metrics();
    }

    public List<ToolView> tools() {
        return tools.allowedTools().stream()
                .map(name -> new ToolView(name, name))
                .toList();
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", properties.enabled());
        status.put("profile", properties.profile());
        status.put("tools", tools.allowedTools());
        status.put("lectureWorkspaceEnabled", properties.lectureWorkspaceEnabled());
        status.put("maxSteps", properties.maxSteps());
        status.put("maxToolCalls", properties.maxToolCalls());
        status.put("timeout", properties.timeout().toString());
        status.put("externalMcpProfile", properties.mcpReadKey() != null
                && !properties.mcpReadKey().isBlank() ? "key-protected" : "disabled");
        return status;
    }

    private String validateGoal(String goal) {
        if (goal == null || goal.isBlank()) {
            throw new FileAgentException("Цель не может быть пустой");
        }
        String value = goal.strip();
        if (value.length() > Math.max(100, properties.goalChars())) {
            throw new FileAgentException("Цель слишком длинная (максимум " + properties.goalChars()
                    + " символов)");
        }
        return value;
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new FileAgentException("File Agent отключён (app.file-agent.enabled=false)");
        }
    }

    /** Tool description for the UI. */
    public record ToolView(String name, String description) {
    }
}
