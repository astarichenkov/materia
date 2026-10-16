package com.lectureprocessor.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.lectureprocessor.support.TestProperties;

class DocumentationCorpusServiceTest {

    @TempDir
    Path root;

    private DocumentationCorpusService service() {
        return new DocumentationCorpusService(TestProperties.defaults(root.toString()),
                new ProjectRootResolver(TestProperties.defaults(root.toString())));
    }

    @Test
    void loadsReadmeAndDocsButNotAgentsFile() throws Exception {
        Files.writeString(root.resolve("README.md"), "# Project\n\nIntro.");
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs").resolve("architecture.md"), "# Architecture\n\nDetails.");
        Files.writeString(root.resolve("AGENTS.md"), "# Internal agent rules\n\nSecret.");
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target").resolve("generated.md"), "# Generated\n\nNoise.");

        List<DocSource> sources = service().load();

        assertThat(sources).extracting(DocSource::path)
                .containsExactly("README.md", "docs/architecture.md");
    }

    @Test
    void pathsOutsideProjectRootAreRejected() {
        ProjectRootResolver resolver = new ProjectRootResolver(TestProperties.defaults(root.toString()));
        assertThatThrownBy(() -> resolver.resolveInside("../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
