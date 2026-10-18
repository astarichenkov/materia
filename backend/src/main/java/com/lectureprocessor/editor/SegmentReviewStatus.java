package com.lectureprocessor.editor;

/**
 * Review status of a segment shown in the editor. These states describe concrete
 * checks performed by a human or by the quality validator — they are never an
 * objective accuracy score.
 */
public enum SegmentReviewStatus {
    OK,
    WARNING,
    MANUALLY_CORRECTED,
    REVIEWED
}
