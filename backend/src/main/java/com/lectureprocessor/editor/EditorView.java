package com.lectureprocessor.editor;

import java.util.List;
import java.util.Map;

import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.domain.Slide;
import com.lectureprocessor.domain.TranscriptSegment;
import com.lectureprocessor.multilingual.RetranslationSuggestion;
import com.lectureprocessor.multilingual.TranslationSegment;

/**
 * Read model for the lecture editor page / API. It contains only data that is
 * scoped to one verified lecture.
 */
public record EditorView(
        Lecture lecture,
        String detectedLanguage,
        String sourceLanguage,
        String translationMode,
        String targetLanguage,
        List<TranscriptSegment> transcriptSegments,
        TranslationView translation,
        String notesMarkdown,
        Map<DocumentType, LectureDocumentState> documentStates,
        Map<DocumentType, RevisionInfo> revisions,
        List<Slide> slides,
        QualitySummary quality,
        List<ExportState> exports,
        List<LectureExport> exportHistory,
        boolean allowCloudRetry,
        boolean cloudRetryConfigured) {

    public record TranslationView(
            Long translationId,
            String provider,
            String model,
            String sourceLanguage,
            String targetLanguage,
            String status,
            List<TranslationSegment> segments) {
    }

    public record RevisionInfo(int revisionNumber, long revisionId, String editSource, String createdAt) {
    }

    /**
     * Transparent review progress. This is a checklist of performed checks, not a
     * claim about objective translation accuracy.
     */
    public record QualitySummary(
            int totalSegments,
            int translated,
            int warnings,
            int manuallyCorrected,
            int reviewed,
            int stale,
            int pending) {
    }

    public record ExportState(
            ExportVariant variant,
            String docxArtifact,
            String markdownArtifact,
            boolean available,
            boolean stale,
            Long lastExportId,
            String lastExportAt,
            String reason) {
    }

    /** Response of the segment retry preview. */
    public record RetranslationPreview(
            RetranslationSuggestion suggestion,
            boolean humanCorrectionProtected,
            int expectedRevision) {
    }
}
