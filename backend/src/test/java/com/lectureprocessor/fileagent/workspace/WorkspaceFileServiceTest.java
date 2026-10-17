package com.lectureprocessor.fileagent.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.lectureprocessor.fileagent.FileAgentProperties;
import com.lectureprocessor.fileagent.FileWorkspace;

class WorkspaceFileServiceTest {

    @TempDir
    Path root;

    private WorkspaceFileService service;
    private FileWorkspaceRef workspace;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.createDirectories(root.resolve("src/main/java/com/example"));
        Files.writeString(root.resolve("README.md"), "# Project\nLlmTaskSettingsService routing\n");
        Files.writeString(root.resolve("docs/llm.md"), "Model routing is configured per task.\n");
        Files.writeString(root.resolve("src/main/java/com/example/LlmTaskSettingsService.java"), """
                package com.example;

                public class LlmTaskSettingsService {
                    public String configFor(String task) {
                        return task;
                    }
                }
                """);
        FileAgentProperties properties = FileAgentProperties.defaults(root.toString());
        WorkspacePathGuard guard = new WorkspacePathGuard(properties);
        service = new WorkspaceFileService(guard, properties);
        workspace = new FileWorkspaceRef(FileWorkspace.PROJECT, null, root.toAbsolutePath(),
                root.toRealPath());
    }

    @Test
    void searchesByNameAndContentWithBoundedResult() {
        List<FileSearchHit> hits = service.searchFiles(workspace, "LlmTaskSettingsService", "*.java", 10);
        assertThat(hits).extracting(FileSearchHit::path)
                .contains("src/main/java/com/example/LlmTaskSettingsService.java");
        assertThat(hits.getFirst().score()).isGreaterThan(0);
    }

    @Test
    void findsSymbolsWithLineRanges() {
        List<SymbolHit> hits = service.searchSymbols(workspace, "LlmTaskSettingsService", 10);
        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().startLine()).isGreaterThan(0);
        assertThat(hits.getFirst().path()).endsWith(".java");
    }

    @Test
    void readsWithNumbersAndHashAndBoundsLines() {
        FileContent content = service.read(workspace, "README.md", 1, 1);
        assertThat(content.lines()).hasSize(1);
        assertThat(content.truncated()).isTrue();
        assertThat(content.sha256()).hasSize(64);
        assertThat(content.numberedText()).startsWith("1\t# Project");
    }

    @Test
    void metadataReportsSizeTypeAndFlags() {
        FileMetadata metadata = service.metadata(workspace, "docs/llm.md");
        assertThat(metadata.contentType()).isEqualTo("text/markdown");
        assertThat(metadata.sizeBytes()).isGreaterThan(0);
        assertThat(metadata.sha256()).hasSize(64);
        assertThat(metadata.readOnly()).isFalse();
    }
}
