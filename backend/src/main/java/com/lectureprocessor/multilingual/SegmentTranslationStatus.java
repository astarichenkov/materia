package com.lectureprocessor.multilingual;

/** Per-segment translation state used for resume and quality reporting. */
public enum SegmentTranslationStatus {
    PENDING,
    TRANSLATED,
    WARNING,
    FAILED
}
