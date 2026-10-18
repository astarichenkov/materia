package com.lectureprocessor.editor;

import java.util.List;

/**
 * Request/response payloads for the lecture editor API. All writes carry the
 * revision the client loaded so a concurrent edit is rejected instead of lost.
 */
public final class EditorRequests {

    private EditorRequests() {
    }

    /** One transcript segment edit; timestamps are immutable and never sent. */
    public record SegmentEdit(int sequence, String text) {
    }

    public record TranscriptSave(
            Integer expectedRevision,
            List<SegmentEdit> edits,
            String description) {
    }

    /** One translation edit. AI-assisted edits come from an accepted suggestion. */
    public record TranslationEdit(int sequence, String text, String provider, String model,
            Boolean aiAssisted, String note) {
    }

    public record TranslationSave(
            Integer expectedRevision,
            List<TranslationEdit> edits,
            String description) {
    }

    public record NotesSave(Integer expectedRevision, String markdown, String description) {
    }

    public record ReviewRequest(Integer sequence) {
    }

    public record AcceptSuggestionRequest(
            Integer sequence,
            String text,
            String provider,
            String model,
            Integer expectedRevision,
            String description) {
    }

    public record RetryRequest(Integer sequence) {
    }

    public record MarkdownPreviewRequest(String markdown) {
    }

    public record CloudRetrySetting(Boolean enabled) {
    }
}
