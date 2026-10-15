package com.lectureprocessor.download;

/**
 * Machine-readable download progress emitted by the downloader.
 */
public record DownloadProgress(
        String status,
        Long downloadedBytes,
        Long totalBytes,
        Double percent,
        Double speedBps,
        Long etaSeconds,
        String message) {

    public static DownloadProgress starting() {
        return new DownloadProgress("DOWNLOADING", null, null, null, null, null, "Скачивание началось");
    }

    public DownloadProgress withTotals(Long downloaded, Long total, Double pct, Double speed, Long eta) {
        return new DownloadProgress(status, downloaded, total, pct, speed, eta, message);
    }
}
