package com.lectureprocessor.discovery;

import java.time.Instant;
import java.util.List;

/**
 * Topic profile used for local recommendations. The embedding is computed with
 * the existing local {@code EmbeddingProvider}; it is never a cloud call.
 */
public record LectureTopic(
        String lectureId,
        String title,
        String summary,
        List<String> keyTopics,
        List<String> keywords,
        String profileText,
        float[] embedding,
        String sourceHash,
        Instant updatedAt) {

    public LectureTopic {
        keyTopics = keyTopics == null ? List.of() : List.copyOf(keyTopics);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }
}
