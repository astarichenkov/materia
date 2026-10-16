package com.lectureprocessor.assistant;

/**
 * A retrieval unit derived from project documentation (README, docs/*.md),
 * stored in its own table so that documentation RAG can never be confused with
 * the per-lecture transcript index.
 */
public record DocChunk(
        long id,
        String sourcePath,
        String sourceTitle,
        String heading,
        int sequence,
        String text,
        float[] embedding) {
}
