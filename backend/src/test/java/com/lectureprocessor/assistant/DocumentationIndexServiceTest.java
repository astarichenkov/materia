package com.lectureprocessor.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.repository.DocChunkRepository;
import com.lectureprocessor.support.FakeLlmProvider;
import com.lectureprocessor.support.TestProperties;

class DocumentationIndexServiceTest {

    @TempDir
    Path root;

    @Test
    void indexesDocumentationIntoItsOwnCollection() throws Exception {
        Files.writeString(root.resolve("README.md"), "# Project\n\nOverview of the project.");
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs").resolve("guide.md"), "# Guide\n\nHow to run it.");

        AppProperties properties = TestProperties.defaults(root.toString());
        DocChunkRepository repository = mock(DocChunkRepository.class);
        FakeLlmProvider llm = new FakeLlmProvider();
        DocumentationIndexService service = new DocumentationIndexService(
                new DocumentationCorpusService(properties, new ProjectRootResolver(properties)),
                new MarkdownChunker(), llm, repository, properties, new ProjectRootResolver(properties));

        IndexResult result = service.index();

        assertThat(result.documents()).isEqualTo(2);
        assertThat(result.chunks()).isEqualTo(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DocChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).replaceAll(captor.capture(), anyString());
        List<DocChunk> stored = captor.getValue();
        assertThat(stored).hasSize(2);
        assertThat(stored).allSatisfy(chunk -> assertThat(chunk.embedding()).isNotEmpty());
        assertThat(stored).extracting(DocChunk::sourcePath)
                .containsExactlyInAnyOrder("README.md", "docs/guide.md");
    }

    @Test
    void statusReportsStoredCounts() {
        AppProperties properties = TestProperties.defaults(root.toString());
        DocChunkRepository repository = mock(DocChunkRepository.class);
        when(repository.count()).thenReturn(5);
        when(repository.countSources()).thenReturn(2);
        when(repository.lastIndexedAt()).thenReturn("2026-01-01T00:00:00Z");
        when(repository.findAll()).thenReturn(List.of(
                new DocChunk(1, "README.md", "t", "h", 0, "x", new float[32]),
                new DocChunk(2, "docs/a.md", "t", "h", 0, "y", new float[32])));
        DocumentationIndexService service = new DocumentationIndexService(
                new DocumentationCorpusService(properties, new ProjectRootResolver(properties)),
                new MarkdownChunker(), new FakeLlmProvider(), repository, properties,
                new ProjectRootResolver(properties));

        DocumentationIndexStatus status = service.status();

        assertThat(status.sourceCount()).isEqualTo(2);
        assertThat(status.chunkCount()).isEqualTo(5);
        assertThat(status.sources()).containsExactly("README.md", "docs/a.md");
    }
}
