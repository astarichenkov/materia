package com.lectureprocessor.processing;

import java.time.Instant;

/**
 * Immutable archive of one produced document variant. The working artifacts
 * ({@code notes.md} / {@code notes.docx}) are still overwritten by the pipeline;
 * this row keeps the previous result downloadable and comparable.
 */
public record OutputVariant(
        long id,
        String lectureId,
        MaterialType materialType,
        OutputFormat outputFormat,
        DetailLevel detailLevel,
        String promptTemplate,
        String provider,
        String model,
        String notesMarkdown,
        String markdownArtifact,
        String docxArtifact,
        String contentHash,
        Instant createdAt) {

    public boolean hasMarkdown() {
        return markdownArtifact != null && !markdownArtifact.isBlank();
    }

    public boolean hasDocx() {
        return docxArtifact != null && !docxArtifact.isBlank();
    }
}
