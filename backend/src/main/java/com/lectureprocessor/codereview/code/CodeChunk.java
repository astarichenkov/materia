package com.lectureprocessor.codereview.code;

/**
 * One indexed source-code chunk, versioned by commit. Deliberately separate from
 * documentation chunks ({@code doc_chunk}) and lecture chunks
 * ({@code lecture_chunk}).
 */
public record CodeChunk(
        Long id,
        String repository,
        String gitRef,
        String commitSha,
        String filePath,
        String chunkType,
        String symbol,
        int startLine,
        int endLine,
        String contentHash,
        String text,
        float[] embedding) {
}
