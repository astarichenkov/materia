package com.lectureprocessor.media;

import java.util.List;

/**
 * Machine-readable metadata returned by the remote extractor (yt-dlp). Never
 * parsed from free-form user output.
 */
public record MediaMetadata(
        String title,
        String provider,
        String externalId,
        String uploader,
        Long durationMs,
        int width,
        int height,
        long estimatedBytes,
        String originalUrl,
        List<MediaFormat> formats,
        String rawJson) {

    public record MediaFormat(
            String formatId,
            String ext,
            Integer height,
            Integer width,
            Long filesize,
            String videoCodec,
            String audioCodec,
            Double audioBitrate) {
    }
}
