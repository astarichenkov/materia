package com.lectureprocessor.text;

import java.util.List;

/**
 * Programmatic quality signals for a generated document. They are heuristics,
 * not a proof of correctness: a technical term may repeat legitimately. The
 * report is persisted next to the notes so the UI can warn without silently
 * discarding a document the user may still want.
 */
public record TextQualityReport(
        int chunkCount,
        int sectionCount,
        int duplicateBlocksRemoved,
        int duplicateQuestionsRemoved,
        int promptLeakLinesRemoved,
        int truncatedChunks,
        int repeatedSentences,
        List<String> warnings) {

    public TextQualityReport {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    public static TextQualityReport empty() {
        return new TextQualityReport(0, 0, 0, 0, 0, 0, 0, List.of());
    }
}
