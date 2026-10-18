package com.lectureprocessor.download;

/**
 * Raised when a download fails (upstream extractor error, timeout, disk, corrupt
 * output). Distinct from {@link UnsafeMediaUrlException} (validation failure).
 *
 * <p>{@code retryable} controls the bounded retry loop: permanent access
 * problems (bot check, private video, missing FFmpeg, unavailable format) are
 * not retried. {@code detail} keeps the raw tool output for logs only.</p>
 */
public class MediaDownloadException extends RuntimeException {

    private final boolean retryable;
    private final String detail;
    private final String reason;

    public MediaDownloadException(String message) {
        this(message, null, true, null, null);
    }

    public MediaDownloadException(String message, Throwable cause) {
        this(message, cause, true, null, null);
    }

    public MediaDownloadException(String message, boolean retryable, String detail) {
        this(message, null, retryable, detail, null);
    }

    public MediaDownloadException(String message, Throwable cause, boolean retryable, String detail) {
        this(message, cause, retryable, detail, null);
    }

    public MediaDownloadException(String message, Throwable cause, boolean retryable, String detail,
            String reason) {
        super(message, cause);
        this.retryable = retryable;
        this.detail = detail;
        this.reason = reason;
    }

    public boolean retryable() {
        return retryable;
    }

    /** Raw yt-dlp output for diagnostics; never shown verbatim in the UI. */
    public String detail() {
        return detail;
    }

    /** Stable machine-readable reason (e.g. {@code BOT_CHECK}); may be null. */
    public String reason() {
        return reason;
    }

    public static MediaDownloadException fromFailure(YtDlpFailure failure) {
        return new MediaDownloadException(failure.message(), null, failure.retryable(),
                failure.detail(), failure.kind().name());
    }
}
