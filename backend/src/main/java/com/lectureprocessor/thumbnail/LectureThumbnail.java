package com.lectureprocessor.thumbnail;

import java.time.Instant;

/** Persisted cache row for a lecture thumbnail. */
public record LectureThumbnail(
        String lectureId,
        ThumbnailStatus status,
        String source,
        String relativePath,
        long sizeBytes,
        String error,
        Instant updatedAt) {

    public static LectureThumbnail ready(String lectureId, String source, String relativePath,
            long sizeBytes) {
        return new LectureThumbnail(lectureId, ThumbnailStatus.READY, source, relativePath,
                sizeBytes, null, Instant.now());
    }

    public static LectureThumbnail failed(String lectureId, String source, String error) {
        return new LectureThumbnail(lectureId, ThumbnailStatus.FAILED, source, null, 0, error,
                Instant.now());
    }

    public static LectureThumbnail noSource(String lectureId) {
        return new LectureThumbnail(lectureId, ThumbnailStatus.NO_SOURCE, "NONE", null, 0, null,
                Instant.now());
    }

    public boolean servable() {
        return status == ThumbnailStatus.READY && relativePath != null && sizeBytes > 0;
    }
}
