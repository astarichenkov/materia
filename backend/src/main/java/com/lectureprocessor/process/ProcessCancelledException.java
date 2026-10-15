package com.lectureprocessor.process;

/**
 * Thrown when an operation was cancelled through a {@link CancellationToken}.
 * Distinct from failure so the pipeline can mark the job {@code CANCELLED}.
 */
public class ProcessCancelledException extends RuntimeException {

    public ProcessCancelledException(String message) {
        super(message);
    }
}
