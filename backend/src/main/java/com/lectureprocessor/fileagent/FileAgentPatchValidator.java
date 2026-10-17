package com.lectureprocessor.fileagent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.lectureprocessor.fileagent.diff.UnifiedDiffBuilder;
import com.lectureprocessor.fileagent.model.ChangeProposalDraft;
import com.lectureprocessor.fileagent.model.ProposedChangeDraft;
import com.lectureprocessor.fileagent.model.ValidatedChange;
import com.lectureprocessor.fileagent.model.ValidatedProposal;
import com.lectureprocessor.fileagent.workspace.FileContent;
import com.lectureprocessor.fileagent.workspace.FileWorkspaceRef;
import com.lectureprocessor.fileagent.workspace.ResolvedFile;
import com.lectureprocessor.fileagent.workspace.WorkspaceFileService;
import com.lectureprocessor.fileagent.workspace.WorkspacePathGuard;

/**
 * Deterministic validation of a model proposal. This is the security boundary:
 * no path, hash, size or change type is trusted from the model.
 */
@Service
public class FileAgentPatchValidator {

    private final WorkspacePathGuard guard;
    private final WorkspaceFileService files;
    private final FileAgentProperties properties;

    public FileAgentPatchValidator(WorkspacePathGuard guard, WorkspaceFileService files,
            FileAgentProperties properties) {
        this.guard = guard;
        this.files = files;
        this.properties = properties;
    }

    public ValidatedProposal validate(FileWorkspaceRef workspace, ChangeProposalDraft draft,
            Map<String, FileContent> readFiles, List<String> allowedTargets) {
        if (draft == null || draft.changes() == null || draft.changes().isEmpty()) {
            throw new FileAgentException("Предложение не содержит изменений");
        }
        if (draft.changes().size() > Math.max(1, properties.maxProposals())) {
            throw new FileAgentException("Слишком много изменений в предложении: "
                    + draft.changes().size() + " > " + properties.maxProposals());
        }
        Set<String> targets = new HashSet<>();
        if (allowedTargets != null) {
            for (String target : allowedTargets) {
                try {
                    targets.add(WorkspacePathGuard.normalize(target));
                } catch (FileAgentException ignored) {
                    // An invalid plan target is simply not allowed.
                }
            }
        }
        Set<String> seen = new HashSet<>();
        List<ValidatedChange> changes = new ArrayList<>();
        StringBuilder diff = new StringBuilder();
        long totalBytes = 0;
        boolean allMarkdown = true;
        boolean anyJava = false;

        for (ProposedChangeDraft candidate : draft.changes()) {
            if (candidate == null || candidate.content() == null) {
                throw new FileAgentException("Изменение без содержимого отклонено");
            }
            ResolvedFile target = guard.validatePath(workspace, candidate.path());
            String path = target.relativePath();
            if (!seen.add(path)) {
                throw new FileAgentException("Дублирующееся изменение файла: " + path);
            }
            FileChangeType type = parseType(candidate.action());
            if (!allowedByPlan(path, targets, type)) {
                throw new FileAgentException("Путь не входит в разрешённые цели плана: " + path);
            }
            if (guard.isWriteForbidden(path)) {
                throw new FileAgentException("Файл защищён от записи: " + path);
            }
            String content = candidate.content();
            if (isAdr(path)) {
                content = normalizeAdr(content, readFiles == null ? List.of()
                        : readFiles.keySet().stream().sorted().toList());
            }
            if (content.indexOf('\0') >= 0) {
                throw new FileAgentException("Содержимое содержит NUL-байты: " + path);
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > properties.maxFileBytes()) {
                throw new FileAgentException("Новое содержимое превышает лимит: " + path);
            }
            totalBytes += bytes.length;
            if (totalBytes > properties.maxPatchBytes()) {
                throw new FileAgentException("Общий размер предложения превышает лимит");
            }

            String oldContent = "";
            String originalHash = null;
            boolean exists = java.nio.file.Files.exists(target.absolutePath());
            if (type == FileChangeType.CREATE) {
                if (exists) {
                    throw new FileAgentException("CREATE для существующего файла: " + path);
                }
            } else {
                if (!exists) {
                    throw new FileAgentException("MODIFY для несуществующего файла: " + path);
                }
                if (readFiles == null || !readFiles.containsKey(path)) {
                    throw new FileAgentException(
                            "MODIFY разрешён только для файла, прочитанного агентом: " + path);
                }
                FileContent current = files.readFull(workspace, path);
                oldContent = current.text();
                originalHash = current.sha256();
            }
            if (content.equals(oldContent)) {
                throw new FileAgentException("Предложение не меняет файл: " + path);
            }
            String newHash = WorkspaceFileService.sha256(bytes);
            String fileDiff = UnifiedDiffBuilder.build(path, oldContent, content);
            if (UnifiedDiffBuilder.isEmpty(fileDiff)) {
                throw new FileAgentException("Не удалось построить diff для " + path);
            }
            diff.append(fileDiff);
            if (!path.toLowerCase(Locale.ROOT).endsWith(".md")) {
                allMarkdown = false;
            }
            if (path.toLowerCase(Locale.ROOT).endsWith(".java")) {
                anyJava = true;
            }
            changes.add(new ValidatedChange(path, type, originalHash, newHash, oldContent, content,
                    candidate.reason()));
        }
        String verification = anyJava ? "JAVA" : (allMarkdown ? "MARKDOWN" : "TEXT");
        return new ValidatedProposal(safeSummary(draft.summary()), List.copyOf(changes),
                diff.toString(), draft.notes() == null ? List.of() : List.copyOf(draft.notes()),
                verification);
    }

    private boolean allowedByPlan(String path, Set<String> targets, FileChangeType type) {
        if (!targets.isEmpty()) {
            return targets.contains(path);
        }
        // A plan without explicit targets may create documentation only.
        return type == FileChangeType.CREATE && (path.startsWith("docs/") || path.equals("README.md"));
    }

    /**
     * Local models sometimes repeat ADR sections. Keep the first occurrence of
     * each H2 section so the persisted ADR has a single canonical structure.
     * This is deterministic post-processing, not model-controlled.
     */
    static String normalizeAdr(String content) {
        return normalizeAdr(content, List.of());
    }

    static String normalizeAdr(String content, List<String> references) {
        String[] lines = content.split(String.valueOf((char) 10), -1);
        StringBuilder out = new StringBuilder();
        java.util.Set<String> seen = new java.util.HashSet<>();
        boolean skipping = false;
        for (String line : lines) {
            if (line.startsWith("## ")) {
                String heading = line.substring(3).strip().toLowerCase(Locale.ROOT);
                if (!seen.add(heading)) {
                    skipping = true;
                    continue;
                }
                skipping = false;
            }
            if (!skipping) {
                out.append(line).append((char) 10);
            }
        }
        String normalized = out.toString().stripTrailing();
        if (normalized.isEmpty()) {
            return content;
        }
        StringBuilder result = new StringBuilder(normalized).append((char) 10);
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!lower.contains("## alternatives")) {
            result.append((char) 10).append("## Alternatives").append((char) 10)
                    .append("- Manual documentation update; rejected because the agent can derive ")
                    .append("the decision from the indexed sources.").append((char) 10);
        }
        if (references != null && !references.isEmpty() && !lower.contains("## references")) {
            result.append((char) 10).append("## References").append((char) 10);
            references.stream().limit(6).forEach(path -> result.append("- `").append(path)
                    .append("` (read by the file agent)").append((char) 10));
        }
        return result.toString();
    }

    private static boolean isAdr(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("/adr/") || lower.startsWith("adr/") || lower.contains("adr-");
    }

    private static FileChangeType parseType(String action) {
        if (action == null || action.isBlank()) {
            throw new FileAgentException("Не указан тип изменения (CREATE|MODIFY)");
        }
        String normalized = action.strip().toUpperCase(Locale.ROOT);
        if (normalized.equals("UPDATE") || normalized.equals("EDIT")) {
            return FileChangeType.MODIFY;
        }
        try {
            return FileChangeType.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new FileAgentException("Недопустимый тип изменения: " + action);
        }
    }

    private static String safeSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return "Предложены изменения файлов";
        }
        return summary.strip();
    }
}
