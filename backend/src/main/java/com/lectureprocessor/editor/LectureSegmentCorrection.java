package com.lectureprocessor.editor;

import java.time.Instant;

/**
 * Recorded human correction / review state for one segment. {@code originalText}
 * is the model output the correction replaced; it is never modified afterwards.
 */
public record LectureSegmentCorrection(
        long id,
        String lectureId,
        DocumentType documentType,
        int sequence,
        Long translationId,
        String originalText,
        String correctedText,
        SegmentReviewStatus reviewStatus,
        String note,
        String provider,
        String model,
        Instant updatedAt) {
}
