package com.lectureprocessor.editor;

import java.time.Instant;

/**
 * One immutable revision of an editable lecture document.
 *
 * @param id               database id
 * @param lectureId        owning lecture
 * @param documentType     which document the revision belongs to
 * @param revisionNumber   1-based, monotonically increasing per document
 * @param content          full serialized content (JSON for segments, Markdown for notes)
 * @param contentHash      SHA-256 of {@code content}, used for optimistic locking
 * @param parentRevisionId previous revision id ({@code null} for the initial one)
 * @param editSource       who produced this revision
 * @param description      optional human-readable edit description
 * @param createdAt        creation timestamp
 */
public record LectureDocumentRevision(
        long id,
        String lectureId,
        DocumentType documentType,
        int revisionNumber,
        String content,
        String contentHash,
        Long parentRevisionId,
        EditSource editSource,
        String description,
        Instant createdAt) {
}
