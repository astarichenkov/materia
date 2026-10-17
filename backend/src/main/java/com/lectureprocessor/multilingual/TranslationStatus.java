package com.lectureprocessor.multilingual;

/** Lifecycle of a persisted translation run. */
public enum TranslationStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    PARTIAL,
    FAILED,
    SKIPPED
}
