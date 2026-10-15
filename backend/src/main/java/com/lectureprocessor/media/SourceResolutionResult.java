package com.lectureprocessor.media;

import java.util.List;

/**
 * Result of resolving a {@link MediaSource}. For remote sources the artifact may
 * not have a downloaded file yet (the DOWNLOADING stage fills it in).
 */
public record SourceResolutionResult(
        MediaSource source,
        MediaArtifact artifact,
        MediaMetadata metadata,
        List<String> warnings) {

    public static SourceResolutionResult of(MediaSource source, MediaArtifact artifact,
            MediaMetadata metadata) {
        return new SourceResolutionResult(source, artifact, metadata, List.of());
    }
}
