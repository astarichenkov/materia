package com.lectureprocessor.multilingual;

/**
 * One transcript segment's translation state. Timestamps are copied from the
 * source segment and are never changed by translation, so slides and RAG stay
 * aligned with the original timeline.
 *
 * <p>Human states ({@code MANUALLY_CORRECTED}, {@code REVIEWED}) are considered
 * done and are never overwritten by automatic translation. A {@code STALE}
 * segment still holds the previous text for reference but is not "done": it is
 * not exported as a current translation and will be re-translated on resume.</p>
 */
public record TranslationSegment(
        int sequence,
        long startMs,
        long endMs,
        String sourceText,
        String translatedText,
        SegmentTranslationStatus status,
        String quality,
        String warning,
        String error,
        int attempts) {

    public static TranslationSegment pending(com.lectureprocessor.domain.TranscriptSegment source) {
        return new TranslationSegment(source.sequence(), source.startMs(), source.endMs(),
                source.text(), null, SegmentTranslationStatus.PENDING, "OK", null, null, 0);
    }

    public TranslationSegment translated(String text, String quality, String warning, int newAttempts) {
        SegmentTranslationStatus status = warning == null || warning.isBlank()
                ? SegmentTranslationStatus.TRANSLATED : SegmentTranslationStatus.WARNING;
        return new TranslationSegment(sequence, startMs, endMs, sourceText, text, status,
                quality == null ? "OK" : quality, warning, null, newAttempts);
    }

    public TranslationSegment failed(String message, int newAttempts) {
        return new TranslationSegment(sequence, startMs, endMs, sourceText, translatedText,
                SegmentTranslationStatus.FAILED, quality, warning, message, newAttempts);
    }

    /** Human edit: marks the segment as manually corrected and clears warnings. */
    public TranslationSegment corrected(String text) {
        return new TranslationSegment(sequence, startMs, endMs, sourceText, text,
                SegmentTranslationStatus.MANUALLY_CORRECTED, "OK", null, null, attempts + 1);
    }

    /** Human review of an existing translation (no text change). */
    public TranslationSegment reviewed() {
        return new TranslationSegment(sequence, startMs, endMs, sourceText, translatedText,
                SegmentTranslationStatus.REVIEWED, quality, null, null, attempts);
    }

    /** Marks the translation as outdated after the original segment changed. */
    public TranslationSegment stale(String reason) {
        return new TranslationSegment(sequence, startMs, endMs, sourceText, translatedText,
                SegmentTranslationStatus.STALE, quality, reason, null, attempts);
    }

    public boolean done() {
        return (status == SegmentTranslationStatus.TRANSLATED
                || status == SegmentTranslationStatus.WARNING
                || status == SegmentTranslationStatus.MANUALLY_CORRECTED
                || status == SegmentTranslationStatus.REVIEWED)
                && translatedText != null && !translatedText.isBlank();
    }

    /** True when the segment is a human-owned translation. */
    public boolean humanOwned() {
        return status == SegmentTranslationStatus.MANUALLY_CORRECTED
                || status == SegmentTranslationStatus.REVIEWED;
    }

    /** True when a human explicitly approved this text. */
    public boolean approved() {
        return humanOwned() && translatedText != null && !translatedText.isBlank();
    }

    public com.lectureprocessor.domain.TranscriptSegment toTranscriptSegment() {
        String text = done() ? translatedText : sourceText;
        return new com.lectureprocessor.domain.TranscriptSegment(sequence, startMs, endMs, text);
    }
}
