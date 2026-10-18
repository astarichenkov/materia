package com.lectureprocessor.pipeline;

/**
 * State of a single stage execution.
 */
public enum StageState {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
    INVALIDATED
}
