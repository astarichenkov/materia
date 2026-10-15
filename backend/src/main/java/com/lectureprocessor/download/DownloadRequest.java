package com.lectureprocessor.download;

import java.nio.file.Path;

import com.lectureprocessor.media.MediaMetadata;

/**
 * Input for a download: the validated URL, requested quality, audio-only flag,
 * the lecture workspace directory and optional pre-fetched metadata.
 */
public record DownloadRequest(
        String lectureId,
        String url,
        MediaQuality quality,
        boolean audioOnly,
        Path targetDir,
        MediaMetadata metadata) {

    public DownloadRequest(String lectureId, String url, MediaQuality quality, boolean audioOnly,
            Path targetDir) {
        this(lectureId, url, quality, audioOnly, targetDir, null);
    }
}
