package com.lectureprocessor.download;

/**
 * Maps a requested quality to a yt-dlp {@code -f} format expression with safe
 * fallbacks. When FFmpeg is not available for merging separate streams we fall
 * back to progressive single-file formats.
 */
public final class FormatSelector {

    private FormatSelector() {
    }

    public static String formatExpression(MediaQuality quality, boolean audioOnly,
            boolean ffmpegAvailable) {
        if (audioOnly) {
            return "bestaudio/best";
        }
        int maxHeight = quality.maxHeight();
        if (!ffmpegAvailable) {
            return maxHeight == 0 ? "b" : "b[height<=" + maxHeight + "]/b";
        }
        if (maxHeight == 0) {
            return "bv*+ba/b";
        }
        return "bv*[height<=" + maxHeight + "]+ba/b[height<=" + maxHeight + "]/b";
    }
}
