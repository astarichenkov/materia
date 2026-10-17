package com.lectureprocessor.multilingual;

/**
 * One transcript segment's translation state. Timestamps are copied from the
 * source segment and are never changed by translation, so slides and RAG stay
 * aligned with the original timeline.
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

    public boolean done() {
        return (status == SegmentTranslationStatus.TRANSLATED
                || status == SegmentTranslationStatus.WARNING)
                && translatedText != null && !translatedText.isBlank();
    }

    public com.lectureprocessor.domain.TranscriptSegment toTranscriptSegment() {
        String text = translatedText != null && !translatedText.isBlank() ? translatedText : sourceText;
        return new com.lectureprocessor.domain.TranscriptSegment(sequence, startMs, endMs, text);
    }
}
