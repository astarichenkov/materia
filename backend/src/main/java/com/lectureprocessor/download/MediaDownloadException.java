package com.lectureprocessor.download;

/**
 * Raised when a download fails (upstream extractor error, timeout, disk, corrupt
 * output). Distinct from {@link UnsafeMediaUrlException} (validation failure).
 */
public class MediaDownloadException extends RuntimeException {

    public MediaDownloadException(String message) {
        super(message);
    }

    public MediaDownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
