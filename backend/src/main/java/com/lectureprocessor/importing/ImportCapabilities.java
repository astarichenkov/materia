package com.lectureprocessor.importing;

import java.util.List;

/**
 * Read-only description of what the import wizard can actually offer in this
 * installation. The UI uses it instead of promising features the backend does
 * not have (for example local MP3/M4A/WAV uploads are not supported).
 *
 * @param fileExtensions  accepted local file extensions, lower case with dot
 * @param maxFileSizeBytes upload limit enforced by {@code LectureService}
 * @param platforms       supported remote platform display names
 * @param ytDlpAvailable  whether the yt-dlp executable can be located
 * @param ffmpegAvailable whether FFmpeg can be located (needed to merge streams)
 * @param defaultQuality  default requested download quality label
 * @param allowHttp       whether plain-HTTP source URLs are accepted
 */
public record ImportCapabilities(
        List<String> fileExtensions,
        long maxFileSizeBytes,
        List<String> platforms,
        boolean ytDlpAvailable,
        boolean ffmpegAvailable,
        String defaultQuality,
        boolean allowHttp) {

    public String formattedMaxFileSize() {
        return formatBytes(maxFileSizeBytes);
    }

    /** "MP4, MKV, ..." for display next to the file picker. */
    public String formattedExtensions() {
        return fileExtensions.stream()
                .map(extension -> extension.replace(".", "").toUpperCase(java.util.Locale.ROOT))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    public static String formatBytes(long bytes) {
        if (bytes <= 0) {
            return "0";
        }
        long gigabytes = bytes / (1024L * 1024L * 1024L);
        if (gigabytes > 0 && bytes % (1024L * 1024L * 1024L) == 0) {
            return gigabytes + " ГБ";
        }
        long megabytes = Math.max(1, bytes / (1024L * 1024L));
        return megabytes + " МБ";
    }
}
