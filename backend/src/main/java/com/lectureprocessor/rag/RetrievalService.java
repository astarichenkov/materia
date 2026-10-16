package com.lectureprocessor.rag;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.domain.LectureChunk;
import com.lectureprocessor.llm.EmbeddingProvider;
import com.lectureprocessor.repository.ChunkRepository;
import com.lectureprocessor.util.VectorMath;

/**
 * Exact cosine retrieval over the locally stored chunk embeddings. Suitable for
 * the small per-lecture collections this application targets, and replaceable
 * behind this service if a dedicated vector store is added later.
 */
@Service
public class RetrievalService {

    private final ChunkRepository chunkRepository;
    private final EmbeddingProvider llm;
    private final AppProperties properties;

    public RetrievalService(ChunkRepository chunkRepository, EmbeddingProvider llm, AppProperties properties) {
        this.chunkRepository = chunkRepository;
        this.llm = llm;
        this.properties = properties;
    }

    public List<ScoredChunk> retrieve(String lectureId, String question) {
        return retrieve(lectureId, question,
                properties.chat().retrievalTopK(),
                properties.chat().minSimilarity());
    }

    public List<ScoredChunk> retrieve(String lectureId, String question, int topK, double minSimilarity) {
        List<LectureChunk> chunks = chunkRepository.findByLecture(lectureId);
        if (chunks.isEmpty()) {
            return List.of();
        }
        float[] query = llm.embed(List.of(question)).getFirst();
        return chunks.stream()
                .filter(chunk -> chunk.embedding() != null)
                .map(chunk -> new ScoredChunk(chunk, VectorMath.cosine(query, chunk.embedding())))
                .filter(scored -> scored.score() >= minSimilarity)
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(topK)
                .toList();
    }
}
