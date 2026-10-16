package com.lectureprocessor.assistant;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.llm.LlmProvider;
import com.lectureprocessor.repository.DocChunkRepository;
import com.lectureprocessor.util.VectorMath;

/**
 * Exact cosine search over the project-documentation collection. Mirrors
 * {@code RetrievalService} for lectures but never touches lecture chunks.
 */
@Service
public class DocumentationRetrievalService {

    private static final int EXCERPT_LENGTH = 400;

    private final DocChunkRepository repository;
    private final LlmProvider llm;
    private final AppProperties properties;

    public DocumentationRetrievalService(DocChunkRepository repository, LlmProvider llm,
            AppProperties properties) {
        this.repository = repository;
        this.llm = llm;
        this.properties = properties;
    }

    public List<DocSearchResult> search(String question) {
        return search(question, properties.assistant().retrievalTopK(),
                properties.assistant().minSimilarity());
    }

    public List<DocSearchResult> search(String question, int topK, double minSimilarity) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        List<DocChunk> chunks = repository.findAll();
        if (chunks.isEmpty()) {
            return List.of();
        }
        float[] query = llm.embed(List.of(question)).getFirst();
        return chunks.stream()
                .filter(chunk -> chunk.embedding() != null)
                .map(chunk -> new DocSearchResult(chunk, VectorMath.cosine(query, chunk.embedding())))
                .filter(result -> result.score() >= minSimilarity)
                .sorted(Comparator.comparingDouble(DocSearchResult::score).reversed())
                .limit(Math.max(1, topK))
                .toList();
    }

    static String excerpt(String text) {
        String stripped = text.strip();
        return stripped.length() <= EXCERPT_LENGTH
                ? stripped
                : stripped.substring(0, EXCERPT_LENGTH) + "…";
    }
}
