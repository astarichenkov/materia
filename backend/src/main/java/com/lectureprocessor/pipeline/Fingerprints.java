package com.lectureprocessor.pipeline;

import com.lectureprocessor.util.FileChecksum;

/**
 * Small helpers for building stable stage fingerprints. The pipeline never
 * assumes "file exists" means "artifact current": a fingerprint changes when
 * the inputs (size/mtime, model, config, prompt) change, and the checkpoint is
 * invalidated.
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String of(String... parts) {
        return FileChecksum.sha256(String.join("|", parts));
    }

    /**
     * Cheap content key for a media file: absolute path, size and last-modified
     * time. Avoids hashing multi-GB videos on every run.
     */
    public static String mediaKey(java.nio.file.Path path) {
        if (path == null) {
            return "none";
        }
        try {
            long size = java.nio.file.Files.size(path);
            long modified = java.nio.file.Files.getLastModifiedTime(path).toMillis();
            return path.toAbsolutePath() + ":" + size + ":" + modified;
        } catch (java.io.IOException e) {
            return path.toAbsolutePath() + ":missing";
        }
    }
}
