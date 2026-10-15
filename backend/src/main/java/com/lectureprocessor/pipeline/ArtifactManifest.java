package com.lectureprocessor.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.lectureprocessor.storage.FileStorageService;

/**
 * Declares the files a stage is expected to produce, so checkpoint reuse can
 * validate that the outputs still exist (not just that a row says COMPLETED).
 */
public record ArtifactManifest(List<ArtifactRef> artifacts) {

    public record ArtifactRef(String name, String relativePath, boolean required) {
    }

    public static ArtifactManifest empty() {
        return new ArtifactManifest(List.of());
    }

    public static ArtifactManifest of(ArtifactRef... refs) {
        return new ArtifactManifest(List.of(refs));
    }

    public static ArtifactRef required(String name, String relativePath) {
        return new ArtifactRef(name, relativePath, true);
    }

    public static ArtifactRef optional(String name, String relativePath) {
        return new ArtifactRef(name, relativePath, false);
    }

    public static ArtifactManifest merge(ArtifactManifest... manifests) {
        List<ArtifactRef> all = new java.util.ArrayList<>();
        for (ArtifactManifest manifest : manifests) {
            if (manifest != null) {
                all.addAll(manifest.artifacts());
            }
        }
        return new ArtifactManifest(all);
    }

    /**
     * True when every required artifact exists and is non-empty. Optional
     * artifacts are ignored for validity but still surfaced.
     */
    public boolean isValid(FileStorageService storage, String lectureId) {
        for (ArtifactRef ref : artifacts) {
            if (!ref.required()) {
                continue;
            }
            Path path = storage.resolve(lectureId, ref.relativePath());
            if (!Files.isRegularFile(path) || sizeOf(path) <= 0) {
                return false;
            }
        }
        return true;
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (java.io.IOException e) {
            return 0;
        }
    }
}
