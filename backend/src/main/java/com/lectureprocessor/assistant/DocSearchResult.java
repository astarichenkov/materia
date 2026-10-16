package com.lectureprocessor.assistant;

/** A documentation chunk together with its exact cosine similarity to a query. */
public record DocSearchResult(DocChunk chunk, double score) {
}
