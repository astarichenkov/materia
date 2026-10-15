package com.lectureprocessor.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.lectureprocessor.storage.FileStorageService;
import com.lectureprocessor.support.TestProperties;

class ArtifactManifestTest {

    @TempDir
    Path tempDir;

    @Test
    void validWhenRequiredArtifactsExist() throws Exception {
        FileStorageService storage = new FileStorageService(TestProperties.defaults(tempDir.toString()));
        Files.writeString(storage.resolve("l1", "notes.md"), "content");
        ArtifactManifest manifest = ArtifactManifest.of(
                ArtifactManifest.required("notes", "notes.md"),
                ArtifactManifest.optional("cleaned", "cleaned.txt"));

        assertThat(manifest.isValid(storage, "l1")).isTrue();
    }

    @Test
    void invalidWhenRequiredArtifactMissingOrEmpty() throws Exception {
        FileStorageService storage = new FileStorageService(TestProperties.defaults(tempDir.toString()));
        ArtifactManifest missing = ArtifactManifest.of(ArtifactManifest.required("notes", "notes.md"));
        assertThat(missing.isValid(storage, "l2")).isFalse();

        Files.createDirectories(storage.resolve("l2", "."));
        Files.writeString(storage.resolve("l2", "notes.md"), "");
        assertThat(missing.isValid(storage, "l2")).isFalse();
    }
}
