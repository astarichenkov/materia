package com.lectureprocessor.media;

import java.nio.file.Path;

/**
 * Concrete, locally available media file produced from a {@link MediaSource}.
 * All paths are confined to the managed workspace (relative to the lecture dir).
 */
public record MediaArtifact(
        String lectureId,
        Path path,
        String mediaType,
        long sizeBytes,
        Long durationMs,
        MediaSourceKind sourceKind,
        String source,
        String checksum) {

    public boolean exists() {
        return path != null && java.nio.file.Files.isRegularFile(path)
                && sizeBytes > 0;
    }

    public MediaArtifact withFile(Path newPath, long size, String newChecksum) {
        return new MediaArtifact(lectureId, newPath, mediaType, size, durationMs, sourceKind,
                source, newChecksum);
    }

    public MediaArtifact withDuration(long ms) {
        return new MediaArtifact(lectureId, path, mediaType, sizeBytes, ms, sourceKind, source,
                checksum);
    }
}
