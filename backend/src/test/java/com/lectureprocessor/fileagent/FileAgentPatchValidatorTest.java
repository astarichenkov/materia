package com.lectureprocessor.fileagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.lectureprocessor.fileagent.model.ChangeProposalDraft;
import com.lectureprocessor.fileagent.model.ProposedChangeDraft;
import com.lectureprocessor.fileagent.model.ValidatedProposal;
import com.lectureprocessor.fileagent.FileWorkspace;
import com.lectureprocessor.fileagent.workspace.FileContent;
import com.lectureprocessor.fileagent.workspace.FileWorkspaceRef;
import com.lectureprocessor.fileagent.workspace.WorkspaceFileService;
import com.lectureprocessor.fileagent.workspace.WorkspacePathGuard;

class FileAgentPatchValidatorTest {

    @TempDir
    Path root;

    private FileAgentPatchValidator validator;
    private FileWorkspaceRef workspace;
    private Map<String, FileContent> read;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("README.md"), "# Project\nold\n");
        Files.writeString(root.resolve("docs/llm.md"), "old docs\n");
        FileAgentProperties properties = FileAgentProperties.defaults(root.toString());
        WorkspacePathGuard guard = new WorkspacePathGuard(properties);
        WorkspaceFileService files = new WorkspaceFileService(guard, properties);
        validator = new FileAgentPatchValidator(guard, files, properties);
        workspace = new FileWorkspaceRef(FileWorkspace.PROJECT, null, root.toAbsolutePath(),
                root.toRealPath());
        read = new LinkedHashMap<>();
        read.put("README.md", files.readFull(workspace, "README.md"));
        read.put("docs/llm.md", files.readFull(workspace, "docs/llm.md"));
    }

    @Test
    void validatesModifyAndCreateAndBuildsDiff() {
        ChangeProposalDraft draft = new ChangeProposalDraft("Update docs", List.of(
                new ProposedChangeDraft("README.md", "MODIFY", "# Project\nnew\n", "update"),
                new ProposedChangeDraft("docs/adr/ADR-001-test.md", "CREATE",
                        "# ADR\nStatus: Accepted\n", "adr")), List.of());
        ValidatedProposal proposal = validator.validate(workspace, draft, read,
                List.of("README.md", "docs/adr/ADR-001-test.md"));
        assertThat(proposal.changes()).hasSize(2);
        assertThat(proposal.diff()).contains("--- a/README.md", "+new", "--- a/docs/adr");
        assertThat(proposal.verificationProfile()).isEqualTo("MARKDOWN");
    }

    @Test
    void rejectsPathOutsideWorkspace() {
        ChangeProposalDraft draft = new ChangeProposalDraft("bad", List.of(
                new ProposedChangeDraft("../secret.md", "CREATE", "x", "bad")), List.of());
        assertThatThrownBy(() -> validator.validate(workspace, draft, read, List.of("../secret.md")))
                .isInstanceOf(FileAgentException.class)
                .hasMessageContaining("traversal");
    }

    @Test
    void rejectsProtectedAndWriteForbiddenFiles() {
        ChangeProposalDraft draft = new ChangeProposalDraft("bad", List.of(
                new ProposedChangeDraft("AGENTS.md", "MODIFY", "x", "bad")), List.of());
        assertThatThrownBy(() -> validator.validate(workspace, draft, read, List.of("AGENTS.md")))
                .isInstanceOf(FileAgentException.class);
        ChangeProposalDraft env = new ChangeProposalDraft("bad", List.of(
                new ProposedChangeDraft(".env", "CREATE", "x", "bad")), List.of());
        assertThatThrownBy(() -> validator.validate(workspace, env, read, List.of(".env")))
                .isInstanceOf(FileAgentException.class);
    }

    @Test
    void rejectsModifyOfUnreadFileAndCreateOfExistingFile() {
        ChangeProposalDraft unread = new ChangeProposalDraft("bad", List.of(
                new ProposedChangeDraft("docs/other.md", "MODIFY", "x", "bad")), List.of());
        assertThatThrownBy(() -> validator.validate(workspace, unread, read, List.of("docs/other.md")))
                .isInstanceOf(FileAgentException.class);
        ChangeProposalDraft existing = new ChangeProposalDraft("bad", List.of(
                new ProposedChangeDraft("docs/llm.md", "CREATE", "x", "bad")), List.of());
        assertThatThrownBy(() -> validator.validate(workspace, existing, read, List.of("docs/llm.md")))
                .isInstanceOf(FileAgentException.class);
    }

    @Test
    void rejectsPlanTargetOutsideAllowedList() {
        ChangeProposalDraft draft = new ChangeProposalDraft("bad", List.of(
                new ProposedChangeDraft("docs/llm.md", "MODIFY", "new docs", "bad")), List.of());
        assertThatThrownBy(() -> validator.validate(workspace, draft, read, List.of("README.md")))
                .isInstanceOf(FileAgentException.class);
    }
}
