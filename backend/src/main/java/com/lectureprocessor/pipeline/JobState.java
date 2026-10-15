package com.lectureprocessor.pipeline;

/**
 * High-level state of a pipeline job.
 */
public enum JobState {
    QUEUED,
    RUNNING,
    INTERRUPTED,
    COMPLETED,
    PARTIAL,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == PARTIAL || this == FAILED || this == CANCELLED;
    }
}
