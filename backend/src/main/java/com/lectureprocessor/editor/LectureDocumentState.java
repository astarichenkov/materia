package com.lectureprocessor.editor;

import java.time.Instant;

/**
 * Staleness state of one editable document. {@code STALE} means an upstream
 * document changed after this one was produced, so it must not be presented as
 * current.
 */
public record LectureDocumentState(
        String lectureId,
        DocumentType documentType,
        DocumentStatus status,
        String reason,
        Instant updatedAt) {

    public boolean stale() {
        return status == DocumentStatus.STALE;
    }
}
