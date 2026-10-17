package com.lectureprocessor.fileagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.fileagent.model.FileAgentTask;
import com.lectureprocessor.fileagent.model.FileChangeProposal;
import com.lectureprocessor.fileagent.model.ProposedFileChange;
import com.lectureprocessor.fileagent.model.ValidatedChange;
import com.lectureprocessor.fileagent.repository.FileAgentTaskRepository;
import com.lectureprocessor.fileagent.repository.FileChangeProposalRepository;
import com.lectureprocessor.fileagent.repository.ProposedFileChangeRepository;
import com.lectureprocessor.fileagent.workspace.FileContent;
import com.lectureprocessor.fileagent.workspace.FileWorkspaceRef;
import com.lectureprocessor.fileagent.workspace.FileWorkspaceResolver;
import com.lectureprocessor.fileagent.workspace.ResolvedFile;
import com.lectureprocessor.fileagent.workspace.WorkspaceFileService;
import com.lectureprocessor.fileagent.workspace.WorkspacePathGuard;
import com.lectureprocessor.storage.FileStorageService;

/**
 * Applies an approved proposal with revalidation, backups, best-effort rollback
 * and post-write verification. This is the only component that writes file
 * content for the file agent.
 */
@Service
public class FileChangeApplicationService {

    private static final Logger log = LoggerFactory.getLogger(FileChangeApplicationService.class);

    private final FileChangeProposalRepository proposals;
    private final ProposedFileChangeRepository changes;
    private final FileAgentTaskRepository tasks;
    private final FileWorkspaceResolver resolver;
    private final WorkspacePathGuard guard;
    private final WorkspaceFileService files;
    private final FileAgentVerificationService verification;
    private final FileStorageService storage;

    public FileChangeApplicationService(FileChangeProposalRepository proposals,
            ProposedFileChangeRepository changes, FileAgentTaskRepository tasks,
            FileWorkspaceResolver resolver, WorkspacePathGuard guard, WorkspaceFileService files,
            FileAgentVerificationService verification, FileStorageService storage) {
        this.proposals = proposals;
        this.changes = changes;
        this.tasks = tasks;
        this.resolver = resolver;
        this.guard = guard;
        this.files = files;
        this.verification = verification;
        this.storage = storage;
    }

    /** Revalidates and writes an APPROVED proposal. Never called by the LLM. */
    public ApplyResult apply(long proposalId) {
        FileChangeProposal proposal = proposals.findById(proposalId)
                .orElseThrow(() -> new FileAgentException("Proposal не найден: " + proposalId));
        if (!proposals.transition(proposalId, ProposalStatus.APPROVED, ProposalStatus.APPLYING, null)) {
            throw new FileAgentException("Proposal не находится в статусе APPROVED (повторное применение запрещено)");
        }
        FileAgentTask task = tasks.findById(proposal.taskId())
                .orElseThrow(() -> new FileAgentException("Task не найден: " + proposal.taskId()));
        tasks.update(task.withStatus(FileAgentTaskStatus.APPLYING, Instant.now()));

        FileWorkspaceRef workspace = resolver.resolve(proposal.workspace(), proposal.lectureId());
        List<ProposedFileChange> entries = changes.findByProposal(proposalId);
        if (entries.isEmpty()) {
            return fail(proposal, task, null, "Proposal не содержит изменений", 0);
        }
        List<ResolvedFile> resolved = new ArrayList<>();
        for (ProposedFileChange change : entries) {
            try {
                ResolvedFile target = guard.resolveWrite(workspace, change.path());
                if (change.type() == FileChangeType.MODIFY) {
                    FileContent current = files.readFull(workspace, change.path());
                    if (change.originalHash() == null || !change.originalHash().equals(current.sha256())) {
                        proposals.updateStatus(proposalId, ProposalStatus.CONFLICT,
                                "Файл изменился после подготовки diff: " + change.path(),
                                null, null, null, null, null);
                        tasks.update(task.withStatus(FileAgentTaskStatus.CONFLICT, Instant.now()));
                        return new ApplyResult(proposalId, ProposalStatus.CONFLICT, false,
                                "CONFLICT: " + change.path(), List.of());
                    }
                } else if (Files.exists(target.absolutePath())) {
                    proposals.updateStatus(proposalId, ProposalStatus.CONFLICT,
                            "CREATE target уже существует: " + change.path(), null, null, null, null, null);
                    tasks.update(task.withStatus(FileAgentTaskStatus.CONFLICT, Instant.now()));
                    return new ApplyResult(proposalId, ProposalStatus.CONFLICT, false,
                            "CONFLICT: " + change.path(), List.of());
                }
                resolved.add(target);
            } catch (RuntimeException e) {
                return fail(proposal, task, null, "Ошибка валидации пути: " + e.getMessage(), 0);
            }
        }

        Path backupDir = storage.root().resolve(".file-agent").resolve("backups")
                .resolve(proposal.taskId()).resolve(String.valueOf(proposalId));
        int rollbacks = 0;
        try {
            Files.createDirectories(backupDir);
            for (int i = 0; i < entries.size(); i++) {
                ProposedFileChange change = entries.get(i);
                Path target = resolved.get(i).absolutePath();
                if (change.type() == FileChangeType.MODIFY) {
                    Path backup = backupDir.resolve(change.path());
                    Files.createDirectories(backup.getParent());
                    Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            List<Path> written = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                ProposedFileChange change = entries.get(i);
                Path target = resolved.get(i).absolutePath();
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                atomicWrite(target, change.newContent());
                written.add(target);
            }
            FileAgentTask applying = tasks.findById(proposal.taskId()).orElse(task)
                    .withStatus(FileAgentTaskStatus.VERIFYING, Instant.now());
            tasks.update(applying);
            proposals.updateStatus(proposalId, ProposalStatus.VERIFYING, null, backupDir.toString(),
                    proposal.approvedBy(), null, null, null);

            List<ValidatedChange> validated = new ArrayList<>();
            for (ProposedFileChange change : entries) {
                validated.add(new ValidatedChange(change.path(), change.type(),
                        change.originalHash(), change.newHash(), change.originalContent(),
                        change.newContent(), change.reason()));
            }
            FileAgentVerificationService.VerificationResult result = verification.verify(workspace,
                    validated, verificationProfile(entries));
            if (!result.ok()) {
                rollbacks = rollback(entries, resolved, backupDir);
                proposals.updateStatus(proposalId, ProposalStatus.FAILED, result.text(),
                        backupDir.toString(), proposal.approvedBy(), result.text(), null, null);
                TaskFailed failed = failedTask(applying, "Верификация не пройдена: " + result.text(),
                        result.text(), rollbacks);
                tasks.update(failed.task());
                return new ApplyResult(proposalId, ProposalStatus.FAILED, false,
                        "Верификация не пройдена: " + result.text(), result.details());
            }
            Instant now = Instant.now();
            proposals.updateStatus(proposalId, ProposalStatus.COMPLETED, null, backupDir.toString(),
                    proposal.approvedBy(), result.text(), null, now);
            FileAgentTask completed = applying;
            completed = new FileAgentTask(completed.id(), completed.workspace(), completed.lectureId(),
                    completed.goal(), FileAgentTaskStatus.COMPLETED, completed.plan(),
                    completed.relevantFiles(), completed.provider(), completed.model(), null,
                    "Изменения применены и проверены", completed.filesSearched(), completed.filesRead(),
                    completed.filesProposed(), entries.size(), completed.toolCalls(),
                    completed.toolFailures(), completed.planningMs(), completed.durationMs(),
                    completed.inputTokens(), completed.outputTokens(), completed.estimatedCost(),
                    completed.costCurrency(), completed.rollbackAttempts(), result.text(),
                    completed.createdAt(), now, now);
            tasks.update(completed);
            return new ApplyResult(proposalId, ProposalStatus.COMPLETED, true,
                    "Изменения применены: " + entries.size() + " файл(ов)", result.details());
        } catch (IOException | RuntimeException e) {
            log.warn("File agent apply failed for proposal {}: {}", proposalId, e.getMessage());
            rollbacks = rollback(entries, resolved, backupDir);
            return fail(proposal, task, backupDir, "Ошибка применения: " + e.getMessage(), rollbacks);
        }
    }

    public void reject(long proposalId, String reason) {
        FileChangeProposal proposal = proposals.findById(proposalId)
                .orElseThrow(() -> new FileAgentException("Proposal не найден: " + proposalId));
        if (!proposals.transition(proposalId, ProposalStatus.AWAITING_APPROVAL, ProposalStatus.REJECTED,
                reason)) {
            throw new FileAgentException("Отклонить можно только предложение, ожидающее подтверждения");
        }
        tasks.findById(proposal.taskId()).ifPresent(task -> {
            FileAgentTask rejected = new FileAgentTask(task.id(), task.workspace(), task.lectureId(),
                    task.goal(), FileAgentTaskStatus.REJECTED, task.plan(), task.relevantFiles(),
                    task.provider(), task.model(), null, "Предложение отклонено пользователем",
                    task.filesSearched(), task.filesRead(), task.filesProposed(), 0, task.toolCalls(),
                    task.toolFailures(), task.planningMs(), task.durationMs(), task.inputTokens(),
                    task.outputTokens(), task.estimatedCost(), task.costCurrency(),
                    task.rollbackAttempts(), task.verificationStatus(), task.createdAt(),
                    Instant.now(), Instant.now());
            tasks.update(rejected);
        });
    }

    private ApplyResult fail(FileChangeProposal proposal, FileAgentTask task, Path backupDir,
            String error, int rollbacks) {
        proposals.updateStatus(proposal.id(), ProposalStatus.FAILED, error,
                backupDir == null ? null : backupDir.toString(), proposal.approvedBy(), null, null, null);
        tasks.update(failedTask(task, error, task.verificationStatus(), rollbacks).task());
        return new ApplyResult(proposal.id(), ProposalStatus.FAILED, false, error, List.of());
    }

    private static TaskFailed failedTask(FileAgentTask task, String error, String verification,
            int rollbacks) {
        FileAgentTask failed = new FileAgentTask(task.id(), task.workspace(), task.lectureId(),
                task.goal(), FileAgentTaskStatus.FAILED, task.plan(), task.relevantFiles(),
                task.provider(), task.model(), error, task.resultSummary(), task.filesSearched(),
                task.filesRead(), task.filesProposed(), task.filesChanged(), task.toolCalls(),
                task.toolFailures(), task.planningMs(), task.durationMs(), task.inputTokens(),
                task.outputTokens(), task.estimatedCost(), task.costCurrency(),
                task.rollbackAttempts() + rollbacks, verification, task.createdAt(),
                Instant.now(), Instant.now());
        return new TaskFailed(failed);
    }

    private record TaskFailed(FileAgentTask task) {
    }

    private int rollback(List<ProposedFileChange> entries, List<ResolvedFile> resolved, Path backupDir) {
        int attempts = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            ProposedFileChange change = entries.get(i);
            if (i >= resolved.size()) {
                continue;
            }
            Path target = resolved.get(i).absolutePath();
            attempts++;
            try {
                if (change.type() == FileChangeType.CREATE) {
                    Files.deleteIfExists(target);
                } else {
                    Path backup = backupDir.resolve(change.path());
                    if (Files.exists(backup)) {
                        atomicWrite(target, Files.readString(backup, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException | RuntimeException e) {
                log.warn("Rollback failed for {}: {}", change.path(), e.getMessage());
            }
        }
        return attempts;
    }

    private static void atomicWrite(Path target, String content) throws IOException {
        Path parent = target.getParent();
        Path temp = Files.createTempFile(parent, ".file-agent-", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String verificationProfile(List<ProposedFileChange> entries) {
        boolean java = entries.stream().anyMatch(entry -> entry.path().endsWith(".java"));
        if (java) {
            return "JAVA";
        }
        boolean markdown = entries.stream().allMatch(entry -> entry.path().endsWith(".md"));
        return markdown ? "MARKDOWN" : "TEXT";
    }

    /** Result of an apply attempt. */
    public record ApplyResult(long proposalId, ProposalStatus status, boolean applied, String message,
            List<String> details) {
    }
}
