package com.lectureprocessor.download;

import java.time.Instant;

/**
 * Persisted snapshot of a remote download for UI/API consumption.
 */
public record DownloadState(
        String lectureId,
        String status,
        Long bytesDownloaded,
        Long bytesTotal,
        Double percent,
        Double speedBps,
        Long etaSeconds,
        String error,
        Instant updatedAt) {

    public static DownloadState pending(String lectureId) {
        return new DownloadState(lectureId, "PENDING", null, null, null, null, null, null,
                Instant.now());
    }
}
