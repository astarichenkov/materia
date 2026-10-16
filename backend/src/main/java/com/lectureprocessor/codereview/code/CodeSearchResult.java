package com.lectureprocessor.codereview.code;

/**
 * Hybrid retrieval hit over the source-code index.
 *
 * @param chunk        matched chunk (file path, symbol, line range)
 * @param score        combined score used for ranking
 * @param vectorScore  cosine similarity (0 when embeddings were unavailable)
 * @param lexicalScore symbol/path/text lexical overlap
 */
public record CodeSearchResult(CodeChunk chunk, double score, double vectorScore, double lexicalScore) {
}
