package com.lectureprocessor.editor;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits and retention for the lecture editor. Size limits are enforced on the
 * server: the UI cannot bypass them.
 *
 * @param enabled              master switch for the editor endpoints
 * @param maxRevisionsPerDocument number of revisions kept per document type
 * @param maxSegmentChars      maximum length of one transcript/translation segment
 * @param maxNotesChars        maximum length of the Markdown notes document
 * @param maxBatchSegments     maximum number of segments in one save request
 * @param maxDescriptionChars  maximum edit description length
 * @param allowCloudRetry      whether WARNING segments may be retried with the
 *                             configured (possibly cloud) translation provider;
 *                             OFF by default so private text is never sent to a
 *                             cloud provider without explicit consent
 * @param maxCloudRetries      hard cap of cloud retry calls per lecture
 * @param previewMaxChars      cap for the server-side Markdown preview output
 */
@ConfigurationProperties(prefix = "app.editor")
public record EditorProperties(
        boolean enabled,
        int maxRevisionsPerDocument,
        int maxSegmentChars,
        int maxNotesChars,
        int maxBatchSegments,
        int maxDescriptionChars,
        boolean allowCloudRetry,
        int maxCloudRetries,
        int previewMaxChars) {

    public static EditorProperties defaults() {
        return new EditorProperties(true, 100, 8000, 400_000, 500, 500, false, 30, 200_000);
    }
}
