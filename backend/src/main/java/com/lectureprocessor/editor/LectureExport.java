package com.lectureprocessor.editor;

import java.time.Instant;

/**
 * One versioned export. Re-export never re-runs download/ffmpeg/whisper/
 * translation/text-processing: it only renders already saved revisions.
 */
public record LectureExport(
        long id,
        String lectureId,
        ExportVariant variant,
        String artifact,
        String contentHash,
        Long transcriptRevisionId,
        Long translationRevisionId,
        Long notesRevisionId,
        long sizeBytes,
        String status,
        String error,
        Instant createdAt) {
}
