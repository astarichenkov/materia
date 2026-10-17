package com.lectureprocessor.multilingual;

import java.time.Instant;
import java.util.List;

/**
 * Metadata for one persisted translation run. Uniqueness is
 * {@code (lecture_id, source_fingerprint, settings_hash)}: a changed transcript,
 * model or glossary produces a different row instead of mixing versions.
 */
public record LectureTranslation(
        long id,
        String lectureId,
        String sourceFingerprint,
        String sourceLanguage,
        String targetLanguage,
        String provider,
        String model,
        String promptVersion,
        String glossaryVersion,
        String settingsHash,
        TranslationStatus status,
        int segmentsTotal,
        int segmentsTranslated,
        int segmentsFailed,
        List<String> warnings,
        String error,
        Integer inputTokens,
        Integer outputTokens,
        int llmCalls,
        Double estimatedCost,
        String costCurrency,
        Instant createdAt,
        Instant updatedAt) {

    public LectureTranslation {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public boolean complete() {
        return status == TranslationStatus.COMPLETED && segmentsFailed == 0
                && segmentsTranslated == segmentsTotal;
    }
}
