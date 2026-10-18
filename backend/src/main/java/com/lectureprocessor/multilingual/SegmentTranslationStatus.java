package com.lectureprocessor.multilingual;

/** Per-segment translation state used for resume, review and staleness. */
public enum SegmentTranslationStatus {
    PENDING,
    TRANSLATED,
    WARNING,
    FAILED,
    /** Human corrected the machine translation; protected from automatic overwrite. */
    MANUALLY_CORRECTED,
    /** A human explicitly reviewed the segment (e.g. accepted a warning). */
    REVIEWED,
    /**
     * The original transcript segment changed after this translation was
     * produced. The old text is kept for reference but is never treated as a
     * current translation or exported as if it were up to date.
     */
    STALE
}
