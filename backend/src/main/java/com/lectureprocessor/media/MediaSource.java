package com.lectureprocessor.media;

import java.time.Instant;

/**
 * Logical description of a lecture's media source, persisted in the
 * {@code media_source} table. Either a local upload or a remote URL.
 */
public record MediaSource(
        String lectureId,
        MediaSourceKind kind,
        String originalFilename,
        String originalUrl,
        String quality,
        boolean audioOnly,
        String provider,
        String externalId,
        String title,
        String uploader,
        Long durationMs,
        String metadataJson,
        String checksum,
        Instant createdAt) {

    public static MediaSource local(String lectureId, String filename) {
        return new MediaSource(lectureId, MediaSourceKind.LOCAL_FILE, filename, null, null, false,
                null, null, null, null, null, null, null, Instant.now());
    }

    public static MediaSource remote(String lectureId, String url, String quality, boolean audioOnly) {
        return new MediaSource(lectureId, MediaSourceKind.REMOTE_URL, null, url, quality, audioOnly,
                null, null, null, null, null, null, null, Instant.now());
    }

    public MediaSource withMetadata(String provider, String externalId, String title, String uploader,
            Long durationMs, String metadataJson, String checksum) {
        return new MediaSource(lectureId, kind, originalFilename, originalUrl, quality, audioOnly,
                provider, externalId, title, uploader, durationMs, metadataJson, checksum, createdAt);
    }
}
