package com.lectureprocessor.download;

import java.nio.file.Path;
import java.util.List;

import com.lectureprocessor.media.MediaMetadata;

/**
 * Successful download result.
 */
public record DownloadResult(
        Path file,
        long sizeBytes,
        MediaMetadata metadata,
        List<String> warnings) {
}
