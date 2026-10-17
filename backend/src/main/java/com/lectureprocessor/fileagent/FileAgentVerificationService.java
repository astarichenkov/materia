package com.lectureprocessor.fileagent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.lectureprocessor.fileagent.model.ValidatedChange;
import com.lectureprocessor.fileagent.workspace.FileContent;
import com.lectureprocessor.fileagent.workspace.FileWorkspaceRef;
import com.lectureprocessor.fileagent.workspace.WorkspaceFileService;
import com.lectureprocessor.fileagent.workspace.WorkspacePathGuard;

/**
 * Only predefined verification profiles run. The model cannot inject a shell
 * command; Java checks the bytes it actually wrote.
 */
@Service
public class FileAgentVerificationService {

    private final WorkspaceFileService files;
    private final WorkspacePathGuard guard;

    public FileAgentVerificationService(WorkspaceFileService files, WorkspacePathGuard guard) {
        this.files = files;
        this.guard = guard;
    }

    public VerificationResult verify(FileWorkspaceRef workspace, List<ValidatedChange> changes,
            String profile) {
        List<String> details = new ArrayList<>();
        boolean ok = true;
        for (ValidatedChange change : changes) {
            try {
                FileContent content = files.readFull(workspace, change.path());
                if (!content.sha256().equals(change.newHash())) {
                    ok = false;
                    details.add(change.path() + ": hash не совпадает с утверждённым");
                    continue;
                }
                if (guard.isProtected(change.path())) {
                    ok = false;
                    details.add(change.path() + ": защищённый файл был изменён");
                }
                if ("MARKDOWN".equals(profile)) {
                    String markdown = content.text();
                    if (markdown.isBlank()) {
                        ok = false;
                        details.add(change.path() + ": пустой Markdown");
                    }
                    long fences = markdown.lines().filter(line -> line.strip().startsWith("```")).count();
                    if (fences % 2 != 0) {
                        ok = false;
                        details.add(change.path() + ": непарные ``` блоки");
                    }
                    if (change.path().toLowerCase(Locale.ROOT).contains("adr")) {
                        for (String heading : List.of("Status", "Context", "Decision", "Consequences")) {
                            if (!markdown.toLowerCase(Locale.ROOT).contains(heading.toLowerCase(Locale.ROOT))) {
                                ok = false;
                                details.add(change.path() + ": отсутствует раздел " + heading);
                            }
                        }
                    }
                }
                details.add(change.path() + ": записан, sha256=" + content.sha256());
            } catch (RuntimeException e) {
                ok = false;
                details.add(change.path() + ": ошибка проверки " + e.getMessage());
            }
        }
        return new VerificationResult(ok, profile, details);
    }

    public record VerificationResult(boolean ok, String profile, List<String> details) {
        public String text() {
            return (ok ? "PASSED" : "FAILED") + " (" + profile + ")\n" + String.join("\n", details);
        }
    }
}
