package com.lectureprocessor.fileagent.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.lectureprocessor.fileagent.FileAgentException;
import com.lectureprocessor.fileagent.FileAgentProperties;
import com.lectureprocessor.fileagent.FileWorkspace;

class WorkspaceSecurityTest {

    @TempDir
    Path root;

    private FileWorkspaceRef workspace;
    private WorkspacePathGuard guard;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("README.md"), "# Project\n");
        Files.writeString(root.resolve("docs/llm.md"), "LLM docs\n");
        Files.writeString(root.resolve("AGENTS.md"), "trusted instructions\n");
        Files.writeString(root.resolve(".env"), "SECRET=1\n");
        Files.writeString(root.resolve("server.key"), "key\n");
        Files.writeString(root.resolve("src/main/java/A.java"), "class A {}\n");
        FileAgentProperties properties = FileAgentProperties.defaults(root.toString());
        guard = new WorkspacePathGuard(properties);
        workspace = new FileWorkspaceRef(FileWorkspace.PROJECT, null, root.toAbsolutePath(),
                root.toRealPath());
    }

    @Test
    void acceptsNormalRelativePath() {
        ResolvedFile file = guard.resolveRead(workspace, "docs/llm.md");
        assertThat(file.relativePath()).isEqualTo("docs/llm.md");
        assertThat(file.absolutePath()).startsWith(root);
    }

    @Test
    void rejectsTraversalAbsoluteUncAndDrivePaths() {
        for (String path : new String[]{"../outside.md", "docs/../../outside.md",
                "/etc/passwd", "\\\\server\\share\\file.md", "C:\\Windows\\win.ini",
                "C:/Windows/win.ini", "docs//../../secret.md"}) {
            assertThatThrownBy(() -> guard.resolveRead(workspace, path))
                    .as(path).isInstanceOf(FileAgentException.class);
        }
    }

    @Test
    void rejectsProtectedFilesAndSecrets() {
        assertThatThrownBy(() -> guard.resolveRead(workspace, ".env"))
                .isInstanceOf(FileAgentException.class);
        assertThatThrownBy(() -> guard.resolveRead(workspace, "server.key"))
                .isInstanceOf(FileAgentException.class);
        assertThatThrownBy(() -> guard.resolveRead(workspace, ".git/config"))
                .isInstanceOf(FileAgentException.class);
        assertThat(guard.isProtected(".env")).isTrue();
        assertThat(guard.isProtected("src/main/java/A.java")).isFalse();
    }

    @Test
    void forbidsWritingSecurityFilesButAllowsReadingInstructions() {
        assertThat(guard.resolveRead(workspace, "AGENTS.md").relativePath()).isEqualTo("AGENTS.md");
        assertThatThrownBy(() -> guard.resolveWrite(workspace, "AGENTS.md"))
                .isInstanceOf(FileAgentException.class);
        assertThatThrownBy(() -> guard.resolveWrite(workspace, "pom.xml"))
                .isInstanceOf(FileAgentException.class);
        assertThat(guard.resolveWrite(workspace, "docs/new.md").relativePath())
                .isEqualTo("docs/new.md");
    }

    @Test
    void rejectsUnsupportedExtensionAndOversizeBinary() throws Exception {
        Files.writeString(root.resolve("image.svg"), "<svg/>");
        assertThatThrownBy(() -> guard.resolveRead(workspace, "image.svg"))
                .isInstanceOf(FileAgentException.class);
        Files.write(root.resolve("docs/binary.md"), new byte[]{'a', 0, 'b'});
        assertThatThrownBy(() -> guard.resolveRead(workspace, "docs/binary.md"))
                .isInstanceOf(FileAgentException.class);
    }

    @Test
    void rejectsSymlinkEscape() throws Exception {
        Path outside = Files.createTempDirectory("file-agent-outside");
        Path secret = outside.resolve("secret.md");
        Files.writeString(secret, "outside\n");
        Path link = root.resolve("docs/link.md");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            assumeTrue(false, "symlink creation is not permitted on this platform");
        }
        assertThatThrownBy(() -> guard.resolveRead(workspace, "docs/link.md"))
                .isInstanceOf(FileAgentException.class);
    }
}
