package com.lectureprocessor.codereview.code;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.llm.EmbeddingProvider;
import com.lectureprocessor.repository.CodeChunkRepository;
import com.lectureprocessor.util.VectorMath;

/**
 * Hybrid source-code retrieval: vector similarity combined with lexical
 * symbol/path matching. Vector-only retrieval is not enough to locate a class,
 * method or changed file by name, so exact symbols and paths are boosted.
 */
@Service
public class CodeRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(CodeRetrievalService.class);
    private static final double VECTOR_WEIGHT = 0.6;
    private static final double LEXICAL_WEIGHT = 0.4;

    private final CodeChunkRepository repository;
    private final EmbeddingProvider embeddings;

    public CodeRetrievalService(CodeChunkRepository repository, EmbeddingProvider embeddings) {
        this.repository = repository;
        this.embeddings = embeddings;
    }

    public List<CodeSearchResult> retrieve(String repositoryId, String commitSha, String query, int topK,
            double minSimilarity) {
        List<CodeChunk> chunks = repository.findByCommit(repositoryId, commitSha);
        if (chunks.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        float[] queryVector = embedQuery(query);
        Set<String> tokens = tokenize(query);
        return chunks.stream()
                .map(chunk -> score(chunk, queryVector, tokens))
                .filter(result -> result.score() >= Math.max(0, minSimilarity))
                .sorted(Comparator.comparingDouble(CodeSearchResult::score).reversed())
                .limit(Math.max(1, topK))
                .toList();
    }

    public List<CodeChunk> fileChunks(String repositoryId, String commitSha, String filePath) {
        return repository.findByFile(repositoryId, commitSha, filePath);
    }

    public List<CodeChunk> symbolChunks(String repositoryId, String commitSha, String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return List.of();
        }
        String needle = symbol.toLowerCase(Locale.ROOT);
        return repository.findByCommit(repositoryId, commitSha).stream()
                .filter(chunk -> (chunk.symbol() != null && chunk.symbol().toLowerCase(Locale.ROOT).contains(needle))
                        || chunk.text().toLowerCase(Locale.ROOT).contains(needle))
                .limit(20)
                .toList();
    }

    private CodeSearchResult score(CodeChunk chunk, float[] queryVector, Set<String> tokens) {
        double vector = 0;
        if (queryVector != null && chunk.embedding() != null) {
            vector = Math.max(0, VectorMath.cosine(queryVector, chunk.embedding()));
        }
        double lexical = lexicalScore(chunk, tokens);
        double combined = (queryVector == null ? lexical : VECTOR_WEIGHT * vector + LEXICAL_WEIGHT * lexical);
        return new CodeSearchResult(chunk, combined, vector, lexical);
    }

    private float[] embedQuery(String query) {
        try {
            if (embeddings.isAvailable()) {
                return embeddings.embed(List.of(query)).getFirst();
            }
        } catch (RuntimeException e) {
            log.debug("Query embedding unavailable, lexical-only retrieval: {}", e.getMessage());
        }
        return null;
    }

    /** Lexical overlap: path and symbol hits weigh more than content hits. */
    static double lexicalScore(CodeChunk chunk, Set<String> tokens) {
        if (tokens.isEmpty()) {
            return 0;
        }
        String path = chunk.filePath() == null ? "" : chunk.filePath().toLowerCase(Locale.ROOT);
        String symbol = chunk.symbol() == null ? "" : chunk.symbol().toLowerCase(Locale.ROOT);
        String text = chunk.text() == null ? "" : chunk.text().toLowerCase(Locale.ROOT);
        double score = 0;
        for (String token : tokens) {
            double tokenScore = 0;
            if (path.contains(token)) {
                tokenScore = Math.max(tokenScore, 1.0);
            }
            if (symbol.contains(token)) {
                tokenScore = Math.max(tokenScore, 0.8);
            }
            if (text.contains(token)) {
                tokenScore = Math.max(tokenScore, 0.3);
            }
            score += tokenScore;
        }
        return Math.min(1.0, score / tokens.size());
    }

    static Set<String> tokenize(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String raw : query.split("[^A-Za-z0-9_]+")) {
            if (raw.isBlank()) {
                continue;
            }
            tokens.add(raw.toLowerCase(Locale.ROOT));
            // Split camelCase identifiers into parts as well.
            for (String part : raw.split("(?<=[a-z0-9])(?=[A-Z])")) {
                if (part.length() > 2) {
                    tokens.add(part.toLowerCase(Locale.ROOT));
                }
            }
        }
        return tokens;
    }

    /** Rough estimate of retrieved context characters for budget checks. */
    public static int contextChars(List<CodeSearchResult> results) {
        return results.stream().mapToInt(result -> result.chunk().text().length()).sum();
    }

    public static int uniqueFileCount(List<CodeSearchResult> results) {
        List<String> files = new ArrayList<>();
        results.forEach(result -> files.add(result.chunk().filePath()));
        return (int) files.stream().distinct().count();
    }
}
