package com.lectureprocessor.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import com.lectureprocessor.llm.LlmRunConfig;
import com.lectureprocessor.storage.FileStorageService;

class OutputVariantServiceTest {

    @TempDir
    Path tempDir;

    private final OutputVariantRepository repository = mock(OutputVariantRepository.class);
    private FileStorageService storage;

    @org.junit.jupiter.api.BeforeEach
    void setUpStorage() {
        storage = new FileStorageService(
                com.lectureprocessor.support.TestProperties.defaults(tempDir.toString()));
    }

    @Test
    void archivesMarkdownWithoutTouchingWorkingArtifacts() throws Exception {
        when(repository.insert(any())).thenReturn(7L);
        OutputVariantService service = new OutputVariantService(repository, storage);

        Long id = service.archive("L1",
                ProcessingProfile.explicit(MaterialType.ACADEMIC_LECTURE,
                        OutputFormat.DETAILED_NOTES, DetailLevel.DETAILED, null),
                new LlmRunConfig("ollama", "qwen3:1.7b", 0.1, 1536, 4096, false, "notes-academic-v1"),
                "# Конспект\n\nСодержание");

        assertThat(id).isEqualTo(7L);
        ArgumentCaptor<OutputVariant> captor = ArgumentCaptor.forClass(OutputVariant.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().provider()).isEqualTo("ollama");
        assertThat(captor.getValue().model()).isEqualTo("qwen3:1.7b");
        assertThat(captor.getValue().promptTemplate()).isEqualTo("notes-academic-v1");
        ArgumentCaptor<String> relative = ArgumentCaptor.forClass(String.class);
        verify(repository).updateArtifacts(org.mockito.ArgumentMatchers.eq(7L), relative.capture(),
                org.mockito.ArgumentMatchers.isNull());
        assertThat(relative.getValue()).startsWith("variants/").endsWith(".md");
        assertThat(Files.readString(storage.resolve("L1", relative.getValue())))
                .contains("# Конспект");
    }

    @Test
    void attachesExportedDocxToTheLatestVariant() throws Exception {
        OutputVariant variant = new OutputVariant(7L, "L1", MaterialType.AUTO,
                OutputFormat.DETAILED_NOTES, DetailLevel.STANDARD, "notes-academic-v1",
                "ollama", "m", "# text", "variants/a.md", null, "hash", java.time.Instant.now());
        when(repository.latest("L1")).thenReturn(Optional.of(variant));
        OutputVariantService service = new OutputVariantService(repository, storage);

        Path working = storage.resolve("L1", "notes.docx");
        Files.createDirectories(working.getParent());
        Files.writeString(working, "docx-bytes");
        service.attachExport("L1", working);

        ArgumentCaptor<String> docx = ArgumentCaptor.forClass(String.class);
        verify(repository).updateArtifacts(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.isNull(), docx.capture());
        assertThat(docx.getValue()).startsWith("variants/").endsWith(".docx");
        assertThat(Files.readString(storage.resolve("L1", docx.getValue()))).isEqualTo("docx-bytes");
    }

    @Test
    void attachingTwiceDoesNotOverwriteAnExistingDocx() throws Exception {
        OutputVariant variant = new OutputVariant(7L, "L1", MaterialType.AUTO,
                OutputFormat.BRIEF_NOTES, DetailLevel.BRIEF, "notes-brief-v1", "ollama", "m",
                "# text", "variants/a.md", "variants/a.docx", "hash", java.time.Instant.now());
        when(repository.latest("L1")).thenReturn(Optional.of(variant));
        OutputVariantService service = new OutputVariantService(repository, storage);
        Path working = storage.resolve("L1", "notes.docx");
        Files.createDirectories(working.getParent());
        Files.writeString(working, "new");

        service.attachExport("L1", working);

        verify(repository, org.mockito.Mockito.never()).updateArtifacts(anyLong(), any(),
                any());
    }

    @Test
    void artifactPathRejectsAFileOfAnotherLecture() throws Exception {
        OutputVariant variant = new OutputVariant(7L, "L1", MaterialType.AUTO,
                OutputFormat.BRIEF_NOTES, DetailLevel.BRIEF, "notes-brief-v1", "ollama", "m",
                "# text", "variants/a.md", null, "hash", java.time.Instant.now());
        OutputVariantService service = new OutputVariantService(repository, storage);
        assertThat(service.artifactPath(variant, "md")).isNull();
        assertThat(service.artifactPath(variant, "../etc/passwd")).isNull();
    }
}
