package com.lectureprocessor.domain;

/**
 * Lifecycle of a lecture processing job. Persisted so that progress survives
 * application restarts and can be observed from the UI.
 */
public enum JobStatus {
    UPLOADED,
    DOWNLOADING,
    VALIDATING,
    EXTRACTING_AUDIO,
    TRANSCRIBING,
    TRANSLATING,
    PROCESSING_TEXT,
    EXTRACTING_SLIDES,
    EXPORTING,
    COMPLETED,
    PARTIAL,
    INTERRUPTED,
    CANCELLED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == PARTIAL || this == FAILED || this == CANCELLED;
    }

    /** A job that can be submitted again to make progress. */
    public boolean isRetryable() {
        return this == UPLOADED || this == INTERRUPTED || this == FAILED || this == CANCELLED
                || this == PARTIAL;
    }
}
