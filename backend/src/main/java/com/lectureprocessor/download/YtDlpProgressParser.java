package com.lectureprocessor.download;

import java.util.Optional;

/**
 * Parses yt-dlp {@code --progress-template} output lines. The template is
 * emitted as: {@code download:progress|<status>|<downloaded>|<total>|<total_est>|<speed>|<eta>}
 * where missing numeric values are printed as {@code NA}.
 */
public final class YtDlpProgressParser {

    private static final String PREFIX = "download:";

    private YtDlpProgressParser() {
    }

    public static Optional<DownloadProgress> parse(String line) {
        if (line == null || !line.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String body = line.substring(PREFIX.length());
        if (!body.startsWith("progress|")) {
            return Optional.empty();
        }
        String[] parts = body.split("\\|", -1);
        if (parts.length < 7) {
            return Optional.empty();
        }
        String status = parts[1];
        Long downloaded = parseLong(parts[2]);
        Long total = parseLong(parts[3]);
        Long totalEstimate = parseLong(parts[4]);
        Double speed = parseDouble(parts[5]);
        Long eta = parseLong(parts[6]);

        Long effectiveTotal = total != null && total > 0
                ? total
                : (totalEstimate != null && totalEstimate > 0 ? totalEstimate : null);
        Double percent = null;
        if (downloaded != null && effectiveTotal != null && effectiveTotal > 0) {
            percent = Math.min(100.0, downloaded * 100.0 / effectiveTotal);
        }
        String normalized = "finished".equalsIgnoreCase(status) ? "FINISHED" : "DOWNLOADING";
        return Optional.of(new DownloadProgress(normalized, downloaded, effectiveTotal, percent,
                speed, eta, "Скачивание"));
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank() || "NA".equalsIgnoreCase(value) || "none".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return (long) Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDouble(String value) {
        if (value == null || value.isBlank() || "NA".equalsIgnoreCase(value) || "none".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
