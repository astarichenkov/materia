package com.lectureprocessor.domain;

import java.time.Instant;

/**
 * A lecture uploaded by the user and its processing state.
 */
public record Lecture(
        String id,
        String originalFilename,
        String storedFilename,
        long sizeBytes,
        String contentType,
        JobStatus status,
        String errorMessage,
        Long durationMs,
        boolean cleanTranscript,
        boolean extractSlides,
        String rawTranscriptPath,
        String cleanedText,
        String notesMarkdown,
        String docxPath,
        Instant createdAt,
        Instant updatedAt) {

    public Lecture withStatus(JobStatus newStatus) {
        return new Lecture(id, originalFilename, storedFilename, sizeBytes, contentType, newStatus,
                status == JobStatus.FAILED ? errorMessage : null, durationMs, cleanTranscript,
                extractSlides, rawTranscriptPath, cleanedText, notesMarkdown, docxPath,
                createdAt, Instant.now());
    }

    public Lecture withError(String message) {
        return new Lecture(id, originalFilename, storedFilename, sizeBytes, contentType,
                JobStatus.FAILED, message, durationMs, cleanTranscript, extractSlides,
                rawTranscriptPath, cleanedText, notesMarkdown, docxPath, createdAt, Instant.now());
    }

    public Lecture withDuration(Long newDurationMs) {
        return new Lecture(id, originalFilename, storedFilename, sizeBytes, contentType, status,
                errorMessage, newDurationMs, cleanTranscript, extractSlides, rawTranscriptPath,
                cleanedText, notesMarkdown, docxPath, createdAt, Instant.now());
    }

    public Lecture withStoredMedia(String newStoredFilename, long newSize, String newContentType) {
        return new Lecture(id, originalFilename, newStoredFilename, newSize, newContentType, status,
                errorMessage, durationMs, cleanTranscript, extractSlides, rawTranscriptPath,
                cleanedText, notesMarkdown, docxPath, createdAt, Instant.now());
    }

    public Lecture withOutputs(String newRawTranscriptPath, String newCleanedText,
            String newNotesMarkdown, String newDocxPath) {
        return new Lecture(id, originalFilename, storedFilename, sizeBytes, contentType, status,
                errorMessage, durationMs, cleanTranscript, extractSlides, newRawTranscriptPath,
                newCleanedText, newNotesMarkdown, newDocxPath, createdAt, Instant.now());
    }
}
